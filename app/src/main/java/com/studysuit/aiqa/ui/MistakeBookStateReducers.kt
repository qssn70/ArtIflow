package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.MistakeBookItem
import com.studysuit.aiqa.data.MistakeRecognitionDraft
import com.studysuit.aiqa.data.MistakeReviewJudgementSource
import com.studysuit.aiqa.data.MistakeSrsEngine
import com.studysuit.aiqa.data.MistakeStatus

internal fun saveMistakeRecognitionDraftState(
  current: ChatUiState,
  draft: MistakeRecognitionDraft
): ChatUiState {
  val updatedDrafts = listOf(draft) + current.mistakeRecognitionDrafts.filterNot { existing -> existing.id == draft.id }
  return current.copy(
    activePage = WorkspacePage.MISTAKES,
    mistakeRecognitionDrafts = updatedDrafts,
    activeMistakeDraftId = draft.id,
    activeMistakeReviewId = null,
    isMistakeDueReviewMode = false,
    toastMessage = "已生成错题草稿"
  )
}

internal fun createMistakeBookItemFromDraft(
  draft: MistakeRecognitionDraft,
  itemId: String,
  now: Long
): MistakeBookItem {
  return MistakeBookItem.create(
    id = itemId,
    question = draft.question,
    correctAnswer = draft.correctAnswer,
    imageRefs = draft.imageRefs,
    subject = draft.subject,
    questionType = draft.questionType,
    knowledgeTags = draft.knowledgeTags,
    studentAnswer = draft.studentAnswer,
    explanation = draft.explanation,
    mistakeReason = draft.mistakeReason,
    mistakeType = draft.mistakeType,
    createdAt = now,
    recognitionDraftId = draft.id
  )
}

internal fun upsertMistakeItemState(
  current: ChatUiState,
  item: MistakeBookItem,
  toastMessage: String? = current.toastMessage
): ChatUiState {
  val exists = current.mistakeItems.any { existing -> existing.id == item.id }
  val updatedItems = if (exists) {
    current.mistakeItems.map { existing -> if (existing.id == item.id) item else existing }
  } else {
    listOf(item) + current.mistakeItems
  }

  return current.copy(
    mistakeItems = sortMistakeItems(updatedItems),
    activePage = WorkspacePage.MISTAKES,
    activeMistakeReviewId = item.id,
    activeMistakeDraftId = item.recognitionDraftId ?: current.activeMistakeDraftId,
    isMistakeDueReviewMode = false,
    toastMessage = toastMessage
  )
}

internal fun recordMistakeReviewState(
  current: ChatUiState,
  itemId: String,
  isCorrect: Boolean,
  reviewedAt: Long,
  userAnswer: String,
  judgementSource: MistakeReviewJudgementSource,
  modelSuggestion: String,
  note: String
): ChatUiState {
  val target = current.mistakeItems.firstOrNull { item -> item.id == itemId }
    ?: return current.copy(toastMessage = "错题不存在")
  val reviewed = MistakeSrsEngine.recordReview(
    item = target,
    isCorrect = isCorrect,
    reviewedAt = reviewedAt,
    userAnswer = userAnswer,
    judgementSource = judgementSource,
    modelSuggestion = modelSuggestion,
    note = note
  )
  val updatedItems = current.mistakeItems.map { item -> if (item.id == itemId) reviewed else item }
  return current.copy(
    mistakeItems = sortMistakeItems(updatedItems),
    activePage = WorkspacePage.MISTAKES,
    activeMistakeReviewId = reviewed.id,
    activeMistakeReviewSuggestion = current.activeMistakeReviewSuggestion?.takeUnless { suggestion ->
      suggestion.itemId == itemId
    },
    isMistakeDueReviewMode = current.isMistakeDueReviewMode && reviewed.status != MistakeStatus.COMPLETED,
    toastMessage = mistakeReviewToast(reviewed, isCorrect)
  )
}

internal fun setMistakeReviewSuggestionState(
  current: ChatUiState,
  suggestion: MistakeReviewSuggestion
): ChatUiState {
  val targetExists = current.mistakeItems.any { item -> item.id == suggestion.itemId }
  if (!targetExists) {
    return current.copy(toastMessage = "错题不存在")
  }
  return current.copy(
    activePage = WorkspacePage.MISTAKES,
    activeMistakeReviewId = suggestion.itemId,
    activeMistakeReviewSuggestion = suggestion,
    toastMessage = "模型已给出判题建议"
  )
}

internal fun confirmMistakeReviewSuggestionState(
  current: ChatUiState,
  itemId: String,
  finalIsCorrect: Boolean,
  reviewedAt: Long
): ChatUiState {
  val suggestion = current.activeMistakeReviewSuggestion
    ?.takeIf { activeSuggestion -> activeSuggestion.itemId == itemId }
    ?: return current.copy(toastMessage = "暂无模型判题建议")

  return recordMistakeReviewState(
    current = current,
    itemId = itemId,
    isCorrect = finalIsCorrect,
    reviewedAt = reviewedAt,
    userAnswer = suggestion.userAnswer,
    judgementSource = MistakeReviewJudgementSource.USER_CONFIRMED_MODEL,
    modelSuggestion = buildMistakeReviewSuggestionSummary(suggestion),
    note = suggestion.answerOcrText
  ).copy(activeMistakeReviewSuggestion = null)
}

internal fun addSavedQuestionToMistakeBookState(
  current: ChatUiState,
  savedQuestionId: String,
  itemId: String,
  imageRefs: List<String>,
  now: Long
): ChatUiState {
  val saved = current.savedQuestions.firstOrNull { question -> question.id == savedQuestionId }
    ?: return current.copy(toastMessage = "未找到归档题")
  val existing = current.mistakeItems.firstOrNull { item -> item.sourceSavedQuestionId == saved.id }
  val item = savedQuestionToMistakeBookItem(
    saved = saved,
    id = existing?.id ?: itemId,
    imageRefs = imageRefs,
    now = existing?.createdAt ?: now
  ).let { converted ->
    if (existing == null) {
      converted
    } else {
      converted.copy(
        reviewState = existing.reviewState,
        reviewAttempts = existing.reviewAttempts,
        status = existing.status,
        updatedAt = now
      )
    }
  }

  return upsertMistakeItemState(
    current = current,
    item = item,
    toastMessage = if (existing == null) "已加入错题本" else "已更新错题本条目"
  )
}

internal fun deleteMistakeItemState(current: ChatUiState, itemId: String): ChatUiState {
  val updatedItems = current.mistakeItems.filterNot { item -> item.id == itemId }
  if (updatedItems.size == current.mistakeItems.size) {
    return current.copy(toastMessage = "错题不存在")
  }
  return current.copy(
    mistakeItems = updatedItems,
    activeMistakeReviewId = current.activeMistakeReviewId.takeUnless { activeId -> activeId == itemId },
    toastMessage = "已删除错题"
  )
}

internal fun reopenMistakeItemState(current: ChatUiState, itemId: String, reopenedAt: Long): ChatUiState {
  val target = current.mistakeItems.firstOrNull { item -> item.id == itemId }
    ?: return current.copy(toastMessage = "错题不存在")
  val reopened = MistakeSrsEngine.reopenCompleted(target, reopenedAt = reopenedAt)
  return upsertMistakeItemState(
    current = current,
    item = reopened,
    toastMessage = "已重新加入复习"
  )
}

internal fun sortMistakeItems(items: List<MistakeBookItem>): List<MistakeBookItem> {
  return items.sortedWith(
    compareBy<MistakeBookItem> { item ->
      when (item.status) {
        MistakeStatus.DUE -> 0
        MistakeStatus.ACTIVE -> 1
        MistakeStatus.DRAFT -> 2
        MistakeStatus.COMPLETED -> 3
        MistakeStatus.ARCHIVED -> 4
      }
    }.thenBy { item -> item.reviewState.nextReviewAt ?: Long.MAX_VALUE }
      .thenByDescending { item -> item.updatedAt }
  )
}

private fun mistakeReviewToast(item: MistakeBookItem, isCorrect: Boolean): String {
  if (item.status == MistakeStatus.COMPLETED) {
    return "已连续做对，标记为已完成"
  }
  return if (isCorrect) {
    item.reviewState.nextReviewAt
      ?.let { next -> "已记录：做对，下次复习 ${formatSessionTime(next)}" }
      ?: "已记录：做对"
  } else {
    "已记录：做错，10分钟后再看"
  }
}

private fun buildMistakeReviewSuggestionSummary(suggestion: MistakeReviewSuggestion): String {
  return buildString {
    append("模型建议：")
    append(if (suggestion.isCorrect) "做对" else "做错")
    append("；置信度 ")
    append((suggestion.confidence.coerceIn(0.0, 1.0) * 100).toInt())
    append("%")
    suggestion.suggestedScore?.let { score ->
      append("；建议得分 ")
      append(score)
    }
    if (suggestion.reason.isNotBlank()) {
      append("；理由：")
      append(suggestion.reason)
    }
  }
}
