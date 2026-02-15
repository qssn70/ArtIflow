package com.studysuit.aiqa.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studysuit.aiqa.data.ArkApiClient
import com.studysuit.aiqa.data.OpenSpeechAsrClient
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

  fun submitImageQuestion(
    imageBytes: ByteArray,
    source: String,
    note: String? = null,
    imageCount: Int = 1,
    previewImages: List<ByteArray> = emptyList()
  ) {
    if (imageBytes.isEmpty()) {
      postToast("图片为空，无法搜题")
      return
    }

    val requestConversationToken = conversationToken
    val settings = _uiState.value.settings
    val normalizedNote = note?.trim().orEmpty()
    val question = when {
      normalizedNote.isNotBlank() -> "$source：$normalizedNote"
      imageCount > 1 -> "$source：请识别并讲解这${imageCount}张题目图片"
      else -> "$source：请识别并讲解这道题"
    }
    val userMessage = ChatMessage.User(
      id = nextMessageId(),
      time = currentTime(),
      text = question,
      imagePreviewBytes = previewImages.firstOrNull() ?: imageBytes,
      imagePreviewList = previewImages.take(6).ifEmpty { listOf(imageBytes) }
    )

    startRequest(toastMessage = "$source 处理中...") { current ->
      queueImageQuestionState(
        current = current,
        userMessage = userMessage,
        question = question,
        source = source
      )
    }

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = {
        requestCoordinator.replyForImageQuestion(
          imageBytes = imageBytes,
          settings = settings,
          note = normalizedNote,
          imageCount = imageCount
        )
      },
      onStale = { finishRequest() },
      onSuccess = { reply ->
        val assistantMessage = createAssistantMessage(reply, question)
        finishRequest { current ->
          appendAssistantMessageState(
            current = current,
            assistantMessage = assistantMessage,
            toastMessage = "$source 已完成",
            knowledgeTexts = listOf(reply)
          )
        }
      },
      onFailure = { throwable ->
        handleRequestFailure(
          throwable = throwable,
          onError = { current, errorHint ->
            current.copy(toastMessage = "$source 失败：$errorHint")
          }
        )
      }
    )
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
      val target = current.copy(
        activePage = page,
        isDueReviewMode = false,
        focusedDeckName = null
      )
      if (current.activePage == page && !current.isDueReviewMode && current.focusedDeckName == null) {
        current
      } else {
        target
      }
    }
  }

  fun openDueReviewQueue() {
    val dueCount = countDueReviewCards(_uiState.value.ankiCards)
    if (dueCount <= 0) {
      postToast("今日暂无待复习")
      return
    }

    updateUiState(persistSession = true) { current ->
      current.copy(
        activePage = WorkspacePage.ANKI,
        isDueReviewMode = true,
        focusedDeckName = null,
        toastMessage = "进入今日待复习（$dueCount 张）"
      )
    }
  }

  fun openDeckFocusedPractice(deckName: String) {
    val normalizedDeck = normalizeDeckName(deckName)
    if (normalizedDeck.isNullOrBlank()) {
      postToast("卡组名称无效")
      return
    }

    val targetCards = _uiState.value.ankiCards.filter { card ->
      (normalizeDeckName(card.deckName) ?: DEFAULT_ANKI_DECK_NAME) == normalizedDeck
    }
    if (targetCards.isEmpty()) {
      postToast("该卡组暂无可练习卡片")
      return
    }

    updateUiState(persistSession = true) { current ->
      current.copy(
        activePage = WorkspacePage.ANKI,
        isDueReviewMode = false,
        focusedDeckName = normalizedDeck,
        toastMessage = "进入卡组专练：$normalizedDeck"
      )
    }
  }

  fun closeDueReviewMode() {
    updateUiState { current ->
      if (!current.isDueReviewMode) {
        current
      } else {
        current.copy(isDueReviewMode = false, focusedDeckName = null)
      }
    }
  }

  fun closeDeckFocusedPractice() {
    updateUiState { current ->
      if (current.focusedDeckName == null) {
        current
      } else {
        current.copy(focusedDeckName = null)
      }
    }
  }

  fun switchSession(sessionId: String) {
    val target = sessionRegistry.get(sessionId) ?: return
    val now = System.currentTimeMillis()
    sessionRegistry.put(target.copy(updatedAt = now), moveToFront = true)
    val latestTarget = sessionRegistry.get(sessionId) ?: target
    val settings = _uiState.value.settings
    val sharedAnkiCards = _uiState.value.ankiCards
    activateSession(
      session = latestTarget,
      ankiCards = sharedAnkiCards,
      settings = settings,
      toastMessage = "已切换到历史会话",
      persistSession = true
    )
  }

  fun deleteSession(sessionId: String) {
    val activeId = _uiState.value.activeSessionId
    val isActive = sessionId == activeId
    val sharedAnkiCards = _uiState.value.ankiCards

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
    val fallback = sessionRegistry.get(fallbackId) ?: return
    val settings = _uiState.value.settings
    activateSession(
      session = fallback,
      ankiCards = sharedAnkiCards,
      settings = settings,
      toastMessage = "会话已删除",
      persistSession = true
    )
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
          ankiCards = sortAnkiCardsForReview(updatedCards),
          toastMessage = "Anki 卡片已更新"
        )
      }
    }
  }

  fun setAnkiCardMastery(cardId: String, mastery: CardMasteryLevel) {
    updateUiState(persistSession = true) { current ->
      val index = current.ankiCards.indexOfFirst { card -> card.id == cardId }
      if (index < 0) {
        current.copy(toastMessage = "卡片不存在")
      } else {
        val updatedCards = current.ankiCards.toMutableList()
        val card = updatedCards[index]
        val reviewedCard = applySrsReview(card, mastery)
        updatedCards[index] = reviewedCard
        current.copy(
          ankiCards = sortAnkiCardsForReview(updatedCards),
          toastMessage = "已标记${mastery.label}，下次复习 ${formatSessionTime(reviewedCard.nextReviewAt)}"
        )
      }
    }
  }

  fun renameAnkiDeck(deckName: String, newDeckName: String) {
    val fromDeck = deckName.trim()
    val toDeck = newDeckName.trim().replace(Regex("\\s+"), " ").take(12)
    if (fromDeck.isBlank() || toDeck.isBlank()) {
      postToast("卡组名不能为空")
      return
    }
    if (fromDeck == DEFAULT_ANKI_DECK_NAME) {
      postToast("系统卡组不可重命名")
      return
    }
    if (fromDeck == toDeck) {
      postToast("卡组名未变化")
      return
    }

    updateUiState(persistSession = true) { current ->
      val updatedCards = current.ankiCards.map { card ->
        if (card.deckName == fromDeck) {
          card.copy(deckName = toDeck)
        } else {
          card
        }
      }
      if (updatedCards == current.ankiCards) {
        current.copy(toastMessage = "卡组不存在")
      } else {
        current.copy(
          ankiCards = sortAnkiCardsForReview(updatedCards),
          toastMessage = "已重命名卡组"
        )
      }
    }
  }

  fun archiveAnkiDeck(deckName: String) {
    val deck = deckName.trim()
    if (deck.isBlank() || deck == DEFAULT_ANKI_DECK_NAME) {
      return
    }

    updateUiState(persistSession = true) { current ->
      val updatedCards = current.ankiCards.map { card ->
        if (card.deckName == deck) {
          card.copy(deckName = DEFAULT_ANKI_DECK_NAME)
        } else {
          card
        }
      }
      if (updatedCards == current.ankiCards) {
        current.copy(toastMessage = "卡组不存在")
      } else {
        current.copy(
          ankiCards = sortAnkiCardsForReview(updatedCards),
          toastMessage = "已归档到未分类"
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

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = { requestCoordinator.replyForAutoExplain(spanContent = span.content, settings = settings) },
      onStale = {
        finishRequest { current ->
          clearSpanProcessing(current, spanId)
        }
      },
      onSuccess = { reply ->
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
      },
      onFailure = { throwable ->
        handleRequestFailure(
          throwable = throwable,
          onCancel = { current -> clearSpanProcessing(current, spanId) },
          onError = { current, errorHint ->
            clearSpanProcessing(current, spanId, toastMessage = "自动讲解失败：$errorHint")
          }
        )
      }
    )
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

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = { requestCoordinator.transcribe(audioBytes = audioBytes, settings = settings) },
      onStale = {
        finishRequest { current ->
          clearSpanProcessing(current, span.id)
        }
      },
      onSuccess = { transcriptRaw ->
        finishRequest()

        val transcript = transcriptRaw.trim()
        if (transcript.isNotBlank()) {
          enqueueSpanFollowupAndFetch(
            span = span,
            followupQuestion = transcript,
            isVoice = true,
            mode = "语音追问"
          )
        } else {
          updateUiState { current ->
            clearSpanProcessing(
              current,
              span.id,
              toastMessage = "豆包语音识别失败：识别结果为空，未提交追问"
            )
          }
        }
      },
      onFailure = { throwable ->
        handleRequestFailure(
          throwable = throwable,
          fallback = "识别失败",
          onCancel = { current -> clearSpanProcessing(current, span.id) },
          onError = { current, errorHint ->
            clearSpanProcessing(
              current,
              span.id,
              toastMessage = "豆包语音识别失败：$errorHint，未提交追问"
            )
          }
        )
      }
    )
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
      queueSpanFollowupState(
        current = current,
        spanId = span.id,
        question = normalizedQuestion,
        isVoice = isVoice
      )
    }

    val detailsSnapshot = _uiState.value.histories[span.id].orEmpty()

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = {
        requestCoordinator.replyForSpanFollowup(
          span = span,
          followupQuestion = normalizedQuestion,
          details = detailsSnapshot,
          settings = settings
        )
      },
      onStale = {
        finishRequest { current ->
          clearSpanProcessing(current, span.id)
        }
      },
      onSuccess = { reply ->
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
      },
      onFailure = { throwable ->
        handleRequestFailure(
          throwable = throwable,
          onCancel = { current -> clearSpanProcessing(current, span.id) },
          onError = { current, errorHint ->
            clearSpanProcessing(current, span.id, toastMessage = "追问失败：$errorHint")
          }
        )
      }
    )
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
      queueQuestionState(
        current = current,
        userMessage = userMessage,
        question = question,
        isFollowup = isFollowup,
        isVoice = isVoice,
        clearInput = clearInput
      )
    }

    val messagesSnapshot = _uiState.value.messages

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = { requestCoordinator.replyForConversation(messages = messagesSnapshot, settings = settings) },
      onStale = { finishRequest() },
      onSuccess = { reply ->
        val assistantMessage = createAssistantMessage(reply, question)

        finishRequest { current ->
          appendAssistantMessageState(
            current = current,
            assistantMessage = assistantMessage,
            toastMessage = null
          )
        }
      },
      onFailure = { throwable ->
        handleRequestFailure(
          throwable = throwable,
          onError = { current, errorHint ->
            current.copy(toastMessage = "回答失败：$errorHint")
          }
        )
      }
    )
  }

  private fun resetConversation(showIntroToast: Boolean = false) {
    resetRequestTracking()
    messageSeed = 0
    spanSeed = 0
    detailSeed = 0
    sessionSeed += 1
    val sharedAnkiCards = sortAnkiCardsForReview(_uiState.value.ankiCards)
    cardSeed = deriveCardSeed(sharedAnkiCards)

    val now = System.currentTimeMillis()
    val sessionId = "session-$now-$sessionSeed"
    val settings = _uiState.value.settings
    val settingsDraft = _uiState.value.settingsDraft
    val introMessage = createAssistantMessage(buildIntroGuideContent(), "初始化引导")

    val initialState = createInitialSessionState(
      sessionId = sessionId,
      introMessage = introMessage,
      ankiCards = sharedAnkiCards,
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
    val updated = buildSyncedSessionSnapshot(
      state = state,
      fallbackTime = currentTime(),
      now = now,
      existingCreatedAt = sessionRegistry.createdAtOf(sessionId)
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
    val sharedAnkiCards = mergeGlobalAnkiCards(restored.sessions)
    activateSession(
      session = active,
      ankiCards = sharedAnkiCards,
      settings = settings,
      toastMessage = null,
      persistSession = false
    )
  }

  private fun persistSessionsAsync() {
    val store = sessionStorage ?: return
    val state = _uiState.value
    val unifiedSessions = sessionRegistry.orderedSessions().map { session ->
      if (session.ankiCards == state.ankiCards) {
        session
      } else {
        session.copy(ankiCards = state.ankiCards)
      }
    }
    val payload = buildPersistedSessionsPayload(
      state = state,
      sessions = unifiedSessions
    ) ?: return

    viewModelScope.launch(Dispatchers.IO) {
      store.save(payload)
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
    tags: List<String>,
    deckName: String
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
      createdAt = System.currentTimeMillis(),
      deckName = deckName
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
    val deckSnapshot = detectExistingDeckCategories(_uiState.value.ankiCards)

    viewModelScope.launch {
      val result = requestCoordinator.generateAnkiPayload(
        AnkiGenerationInput(
          mode = mode,
          spanContent = spanContent,
          question = question,
          answer = answer,
          profile = profileSnapshot,
          knowledgePoints = knowledgeSnapshot,
          existingDecks = deckSnapshot,
          settings = settingsSnapshot
        )
      )

      if (isTokenStale(requestConversationToken, conversationToken)) {
        return@launch
      }

      result.getOrNull()?.let { parsedPayload ->
        updateUiState(persistSession = true) { current ->
          val resolvedDeck = resolveDeckNameForAutoCard(
            suggestedDeck = parsedPayload.deck,
            tags = parsedPayload.tags,
            existingCards = current.ankiCards
          )
          val parsed = createAnkiCard(
            front = parsedPayload.front,
            back = parsedPayload.back,
            source = source,
            tags = parsedPayload.tags,
            deckName = resolvedDeck
          )
          current.copy(ankiCards = sortAnkiCardsForReview(prependAnkiCard(current.ankiCards, parsed)))
        }
      }
    }
  }

  private fun activateSession(
    session: StoredSession,
    ankiCards: List<AnkiCard>,
    settings: RuntimeSettings,
    toastMessage: String?,
    persistSession: Boolean
  ) {
    applySessionSeeds(session)
    cardSeed = maxOf(cardSeed, deriveCardSeed(ankiCards))
    resetRequestTracking()
    _uiState.value = buildUiStateFromSession(
      session = session,
      ankiCards = ankiCards,
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

  private fun <T> launchTokenAwareRequest(
    requestConversationToken: Long,
    request: suspend () -> Result<T>,
    onStale: () -> Unit,
    onSuccess: (T) -> Unit,
    onFailure: (Throwable) -> Unit
  ) {
    viewModelScope.launch {
      val result = request()
      deliverTokenAwareResult(
        result = result,
        requestToken = requestConversationToken,
        activeToken = conversationToken,
        onStale = onStale,
        onSuccess = onSuccess,
        onFailure = onFailure
      )
    }
  }

  private fun handleRequestFailure(
    throwable: Throwable,
    fallback: String = "网络不可用",
    onCancel: (ChatUiState) -> ChatUiState = { state -> state },
    onError: (ChatUiState, String) -> ChatUiState
  ) {
    routeRequestFailure(
      throwable = throwable,
      fallback = fallback,
      onCancel = { finishRequest(onCancel) },
      onError = { errorHint ->
        finishRequest { current ->
          onError(current, errorHint)
        }
      }
    )
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

  private fun deriveCardSeed(cards: List<AnkiCard>): Int {
    return cards
      .mapNotNull { card -> card.id.removePrefix("card-").toIntOrNull() }
      .maxOrNull() ?: 0
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
