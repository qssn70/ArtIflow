package com.studysuit.aiqa.ui

import android.content.Context
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studysuit.aiqa.BuildConfig
import com.studysuit.aiqa.data.ArkApiClient
import com.studysuit.aiqa.data.ArkRequestMessage
import com.studysuit.aiqa.data.ArkRuntimeConfig
import com.studysuit.aiqa.data.OpenSpeechAsrClient
import com.studysuit.aiqa.data.OpenSpeechRuntimeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
  private val arkApiClient = ArkApiClient()
  private val openSpeechAsrClient = OpenSpeechAsrClient()
  private var sessionStorage: SessionStorage? = null
  private val sessionsById = linkedMapOf<String, StoredSession>()
  private val sessionOrder = mutableListOf<String>()

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

    val prompt = normalizeImagePrompt(settings.imagePrompt)
    val arkConfig = settings.toArkRuntimeConfig()

    viewModelScope.launch {
      val result = arkApiClient.generateReplyWithImage(
        prompt = prompt,
        imageBytes = imageBytes,
        mimeType = "image/jpeg",
        config = arkConfig
      )

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

        val errorHint = throwable.message?.take(80).orEmpty().ifBlank { "网络不可用" }
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
    val target = sessionsById[sessionId] ?: return
    val now = System.currentTimeMillis()
    sessionsById[sessionId] = target.copy(updatedAt = now)
    touchSession(sessionId)
    val latestTarget = sessionsById[sessionId] ?: target
    val settings = _uiState.value.settings
    hydrateSeeds(latestTarget)

    conversationToken += 1
    inFlightRequests = 0

    _uiState.value = ChatUiState(
      messages = latestTarget.messages,
      histories = latestTarget.histories,
      profile = latestTarget.profile,
      input = latestTarget.input,
      selectedSpanId = null,
      activePage = latestTarget.activePage,
      knowledgePoints = latestTarget.knowledgePoints,
      ankiCards = latestTarget.ankiCards,
      activeSessionId = latestTarget.id,
      sessionSummaries = currentSessionSummaries(),
      isSessionsOpen = false,
      toastMessage = "已切换到历史会话",
      isLoading = false,
      isSettingsOpen = false,
      settings = settings,
      settingsDraft = settings
    )
    persistSessionsAsync()
  }

  fun deleteSession(sessionId: String) {
    val activeId = _uiState.value.activeSessionId
    val isActive = sessionId == activeId

    sessionsById.remove(sessionId)
    sessionOrder.remove(sessionId)

    if (sessionsById.isEmpty()) {
      resetConversation(showIntroToast = false)
      postToast("会话已删除")
      return
    }

    if (!isActive) {
      updateUiState {
        it.copy(
          sessionSummaries = currentSessionSummaries(),
          toastMessage = "会话已删除"
        )
      }
      persistSessionsAsync()
      return
    }

    val fallbackId = sessionOrder.firstOrNull() ?: sessionsById.keys.first()
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
    val arkConfig = _uiState.value.settings.toArkRuntimeConfig()

    startRequest(toastMessage = "正在生成该段讲解...") { current ->
      current.copy(processingSpanIds = current.processingSpanIds + spanId)
    }

    val requestMessages = listOf(
      ArkRequestMessage(role = "user", text = buildAutoExplainPrompt(span.content))
    )

    viewModelScope.launch {
      val result = arkApiClient.generateReply(requestMessages, config = arkConfig)

      if (requestConversationToken != conversationToken) {
        finishRequest { current ->
          current.copy(processingSpanIds = current.processingSpanIds - spanId)
        }
        return@launch
      }

      result.onSuccess { reply ->
        finishRequest { current ->
          val updatedHistory = current.histories.toMutableMap()
          val detail = SpanDetail(
            id = nextDetailId(),
            mode = "自动讲解",
            time = currentDateTime(),
            answer = reply
          )
          updatedHistory[spanId] = listOf(detail) + current.histories[spanId].orEmpty()

          current.copy(
            histories = updatedHistory,
            processingSpanIds = current.processingSpanIds - spanId,
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
            current.copy(processingSpanIds = current.processingSpanIds - spanId)
          }
          throw throwable
        }

        val errorHint = throwable.message?.take(80).orEmpty().ifBlank { "网络不可用" }

        finishRequest { current ->
          current.copy(
            processingSpanIds = current.processingSpanIds - spanId,
            toastMessage = "自动讲解失败：$errorHint"
          )
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

    val speechConfig = _uiState.value.settings.toOpenSpeechRuntimeConfig()
    if (!openSpeechAsrClient.isConfigured(speechConfig)) {
      postToast("未配置豆包语音识别，未提交追问")
      return
    }

    val requestConversationToken = conversationToken
    startRequest(toastMessage = "豆包语音识别中...") { current ->
      current.copy(processingSpanIds = current.processingSpanIds + span.id)
    }

    viewModelScope.launch {
      val asrResult = openSpeechAsrClient.transcribeByAudioData(
        audioBytes = audioBytes,
        config = speechConfig
      )
      if (requestConversationToken != conversationToken) {
        finishRequest { current ->
          current.copy(processingSpanIds = current.processingSpanIds - span.id)
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
        asrResult.exceptionOrNull()?.message?.take(80).orEmpty().ifBlank { "识别失败" }
      } else {
        "识别结果为空"
      }
      updateUiState { current ->
        current.copy(
          processingSpanIds = current.processingSpanIds - span.id,
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
    val arkConfig = _uiState.value.settings.toArkRuntimeConfig()
    startRequest(clearToast = true) { current ->
      current.copy(
        profile = current.profile.updateWith(
          text = normalizedQuestion,
          isFollowup = true,
          isVoice = isVoice
        ),
        knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(normalizedQuestion)),
        processingSpanIds = current.processingSpanIds + span.id
      )
    }

    val historyForRequest = toSpanFollowupMessages(
      span = span,
      followupQuestion = normalizedQuestion,
      details = _uiState.value.histories[span.id].orEmpty()
    )

    viewModelScope.launch {
      val result = arkApiClient.generateReply(historyForRequest, config = arkConfig)

      if (requestConversationToken != conversationToken) {
        finishRequest { current ->
          current.copy(processingSpanIds = current.processingSpanIds - span.id)
        }
        return@launch
      }

      result.onSuccess { reply ->
        finishRequest { current ->
          val updatedHistory = current.histories.toMutableMap()
          val detail = SpanDetail(
            id = nextDetailId(),
            mode = mode,
            time = currentDateTime(),
            question = normalizedQuestion,
            answer = reply
          )
          updatedHistory[span.id] = listOf(detail) + current.histories[span.id].orEmpty()

          current.copy(
            histories = updatedHistory,
            processingSpanIds = current.processingSpanIds - span.id,
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
            current.copy(processingSpanIds = current.processingSpanIds - span.id)
          }
          throw throwable
        }

        val errorHint = throwable.message?.take(80).orEmpty().ifBlank { "网络不可用" }

        finishRequest { current ->
          current.copy(
            processingSpanIds = current.processingSpanIds - span.id,
            toastMessage = "追问失败：$errorHint"
          )
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
    val arkConfig = _uiState.value.settings.toArkRuntimeConfig()
    val userMessage = ChatMessage.User(id = nextMessageId(), time = currentTime(), text = question)

    startRequest(clearToast = true) { current ->
      current.copy(
        input = if (clearInput) "" else current.input,
        messages = current.messages + userMessage,
        profile = current.profile.updateWith(text = question, isFollowup = isFollowup, isVoice = isVoice),
        knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(question))
      )
    }

    val historyForRequest = toArkMessages(_uiState.value.messages)

    viewModelScope.launch {
      val result = arkApiClient.generateReply(historyForRequest, config = arkConfig)

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

        val errorHint = throwable.message?.take(80).orEmpty().ifBlank { "网络不可用" }

        finishRequest { current ->
          current.copy(
            toastMessage = "回答失败：$errorHint"
          )
        }
      }
    }
  }

  private fun toArkMessages(messages: List<ChatMessage>): List<ArkRequestMessage> {
    return messages
      .filterNot { message ->
        message is ChatMessage.Assistant && message.spans.firstOrNull()?.sourceQuestion == "初始化引导"
      }
      .takeLast(12)
      .mapNotNull { message ->
        when (message) {
          is ChatMessage.User -> {
            val text = message.text.trim()
            if (text.isBlank()) null else ArkRequestMessage(role = "user", text = text)
          }

          is ChatMessage.Assistant -> {
            val text = message.spans.joinToString(separator = "\n\n") { span -> span.content }.trim()
            if (text.isBlank()) null else ArkRequestMessage(role = "assistant", text = text)
          }
        }
      }
      .toList()
  }

  private fun toSpanFollowupMessages(
    span: SpanData,
    followupQuestion: String,
    details: List<SpanDetail>
  ): List<ArkRequestMessage> {
    val recentDetails = details.take(4).asReversed()
    val contextMessage = buildString {
      append("我们只讨论这一段内容，请基于段落回答追问。\n")
      append("回答要求：简洁直接，先结论后要点，默认不超过6行。\n")
      append("段落：")
      append(span.content)
    }

    return buildList {
      add(ArkRequestMessage(role = "user", text = contextMessage))

      recentDetails.forEach { detail ->
        detail.question?.takeIf { question -> question.isNotBlank() }?.let { question ->
          add(ArkRequestMessage(role = "user", text = question))
        }

        val answer = detail.answer.trim()
        if (answer.isNotBlank()) {
          add(ArkRequestMessage(role = "assistant", text = answer))
        }
      }

      add(ArkRequestMessage(role = "user", text = followupQuestion))
    }
  }

  private fun resetConversation(showIntroToast: Boolean = false) {
    conversationToken += 1
    inFlightRequests = 0
    messageSeed = 0
    spanSeed = 0
    detailSeed = 0
    cardSeed = 0
    sessionSeed += 1

    val intro = listOf(
      "你好，我是你的学习搭子。这个界面看起来是普通 AI Chat，但每一段都能左滑右滑交互。",
      "左滑松手会自动讲解当前段落，不会把讲解追加到底部，而是存进段落详解记录。",
      "左滑后不松手会进入语音追问模式，松手即提交追问并更新你的学习画像。",
      "右滑可拉出详解弹窗，回看该段追问与回答；追问内容不会追加到底部聊天。"
    ).joinToString(separator = "\n\n")

    val sessionId = "session-${System.currentTimeMillis()}-$sessionSeed"
    val settings = _uiState.value.settings
    val settingsDraft = _uiState.value.settingsDraft

    val initialState = ChatUiState(
      messages = listOf(createAssistantMessage(intro, "初始化引导")),
      histories = emptyMap(),
      profile = ProfileState(level = "高二 · 进阶冲刺"),
      input = "",
      selectedSpanId = null,
      activePage = WorkspacePage.CHAT,
      knowledgePoints = emptyMap(),
      ankiCards = emptyList(),
      activeSessionId = sessionId,
      sessionSummaries = emptyList(),
      isSessionsOpen = false,
      toastMessage = if (showIntroToast) "已开始新对话" else null,
      isLoading = false,
      isSettingsOpen = false,
      settings = settings,
      settingsDraft = settingsDraft
    )

    sessionsById[sessionId] = StoredSession(
      id = sessionId,
      title = "新会话",
      createdAt = System.currentTimeMillis(),
      updatedAt = System.currentTimeMillis(),
      messages = initialState.messages,
      histories = initialState.histories,
      profile = initialState.profile,
      input = initialState.input,
      activePage = initialState.activePage,
      knowledgePoints = initialState.knowledgePoints,
      ankiCards = initialState.ankiCards
    )
    sessionOrder.remove(sessionId)
    sessionOrder.add(0, sessionId)

    _uiState.value = initialState.copy(
      sessionSummaries = currentSessionSummaries()
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
    val title = buildSessionTitle(state.messages)

    val updated = StoredSession(
      id = sessionId,
      title = title,
      createdAt = sessionsById[sessionId]?.createdAt ?: now,
      updatedAt = now,
      messages = state.messages,
      histories = state.histories,
      profile = state.profile,
      input = state.input,
      activePage = state.activePage,
      knowledgePoints = state.knowledgePoints,
      ankiCards = state.ankiCards
    )

    sessionsById[sessionId] = updated
    touchSession(sessionId)

    _uiState.update { current ->
      current.copy(sessionSummaries = currentSessionSummaries())
    }
  }

  private fun touchSession(sessionId: String) {
    sessionOrder.remove(sessionId)
    sessionOrder.add(0, sessionId)
  }

  private fun currentSessionSummaries(): List<SessionSummary> {
    return sessionOrder.mapNotNull { id ->
      val item = sessionsById[id] ?: return@mapNotNull null
      SessionSummary(
        id = item.id,
        title = item.title,
        updatedAt = item.updatedAt,
        messageCount = item.messages.size
      )
    }.sortedByDescending { it.updatedAt }
  }

  private fun buildSessionTitle(messages: List<ChatMessage>): String {
    val firstUserText = messages.asSequence()
      .filterIsInstance<ChatMessage.User>()
      .map { user -> user.text.trim() }
      .firstOrNull { text -> text.isNotBlank() }

    if (!firstUserText.isNullOrBlank()) {
      return firstUserText.take(18)
    }

    return "新会话 ${currentTime()}"
  }

  private fun restoreSessionsFromStorage() {
    val store = sessionStorage ?: return
    val restored = store.load()
    if (restored == null || restored.sessions.isEmpty()) {
      sessionsById.clear()
      sessionOrder.clear()
      resetConversation()
      return
    }

    sessionsById.clear()
    sessionOrder.clear()

    restored.sessions.forEach { session ->
      sessionsById[session.id] = session
      sessionOrder += session.id
    }

    val activeId = restored.activeSessionId.takeIf { id -> sessionsById.containsKey(id) }
      ?: sessionOrder.first()
    val active = sessionsById[activeId] ?: run {
      sessionsById.clear()
      sessionOrder.clear()
      resetConversation()
      return
    }

    val settings = restored.settings
    hydrateSeeds(active)

    conversationToken += 1
    inFlightRequests = 0

    _uiState.value = ChatUiState(
      messages = active.messages,
      histories = active.histories,
      profile = active.profile,
      input = active.input,
      selectedSpanId = null,
      activePage = active.activePage,
      knowledgePoints = active.knowledgePoints,
      ankiCards = active.ankiCards,
      activeSessionId = active.id,
      sessionSummaries = currentSessionSummaries(),
      isSessionsOpen = false,
      toastMessage = null,
      isLoading = false,
      isSettingsOpen = false,
      settings = settings,
      settingsDraft = settings
    )
  }

  private fun hydrateSeeds(active: StoredSession) {
    messageSeed = active.messages.mapNotNull { message ->
      message.id.removePrefix("msg-").toIntOrNull()
    }.maxOrNull() ?: 0

    spanSeed = active.messages
      .filterIsInstance<ChatMessage.Assistant>()
      .flatMap { assistant -> assistant.spans }
      .mapNotNull { span -> span.id.removePrefix("span-").toIntOrNull() }
      .maxOrNull() ?: 0

    detailSeed = active.histories.values
      .flatten()
      .mapNotNull { detail -> detail.id.removePrefix("detail-").toIntOrNull() }
      .maxOrNull() ?: 0

    cardSeed = active.ankiCards.mapNotNull { card ->
      card.id.removePrefix("card-").toIntOrNull()
    }.maxOrNull() ?: 0
  }

  private fun persistSessionsAsync() {
    val store = sessionStorage ?: return
    val state = _uiState.value
    val activeId = state.activeSessionId.ifBlank { return }
    val settings = state.settings
    val sessionsSnapshot = sessionOrder.mapNotNull { id -> sessionsById[id] }

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

  private fun splitParagraphs(content: String): List<String> {
    val byBlankLines = content
      .split(Regex("\\n{2,}"))
      .map(String::trim)
      .filter(String::isNotEmpty)

    if (byBlankLines.size > 1) {
      return byBlankLines
    }

    val sentences = Regex("[^。！？!?]+[。！？!?]?").findAll(content).map { it.value }.toList()
    if (sentences.isEmpty()) {
      return listOf(content.trim()).filter(String::isNotEmpty)
    }

    val chunks = mutableListOf<String>()
    var current = ""

    sentences.forEach { sentence ->
      if ((current + sentence).length > 54 && current.isNotBlank()) {
        chunks += current.trim()
        current = sentence
      } else {
        current += sentence
      }
    }

    if (current.isNotBlank()) {
      chunks += current.trim()
    }

    return chunks
  }

  private fun buildAutoExplainPrompt(spanContent: String): String {
    return buildString {
      append("请只针对下面这一段内容做简洁讲解。")
      append("输出要求：")
      append("1) 用中文；2) 先1句结论；3) 再给2~3条关键点；4) 总字数尽量控制在120字内；5) 不要套话。")
      append("\n\n段落内容：")
      append(spanContent)
    }
  }

  private fun mergeKnowledgePoints(
    current: Map<String, Int>,
    texts: List<String>
  ): Map<String, Int> {
    val merged = current.toMutableMap()
    val points = texts
      .asSequence()
      .flatMap { text -> detectKnowledgePoints(text).asSequence() }
      .filter { point -> point.isNotBlank() }
      .distinct()
      .toList()

    points.forEach { point ->
      merged[point] = (merged[point] ?: 0) + 1
    }

    return merged
  }

  private fun detectKnowledgePoints(text: String): List<String> {
    val normalized = text.lowercase(Locale.getDefault())
    val matched = knowledgeRules
      .filter { rule -> rule.keywords.any { keyword -> normalized.contains(keyword) } }
      .map { rule -> rule.point }
      .distinct()

    return if (matched.isEmpty()) detectTopicsForProfile(text) else matched
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
    val cardConfig = settingsSnapshot.toArkRuntimeConfig().copy(systemPrompt = ANKI_CARD_SYSTEM_PROMPT)
    val profileSnapshot = _uiState.value.profile
    val knowledgeSnapshot = _uiState.value.knowledgePoints

    viewModelScope.launch {
      val prompt = buildAnkiGenerationPrompt(
        mode = mode,
        spanContent = spanContent,
        question = question,
        answer = answer,
        profile = profileSnapshot,
        knowledgePoints = knowledgeSnapshot
      )
      val result = arkApiClient.generateReply(
        messages = listOf(ArkRequestMessage(role = "user", text = prompt)),
        config = cardConfig
      )

      if (requestConversationToken != conversationToken) {
        return@launch
      }

      result.getOrNull()?.let { raw ->
        val parsed = parseAiAnkiCard(raw = raw, source = source) ?: return@let
        updateUiState(persistSession = true) { current ->
          current.copy(ankiCards = prependAnkiCard(current.ankiCards, parsed))
        }
      }
    }
  }

  private fun buildAnkiGenerationPrompt(
    mode: String,
    spanContent: String,
    question: String?,
    answer: String,
    profile: ProfileState,
    knowledgePoints: Map<String, Int>
  ): String {
    val topTopics = profile.topicHits.entries
      .sortedByDescending { entry -> entry.value }
      .take(4)
      .joinToString(separator = "，") { entry -> "${entry.key}(${entry.value})" }
      .ifBlank { "暂无" }
    val topKnowledge = knowledgePoints.entries
      .sortedByDescending { entry -> entry.value }
      .take(6)
      .joinToString(separator = "，") { entry -> "${entry.key}(${entry.value})" }
      .ifBlank { "暂无" }

    return buildString {
      append("请根据下面学习交互，生成1张最合适的Anki卡片。")
      append("不要套固定模板，请自行判断卡型（概念/对比/因果/步骤/易错点/例题等）。")
      append("要求：front可直接测验、back简洁准确、不要套话。")
      append("如本次内容不适合制卡，返回 skip=true。")
      append("\n仅输出JSON，不要代码块，不要解释。")
      append("\nJSON格式：{\"skip\":false,\"front\":\"...\",\"back\":\"...\",\"tags\":[\"...\"],\"card_type\":\"...\"}")
      append("\n约束：front<=60字，back<=180字，tags<=6。")
      append("\n\n交互模式：")
      append(mode)
      append("\n用户画像热点：")
      append(topTopics)
      append("\n知识点热度：")
      append(topKnowledge)
      append("\n段落内容：")
      append(spanContent)
      if (!question.isNullOrBlank()) {
        append("\n用户追问：")
        append(question)
      }
      append("\nAI回答：")
      append(answer)
    }
  }

  private fun parseAiAnkiCard(raw: String, source: String): AnkiCard? {
    val payload = parseJsonObjectSafely(raw) ?: return null
    if (payload.optBoolean("skip", false)) {
      return null
    }

    val front = payload.optString("front").trim()
    val back = payload.optString("back").trim()
    if (front.isBlank() || back.isBlank()) {
      return null
    }

    val tags = payload.optJSONArray("tags")?.let { array ->
      buildList {
        for (index in 0 until array.length()) {
          val tag = array.optString(index).trim()
          if (tag.isNotBlank()) {
            add(tag)
          }
        }
      }
    }.orEmpty()

    return createAnkiCard(
      front = front,
      back = back,
      source = source,
      tags = tags
    )
  }

  private fun parseJsonObjectSafely(raw: String): JSONObject? {
    val fenced = Regex("```(?:json)?\\s*(\\{[\\s\\S]*})\\s*```", setOf(RegexOption.IGNORE_CASE))
      .find(raw)
      ?.groupValues
      ?.getOrNull(1)
      ?.trim()

    val trimmed = raw.trim()
    val bracketStart = trimmed.indexOf('{')
    val bracketEnd = trimmed.lastIndexOf('}')
    val sliced = if (bracketStart >= 0 && bracketEnd > bracketStart) {
      trimmed.substring(bracketStart, bracketEnd + 1)
    } else {
      null
    }

    val candidates = buildList {
      if (!fenced.isNullOrBlank()) add(fenced)
      if (!sliced.isNullOrBlank()) add(sliced)
      if (trimmed.isNotBlank()) add(trimmed)
    }

    candidates.forEach { candidate ->
      runCatching { JSONObject(candidate) }.getOrNull()?.let { parsed ->
        return parsed
      }
    }
    return null
  }

  private fun normalizeCardText(text: String, maxLen: Int): String {
    val normalized = text
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .trim()
    if (normalized.isEmpty()) {
      return ""
    }

    val trimmedLines = normalized
      .split('\n')
      .map { line -> line.trimEnd() }

    val collapsed = buildString {
      var pendingBlank = 0
      trimmedLines.forEach { line ->
        if (line.isBlank()) {
          pendingBlank += 1
          return@forEach
        }

        if (length > 0) {
          val blanksToInsert = pendingBlank.coerceIn(1, 2)
          repeat(blanksToInsert) { append('\n') }
        }
        pendingBlank = 0
        append(line)
      }
    }.trim()

    return collapsed.take(maxLen)
  }

  private fun prependAnkiCard(current: List<AnkiCard>, card: AnkiCard): List<AnkiCard> {
    val deduplicated = current.filterNot { existing ->
      existing.front == card.front && existing.back == card.back
    }
    return listOf(card) + deduplicated.take(199)
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
