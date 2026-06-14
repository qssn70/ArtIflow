package com.studysuit.aiqa.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeRecognitionCoordinatorTest {

  @Test
  fun parseMistakeRecognitionPayloadAcceptsFencedJsonAndChineseMistakeType() {
    val draft = parseMistakeRecognitionPayload(
      raw = """
        ```json
        {
          "question": "已知二次函数 y=x^2-2x，求最小值。",
          "subject": "数学",
          "question_type": "解答题",
          "knowledge_tags": ["二次函数", "函数与图像"],
          "student_answer": "0",
          "correct_answer": "-1",
          "explanation": "配方得 (x-1)^2-1，所以最小值为 -1。",
          "mistake_reason": "把 x=0 代入当成最小值。",
          "mistake_type": "概念错误"
        }
        ```
      """.trimIndent(),
      id = "draft-1",
      imageRefs = listOf("mistake_images/draft-1-0.jpg"),
      ocrText = "OCR 原文",
      now = 100L
    )

    requireNotNull(draft)
    assertEquals(MistakeRecognitionStatus.AI_READY, draft.status)
    assertEquals("数学", draft.subject)
    assertEquals("解答题", draft.questionType)
    assertEquals(listOf("二次函数", "函数与图像"), draft.knowledgeTags)
    assertEquals(MistakeType.CONCEPT_ERROR, draft.mistakeType)
    assertEquals("-1", draft.correctAnswer)
    assertEquals("OCR 原文", draft.ocrText)
  }

  @Test
  fun coordinatorUsesOcrTextThenVisionJson() = runBlocking {
    val vision = FakeMistakeVisionClient(
      result = Result.success(
        """
        {"question":"OCR校对后的题干","subject":"物理","question_type":"实验题","knowledge_tags":["力学"],"correct_answer":"F=ma","mistake_reason":"公式混淆","mistake_type":"方法错误"}
        """.trimIndent()
      )
    )
    val coordinator = MistakeRecognitionCoordinator(
      ocrClient = FakeMistakeOcrClient(Result.success("OCR 识别题干")),
      visionClient = vision,
      idProvider = { "draft-1" },
      clock = { 1_000L }
    )

    val draft = coordinator.recognizeFromImages(
      imageBytesList = listOf(byteArrayOf(1, 2, 3)),
      imageRefs = listOf("mistake_images/draft-1-0.jpg"),
      note = "老师说看第二问",
      config = ArkRuntimeConfig(apiKey = "key")
    )

    assertEquals(MistakeRecognitionStatus.AI_READY, draft.status)
    assertEquals("OCR校对后的题干", draft.question)
    assertEquals("F=ma", draft.correctAnswer)
    assertEquals(MistakeType.METHOD_ERROR, draft.mistakeType)
    assertTrue(vision.lastPrompt.contains("OCR 识别题干"))
    assertTrue(vision.lastPrompt.contains("老师说看第二问"))
  }

  @Test
  fun coordinatorFusesOcrAndTwoVisionResultsToReduceHallucination() = runBlocking {
    val primaryVision = FakeMistakeVisionClient(
      result = Result.success(
        """
        {"question":"已知 x+1=8，求 x","subject":"语文","question_type":"阅读题","correct_answer":"7"}
        """.trimIndent()
      )
    )
    val secondaryVision = FakeMistakeVisionClient(
      result = Result.success(
        """
        {"question":"已知 x+1=3，求 x","subject":"数学","question_type":"填空题","correct_answer":"2","mistake_type":"计算错误"}
        """.trimIndent()
      )
    )
    val fusion = FakeMistakeFusionClient(
      result = Result.success(
        """
        {"question":"已知 x+1=3，求 x","subject":"数学","question_type":"填空题","knowledge_tags":["一元一次方程"],"correct_answer":"2","mistake_reason":"移项计算错误","mistake_type":"计算错误"}
        """.trimIndent()
      )
    )
    val primaryConfig = ArkRuntimeConfig(apiKey = "primary-key", model = "vision-primary")
    val secondaryConfig = ArkRuntimeConfig(apiKey = "secondary-key", model = "vision-secondary")
    val fusionConfig = ArkRuntimeConfig(apiKey = "fusion-key", model = "fusion-judge")
    val coordinator = MistakeRecognitionCoordinator(
      ocrClient = FakeMistakeOcrClient(Result.success("OCR: 已知 x+1=3，求 x")),
      visionClient = primaryVision,
      additionalVisionClients = listOf(secondaryVision),
      fusionClient = fusion,
      idProvider = { "draft-1" },
      clock = { 1_000L }
    )

    val draft = coordinator.recognizeFromImages(
      imageBytesList = listOf(byteArrayOf(1, 2, 3)),
      imageRefs = listOf("mistake_images/draft-1-0.jpg"),
      note = "只录入这一题",
      config = primaryConfig,
      pipelineConfig = MistakeRecognitionPipelineConfig(
        visionConfigs = listOf(primaryConfig, secondaryConfig),
        fusionConfig = fusionConfig
      )
    )

    assertEquals(MistakeRecognitionStatus.AI_READY, draft.status)
    assertEquals("已知 x+1=3，求 x", draft.question)
    assertEquals("数学", draft.subject)
    assertEquals("2", draft.correctAnswer)
    assertEquals(MistakeType.CALCULATION_ERROR, draft.mistakeType)
    assertEquals("vision-primary", primaryVision.lastConfig?.model)
    assertEquals("vision-secondary", secondaryVision.lastConfig?.model)
    assertEquals("fusion-judge", fusion.lastConfig?.model)
    assertTrue(fusion.lastPrompt.contains("OCR: 已知 x+1=3，求 x"))
    assertTrue(fusion.lastPrompt.contains("模型 1 原始输出"))
    assertTrue(fusion.lastPrompt.contains("模型 2 原始输出"))
    assertTrue(fusion.lastPrompt.contains("已知 x+1=8"))
  }

  @Test
  fun coordinatorFallsBackToBestVisionDraftWhenFusionFails() = runBlocking {
    val primaryVision = FakeMistakeVisionClient(
      result = Result.success(
        """
        {"question":"OCR校对后的题干","subject":"物理","correct_answer":"F=ma","mistake_type":"方法错误"}
        """.trimIndent()
      )
    )
    val fusion = FakeMistakeFusionClient(Result.failure(IllegalStateException("fusion offline")))
    val coordinator = MistakeRecognitionCoordinator(
      ocrClient = FakeMistakeOcrClient(Result.success("OCR 题干")),
      visionClient = primaryVision,
      fusionClient = fusion,
      idProvider = { "draft-1" },
      clock = { 1_000L }
    )

    val draft = coordinator.recognizeFromImages(
      imageBytesList = listOf(byteArrayOf(1, 2, 3)),
      imageRefs = listOf("mistake_images/draft-1-0.jpg"),
      config = ArkRuntimeConfig(apiKey = "key"),
      pipelineConfig = MistakeRecognitionPipelineConfig(
        visionConfigs = listOf(ArkRuntimeConfig(apiKey = "key", model = "vision")),
        fusionConfig = ArkRuntimeConfig(apiKey = "fusion-key", model = "fusion")
      )
    )

    assertEquals(MistakeRecognitionStatus.AI_READY, draft.status)
    assertEquals("OCR校对后的题干", draft.question)
    assertEquals("物理", draft.subject)
    assertEquals(MistakeType.METHOD_ERROR, draft.mistakeType)
    assertTrue(fusion.lastPrompt.contains("OCR 题干"))
  }

  @Test
  fun coordinatorKeepsOcrDraftWhenVisionModelFails() = runBlocking {
    val coordinator = MistakeRecognitionCoordinator(
      ocrClient = FakeMistakeOcrClient(Result.success("只有 OCR 题干")),
      visionClient = FakeMistakeVisionClient(Result.failure(IllegalStateException("model offline"))),
      idProvider = { "draft-1" },
      clock = { 1_000L }
    )

    val draft = coordinator.recognizeFromImages(
      imageBytesList = listOf(byteArrayOf(1)),
      imageRefs = listOf("mistake_images/draft-1-0.jpg"),
      note = "",
      config = ArkRuntimeConfig(apiKey = "key")
    )

    assertEquals(MistakeRecognitionStatus.OCR_READY, draft.status)
    assertEquals("只有 OCR 题干", draft.question)
    assertEquals("model offline", draft.errorMessage)
  }

  @Test
  fun coordinatorReturnsFailedDraftWhenBothRecognitionStepsFail() = runBlocking {
    val coordinator = MistakeRecognitionCoordinator(
      ocrClient = FakeMistakeOcrClient(Result.failure(IllegalStateException("ocr unavailable"))),
      visionClient = FakeMistakeVisionClient(Result.failure(IllegalStateException("model offline"))),
      idProvider = { "draft-1" },
      clock = { 1_000L }
    )

    val draft = coordinator.recognizeFromImages(
      imageBytesList = listOf(byteArrayOf(1)),
      imageRefs = listOf("mistake_images/draft-1-0.jpg"),
      note = "",
      config = ArkRuntimeConfig(apiKey = "key")
    )

    assertEquals(MistakeRecognitionStatus.FAILED, draft.status)
    assertEquals("", draft.question)
    assertTrue(draft.errorMessage.contains("model offline"))
  }

  private class FakeMistakeOcrClient(
    private val result: Result<String>
  ) : MistakeOcrClient {
    override suspend fun recognizeText(imageBytesList: List<ByteArray>): Result<String> = result
  }

  private class FakeMistakeVisionClient(
    private val result: Result<String>
  ) : MistakeVisionClient {
    var lastPrompt: String = ""
    var lastConfig: ArkRuntimeConfig? = null

    override suspend fun structureMistake(
      prompt: String,
      imageBytesList: List<ByteArray>,
      config: ArkRuntimeConfig
    ): Result<String> {
      lastPrompt = prompt
      lastConfig = config
      return result
    }
  }

  private class FakeMistakeFusionClient(
    private val result: Result<String>
  ) : MistakeRecognitionFusionClient {
    var lastPrompt: String = ""
    var lastConfig: ArkRuntimeConfig? = null

    override suspend fun fuseMistake(
      prompt: String,
      config: ArkRuntimeConfig
    ): Result<String> {
      lastPrompt = prompt
      lastConfig = config
      return result
    }
  }
}
