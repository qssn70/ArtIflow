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
  isVoice: Boolean,
  clearInput: Boolean = false
): ChatUiState {
  return markSpanProcessing(
    current.copy(
      input = if (clearInput) "" else current.input,
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
    messages = upsertAssistantMessage(current.messages, assistantMessage),
    knowledgePoints = updatedKnowledge,
    toastMessage = toastMessage
  )
}

internal fun upsertAssistantMessageState(
  current: ChatUiState,
  assistantMessage: ChatMessage.Assistant
): ChatUiState {
  return current.copy(messages = upsertAssistantMessage(current.messages, assistantMessage))
}

private fun upsertAssistantMessage(
  messages: List<ChatMessage>,
  assistantMessage: ChatMessage.Assistant
): List<ChatMessage> {
  val index = messages.indexOfFirst { message -> message.id == assistantMessage.id }
  if (index < 0) {
    return messages + assistantMessage
  }

  return messages.toMutableList().also { mutable ->
    mutable[index] = assistantMessage
  }
}

internal fun rollbackQueuedUserMessageState(
  current: ChatUiState,
  messageId: String,
  restoredInput: String? = null,
  toastMessage: String? = null
): ChatUiState {
  val remainingMessages = current.messages.filterNot { message -> message.id == messageId }
  val nextInput = if (restoredInput.isNullOrBlank() || current.input.isNotBlank()) {
    current.input
  } else {
    restoredInput
  }

  return current.copy(
    messages = remainingMessages,
    input = nextInput,
    toastMessage = toastMessage
  )
}
