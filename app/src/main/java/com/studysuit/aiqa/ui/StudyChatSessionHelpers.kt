package com.studysuit.aiqa.ui

internal fun buildIntroGuideContent(): String {
  return listOf(
    "你好，我是你的学习搭子。这个界面看起来是普通 AI Chat，但每一段都能左滑右滑交互。",
    "左滑松手会自动讲解当前段落，不会把讲解追加到底部，而是存进段落详解记录。",
    "左滑后不松手会进入语音追问模式，松手即提交追问并更新你的学习画像。",
    "右滑松手会打开该段详解弹窗；右滑并停留会进入快捷追问子界面，支持嵌套追问。"
  ).joinToString(separator = "\n\n")
}

internal fun buildUiStateFromSession(
  session: StoredSession,
  ankiCards: List<AnkiCard>,
  settings: RuntimeSettings,
  summaries: List<SessionSummary>,
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
    knowledgePoints = session.knowledgePoints,
    ankiCards = sortAnkiCardsForReview(ankiCards),
    activeSessionId = session.id,
    sessionSummaries = summaries,
    isSessionsOpen = false,
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
    knowledgePoints = emptyMap(),
    ankiCards = sortAnkiCardsForReview(ankiCards),
    activeSessionId = sessionId,
    sessionSummaries = emptyList(),
    isSessionsOpen = false,
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
