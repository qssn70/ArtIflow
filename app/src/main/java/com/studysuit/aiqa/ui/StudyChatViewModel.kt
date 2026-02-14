package com.studysuit.aiqa.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studysuit.aiqa.data.ArkApiClient
import com.studysuit.aiqa.data.OpenSpeechAsrClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StudyChatViewModel : ViewModel() {
  private var messageSeed = 0
  private var spanSeed = 0
  private var detailSeed = 0
  private var cardSeed = 0
  private var sessionSeed = 0
  private var conversationToken = 0L
  private var inFlightRequests = 0
  private val requestCoordinator = StudyChatRequestCoordinator(
    arkApiClient = ArkApiClient(),
    openSpeechAsrClient = OpenSpeechAsrClient()
  )
  private var sessionStorage: SessionStorage? = null
  private val sessionRegistry = SessionRegistry()

  private val _uiState = MutableStateFlow(ChatUiState())
  val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  init {
    resetConversation()
  }

  fun ensureStorage(context: Context) {
    if (sessionStorage != null) {
      return
    }

    sessionStorage = SessionStorage(context.applicationContext)
    restoreSessionsFromStorage()
  }

  fun onInputChanged(value: String) {
    updateUiState(persistSession = true) { current ->
      current.copy(input = value)
    }
  }

  fun sendQuestion() {
    val question = _uiState.value.input.trim()
    if (question.isEmpty()) {
      return
    }

    enqueueQuestionAndFetch(
      question = question,
      isFollowup = false,
      isVoice = false
    )
  }

  fun submitImageQuestion(imageBytes: ByteArray, source: String) {
    if (imageBytes.isEmpty()) {
      postToast("图片为空，无法搜题")
      return
    }

    val requestConversationToken = conversationToken
    val settings = _uiState.value.settings
    val question = "$source：请识别并讲解这道题"
    val userMessage = ChatMessage.User(
      id = nextMessageId(),
      time = currentTime(),
      text = question,
      imagePreviewBytes = imageBytes
    )

    startRequest(toastMessage = "$source 处理中...") { current ->
      current.copy(
        messages = current.messages + userMessage,
        profile = current.profile.updateWith(text = question, isFollowup = false, isVoice = false),
        knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(source))
      )
    }

    viewModelScope.launch {
      val result = requestCoordinator.replyForImageQuestion(imageBytes = imageBytes, settings = settings)

      if (requestConversationToken != conversationToken) {
        finishRequest()
        return@launch
      }

      result.onSuccess { reply ->
        val assistantMessage = createAssistantMessage(reply, question)
        finishRequest { current ->
          current.copy(
            messages = current.messages + assistantMessage,
            knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(reply)),
            toastMessage = "$source 已完成"
          )
        }
      }.onFailure { throwable ->
        if (throwable is CancellationException) {
          finishRequest()
          throw throwable
        }

        val errorHint = throwableHint(throwable, fallback = "网络不可用")
        finishRequest { current ->
          current.copy(toastMessage = "$source 失败：$errorHint")
        }
      }
    }
  }

  fun startNewChat() {
    resetConversation(showIntroToast = true)
  }

  fun openSessions() {
    updateUiState { current ->
      current.copy(isSessionsOpen = true)
    }
  }

  fun closeSessions() {
    updateUiState { current ->
      current.copy(isSessionsOpen = false)
    }
  }

  fun switchWorkspacePage(page: WorkspacePage) {
    updateUiState(persistSession = true) { current ->
      if (current.activePage == page) {
        current
      } else {
        current.copy(activePage = page)
      }
    }
  }

  fun switchSession(sessionId: String) {
    val target = sessionRegistry.get(sessionId) ?: return
    val now = System.currentTimeMillis()
    sessionRegistry.put(target.copy(updatedAt = now), moveToFront = true)
    val latestTarget = sessionRegistry.get(sessionId) ?: target
    val settings = _uiState.value.settings
    activateSession(
      session = latestTarget,
      settings = settings,
      toastMessage = "已切换到历史会话",
      persistSession = true
    )
  }

  fun deleteSession(sessionId: String) {
    val activeId = _uiState.value.activeSessionId
    val isActive = sessionId == activeId

    sessionRegistry.remove(sessionId)

    if (sessionRegistry.isEmpty()) {
      resetConversation(showIntroToast = false)
      postToast("会话已删除")
      return
    }

    if (!isActive) {
      updateUiState {
        it.copy(
          sessionSummaries = sessionRegistry.summaries(),
          toastMessage = "会话已删除"
        )
      }
      persistSessionsAsync()
      return
    }

    val fallbackId = sessionRegistry.firstIdOrNull() ?: return
    switchSession(fallbackId)
    postToast("会话已删除")
  }

  fun openSettings() {
    updateUiState { current ->
      current.copy(
        isSettingsOpen = true,
        settingsDraft = current.settings
      )
    }
  }

  fun closeSettings() {
    updateUiState { current ->
      current.copy(isSettingsOpen = false)
    }
  }

  fun setSettingsDraft(newSettings: RuntimeSettings) {
    updateUiState { current ->
      current.copy(settingsDraft = newSettings)
    }
  }

  fun resetSettingsDraft() {
    updateUiState { current ->
      current.copy(settingsDraft = RuntimeSettings.defaults())
    }
  }

  fun saveSettings() {
    updateUiState(persistSession = true) { current ->
      current.copy(
        settings = current.settingsDraft,
        isSettingsOpen = false,
        toastMessage = "设置已保存"
      )
    }
  }

  fun updateAnkiCard(cardId: String, front: String, back: String, tags: List<String>) {
    val normalizedFront = normalizeCardText(front, maxLen = 500)
    val normalizedBack = normalizeCardText(back, maxLen = 1200)
    val normalizedTags = tags.map(String::trim).filter(String::isNotEmpty).distinct().take(10)

    if (normalizedFront.isBlank() || normalizedBack.isBlank()) {
      postToast("题面和答案都不能为空")
      return
    }

    updateUiState(persistSession = true) { current ->
      val index = current.ankiCards.indexOfFirst { card -> card.id == cardId }
      if (index < 0) {
        current.copy(toastMessage = "卡片不存在")
      } else {
        val updatedCards = current.ankiCards.toMutableList()
        val card = updatedCards[index]
        updatedCards[index] = card.copy(
          front = normalizedFront,
          back = normalizedBack,
          tags = normalizedTags
        )
        current.copy(
          ankiCards = updatedCards,
          toastMessage = "Anki 卡片已更新"
        )
      }
    }
  }

  fun deleteAnkiCard(cardId: String) {
    updateUiState(persistSession = true) { current ->
      val remainingCards = current.ankiCards.filterNot { card -> card.id == cardId }
      if (remainingCards.size == current.ankiCards.size) {
        current.copy(toastMessage = "卡片不存在")
      } else {
        current.copy(
          ankiCards = remainingCards,
          toastMessage = "已删除 Anki 卡片"
        )
      }
    }
  }

  fun autoExplain(spanId: String) {
    val span = findSpanById(_uiState.value.messages, spanId) ?: return
    val requestConversationToken = conversationToken
    val settings = _uiState.value.settings

    startRequest(toastMessage = "正在生成该段讲解...") { current ->
      markSpanProcessing(current, spanId)
    }

    viewModelScope.launch {
      val result = requestCoordinator.replyForAutoExplain(spanContent = span.content, settings = settings)

      if (requestConversationToken != conversationToken) {
        finishRequest { current ->
          clearSpanProcessing(current, spanId)
        }
        return@launch
      }

      result.onSuccess { reply ->
        finishRequest { current ->
          val detail = SpanDetail(
            id = nextDetailId(),
            mode = "自动讲解",
            time = currentDateTime(),
            answer = reply
          )
          appendSpanDetailHistory(
            current = current,
            spanId = spanId,
            detail = detail,
            toastMessage = "已生成该段讲解，右滑可查看详解"
          )
        }
        generateAnkiCardByAiAsync(
          requestConversationToken = requestConversationToken,
          source = "左滑自动讲解",
          mode = "自动讲解",
          spanContent = span.content,
          question = null,
          answer = reply
        )
      }.onFailure { throwable ->
        if (throwable is CancellationException) {
          finishRequest { current ->
            clearSpanProcessing(current, spanId)
          }
          throw throwable
        }

        val errorHint = throwableHint(throwable, fallback = "网络不可用")

        finishRequest { current ->
          clearSpanProcessing(current, spanId, toastMessage = "自动讲解失败：$errorHint")
        }
      }
    }
  }

  fun submitVoiceFollowupAudio(spanId: String, audioBytes: ByteArray) {
    val span = findSpanById(_uiState.value.messages, spanId) ?: return
    runVoiceFollowupByOpenSpeech(span = span, audioBytes = audioBytes)
  }

  fun openDetails(spanId: String) {
    updateUiState { current ->
      current.copy(selectedSpanId = spanId)
    }
  }

  fun closeDetails() {
    updateUiState { current ->
      current.copy(selectedSpanId = null)
    }
  }

  fun showRecordingHint() {
    postToast("继续按住在录音，松手后提交追问")
  }

  fun showRecordingCanceled() {
    postToast("已取消语音追问")
  }

  fun showRecordingBusy() {
    postToast("已有录音任务进行中")
  }

  fun showRecordingStartFailed(reason: String) {
    postToast("录音启动失败：$reason")
  }

  fun showVoiceCaptureInvalid(reason: String) {
    postToast("录音无效：$reason")
  }

  fun showMicrophonePermissionDenied() {
    postToast("麦克风权限被拒绝，无法语音追问")
  }

  fun showCameraPermissionDenied() {
    postToast("相机权限被拒绝，无法拍照搜题")
  }

  fun showImageSearchCanceled() {
    postToast("已取消图片搜题")
  }

  fun showImageReadFailed(reason: String) {
    postToast("图片读取失败：$reason")
  }

  fun consumeToast() {
    updateUiState { current ->
      current.copy(toastMessage = null)
    }
  }

  private fun runVoiceFollowupByOpenSpeech(span: SpanData, audioBytes: ByteArray) {
    if (audioBytes.isEmpty()) {
      postToast("录音数据为空，未提交追问")
      return
    }

    val settings = _uiState.value.settings
    if (!requestCoordinator.canTranscribe(settings)) {
      postToast("未配置豆包语音识别，未提交追问")
      return
    }

    val requestConversationToken = conversationToken
    startRequest(toastMessage = "豆包语音识别中...") { current ->
      markSpanProcessing(current, span.id)
    }

    viewModelScope.launch {
      val asrResult = requestCoordinator.transcribe(audioBytes = audioBytes, settings = settings)
      if (requestConversationToken != conversationToken) {
        finishRequest { current ->
          clearSpanProcessing(current, span.id)
        }
        return@launch
      }

      finishRequest()

      val transcript = asrResult.getOrNull()?.trim().orEmpty()
      if (asrResult.isSuccess && transcript.isNotBlank()) {
        enqueueSpanFollowupAndFetch(
          span = span,
          followupQuestion = transcript,
          isVoice = true,
          mode = "语音追问"
        )
        return@launch
      }

      val errorHint = if (asrResult.isFailure) {
        throwableHint(asrResult.exceptionOrNull(), fallback = "识别失败")
      } else {
        "识别结果为空"
      }
      updateUiState { current ->
        clearSpanProcessing(
          current,
          span.id,
          toastMessage = "豆包语音识别失败：$errorHint，未提交追问"
        )
      }
    }
  }

  private fun enqueueSpanFollowupAndFetch(
    span: SpanData,
    followupQuestion: String,
    isVoice: Boolean,
    mode: String
  ) {
    val normalizedQuestion = followupQuestion.trim()
    if (normalizedQuestion.isBlank()) {
      postToast("追问为空，已取消")
      return
    }

    val requestConversationToken = conversationToken
    val settings = _uiState.value.settings
    startRequest(clearToast = true) { current ->
      markSpanProcessing(
        current.copy(
          profile = current.profile.updateWith(
            text = normalizedQuestion,
            isFollowup = true,
            isVoice = isVoice
          ),
          knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(normalizedQuestion))
        ),
        span.id
      )
    }

    val detailsSnapshot = _uiState.value.histories[span.id].orEmpty()

    viewModelScope.launch {
      val result = requestCoordinator.replyForSpanFollowup(
        span = span,
        followupQuestion = normalizedQuestion,
        details = detailsSnapshot,
        settings = settings
      )

      if (requestConversationToken != conversationToken) {
        finishRequest { current ->
          clearSpanProcessing(current, span.id)
        }
        return@launch
      }

      result.onSuccess { reply ->
        finishRequest { current ->
          val detail = SpanDetail(
            id = nextDetailId(),
            mode = mode,
            time = currentDateTime(),
            question = normalizedQuestion,
            answer = reply
          )
          appendSpanDetailHistory(
            current = current,
            spanId = span.id,
            detail = detail,
            toastMessage = "追问已保存，右滑查看本段详解"
          )
        }
        generateAnkiCardByAiAsync(
          requestConversationToken = requestConversationToken,
          source = mode,
          mode = mode,
          spanContent = span.content,
          question = normalizedQuestion,
          answer = reply
        )
      }.onFailure { throwable ->
        if (throwable is CancellationException) {
          finishRequest { current ->
            clearSpanProcessing(current, span.id)
          }
          throw throwable
        }

        val errorHint = throwableHint(throwable, fallback = "网络不可用")

        finishRequest { current ->
          clearSpanProcessing(current, span.id, toastMessage = "追问失败：$errorHint")
        }
      }
    }
  }

  private fun enqueueQuestionAndFetch(
    question: String,
    isFollowup: Boolean,
    isVoice: Boolean,
    clearInput: Boolean = true
  ) {
    val requestConversationToken = conversationToken
    val settings = _uiState.value.settings
    val userMessage = ChatMessage.User(id = nextMessageId(), time = currentTime(), text = question)

    startRequest(clearToast = true) { current ->
      current.copy(
        input = if (clearInput) "" else current.input,
        messages = current.messages + userMessage,
        profile = current.profile.updateWith(text = question, isFollowup = isFollowup, isVoice = isVoice),
        knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(question))
      )
    }

    val messagesSnapshot = _uiState.value.messages

    viewModelScope.launch {
      val result = requestCoordinator.replyForConversation(messages = messagesSnapshot, settings = settings)

      if (requestConversationToken != conversationToken) {
        finishRequest()
        return@launch
      }

      result.onSuccess { reply ->
        val assistantMessage = createAssistantMessage(reply, question)

        finishRequest { current ->
          current.copy(
            messages = current.messages + assistantMessage,
            toastMessage = null
          )
        }
      }.onFailure { throwable ->
        if (throwable is CancellationException) {
          finishRequest()
          throw throwable
        }

        val errorHint = throwableHint(throwable, fallback = "网络不可用")

        finishRequest { current ->
          current.copy(
            toastMessage = "回答失败：$errorHint"
          )
        }
      }
    }
  }

  private fun resetConversation(showIntroToast: Boolean = false) {
    resetRequestTracking()
    messageSeed = 0
    spanSeed = 0
    detailSeed = 0
    cardSeed = 0
    sessionSeed += 1

    val now = System.currentTimeMillis()
    val sessionId = "session-$now-$sessionSeed"
    val settings = _uiState.value.settings
    val settingsDraft = _uiState.value.settingsDraft
    val introMessage = createAssistantMessage(buildIntroGuideContent(), "初始化引导")

    val initialState = createInitialSessionState(
      sessionId = sessionId,
      introMessage = introMessage,
      settings = settings,
      settingsDraft = settingsDraft,
      showIntroToast = showIntroToast
    )

    sessionRegistry.put(
      toStoredSessionSnapshot(
        state = initialState,
        title = "新会话",
        createdAt = now,
        updatedAt = now
      ),
      moveToFront = true
    )

    _uiState.value = initialState.copy(
      sessionSummaries = sessionRegistry.summaries()
    )
    persistSessionsAsync()
  }

  private fun startRequest(
    toastMessage: String? = null,
    clearToast: Boolean = false,
    transform: (ChatUiState) -> ChatUiState = { state -> state }
  ) {
    inFlightRequests += 1
    updateUiState(persistSession = true) { current ->
      val base = transform(current)
      base.copy(
        isLoading = inFlightRequests > 0,
        toastMessage = when {
          toastMessage != null -> toastMessage
          clearToast -> null
          else -> base.toastMessage
        }
      )
    }
  }

  private fun finishRequest(transform: (ChatUiState) -> ChatUiState = { state -> state }) {
    inFlightRequests = (inFlightRequests - 1).coerceAtLeast(0)
    updateUiState(persistSession = true) { current ->
      val base = transform(current)
      base.copy(isLoading = inFlightRequests > 0)
    }
  }

  private fun updateUiState(
    persistSession: Boolean = false,
    transform: (ChatUiState) -> ChatUiState
  ) {
    var latest: ChatUiState? = null
    _uiState.update { current ->
      val updated = transform(current)
      latest = updated
      updated
    }

    if (persistSession) {
      latest?.let { state ->
        syncActiveSessionSnapshot(state)
        persistSessionsAsync()
      }
    }
  }

  private fun syncActiveSessionSnapshot(state: ChatUiState) {
    val sessionId = state.activeSessionId.ifBlank { return }
    val now = System.currentTimeMillis()
    val title = buildSessionTitle(state.messages, currentTime())

    val createdAt = sessionRegistry.createdAtOf(sessionId) ?: now
    val updated = toStoredSessionSnapshot(
      state = state,
      title = title,
      createdAt = createdAt,
      updatedAt = now
    )

    sessionRegistry.put(updated, moveToFront = true)

    _uiState.update { current ->
      current.copy(sessionSummaries = sessionRegistry.summaries())
    }
  }

  private fun restoreSessionsFromStorage() {
    val store = sessionStorage ?: return
    val restored = store.load()
    if (restored == null || restored.sessions.isEmpty()) {
      sessionRegistry.clear()
      resetConversation()
      return
    }

    sessionRegistry.replaceAll(restored.sessions)

    val activeId = restored.activeSessionId.takeIf { id -> sessionRegistry.contains(id) }
      ?: sessionRegistry.firstIdOrNull()
    val active = activeId?.let { id -> sessionRegistry.get(id) } ?: run {
      sessionRegistry.clear()
      resetConversation()
      return
    }

    val settings = restored.settings
    activateSession(
      session = active,
      settings = settings,
      toastMessage = null,
      persistSession = false
    )
  }

  private fun persistSessionsAsync() {
    val store = sessionStorage ?: return
    val state = _uiState.value
    val activeId = state.activeSessionId.ifBlank { return }
    val settings = state.settings
    val sessionsSnapshot = sessionRegistry.orderedSessions()

    viewModelScope.launch(Dispatchers.IO) {
      store.save(
        PersistedSessions(
          activeSessionId = activeId,
          settings = settings,
          sessions = sessionsSnapshot
        )
      )
    }
  }

  private fun createAssistantMessage(content: String, sourceQuestion: String): ChatMessage.Assistant {
    val spans = splitParagraphs(content).map { paragraph ->
      SpanData(id = nextSpanId(), content = paragraph, sourceQuestion = sourceQuestion)
    }

    return ChatMessage.Assistant(
      id = nextMessageId(),
      time = currentTime(),
      spans = spans
    )
  }

  private fun createAnkiCard(
    front: String,
    back: String,
    source: String,
    tags: List<String>
  ): AnkiCard {
    val normalizedFront = normalizeCardText(front, maxLen = 500)
    val normalizedBack = normalizeCardText(back, maxLen = 1200)
    val effectiveTags = tags
      .map(String::trim)
      .filter(String::isNotEmpty)
      .distinct()
      .take(10)

    return AnkiCard(
      id = nextCardId(),
      front = normalizedFront,
      back = normalizedBack,
      tags = effectiveTags,
      source = source,
      createdAt = System.currentTimeMillis()
    )
  }

  private fun generateAnkiCardByAiAsync(
    requestConversationToken: Long,
    source: String,
    mode: String,
    spanContent: String,
    question: String?,
    answer: String
  ) {
    val settingsSnapshot = _uiState.value.settings
    val profileSnapshot = _uiState.value.profile
    val knowledgeSnapshot = _uiState.value.knowledgePoints

    viewModelScope.launch {
      val result = requestCoordinator.generateAnkiPayload(
        AnkiGenerationInput(
          mode = mode,
          spanContent = spanContent,
          question = question,
          answer = answer,
          profile = profileSnapshot,
          knowledgePoints = knowledgeSnapshot,
          settings = settingsSnapshot
        )
      )

      if (requestConversationToken != conversationToken) {
        return@launch
      }

      result.getOrNull()?.let { parsedPayload ->
        val parsed = createAnkiCard(
          front = parsedPayload.front,
          back = parsedPayload.back,
          source = source,
          tags = parsedPayload.tags
        )
        updateUiState(persistSession = true) { current ->
          current.copy(ankiCards = prependAnkiCard(current.ankiCards, parsed))
        }
      }
    }
  }

  private fun activateSession(
    session: StoredSession,
    settings: RuntimeSettings,
    toastMessage: String?,
    persistSession: Boolean
  ) {
    applySessionSeeds(session)
    resetRequestTracking()
    _uiState.value = buildUiStateFromSession(
      session = session,
      settings = settings,
      summaries = sessionRegistry.summaries(),
      toastMessage = toastMessage
    )
    if (persistSession) {
      persistSessionsAsync()
    }
  }

  private fun applySessionSeeds(session: StoredSession) {
    val seeds = deriveSessionSeeds(session)
    messageSeed = seeds.messageSeed
    spanSeed = seeds.spanSeed
    detailSeed = seeds.detailSeed
    cardSeed = seeds.cardSeed
  }

  private fun resetRequestTracking() {
    conversationToken += 1
    inFlightRequests = 0
  }

  private fun throwableHint(throwable: Throwable?, fallback: String): String {
    return throwable?.message?.take(80).orEmpty().ifBlank { fallback }
  }

  private fun postToast(message: String) {
    updateUiState { current ->
      current.copy(toastMessage = message)
    }
  }

  private fun nextMessageId(): String {
    messageSeed += 1
    return "msg-$messageSeed"
  }

  private fun nextSpanId(): String {
    spanSeed += 1
    return "span-$spanSeed"
  }

  private fun nextDetailId(): String {
    detailSeed += 1
    return "detail-$detailSeed"
  }

  private fun nextCardId(): String {
    cardSeed += 1
    return "card-$cardSeed"
  }

  private fun currentTime(): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.SIMPLIFIED_CHINESE)
    return formatter.format(Date())
  }

  private fun currentDateTime(): String {
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
    return formatter.format(Date())
  }
}
