package com.studysuit.aiqa.data

import kotlin.math.roundToLong

enum class MistakeStatus {
  DRAFT,
  ACTIVE,
  DUE,
  COMPLETED,
  ARCHIVED
}

enum class MistakeRecognitionStatus {
  PENDING,
  OCR_READY,
  AI_READY,
  FAILED,
  MANUAL
}

enum class MistakeReviewJudgementSource {
  USER,
  MODEL,
  USER_CONFIRMED_MODEL
}

data class MistakeReviewState(
  val nextReviewAt: Long? = null,
  val lastReviewedAt: Long? = null,
  val reviewCount: Int = 0,
  val correctStreak: Int = 0,
  val easeFactor: Double = MistakeSrsEngine.DEFAULT_EASE_FACTOR,
  val currentIntervalMillis: Long = 0L,
  val completedAt: Long? = null
)

data class MistakeReviewAttempt(
  val id: String,
  val reviewedAt: Long,
  val userAnswer: String = "",
  val isCorrect: Boolean,
  val judgementSource: MistakeReviewJudgementSource = MistakeReviewJudgementSource.USER,
  val modelSuggestion: String = "",
  val note: String = ""
)

data class MistakeRecognitionDraft(
  val id: String,
  val imageRefs: List<String> = emptyList(),
  val ocrText: String = "",
  val question: String = "",
  val subject: String = "",
  val questionType: String = "",
  val knowledgeTags: List<String> = emptyList(),
  val studentAnswer: String = "",
  val correctAnswer: String = "",
  val explanation: String = "",
  val mistakeReason: String = "",
  val mistakeType: MistakeType? = null,
  val status: MistakeRecognitionStatus = MistakeRecognitionStatus.PENDING,
  val errorMessage: String = "",
  val createdAt: Long,
  val updatedAt: Long
)

data class MistakeBookItem(
  val id: String,
  val question: String,
  val imageRefs: List<String> = emptyList(),
  val subject: String = "",
  val questionType: String = "",
  val knowledgeTags: List<String> = emptyList(),
  val studentAnswer: String = "",
  val correctAnswer: String = "",
  val explanation: String = "",
  val mistakeReason: String = "",
  val mistakeType: MistakeType? = null,
  val status: MistakeStatus,
  val createdAt: Long,
  val updatedAt: Long,
  val sourceSavedQuestionId: String? = null,
  val recognitionDraftId: String? = null,
  val reviewState: MistakeReviewState = MistakeReviewState(),
  val reviewAttempts: List<MistakeReviewAttempt> = emptyList()
) {
  val isReadyForReview: Boolean
    get() = question.trim().isNotBlank() && correctAnswer.trim().isNotBlank()

  companion object {
    fun create(
      id: String,
      question: String,
      correctAnswer: String,
      imageRefs: List<String> = emptyList(),
      subject: String = "",
      questionType: String = "",
      knowledgeTags: List<String> = emptyList(),
      studentAnswer: String = "",
      explanation: String = "",
      mistakeReason: String = "",
      mistakeType: MistakeType? = null,
      createdAt: Long,
      sourceSavedQuestionId: String? = null,
      recognitionDraftId: String? = null
    ): MistakeBookItem {
      val normalizedQuestion = question.trim()
      val normalizedAnswer = correctAnswer.trim()
      val ready = normalizedQuestion.isNotBlank() && normalizedAnswer.isNotBlank()
      return MistakeBookItem(
        id = id.trim().ifBlank { "mistake-$createdAt" },
        question = normalizedQuestion,
        imageRefs = imageRefs.map(String::trim).filter(String::isNotBlank).distinct(),
        subject = subject.trim(),
        questionType = questionType.trim(),
        knowledgeTags = knowledgeTags.map(String::trim).filter(String::isNotBlank).distinct().take(12),
        studentAnswer = studentAnswer.trim(),
        correctAnswer = normalizedAnswer,
        explanation = explanation.trim(),
        mistakeReason = mistakeReason.trim(),
        mistakeType = mistakeType,
        status = if (ready) MistakeStatus.DUE else MistakeStatus.DRAFT,
        createdAt = createdAt,
        updatedAt = createdAt,
        sourceSavedQuestionId = sourceSavedQuestionId?.trim()?.ifBlank { null },
        recognitionDraftId = recognitionDraftId?.trim()?.ifBlank { null },
        reviewState = MistakeReviewState(nextReviewAt = if (ready) createdAt else null)
      )
    }
  }
}

object MistakeSrsEngine {
  const val DEFAULT_EASE_FACTOR = 2.5
  const val MIN_EASE_FACTOR = 1.3
  const val WRONG_REVIEW_INTERVAL_MILLIS = 10L * 60L * 1000L
  const val DAY_MILLIS = 24L * 60L * 60L * 1000L
  const val COMPLETION_MIN_GAP_MILLIS = 7L * DAY_MILLIS

  fun dueMistakes(items: List<MistakeBookItem>, now: Long = System.currentTimeMillis()): List<MistakeBookItem> {
    return items
      .asSequence()
      .filter { item -> item.isReadyForReview }
      .filterNot { item -> item.status == MistakeStatus.DRAFT || item.status == MistakeStatus.COMPLETED || item.status == MistakeStatus.ARCHIVED }
      .filter { item -> (item.reviewState.nextReviewAt ?: Long.MAX_VALUE) <= now }
      .map { item -> item.copy(status = MistakeStatus.DUE) }
      .sortedWith(compareBy<MistakeBookItem> { item -> item.reviewState.nextReviewAt ?: Long.MAX_VALUE }.thenBy { it.createdAt })
      .toList()
  }

  fun recordReview(
    item: MistakeBookItem,
    isCorrect: Boolean,
    reviewedAt: Long = System.currentTimeMillis(),
    userAnswer: String = "",
    judgementSource: MistakeReviewJudgementSource = MistakeReviewJudgementSource.USER,
    modelSuggestion: String = "",
    note: String = ""
  ): MistakeBookItem {
    val previousState = item.reviewState
    val previousReviewedAt = previousState.lastReviewedAt
    val nextReviewCount = previousState.reviewCount + 1
    val attempt = MistakeReviewAttempt(
      id = "attempt-$reviewedAt-$nextReviewCount",
      reviewedAt = reviewedAt,
      userAnswer = userAnswer.trim(),
      isCorrect = isCorrect,
      judgementSource = judgementSource,
      modelSuggestion = modelSuggestion.trim(),
      note = note.trim()
    )

    val nextState = if (isCorrect) {
      val nextStreak = previousState.correctStreak + 1
      val nextEase = (previousState.easeFactor + 0.05).coerceAtLeast(MIN_EASE_FACTOR)
      val interval = correctIntervalMillis(nextStreak, previousState.currentIntervalMillis, nextEase)
      val shouldComplete = nextStreak >= 3 &&
        previousReviewedAt != null &&
        reviewedAt - previousReviewedAt >= COMPLETION_MIN_GAP_MILLIS

      previousState.copy(
        reviewCount = nextReviewCount,
        correctStreak = nextStreak,
        easeFactor = nextEase,
        currentIntervalMillis = interval,
        lastReviewedAt = reviewedAt,
        nextReviewAt = if (shouldComplete) null else reviewedAt + interval,
        completedAt = if (shouldComplete) reviewedAt else null
      ) to if (shouldComplete) MistakeStatus.COMPLETED else MistakeStatus.ACTIVE
    } else {
      val nextEase = (previousState.easeFactor - 0.2).coerceAtLeast(MIN_EASE_FACTOR)
      previousState.copy(
        reviewCount = nextReviewCount,
        correctStreak = 0,
        easeFactor = nextEase,
        currentIntervalMillis = WRONG_REVIEW_INTERVAL_MILLIS,
        lastReviewedAt = reviewedAt,
        nextReviewAt = reviewedAt + WRONG_REVIEW_INTERVAL_MILLIS,
        completedAt = null
      ) to MistakeStatus.ACTIVE
    }

    return item.copy(
      status = nextState.second,
      updatedAt = reviewedAt,
      reviewState = nextState.first,
      reviewAttempts = item.reviewAttempts + attempt
    )
  }

  fun reopenCompleted(item: MistakeBookItem, reopenedAt: Long = System.currentTimeMillis()): MistakeBookItem {
    return item.copy(
      status = if (item.isReadyForReview) MistakeStatus.DUE else MistakeStatus.DRAFT,
      updatedAt = reopenedAt,
      reviewState = item.reviewState.copy(
        nextReviewAt = if (item.isReadyForReview) reopenedAt else null,
        completedAt = null
      )
    )
  }

  private fun correctIntervalMillis(streak: Int, previousIntervalMillis: Long, easeFactor: Double): Long {
    return when (streak) {
      1 -> DAY_MILLIS
      2 -> 7L * DAY_MILLIS
      else -> {
        val base = previousIntervalMillis.takeIf { it > 0L } ?: 7L * DAY_MILLIS
        (base * easeFactor).roundToLong().coerceAtLeast(7L * DAY_MILLIS)
      }
    }
  }
}
