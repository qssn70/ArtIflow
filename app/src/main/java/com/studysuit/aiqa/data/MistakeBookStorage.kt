package com.studysuit.aiqa.data

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class MistakeBookStorage(private val baseDir: File) {
  constructor(context: Context) : this(context.filesDir)

  private val storageFile = File(baseDir, STORAGE_FILE_NAME)
  private val imageDir = File(baseDir, IMAGE_DIR_NAME)

  fun load(): List<MistakeBookItem> {
    if (!storageFile.exists()) {
      return emptyList()
    }
    val raw = runCatching { storageFile.readText(Charsets.UTF_8) }
      .onFailure { error -> Log.w(TAG, "Failed to read mistake book", error) }
      .getOrNull()
      ?: return emptyList()

    return parseMistakeBookJson(raw)
      .onFailure { error -> Log.w(TAG, "Failed to parse mistake book", error) }
      .getOrDefault(emptyList())
  }

  fun save(items: List<MistakeBookItem>): Result<Unit> {
    return runCatching {
      storageFile.parentFile?.mkdirs()
      storageFile.writeText(buildMistakeBookJson(items).toString(), Charsets.UTF_8)
    }.onFailure { error -> Log.w(TAG, "Failed to persist mistake book", error) }
  }

  fun upsert(item: MistakeBookItem): Result<List<MistakeBookItem>> {
    val current = load()
    val exists = current.any { existing -> existing.id == item.id }
    val next = if (exists) {
      current.map { existing -> if (existing.id == item.id) item else existing }
    } else {
      listOf(item) + current
    }
    return save(next).map { next }
  }

  fun delete(itemId: String): Result<List<MistakeBookItem>> {
    val next = load().filterNot { item -> item.id == itemId }
    return save(next).map { next }
  }

  fun saveImageBytes(bytes: ByteArray, fileNameHint: String): Result<String> {
    return runCatching {
      require(bytes.isNotEmpty()) { "图片数据为空" }
      imageDir.mkdirs()
      val baseName = sanitizeImageFileName(fileNameHint)
      var candidate = File(imageDir, baseName)
      if (candidate.exists()) {
        val stem = candidate.nameWithoutExtension
        val extension = candidate.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        var index = 1
        while (candidate.exists()) {
          candidate = File(imageDir, "$stem-$index$extension")
          index += 1
        }
      }
      candidate.writeBytes(bytes)
      "$IMAGE_DIR_NAME/${candidate.name}"
    }.onFailure { error -> Log.w(TAG, "Failed to persist mistake image", error) }
  }

  private companion object {
    private const val TAG = "MistakeBookStorage"
    private const val STORAGE_FILE_NAME = "mistake_book_v1.json"
    private const val IMAGE_DIR_NAME = "mistake_images"
  }
}

fun buildMistakeBookJson(items: List<MistakeBookItem>): JSONObject {
  return JSONObject()
    .put("version", 1)
    .put(
      "items",
      JSONArray().apply {
        items.forEach { item -> put(item.toJson()) }
      }
    )
}

fun exportMistakeBookText(items: List<MistakeBookItem>): String {
  return buildMistakeBookJson(items).toString()
}

fun importMistakeBookText(raw: String): Result<List<MistakeBookItem>> {
  return parseMistakeBookJson(raw)
}

fun mergeImportedMistakeBookItems(
  current: List<MistakeBookItem>,
  imported: List<MistakeBookItem>
): List<MistakeBookItem> {
  if (current.isEmpty()) {
    return imported
  }
  if (imported.isEmpty()) {
    return current
  }

  val importedIds = imported.map { item -> item.id }.toSet()
  return imported + current.filterNot { item -> item.id in importedIds }
}

fun parseMistakeBookJson(raw: String): Result<List<MistakeBookItem>> {
  return runCatching {
    val root = JSONObject(raw)
    root.optJSONArray("items")?.toMistakeBookItems().orEmpty()
  }
}

private fun MistakeBookItem.toJson(): JSONObject {
  return JSONObject()
    .put("id", id)
    .put("question", question)
    .put("imageRefs", JSONArray(imageRefs))
    .put("subject", subject)
    .put("questionType", questionType)
    .put("knowledgeTags", JSONArray(knowledgeTags))
    .put("studentAnswer", studentAnswer)
    .put("correctAnswer", correctAnswer)
    .put("explanation", explanation)
    .put("mistakeReason", mistakeReason)
    .put("mistakeType", mistakeType?.name ?: JSONObject.NULL)
    .put("status", status.name)
    .put("createdAt", createdAt)
    .put("updatedAt", updatedAt)
    .put("sourceSavedQuestionId", sourceSavedQuestionId ?: JSONObject.NULL)
    .put("recognitionDraftId", recognitionDraftId ?: JSONObject.NULL)
    .put("reviewState", reviewState.toJson())
    .put(
      "reviewAttempts",
      JSONArray().apply {
        reviewAttempts.forEach { attempt -> put(attempt.toJson()) }
      }
    )
}

private fun MistakeReviewState.toJson(): JSONObject {
  return JSONObject()
    .put("nextReviewAt", nextReviewAt ?: JSONObject.NULL)
    .put("lastReviewedAt", lastReviewedAt ?: JSONObject.NULL)
    .put("reviewCount", reviewCount)
    .put("correctStreak", correctStreak)
    .put("easeFactor", easeFactor)
    .put("currentIntervalMillis", currentIntervalMillis)
    .put("completedAt", completedAt ?: JSONObject.NULL)
}

private fun MistakeReviewAttempt.toJson(): JSONObject {
  return JSONObject()
    .put("id", id)
    .put("reviewedAt", reviewedAt)
    .put("userAnswer", userAnswer)
    .put("isCorrect", isCorrect)
    .put("judgementSource", judgementSource.name)
    .put("modelSuggestion", modelSuggestion)
    .put("note", note)
}

private fun JSONArray.toMistakeBookItems(): List<MistakeBookItem> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      item.toMistakeBookItem()?.let(::add)
    }
  }
}

private fun JSONObject.toMistakeBookItem(): MistakeBookItem? {
  val id = optString("id").trim()
  if (id.isBlank()) {
    return null
  }
  val question = optString("question").trim()
  val correctAnswer = optString("correctAnswer").trim()
  val createdAt = optLong("createdAt", System.currentTimeMillis())
  val updatedAt = optLong("updatedAt", createdAt)
  val ready = question.isNotBlank() && correctAnswer.isNotBlank()
  val parsedStatus = optNullableString("status")?.toMistakeStatusOrDefault()
  val status = normalizeStoredStatus(
    status = parsedStatus,
    ready = ready,
    completedAt = optJSONObject("reviewState")?.optNullableLong("completedAt")
  )
  val reviewState = optJSONObject("reviewState")
    ?.toMistakeReviewState(createdAt = createdAt, ready = ready, status = status)
    ?: MistakeReviewState(nextReviewAt = if (ready) createdAt else null)

  return MistakeBookItem(
    id = id,
    question = question,
    imageRefs = optJSONArray("imageRefs").toStringList(),
    subject = optString("subject").trim(),
    questionType = optString("questionType").trim(),
    knowledgeTags = optJSONArray("knowledgeTags").toStringList().distinct().take(12),
    studentAnswer = optString("studentAnswer").trim(),
    correctAnswer = correctAnswer,
    explanation = optString("explanation").trim(),
    mistakeReason = optString("mistakeReason").trim(),
    mistakeType = optNullableString("mistakeType")?.toMistakeTypeOrNull(),
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sourceSavedQuestionId = optNullableString("sourceSavedQuestionId"),
    recognitionDraftId = optNullableString("recognitionDraftId"),
    reviewState = if (!ready) reviewState.copy(nextReviewAt = null) else reviewState,
    reviewAttempts = optJSONArray("reviewAttempts")?.toMistakeReviewAttempts().orEmpty()
  )
}

private fun JSONObject.toMistakeReviewState(
  createdAt: Long,
  ready: Boolean,
  status: MistakeStatus
): MistakeReviewState {
  val nextReviewAt = optNullableLong("nextReviewAt")
    ?: if (ready && status != MistakeStatus.COMPLETED && status != MistakeStatus.ARCHIVED) createdAt else null

  return MistakeReviewState(
    nextReviewAt = nextReviewAt,
    lastReviewedAt = optNullableLong("lastReviewedAt"),
    reviewCount = optInt("reviewCount", 0).coerceAtLeast(0),
    correctStreak = optInt("correctStreak", 0).coerceAtLeast(0),
    easeFactor = optDouble("easeFactor", MistakeSrsEngine.DEFAULT_EASE_FACTOR)
      .coerceAtLeast(MistakeSrsEngine.MIN_EASE_FACTOR),
    currentIntervalMillis = optLong("currentIntervalMillis", 0L).coerceAtLeast(0L),
    completedAt = optNullableLong("completedAt")
  )
}

private fun JSONArray.toMistakeReviewAttempts(): List<MistakeReviewAttempt> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      val reviewedAt = item.optLong("reviewedAt", 0L)
      add(
        MistakeReviewAttempt(
          id = item.optString("id").trim().ifBlank { "attempt-$reviewedAt-${index + 1}" },
          reviewedAt = reviewedAt,
          userAnswer = item.optString("userAnswer").trim(),
          isCorrect = item.optBoolean("isCorrect", false),
          judgementSource = item.optString("judgementSource").toReviewJudgementSourceOrDefault(),
          modelSuggestion = item.optString("modelSuggestion").trim(),
          note = item.optString("note").trim()
        )
      )
    }
  }
}

private fun JSONArray?.toStringList(): List<String> {
  val array = this ?: return emptyList()
  return buildList {
    for (index in 0 until array.length()) {
      val value = array.optString(index).trim()
      if (value.isNotBlank() && value != "null") {
        add(value)
      }
    }
  }
}

private fun JSONObject.optNullableString(name: String): String? {
  val value = opt(name)
  return when (value) {
    null, JSONObject.NULL -> null
    else -> value.toString().trim().takeIf { it.isNotBlank() && it != "null" }
  }
}

private fun JSONObject.optNullableLong(name: String): Long? {
  val value = opt(name)
  return when (value) {
    null, JSONObject.NULL -> null
    is Number -> value.toLong().takeIf { it >= 0L }
    is String -> value.trim().toLongOrNull()?.takeIf { it >= 0L }
    else -> null
  }
}

private fun String.toMistakeStatusOrDefault(): MistakeStatus {
  return runCatching { MistakeStatus.valueOf(trim()) }.getOrDefault(MistakeStatus.DRAFT)
}

private fun String.toReviewJudgementSourceOrDefault(): MistakeReviewJudgementSource {
  return runCatching { MistakeReviewJudgementSource.valueOf(trim()) }.getOrDefault(MistakeReviewJudgementSource.USER)
}

private fun String.toMistakeTypeOrNull(): MistakeType? {
  val normalized = trim()
  return runCatching { MistakeType.valueOf(normalized) }.getOrNull()
    ?: when (normalized) {
      "概念错误" -> MistakeType.CONCEPT_ERROR
      "计算错误" -> MistakeType.CALCULATION_ERROR
      "审题错误" -> MistakeType.READING_ERROR
      "方法错误" -> MistakeType.METHOD_ERROR
      else -> null
    }
}

private fun normalizeStoredStatus(
  status: MistakeStatus?,
  ready: Boolean,
  completedAt: Long?
): MistakeStatus {
  if (!ready) {
    return MistakeStatus.DRAFT
  }
  if (status == MistakeStatus.COMPLETED || completedAt != null) {
    return MistakeStatus.COMPLETED
  }
  if (status == MistakeStatus.ARCHIVED) {
    return MistakeStatus.ARCHIVED
  }
  return status ?: MistakeStatus.DUE
}

private fun sanitizeImageFileName(raw: String): String {
  val trimmed = raw.trim().ifBlank { "mistake-image.jpg" }
  val normalized = trimmed
    .replace(Regex("[^A-Za-z0-9._-]+"), "-")
    .replace(Regex("-+"), "-")
    .trim('-', '.', '_')
    .ifBlank { "mistake-image.jpg" }
  val hasExtension = normalized.substringAfterLast('.', missingDelimiterValue = "").isNotBlank() &&
    normalized.substringAfterLast('.').length in 2..5
  return if (hasExtension) normalized else "$normalized.jpg"
}
