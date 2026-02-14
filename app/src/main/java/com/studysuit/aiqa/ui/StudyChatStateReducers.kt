package com.studysuit.aiqa.ui

internal fun queueImageQuestionState(
  current: ChatUiState,
  userMessage: ChatMessage.User,
  question: String,
  source: String
): ChatUiState {
  return current.copy(
    messages = current.messages + userMessage,
    profile = current.profile.updateWith(text = question, isFollowup = false, isVoice = false),
    knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(source))
  )
}

internal fun queueQuestionState(
  current: ChatUiState,
  userMessage: ChatMessage.User,
  question: String,
  isFollowup: Boolean,
  isVoice: Boolean,
  clearInput: Boolean
): ChatUiState {
  return current.copy(
    input = if (clearInput) "" else current.input,
    messages = current.messages + userMessage,
    profile = current.profile.updateWith(text = question, isFollowup = isFollowup, isVoice = isVoice),
    knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(question))
  )
}

internal fun queueSpanFollowupState(
  current: ChatUiState,
  spanId: String,
  question: String,
  isVoice: Boolean
): ChatUiState {
  return markSpanProcessing(
    current.copy(
      profile = current.profile.updateWith(text = question, isFollowup = true, isVoice = isVoice),
      knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(question))
    ),
    spanId
  )
}

internal fun appendAssistantMessageState(
  current: ChatUiState,
  assistantMessage: ChatMessage.Assistant,
  toastMessage: String?,
  knowledgeTexts: List<String> = emptyList()
): ChatUiState {
  val updatedKnowledge = if (knowledgeTexts.isEmpty()) {
    current.knowledgePoints
  } else {
    mergeKnowledgePoints(current.knowledgePoints, knowledgeTexts)
  }

  return current.copy(
    messages = current.messages + assistantMessage,
    knowledgePoints = updatedKnowledge,
    toastMessage = toastMessage
  )
}
