package com.studysuit.aiqa.ui

internal fun buildIntroGuideContent(): String {
  return listOf(
    "你好，我是你的学习搭子。主界面里每个题目都独立回答，不共享上下文。",
    "每道题都会生成一组可滑动的回复段落；回复气泡最底下时间那一层也支持左滑交互。",
    "底部时间那一层左滑松手会直接进入本题追问；段落卡左滑松手会自动讲解；左滑后继续按住会进入语音追问。",
    "右滑松手会打开该卡片详解；右滑并停留会进入精细追问，当前题目的追问链会持续保留。"
  ).joinToString(separator = "\n\n")
}

internal fun buildUiStateFromSession(
  session: StoredSession,
  ankiCards: List<AnkiCard>,
  settings: RuntimeSettings,
  toastMessage: String?
): ChatUiState {
  return ChatUiState(
    messages = session.messages,
    histories = session.histories,
    profile = session.profile,
    input = session.input,
    selectedSpanId = null,
    selectedDetailId = null,
    quickFollowupSpanId = session.quickFollowupSpanId,
    quickFollowupDetailId = session.quickFollowupDetailId,
    activePage = session.activePage,
    savedQuestions = session.savedQuestions,
    knowledgePoints = session.knowledgePoints,
    ankiCards = sortAnkiCardsForReview(ankiCards),
    activeSessionId = session.id,
    toastMessage = toastMessage,
    isLoading = false,
    isSettingsOpen = false,
    settings = settings,
    settingsDraft = settings
  )
}

internal fun toStoredSessionSnapshot(
  state: ChatUiState,
  title: String,
  createdAt: Long,
  updatedAt: Long
): StoredSession {
  return StoredSession(
    id = state.activeSessionId,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messages = state.messages,
    histories = state.histories,
    profile = state.profile,
    input = state.input,
    activePage = state.activePage,
    quickFollowupSpanId = state.quickFollowupSpanId,
    quickFollowupDetailId = state.quickFollowupDetailId,
    savedQuestions = state.savedQuestions,
    knowledgePoints = state.knowledgePoints,
    ankiCards = state.ankiCards
  )
}

internal fun createInitialSessionState(
  sessionId: String,
  introMessage: ChatMessage.Assistant,
  ankiCards: List<AnkiCard>,
  settings: RuntimeSettings,
  settingsDraft: RuntimeSettings,
  showIntroToast: Boolean
): ChatUiState {
  return ChatUiState(
    messages = listOf(introMessage),
    histories = emptyMap(),
    profile = ProfileState(level = "高二 · 进阶冲刺"),
    input = "",
    selectedSpanId = null,
    selectedDetailId = null,
    quickFollowupSpanId = null,
    quickFollowupDetailId = null,
    activePage = WorkspacePage.CHAT,
    savedQuestions = emptyList(),
    knowledgePoints = emptyMap(),
    ankiCards = sortAnkiCardsForReview(ankiCards),
    activeSessionId = sessionId,
    toastMessage = if (showIntroToast) "已开始新对话" else null,
    isLoading = false,
    isSettingsOpen = false,
    settings = settings,
    settingsDraft = settingsDraft
  )
}

internal fun buildSessionTitle(messages: List<ChatMessage>, fallbackTime: String): String {
  val firstUserText = messages.asSequence()
    .filterIsInstance<ChatMessage.User>()
    .map { user -> user.text.trim() }
    .firstOrNull { text -> text.isNotBlank() }

  if (!firstUserText.isNullOrBlank()) {
    return firstUserText.take(18)
  }

  return "新会话 $fallbackTime"
}

internal fun buildSyncedSessionSnapshot(
  state: ChatUiState,
  fallbackTime: String,
  now: Long,
  existingCreatedAt: Long?
): StoredSession {
  return toStoredSessionSnapshot(
    state = state,
    title = buildSessionTitle(state.messages, fallbackTime),
    createdAt = existingCreatedAt ?: now,
    updatedAt = now
  )
}

internal fun buildPersistedSessionsPayload(
  state: ChatUiState,
  sessions: List<StoredSession>
): PersistedSessions? {
  val activeSessionId = state.activeSessionId.ifBlank { return null }
  return PersistedSessions(
    activeSessionId = activeSessionId,
    settings = state.settings,
    sessions = sessions
  )
}
