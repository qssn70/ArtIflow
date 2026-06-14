package com.studysuit.aiqa.data

import org.json.JSONArray
import org.json.JSONObject

interface MistakeOcrClient {
  suspend fun recognizeText(imageBytesList: List<ByteArray>): Result<String>
}

interface MistakeVisionClient {
  suspend fun structureMistake(
    prompt: String,
    imageBytesList: List<ByteArray>,
    config: ArkRuntimeConfig = ArkRuntimeConfig()
  ): Result<String>
}

interface MistakeRecognitionFusionClient {
  suspend fun fuseMistake(
    prompt: String,
    config: ArkRuntimeConfig = ArkRuntimeConfig()
  ): Result<String>
}

data class MistakeRecognitionPipelineConfig(
  val visionConfigs: List<ArkRuntimeConfig> = listOf(ArkRuntimeConfig()),
  val fusionConfig: ArkRuntimeConfig? = null
) {
  internal fun normalized(fallbackConfig: ArkRuntimeConfig): MistakeRecognitionPipelineConfig {
    return copy(
      visionConfigs = visionConfigs.ifEmpty { listOf(fallbackConfig) }.take(MAX_VISION_MODEL_COUNT)
    )
  }

  private companion object {
    private const val MAX_VISION_MODEL_COUNT = 2
  }
}

object UnavailableMistakeOcrClient : MistakeOcrClient {
  override suspend fun recognizeText(imageBytesList: List<ByteArray>): Result<String> {
    imageBytesList.size.let { Unit }
    return Result.failure(IllegalStateException("OCR 未初始化"))
  }
}

class ArkMistakeVisionClient(
  private val apiClient: ArkApiClient = ArkApiClient(),
  private val mimeType: String = "image/jpeg"
) : MistakeVisionClient {
  override suspend fun structureMistake(
    prompt: String,
    imageBytesList: List<ByteArray>,
    config: ArkRuntimeConfig
  ): Result<String> {
    val images = imageBytesList.map { bytes -> ImagePayload(bytes = bytes, mimeType = mimeType) }
    return apiClient.generateReplyWithImages(prompt = prompt, images = images, config = config)
  }
}

class ArkMistakeFusionClient(
  private val apiClient: ArkApiClient = ArkApiClient()
) : MistakeRecognitionFusionClient {
  override suspend fun fuseMistake(
    prompt: String,
    config: ArkRuntimeConfig
  ): Result<String> {
    return apiClient.generateReply(
      messages = listOf(ArkRequestMessage(role = "user", text = prompt)),
      config = config
    )
  }
}

class MistakeRecognitionCoordinator(
  private val ocrClient: MistakeOcrClient,
  private val visionClient: MistakeVisionClient,
  private val additionalVisionClients: List<MistakeVisionClient> = emptyList(),
  private val fusionClient: MistakeRecognitionFusionClient? = null,
  private val idProvider: () -> String = { "draft-${System.currentTimeMillis()}" },
  private val clock: () -> Long = { System.currentTimeMillis() }
) {
  suspend fun recognizeFromImages(
    imageBytesList: List<ByteArray>,
    imageRefs: List<String>,
    note: String = "",
    config: ArkRuntimeConfig = ArkRuntimeConfig(),
    pipelineConfig: MistakeRecognitionPipelineConfig = MistakeRecognitionPipelineConfig(
      visionConfigs = listOf(config)
    )
  ): MistakeRecognitionDraft {
    val id = idProvider().trim().ifBlank { "draft-${clock()}" }
    val now = clock()
    val ocrResult = ocrClient.recognizeText(imageBytesList)
    val ocrText = ocrResult.getOrNull().orEmpty().trim()
    val pipeline = pipelineConfig.normalized(fallbackConfig = config)
    val visionClients = (listOf(visionClient) + additionalVisionClients)
      .take(pipeline.visionConfigs.size.coerceAtLeast(1))
    val visionPrompt = buildMistakeRecognitionPrompt(ocrText = ocrText, note = note)
    val visionOutputs = mutableListOf<VisionRecognitionOutput>()
    val visionErrors = mutableListOf<String>()

    visionClients.forEachIndexed { index, client ->
      val modelConfig = pipeline.visionConfigs.getOrNull(index) ?: config
      val result = client.structureMistake(
        prompt = visionPrompt,
        imageBytesList = imageBytesList,
        config = modelConfig
      )
      val raw = result.getOrNull()
      if (raw != null) {
        val draft = parseMistakeRecognitionPayload(
          raw = raw,
          id = id,
          imageRefs = imageRefs,
          ocrText = ocrText,
          now = now
        )
        if (draft == null) {
          visionErrors += formatVisionParseError(index = index, total = visionClients.size)
        }
        if (raw.trim().isNotBlank()) {
          visionOutputs += VisionRecognitionOutput(raw = raw, draft = draft)
        }
      } else {
        result.exceptionOrNull()?.message?.let { message ->
          visionErrors += formatVisionFailureError(index = index, total = visionClients.size, message = message)
        }
      }
    }

    val bestVisionDraft = visionOutputs.firstNotNullOfOrNull { output -> output.draft }
    val fusionConfig = pipeline.fusionConfig
    if (fusionClient != null && fusionConfig != null && visionOutputs.isNotEmpty()) {
      val fusionResult = fusionClient.fuseMistake(
        prompt = buildMistakeFusionPrompt(
          ocrText = ocrText,
          note = note,
          modelOutputs = visionOutputs.map { output -> output.raw }
        ),
        config = fusionConfig
      )
      fusionResult.getOrNull()?.let { raw ->
        parseMistakeRecognitionPayload(
          raw = raw,
          id = id,
          imageRefs = imageRefs,
          ocrText = ocrText,
          now = now
        )?.let { draft -> return draft }
      }
      val fusionErrorMessage = fusionResult.exceptionOrNull()?.message
      if (fusionErrorMessage != null) {
        visionErrors += "融合模型：$fusionErrorMessage"
      } else if (fusionResult.isSuccess) {
        visionErrors += "融合模型结构化结果解析失败"
      }
    }

    bestVisionDraft?.let { draft -> return draft }

    val visionError = visionErrors.joinToString(separator = "；")
    if (ocrText.isNotBlank()) {
      return MistakeRecognitionDraft(
        id = id,
        imageRefs = imageRefs.normalizedRefs(),
        ocrText = ocrText,
        question = ocrText,
        status = MistakeRecognitionStatus.OCR_READY,
        errorMessage = visionError,
        createdAt = now,
        updatedAt = now
      )
    }

    val ocrError = ocrResult.exceptionOrNull()?.message
    return MistakeRecognitionDraft(
      id = id,
      imageRefs = imageRefs.normalizedRefs(),
      status = MistakeRecognitionStatus.FAILED,
      errorMessage = listOfNotNull(ocrError, visionError.takeIf(String::isNotBlank)).joinToString(separator = "；"),
      createdAt = now,
      updatedAt = now
    )
  }

  private data class VisionRecognitionOutput(
    val raw: String,
    val draft: MistakeRecognitionDraft?
  )

  private fun formatVisionFailureError(index: Int, total: Int, message: String): String {
    return if (total <= 1) message else "模型 ${index + 1}：$message"
  }

  private fun formatVisionParseError(index: Int, total: Int): String {
    return if (total <= 1) "模型结构化结果解析失败" else "模型 ${index + 1} 结构化结果解析失败"
  }
}

fun parseMistakeRecognitionPayload(
  raw: String,
  id: String,
  imageRefs: List<String>,
  ocrText: String,
  now: Long
): MistakeRecognitionDraft? {
  val json = extractJsonObjectCandidate(raw) ?: return null
  val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
  val normalizedOcr = ocrText.trim()
  return MistakeRecognitionDraft(
    id = id.trim().ifBlank { "draft-$now" },
    imageRefs = imageRefs.normalizedRefs(),
    ocrText = normalizedOcr,
    question = root.optFlexibleString("question", "题干", "question_text").ifBlank { normalizedOcr },
    subject = root.optFlexibleString("subject", "科目"),
    questionType = root.optFlexibleString("question_type", "questionType", "题型"),
    knowledgeTags = root.optFlexibleStringList("knowledge_tags", "knowledgeTags", "知识点"),
    studentAnswer = root.optFlexibleString("student_answer", "studentAnswer", "学生答案"),
    correctAnswer = root.optFlexibleString("correct_answer", "correctAnswer", "正确答案"),
    explanation = root.optFlexibleString("explanation", "解析"),
    mistakeReason = root.optFlexibleString("mistake_reason", "mistakeReason", "错因"),
    mistakeType = parseMistakeType(root.optFlexibleString("mistake_type", "mistakeType", "错误类型")),
    status = MistakeRecognitionStatus.AI_READY,
    createdAt = now,
    updatedAt = now
  )
}

fun buildMistakeRecognitionPrompt(ocrText: String, note: String = ""): String {
  return """
    你是错题本录入助手。请根据图片、OCR 原文和用户补充说明，校对题干、公式、图表信息，并提取一道错题的结构化字段。

    OCR 原文：
    ${ocrText.trim().ifBlank { "（无）" }}

    用户补充说明：
    ${note.trim().ifBlank { "（无）" }}

    仅输出JSON，不要 Markdown。字段：
    question, subject, question_type, knowledge_tags, student_answer, correct_answer, explanation, mistake_reason, mistake_type。
    mistake_type 只能从：概念错误、计算错误、审题错误、方法错误 中选择；无法判断时留空。
  """.trimIndent()
}

fun buildMistakeFusionPrompt(
  ocrText: String,
  note: String = "",
  modelOutputs: List<String>
): String {
  val modelSections = modelOutputs
    .take(2)
    .mapIndexed { index, raw ->
      """
      模型 ${index + 1} 原始输出：
      ${raw.trim().ifBlank { "（空）" }}
      """.trimIndent()
    }
    .joinToString(separator = "\n\n")

  return """
    你是错题识别结果融合裁判。请把 OCR 原文与多模态模型的结构化输出进行交叉校验，合成一份更可信的错题 JSON。

    规则：
    1. OCR 原文是事实锚点；多模态模型输出与 OCR 冲突时，优先保留 OCR 可支持的题干、数字、公式和条件。
    2. 多模态模型可以补充图片中的图形、选项、解析线索，但不要编造 OCR 和模型输出都没有的内容。
    3. 如果多个模型不一致，只采纳能被 OCR 或另一模型支持的信息；无法判断的字段留空。
    4. 仅输出 JSON，不要 Markdown。

    OCR 原文：
    ${ocrText.trim().ifBlank { "（无）" }}

    用户补充说明：
    ${note.trim().ifBlank { "（无）" }}

    ${modelSections.ifBlank { "模型原始输出：（无）" }}

    输出字段：
    question, subject, question_type, knowledge_tags, student_answer, correct_answer, explanation, mistake_reason, mistake_type。
    mistake_type 只能从：概念错误、计算错误、审题错误、方法错误 中选择；无法判断时留空。
  """.trimIndent()
}

internal fun extractJsonObjectCandidate(raw: String): String? {
  val trimmed = raw.trim()
  if (trimmed.isBlank()) {
    return null
  }

  val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    .find(trimmed)
    ?.groupValues
    ?.getOrNull(1)
    ?.trim()
  if (!fenced.isNullOrBlank()) {
    return fenced
  }

  val start = trimmed.indexOf('{')
  val end = trimmed.lastIndexOf('}')
  return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else null
}

private fun JSONObject.optFlexibleString(vararg names: String): String {
  for (name in names) {
    if (!has(name) || isNull(name)) {
      continue
    }
    val value = opt(name)
    if (value is JSONArray || value is JSONObject) {
      continue
    }
    val text = value?.toString()?.trim().orEmpty()
    if (text.isNotBlank() && text != "null") {
      return text
    }
  }
  return ""
}

private fun JSONObject.optFlexibleStringList(vararg names: String): List<String> {
  for (name in names) {
    if (!has(name) || isNull(name)) {
      continue
    }
    val value = opt(name)
    val parsed = when (value) {
      is JSONArray -> value.toStringList()
      is String -> value.split(Regex("[,，、;；\\n]"))
      else -> emptyList()
    }
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
    if (parsed.isNotEmpty()) {
      return parsed.take(12)
    }
  }
  return emptyList()
}

private fun JSONArray.toStringList(): List<String> {
  return buildList {
    for (index in 0 until length()) {
      val value = optString(index).trim()
      if (value.isNotBlank() && value != "null") {
        add(value)
      }
    }
  }
}

private fun List<String>.normalizedRefs(): List<String> {
  return map(String::trim).filter(String::isNotBlank).distinct()
}

private fun parseMistakeType(raw: String): MistakeType? {
  val normalized = raw.trim()
  return runCatching { MistakeType.valueOf(normalized) }.getOrNull()
    ?: when (normalized) {
      "概念错误", "概念", "concept" -> MistakeType.CONCEPT_ERROR
      "计算错误", "计算", "calculation" -> MistakeType.CALCULATION_ERROR
      "审题错误", "审题", "reading" -> MistakeType.READING_ERROR
      "方法错误", "方法", "method" -> MistakeType.METHOD_ERROR
      else -> null
    }
}
