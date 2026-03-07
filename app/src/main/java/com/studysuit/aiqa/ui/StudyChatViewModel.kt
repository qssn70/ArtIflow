package com.studysuit.aiqa.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studysuit.aiqa.data.ArkApiClient
import com.studysuit.aiqa.data.FlowStudySyncClient
import com.studysuit.aiqa.data.OpenSpeechAsrClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

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
  private val flowStudySyncClient = FlowStudySyncClient()
  private var sessionStorage: SessionStorage? = null
  private val sessionRegistry = SessionRegistry()
  private val persistMutex = Mutex()
  private val latestPersistRequestId = AtomicLong(0L)

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

  fun pairFlowStudy(pairCode: String) {
    val normalized = pairCode.trim()
    if (normalized.isBlank()) {
      updateUiState(persistSession = false) { current ->
        current.copy(toastMessage = "配对码为空")
      }
      return
    }

    startRequest(toastMessage = "FlowStudy 配对中...") { current -> current }
    viewModelScope.launch {
      val state = _uiState.value
      val settings = ensureFlowStudyDeviceId(state.settingsDraft)

      val result = flowStudySyncClient.pairDevice(
        serverUrl = settings.flowStudyServerUrl,
        pairCode = normalized,
        deviceId = settings.flowStudyDeviceId
      )

      result.fold(
        onSuccess = { resp ->
          val updated = settings.copy(flowStudyDeviceToken = resp.deviceToken)
          updateUiState(persistSession = true) { current ->
            current.copy(
              settings = updated,
              settingsDraft = updated,
              toastMessage = "配对成功"
            )
          }
        },
        onFailure = { err ->
          updateUiState(persistSession = false) { current ->
            current.copy(toastMessage = "配对失败：${err.message ?: "未知错误"}")
          }
        }
      )

      finishRequest { current -> current.copy(toastMessage = current.toastMessage) }
    }
  }

  fun pushSessionsToFlowStudy(_recentLimit: Int? = null) {
    _recentLimit?.let { Unit }
    startRequest(toastMessage = "上传主界面中...") { current -> current }
    viewModelScope.launch {
      val store = sessionStorage
      if (store == null) {
        updateUiState(persistSession = false) { current ->
          current.copy(toastMessage = "未初始化存储")
        }
        finishRequest()
        return@launch
      }

      val current = _uiState.value
      val settings = ensureFlowStudyDeviceId(current.settings)
      if (settings.flowStudyServerUrl.isBlank()) {
        updateUiState(persistSession = false) { state ->
          state.copy(toastMessage = "FlowStudy 地址为空")
        }
        finishRequest()
        return@launch
      }
      if (settings.flowStudyDeviceToken.isBlank()) {
        updateUiState(persistSession = false) { state ->
          state.copy(toastMessage = "请先配对获取设备 token")
        }
        finishRequest()
        return@launch
      }

      val unifiedSessions = sessionRegistry.orderedSessions().map { session ->
        if (session.ankiCards == current.ankiCards) {
          session
        } else {
          session.copy(ankiCards = current.ankiCards)
        }
      }

      val activeSession = unifiedSessions.firstOrNull { it.id == current.activeSessionId }
        ?: unifiedSessions.firstOrNull()
      val limitedSessions = activeSession?.let(::listOf).orEmpty()

      val payload = buildPersistedSessionsPayload(
        state = current.copy(settings = settings, settingsDraft = settings),
        sessions = limitedSessions
      )

      if (payload == null || payload.sessions.isEmpty()) {
        updateUiState(persistSession = false) { state ->
          state.copy(toastMessage = "没有可上传的主界面内容")
        }
        finishRequest()
        return@launch
      }

      val uploadSessions = payload.sessions
      val batchSize = 1
      val batches = uploadSessions.chunked(batchSize)
      var insertedTotal = 0
      var updatedTotal = 0
      var skippedTotal = 0

      for ((index, batchSessions) in batches.withIndex()) {
        updateUiState(persistSession = false) { state ->
          state.copy(toastMessage = "上传中 ${index + 1}/${batches.size}...")
        }

        val batchPayload = PersistedSessions(
          activeSessionId = payload.activeSessionId,
          settings = payload.settings,
          sessions = batchSessions
        )

        val payloadJson = store.exportPayloadJson(batchPayload)
        val result = flowStudySyncClient.pushSessions(
          serverUrl = settings.flowStudyServerUrl,
          deviceToken = settings.flowStudyDeviceToken,
          deviceId = settings.flowStudyDeviceId,
          payloadJson = payloadJson
        )

        val ok = result.getOrNull()
        if (ok == null) {
          val err = result.exceptionOrNull()
          updateUiState(persistSession = false) { state ->
            state.copy(toastMessage = "上传失败：${err?.message ?: "未知错误"}")
          }
          finishRequest()
          return@launch
        }

        insertedTotal += ok.insertedSessions
        updatedTotal += ok.updatedSessions
        skippedTotal += ok.skippedSessions
      }

      updateUiState(persistSession = true) { state ->
        state.copy(
          settings = settings,
          settingsDraft = settings,
          toastMessage = "上传完成：+${insertedTotal} 更新${updatedTotal}"
        )
      }

      finishRequest()
    }
  }

  private fun ensureFlowStudyDeviceId(settings: RuntimeSettings): RuntimeSettings {
    val existing = settings.flowStudyDeviceId.trim()
    if (existing.isNotBlank()) {
      return settings.copy(flowStudyDeviceId = existing)
    }

    val generated = "artiflow_${UUID.randomUUID()}"
    val updated = settings.copy(flowStudyDeviceId = generated)
    updateUiState(persistSession = true) { current ->
      if (current.settingsDraft.flowStudyDeviceId.isNotBlank()) {
        current
      } else {
        current.copy(settings = updated, settingsDraft = updated)
      }
    }
    return updated
  }

  fun onInputChanged(value: String) {
    updateUiState(persistSession = true) { current ->
      current.copy(input = value)
    }
  }

  fun sendQuestion() {
    val current = _uiState.value
    val question = current.input.trim()
    if (question.isEmpty()) {
      return
    }

    if (current.activePage == WorkspacePage.QUICK_FOLLOWUP) {
      sendQuickFollowupQuestion(question)
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
    val assistantMessageId = nextMessageId()
    val assistantMessageTime = currentTime()
    val streamBuffer = StringBuilder()
    val reasoningBuffer = StringBuilder()
    val userMessage = ChatMessage.User(
      id = nextMessageId(),
      time = currentTime(),
      text = question,
      imagePreviewBytes = previewImages.firstOrNull() ?: imageBytes,
      imagePreviewList = previewImages.take(6).ifEmpty { listOf(imageBytes) }
    )
    val assistantPlaceholder = createAssistantMessage(
      content = "正在识别题目并生成讲解...",
      sourceQuestion = question,
      reasoningSummary = null,
      messageId = assistantMessageId,
      messageTime = assistantMessageTime
    )

    val pushPartialAssistant: () -> Unit = {
      val partial = streamBuffer.toString().trimStart()
      val reasoning = reasoningBuffer.toString().trim().ifBlank { null }
      val partialMessage = createAssistantMessage(
        content = partial.ifBlank { "正在识别题目并生成讲解..." },
        sourceQuestion = question,
        reasoningSummary = reasoning,
        messageId = assistantMessageId,
        messageTime = assistantMessageTime
      )
      updateUiState(persistSession = false) { current ->
        upsertAssistantMessageState(
          current = current,
          assistantMessage = partialMessage
        )
      }
    }

    startRequest(toastMessage = "$source 处理中...") { current ->
      val queued = queueImageQuestionState(
        current = current,
        userMessage = userMessage,
        question = question,
        source = source
      )
      upsertAssistantMessageState(
        current = queued,
        assistantMessage = assistantPlaceholder
      )
    }

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = {
        requestCoordinator.replyForImageQuestionStream(
          imageBytes = imageBytes,
          settings = settings,
          note = normalizedNote,
          imageCount = imageCount,
          onDelta = { delta ->
            if (isTokenStale(requestConversationToken, conversationToken) || delta.isBlank()) {
              return@replyForImageQuestionStream
            }

            streamBuffer.append(delta)
            pushPartialAssistant()
          },
          onReasoningDelta = { reasoningDelta ->
            if (isTokenStale(requestConversationToken, conversationToken) || reasoningDelta.isBlank()) {
              return@replyForImageQuestionStream
            }

            reasoningBuffer.append(reasoningDelta)
            pushPartialAssistant()
          }
        )
      },
      onStale = { finishRequest() },
      onSuccess = { reply ->
        val resolvedReply = reply.ifBlank { streamBuffer.toString().trim() }
        val resolvedReasoning = reasoningBuffer.toString().trim().ifBlank { null }
        val assistantMessage = createAssistantMessage(
          content = resolvedReply,
          sourceQuestion = question,
          reasoningSummary = resolvedReasoning,
          messageId = assistantMessageId,
          messageTime = assistantMessageTime
        )
        finishRequest { current ->
          appendAssistantMessageState(
            current = current,
            assistantMessage = assistantMessage,
            toastMessage = "$source 已完成",
            knowledgeTexts = listOf(resolvedReply)
          )
        }
      },
      onFailure = { throwable ->
        val partialReply = streamBuffer.toString().trimStart()
        val partialReasoning = reasoningBuffer.toString().trim().ifBlank { null }
        if (partialReply.isNotBlank() || partialReasoning != null) {
          val recoveredMessage = createAssistantMessage(
            content = partialReply.ifBlank { "网络波动，已保留本次回答的已生成内容。" },
            sourceQuestion = question,
            reasoningSummary = partialReasoning,
            messageId = assistantMessageId,
            messageTime = assistantMessageTime
          )
          finishRequest { current ->
            appendAssistantMessageState(
              current = current,
              assistantMessage = recoveredMessage,
              toastMessage = "$source 网络中断，已保留已生成内容",
              knowledgeTexts = listOf(partialReply.ifBlank { partialReasoning.orEmpty() })
            )
          }
          return@launchTokenAwareRequest
        }

        handleRequestFailure(
          throwable = throwable,
          onError = { current, errorHint ->
            val rolledBackPlaceholder = rollbackQueuedUserMessageState(
              current = current,
              messageId = assistantMessageId
            )
            rollbackQueuedUserMessageState(
              current = rolledBackPlaceholder,
              messageId = userMessage.id,
              toastMessage = "$source 失败：$errorHint"
            )
          }
        )
      }
    )
  }

  fun startNewChat() {
    resetConversation(showIntroToast = true)
  }

  fun refreshAssistantReply(messageId: String) {
    val state = _uiState.value
    val assistantIndex = state.messages.indexOfFirst { message ->
      message is ChatMessage.Assistant && message.id == messageId
    }
    val assistantMessage = state.messages.getOrNull(assistantIndex) as? ChatMessage.Assistant
    if (assistantIndex < 0 || assistantMessage == null) {
      postToast("未找到要刷新的回复")
      return
    }

    val sourceQuestion = assistantMessage.mainSpan?.sourceQuestion?.trim()
      .orEmpty()
      .ifBlank { assistantMessage.spans.firstOrNull()?.sourceQuestion?.trim().orEmpty() }
    val sourceUser = state.messages
      .take(assistantIndex)
      .asReversed()
      .filterIsInstance<ChatMessage.User>()
      .firstOrNull()

    val imageBytesList = sourceUser?.let { user ->
      if (user.imagePreviewList.isNotEmpty()) {
        user.imagePreviewList
      } else {
        user.imagePreviewBytes?.let { bytes -> listOf(bytes) }.orEmpty()
      }
    }.orEmpty()

    if (imageBytesList.isNotEmpty()) {
      refreshImageAssistantReply(
        assistantMessage = assistantMessage,
        sourceUser = sourceUser,
        fallbackQuestion = sourceQuestion,
        imageBytesList = imageBytesList
      )
    } else {
      refreshTextAssistantReply(
        assistantMessage = assistantMessage,
        sourceUser = sourceUser,
        fallbackQuestion = sourceQuestion
      )
    }
  }

  fun toggleSavedQuestion(messageId: String) {
    val state = _uiState.value
    val existing = state.savedQuestions.firstOrNull { saved -> saved.sourceMessageId == messageId }
    if (existing != null) {
      updateUiState(persistSession = true) { current ->
        current.copy(
          savedQuestions = current.savedQuestions.filterNot { saved -> saved.sourceMessageId == messageId },
          toastMessage = "已取消收藏该题"
        )
      }
      return
    }

    val saved = buildSavedQuestionSnapshot(state, messageId)
    if (saved == null) {
      postToast("当前题目暂时无法收藏")
      return
    }

    updateUiState(persistSession = true) { current ->
      current.copy(
        savedQuestions = listOf(saved) + current.savedQuestions.filterNot { question -> question.sourceMessageId == messageId },
        toastMessage = "已收藏到题目归档"
      )
    }
  }

  fun removeSavedQuestion(savedQuestionId: String) {
    updateUiState(persistSession = true) { current ->
      val exists = current.savedQuestions.any { saved -> saved.id == savedQuestionId }
      if (!exists) {
        current
      } else {
        current.copy(
          savedQuestions = current.savedQuestions.filterNot { saved -> saved.id == savedQuestionId },
          toastMessage = "已移出题目归档"
        )
      }
    }
  }

  fun restoreSavedQuestionToComposer(savedQuestionId: String) {
    updateUiState(persistSession = true) { current ->
      val saved = current.savedQuestions.firstOrNull { question -> question.id == savedQuestionId }
      if (saved == null) {
        current.copy(toastMessage = "未找到已收藏题目")
      } else {
        current.copy(
          activePage = WorkspacePage.CHAT,
          input = saved.question,
          toastMessage = "已回填到提问框"
        )
      }
    }
  }

  fun switchWorkspacePage(page: WorkspacePage) {
    updateUiState(persistSession = true) { current ->
      val quickSpanId = if (page == WorkspacePage.QUICK_FOLLOWUP) {
        findSpanById(current.messages, current.quickFollowupSpanId)?.id
          ?: findLatestAssistantQuestionSpan(current.messages)?.id
      } else {
        current.quickFollowupSpanId
      }
      val quickDetailId = if (page == WorkspacePage.QUICK_FOLLOWUP) {
        val candidate = current.quickFollowupDetailId
        if (candidate != null && current.histories[quickSpanId].orEmpty().any { detail -> detail.id == candidate }) {
          candidate
        } else {
          null
        }
      } else {
        current.quickFollowupDetailId
      }
      val target = current.copy(
        activePage = page,
        quickFollowupSpanId = quickSpanId,
        quickFollowupDetailId = quickDetailId,
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

  fun openQuickFollowup(spanId: String? = null, detailId: String? = null) {
    updateUiState(persistSession = true) { current ->
      val normalizedSpanId = findSpanById(current.messages, spanId)?.id
      val fallbackSpanId = findSpanById(current.messages, current.quickFollowupSpanId)?.id
      val latestSpanId = findLatestAssistantQuestionSpan(current.messages)?.id
      val targetSpanId = normalizedSpanId ?: fallbackSpanId ?: latestSpanId

      if (targetSpanId == null) {
        current.copy(toastMessage = "当前没有可追问段落")
      } else {
        val availableHistory = current.histories[targetSpanId].orEmpty()
        val normalizedDetailId = detailId?.let { id ->
          if (availableHistory.any { detail -> detail.id == id }) id else null
        }
        val shouldKeepCurrent = current.activePage == WorkspacePage.QUICK_FOLLOWUP &&
          !current.isDueReviewMode &&
          current.focusedDeckName == null &&
          current.quickFollowupSpanId == targetSpanId &&
          current.quickFollowupDetailId == normalizedDetailId &&
          current.selectedSpanId == null &&
          current.selectedDetailId == null

        if (shouldKeepCurrent) {
          current
        } else {
          current.copy(
            activePage = WorkspacePage.QUICK_FOLLOWUP,
            quickFollowupSpanId = targetSpanId,
            quickFollowupDetailId = normalizedDetailId,
            selectedSpanId = null,
            selectedDetailId = null,
            isDueReviewMode = false,
            focusedDeckName = null,
            toastMessage = when {
              normalizedDetailId != null -> "已进入该条追问分支"
              normalizedSpanId != null -> "已切换追问段落"
              else -> "已进入精细追问"
            }
          )
        }
      }
    }
  }

  fun stepBackQuickFollowupLayer(): Boolean {
    var handled = false
    updateUiState(persistSession = true) { current ->
      if (current.activePage != WorkspacePage.QUICK_FOLLOWUP) {
        return@updateUiState current
      }

      val spanId = current.quickFollowupSpanId
      val detailId = current.quickFollowupDetailId
      if (spanId.isNullOrBlank() || detailId.isNullOrBlank()) {
        return@updateUiState current
      }

      val parentDetailId = findDetailById(
        details = current.histories[spanId].orEmpty(),
        detailId = detailId
      )?.parentDetailId?.takeIf { parent -> parent.isNotBlank() }

      handled = true
      current.copy(quickFollowupDetailId = parentDetailId)
    }

    return handled
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
        deckPracticeSelections = emptyMap(),
        showDeckPracticeSummary = false,
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
        current.copy(
          focusedDeckName = null,
          deckPracticeSelections = emptyMap(),
          showDeckPracticeSummary = false
        )
      }
    }
  }

  fun dismissDeckPracticeSummary() {
    updateUiState { current ->
      if (!current.showDeckPracticeSummary) {
        current
      } else {
        current.copy(showDeckPracticeSummary = false)
      }
    }
  }

  fun openDeckPracticeSummary() {
    updateUiState { current ->
      if (current.focusedDeckName.isNullOrBlank()) {
        current
      } else {
        current.copy(showDeckPracticeSummary = true)
      }
    }
  }

  fun restartDeckPracticeRound() {
    updateUiState { current ->
      if (current.focusedDeckName.isNullOrBlank()) {
        current
      } else {
        current.copy(
          deckPracticeSelections = emptyMap(),
          showDeckPracticeSummary = false,
          toastMessage = "已开始新一轮卡组专练"
        )
      }
    }
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
        val normalizedDeck = current.focusedDeckName
        val practiceSelections = if (!normalizedDeck.isNullOrBlank() &&
          (normalizeDeckName(card.deckName) ?: DEFAULT_ANKI_DECK_NAME) == normalizedDeck
        ) {
          current.deckPracticeSelections + (card.id to mastery)
        } else {
          current.deckPracticeSelections
        }

        val deckTotal = if (normalizedDeck.isNullOrBlank()) {
          0
        } else {
          updatedCards.count { each ->
            (normalizeDeckName(each.deckName) ?: DEFAULT_ANKI_DECK_NAME) == normalizedDeck
          }
        }
        val shouldShowSummary = !normalizedDeck.isNullOrBlank() && deckTotal > 0 && practiceSelections.size >= deckTotal

        current.copy(
          ankiCards = sortAnkiCardsForReview(updatedCards),
          deckPracticeSelections = practiceSelections,
          showDeckPracticeSummary = shouldShowSummary,
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
    val detailId = nextDetailId()
    val detailTime = currentDateTime()
    val streamBuffer = StringBuilder()
    val placeholderDetail = SpanDetail(
      id = detailId,
      mode = "自动讲解",
      time = detailTime,
      answer = "正在生成讲解...",
      summary = "正在生成讲解..."
    )

    startRequest(toastMessage = "正在生成该段讲解...") { current ->
      val marked = markSpanProcessing(current, spanId)
      upsertSpanDetailHistory(
        current = marked,
        spanId = spanId,
        detail = placeholderDetail
      )
    }

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = {
        requestCoordinator.replyForAutoExplainStream(
          spanContent = span.content,
          settings = settings
        ) { delta ->
          if (isTokenStale(requestConversationToken, conversationToken) || delta.isBlank()) {
            return@replyForAutoExplainStream
          }

          streamBuffer.append(delta)
          val partialAnswer = streamBuffer.toString().trimStart()
          val partialDetail = placeholderDetail.copy(
            answer = partialAnswer.ifBlank { "正在生成讲解..." },
            summary = buildDetailCardSummary(
              question = null,
              answer = partialAnswer.ifBlank { "正在生成讲解..." }
            )
          )
          updateUiState(persistSession = false) { current ->
            upsertSpanDetailHistory(
              current = current,
              spanId = spanId,
              detail = partialDetail
            )
          }
        }
      },
      onStale = {
        finishRequest { current ->
          val withoutPlaceholder = removeSpanDetailHistory(current, spanId, detailId)
          clearSpanProcessing(withoutPlaceholder, spanId)
        }
      },
      onSuccess = { reply ->
        val resolvedReply = reply.ifBlank { streamBuffer.toString().trim() }
        finishRequest { current ->
          val detail = placeholderDetail.copy(
            answer = resolvedReply,
            summary = buildDetailCardSummary(question = null, answer = resolvedReply)
          )
          upsertSpanDetailHistory(
            current = current,
            spanId = spanId,
            detail = detail,
            clearProcessing = true,
            toastMessage = "已生成该段讲解，右滑停留可进精细追问"
          )
        }
        generateAnkiCardByAiAsync(
          requestConversationToken = requestConversationToken,
          source = "左滑自动讲解",
          mode = "自动讲解",
          spanContent = span.content,
          question = null,
          answer = resolvedReply
        )
      },
      onFailure = { throwable ->
        handleRequestFailure(
          throwable = throwable,
          onCancel = { current ->
            val withoutPlaceholder = removeSpanDetailHistory(current, spanId, detailId)
            clearSpanProcessing(withoutPlaceholder, spanId)
          },
          onError = { current, errorHint ->
            val withoutPlaceholder = removeSpanDetailHistory(current, spanId, detailId)
            clearSpanProcessing(withoutPlaceholder, spanId, toastMessage = "自动讲解失败：$errorHint")
          }
        )
      }
    )
  }

  fun submitVoiceFollowupAudio(spanId: String, audioBytes: ByteArray) {
    val span = findSpanById(_uiState.value.messages, spanId) ?: return
    runVoiceFollowupByOpenSpeech(span = span, audioBytes = audioBytes)
  }

  fun openDetails(spanId: String, detailId: String? = null) {
    updateUiState { current ->
      current.copy(selectedSpanId = spanId, selectedDetailId = detailId)
    }
  }

  fun closeDetails() {
    updateUiState { current ->
      current.copy(selectedSpanId = null, selectedDetailId = null)
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

  private fun refreshTextAssistantReply(
    assistantMessage: ChatMessage.Assistant,
    sourceUser: ChatMessage.User?,
    fallbackQuestion: String
  ) {
    val question = sourceUser?.text?.trim().orEmpty().ifBlank { fallbackQuestion.trim() }
    if (question.isBlank()) {
      postToast("未找到原问题，无法刷新")
      return
    }

    val requestConversationToken = conversationToken
    val settings = _uiState.value.settings
    val requestMessages = listOf(
      sourceUser ?: ChatMessage.User(
        id = "refresh-${assistantMessage.id}",
        time = currentTime(),
        text = question
      )
    )
    val assistantMessageTime = currentTime()
    val streamBuffer = StringBuilder()
    val reasoningBuffer = StringBuilder()
    val assistantPlaceholder = createAssistantMessage(
      content = "正在刷新回答...",
      sourceQuestion = question,
      reasoningSummary = null,
      messageId = assistantMessage.id,
      messageTime = assistantMessageTime
    )

    val pushPartialAssistant: () -> Unit = {
      val partial = streamBuffer.toString().trimStart()
      val reasoning = reasoningBuffer.toString().trim().ifBlank { null }
      val partialMessage = createAssistantMessage(
        content = partial.ifBlank { "正在刷新回答..." },
        sourceQuestion = question,
        reasoningSummary = reasoning,
        messageId = assistantMessage.id,
        messageTime = assistantMessageTime
      )
      updateUiState(persistSession = false) { current ->
        upsertAssistantMessageState(
          current = current,
          assistantMessage = partialMessage
        )
      }
    }

    startRequest(toastMessage = "正在刷新整条回复...") { current ->
      val reset = clearAssistantReplyArtifacts(current, assistantMessage)
      upsertAssistantMessageState(reset, assistantPlaceholder)
    }

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = {
        requestCoordinator.replyForConversationStream(
          messages = requestMessages,
          settings = settings,
          onDelta = { delta ->
            if (isTokenStale(requestConversationToken, conversationToken) || delta.isBlank()) {
              return@replyForConversationStream
            }

            streamBuffer.append(delta)
            pushPartialAssistant()
          },
          onReasoningDelta = { reasoningDelta ->
            if (isTokenStale(requestConversationToken, conversationToken) || reasoningDelta.isBlank()) {
              return@replyForConversationStream
            }

            reasoningBuffer.append(reasoningDelta)
            pushPartialAssistant()
          }
        )
      },
      onStale = { finishRequest() },
      onSuccess = { reply ->
        val resolvedReply = reply.ifBlank { streamBuffer.toString().trim() }
        val resolvedReasoning = reasoningBuffer.toString().trim().ifBlank { null }
        val refreshed = createAssistantMessage(
          content = resolvedReply,
          sourceQuestion = question,
          reasoningSummary = resolvedReasoning,
          messageId = assistantMessage.id,
          messageTime = assistantMessageTime
        )
        finishRequest { current ->
          val updated = appendAssistantMessageState(
            current = current,
            assistantMessage = refreshed,
            toastMessage = "已刷新整条回复",
            knowledgeTexts = listOf(resolvedReply)
          )
          syncSavedQuestionSnapshot(updated, refreshed)
        }
      },
      onFailure = { throwable ->
        handleRequestFailure(
          throwable = throwable,
          onError = { current, errorHint ->
            val restored = upsertAssistantMessageState(current, assistantMessage)
            restored.copy(toastMessage = "刷新失败：$errorHint")
          }
        )
      }
    )
  }

  private fun refreshImageAssistantReply(
    assistantMessage: ChatMessage.Assistant,
    sourceUser: ChatMessage.User?,
    fallbackQuestion: String,
    imageBytesList: List<ByteArray>
  ) {
    val mergedImage = mergeImagesForUpload(imageBytesList)
    if (mergedImage.isEmpty()) {
      postToast("原图片不可用，无法刷新")
      return
    }

    val requestConversationToken = conversationToken
    val settings = _uiState.value.settings
    val question = fallbackQuestion.ifBlank { sourceUser?.text.orEmpty() }
    val assistantMessageTime = currentTime()
    val streamBuffer = StringBuilder()
    val reasoningBuffer = StringBuilder()
    val sourceLabel = sourceUser?.text?.substringBefore('：')?.trim().orEmpty().ifBlank { "图片搜题" }
    val imageCount = imageBytesList.size.coerceAtLeast(1)
    val noteCandidate = sourceUser?.text?.substringAfter('：', "")?.trim().orEmpty()
    val note = noteCandidate.takeIf { candidate ->
      candidate.isNotBlank() && !candidate.startsWith("请识别并讲解这")
    }
    val assistantPlaceholder = createAssistantMessage(
      content = "正在刷新图片回答...",
      sourceQuestion = question,
      reasoningSummary = null,
      messageId = assistantMessage.id,
      messageTime = assistantMessageTime
    )

    val pushPartialAssistant: () -> Unit = {
      val partial = streamBuffer.toString().trimStart()
      val reasoning = reasoningBuffer.toString().trim().ifBlank { null }
      val partialMessage = createAssistantMessage(
        content = partial.ifBlank { "正在刷新图片回答..." },
        sourceQuestion = question,
        reasoningSummary = reasoning,
        messageId = assistantMessage.id,
        messageTime = assistantMessageTime
      )
      updateUiState(persistSession = false) { current ->
        upsertAssistantMessageState(
          current = current,
          assistantMessage = partialMessage
        )
      }
    }

    startRequest(toastMessage = "正在刷新整条回复...") { current ->
      val reset = clearAssistantReplyArtifacts(current, assistantMessage)
      upsertAssistantMessageState(reset, assistantPlaceholder)
    }

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = {
        requestCoordinator.replyForImageQuestionStream(
          imageBytes = mergedImage,
          settings = settings,
          note = note,
          imageCount = imageCount,
          onDelta = { delta ->
            if (isTokenStale(requestConversationToken, conversationToken) || delta.isBlank()) {
              return@replyForImageQuestionStream
            }

            streamBuffer.append(delta)
            pushPartialAssistant()
          },
          onReasoningDelta = { reasoningDelta ->
            if (isTokenStale(requestConversationToken, conversationToken) || reasoningDelta.isBlank()) {
              return@replyForImageQuestionStream
            }

            reasoningBuffer.append(reasoningDelta)
            pushPartialAssistant()
          }
        )
      },
      onStale = { finishRequest() },
      onSuccess = { reply ->
        val resolvedReply = reply.ifBlank { streamBuffer.toString().trim() }
        val resolvedReasoning = reasoningBuffer.toString().trim().ifBlank { null }
        val refreshed = createAssistantMessage(
          content = resolvedReply,
          sourceQuestion = question.ifBlank { "$sourceLabel：请重新识别并讲解" },
          reasoningSummary = resolvedReasoning,
          messageId = assistantMessage.id,
          messageTime = assistantMessageTime
        )
        finishRequest { current ->
          val updated = appendAssistantMessageState(
            current = current,
            assistantMessage = refreshed,
            toastMessage = "已刷新整条回复",
            knowledgeTexts = listOf(resolvedReply)
          )
          syncSavedQuestionSnapshot(updated, refreshed)
        }
      },
      onFailure = { throwable ->
        handleRequestFailure(
          throwable = throwable,
          onError = { current, errorHint ->
            val restored = upsertAssistantMessageState(current, assistantMessage)
            restored.copy(toastMessage = "刷新失败：$errorHint")
          }
        )
      }
    )
  }

  private fun clearAssistantReplyArtifacts(
    current: ChatUiState,
    assistantMessage: ChatMessage.Assistant
  ): ChatUiState {
    val removedSpanIds = assistantMessage.interactiveSpans().map { span -> span.id }.toSet()
    val nextHistories = current.histories.filterKeys { key -> key !in removedSpanIds }
    val shouldResetQuick = current.quickFollowupSpanId in removedSpanIds
    val shouldResetSelected = current.selectedSpanId in removedSpanIds

    return current.copy(
      histories = nextHistories,
      processingSpanIds = current.processingSpanIds - removedSpanIds,
      quickFollowupSpanId = if (shouldResetQuick) null else current.quickFollowupSpanId,
      quickFollowupDetailId = if (shouldResetQuick) null else current.quickFollowupDetailId,
      selectedSpanId = if (shouldResetSelected) null else current.selectedSpanId,
      selectedDetailId = if (shouldResetSelected) null else current.selectedDetailId,
      activePage = if (shouldResetQuick && current.activePage == WorkspacePage.QUICK_FOLLOWUP) {
        WorkspacePage.CHAT
      } else {
        current.activePage
      }
    )
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
    mode: String,
    clearInput: Boolean = false,
    keepQuickFollowupSpan: Boolean = false,
    parentDetailId: String? = null
  ) {
    val normalizedQuestion = followupQuestion.trim()
    if (normalizedQuestion.isBlank()) {
      postToast("追问为空，已取消")
      return
    }

    val requestConversationToken = conversationToken
    val stateSnapshot = _uiState.value
    val settings = stateSnapshot.settings
    val detailsSnapshot = stateSnapshot.histories[span.id].orEmpty()
    val messagesSnapshot = stateSnapshot.messages
    val detailId = nextDetailId()
    val detailTime = currentDateTime()
    val streamBuffer = StringBuilder()
    val derivedParentDetailId = parentDetailId ?: run {
      val state = _uiState.value
      if (keepQuickFollowupSpan || state.activePage == WorkspacePage.QUICK_FOLLOWUP) {
        val targetFromState = state.quickFollowupDetailId
        if (targetFromState != null && state.histories[span.id].orEmpty().any { detail -> detail.id == targetFromState }) {
          targetFromState
        } else {
          state.histories[span.id].orEmpty().firstOrNull()?.id
        }
      } else {
        null
      }
    }
    val placeholderDetail = SpanDetail(
      id = detailId,
      mode = mode,
      time = detailTime,
      question = normalizedQuestion,
      answer = "正在生成追问回答...",
      parentDetailId = derivedParentDetailId,
      summary = buildDetailCardSummary(
        question = normalizedQuestion,
        answer = "正在生成追问回答..."
      )
    )

    startRequest(clearToast = true) { current ->
      val queued = queueSpanFollowupState(
        current = current,
        spanId = span.id,
        question = normalizedQuestion,
        isVoice = isVoice,
        clearInput = clearInput
      )
      val withPlaceholder = upsertSpanDetailHistory(
        current = queued,
        spanId = span.id,
        detail = placeholderDetail
      )

      if (keepQuickFollowupSpan || current.activePage == WorkspacePage.QUICK_FOLLOWUP) {
        withPlaceholder.copy(
          quickFollowupSpanId = span.id,
          quickFollowupDetailId = derivedParentDetailId
        )
      } else {
        withPlaceholder
      }
    }

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = {
        requestCoordinator.replyForSpanFollowupStream(
          span = span,
          followupQuestion = normalizedQuestion,
          details = detailsSnapshot,
          messages = messagesSnapshot,
          settings = settings
        ) { delta ->
          if (isTokenStale(requestConversationToken, conversationToken) || delta.isBlank()) {
            return@replyForSpanFollowupStream
          }

          streamBuffer.append(delta)
          val partialAnswer = streamBuffer.toString().trimStart()
          val partialDetail = placeholderDetail.copy(
            answer = partialAnswer.ifBlank { "正在生成追问回答..." },
            summary = buildDetailCardSummary(
              question = normalizedQuestion,
              answer = partialAnswer.ifBlank { "正在生成追问回答..." }
            )
          )
          updateUiState(persistSession = false) { current ->
            upsertSpanDetailHistory(
              current = current,
              spanId = span.id,
              detail = partialDetail
            )
          }
        }
      },
      onStale = {
        finishRequest { current ->
          val withoutPlaceholder = removeSpanDetailHistory(current, span.id, detailId)
          clearSpanProcessing(withoutPlaceholder, span.id)
        }
      },
      onSuccess = { reply ->
        val resolvedReply = reply.ifBlank { streamBuffer.toString().trim() }
        finishRequest { current ->
          val detail = placeholderDetail.copy(
            answer = resolvedReply,
            summary = buildDetailCardSummary(question = normalizedQuestion, answer = resolvedReply)
          )
          val updated = upsertSpanDetailHistory(
            current = current,
            spanId = span.id,
            detail = detail,
            clearProcessing = true,
            toastMessage = "追问已保存，可继续右滑深入追问"
          )

          if (keepQuickFollowupSpan || current.activePage == WorkspacePage.QUICK_FOLLOWUP) {
            updated.copy(
              quickFollowupSpanId = span.id,
              quickFollowupDetailId = derivedParentDetailId
            )
          } else {
            updated
          }
        }
        generateAnkiCardByAiAsync(
          requestConversationToken = requestConversationToken,
          source = mode,
          mode = mode,
          spanContent = span.content,
          question = normalizedQuestion,
          answer = resolvedReply
        )
      },
      onFailure = { throwable ->
        handleRequestFailure(
          throwable = throwable,
          onCancel = { current ->
            val withoutPlaceholder = removeSpanDetailHistory(current, span.id, detailId)
            clearSpanProcessing(withoutPlaceholder, span.id)
          },
          onError = { current, errorHint ->
            val withoutPlaceholder = removeSpanDetailHistory(current, span.id, detailId)
            clearSpanProcessing(withoutPlaceholder, span.id, toastMessage = "追问失败：$errorHint")
          }
        )
      }
    )
  }

  private fun sendQuickFollowupQuestion(question: String) {
    val current = _uiState.value
    val span = resolveQuickFollowupSpan(current)
    if (span == null) {
      postToast("请先在回复底部时间层或回答段落上右滑并停留进入精细追问")
      return
    }

    enqueueSpanFollowupAndFetch(
      span = span,
      followupQuestion = question,
      isVoice = false,
      mode = "精细追问",
      clearInput = true,
      keepQuickFollowupSpan = true,
      parentDetailId = current.quickFollowupDetailId
    )
  }

  private fun resolveQuickFollowupSpan(state: ChatUiState): SpanData? {
    return findSpanById(state.messages, state.quickFollowupSpanId)
      ?: findLatestAssistantQuestionSpan(state.messages)
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
    val assistantMessageId = nextMessageId()
    val assistantMessageTime = currentTime()
    val requestMessages = listOf(userMessage)
    val streamBuffer = StringBuilder()
    val reasoningBuffer = StringBuilder()
    val assistantPlaceholder = createAssistantMessage(
      content = "正在生成回答...",
      sourceQuestion = question,
      reasoningSummary = null,
      messageId = assistantMessageId,
      messageTime = assistantMessageTime
    )

    val pushPartialAssistant: () -> Unit = {
      val partial = streamBuffer.toString().trimStart()
      val reasoning = reasoningBuffer.toString().trim().ifBlank { null }
      val partialMessage = createAssistantMessage(
        content = partial.ifBlank { "正在生成回答..." },
        sourceQuestion = question,
        reasoningSummary = reasoning,
        messageId = assistantMessageId,
        messageTime = assistantMessageTime
      )
      updateUiState(persistSession = false) { current ->
        upsertAssistantMessageState(
          current = current,
          assistantMessage = partialMessage
        )
      }
    }

    startRequest(clearToast = true) { current ->
      val queued = queueQuestionState(
        current = current,
        userMessage = userMessage,
        question = question,
        isFollowup = isFollowup,
        isVoice = isVoice,
        clearInput = clearInput
      )
      upsertAssistantMessageState(
        current = queued,
        assistantMessage = assistantPlaceholder
      )
    }

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = {
        requestCoordinator.replyForConversationStream(
          messages = requestMessages,
          settings = settings,
          onDelta = { delta ->
            if (isTokenStale(requestConversationToken, conversationToken)) {
              return@replyForConversationStream
            }

            if (delta.isBlank()) {
              return@replyForConversationStream
            }

            streamBuffer.append(delta)
            pushPartialAssistant()
          },
          onReasoningDelta = { reasoningDelta ->
            if (isTokenStale(requestConversationToken, conversationToken)) {
              return@replyForConversationStream
            }

            if (reasoningDelta.isBlank()) {
              return@replyForConversationStream
            }

            reasoningBuffer.append(reasoningDelta)
            pushPartialAssistant()
          }
        )
      },
      onStale = { finishRequest() },
      onSuccess = { reply ->
        val resolvedReply = reply.ifBlank { streamBuffer.toString().trim() }
        val resolvedReasoning = reasoningBuffer.toString().trim().ifBlank { null }
        val assistantMessage = createAssistantMessage(
          content = resolvedReply,
          sourceQuestion = question,
          reasoningSummary = resolvedReasoning,
          messageId = assistantMessageId,
          messageTime = assistantMessageTime
        )

        finishRequest { current ->
          appendAssistantMessageState(
            current = current,
            assistantMessage = assistantMessage,
            toastMessage = null
          )
        }
      },
      onFailure = { throwable ->
        val partialReply = streamBuffer.toString().trimStart()
        val partialReasoning = reasoningBuffer.toString().trim().ifBlank { null }
        if (partialReply.isNotBlank() || partialReasoning != null) {
          val recoveredMessage = createAssistantMessage(
            content = partialReply.ifBlank { "网络波动，已保留本次回答的已生成内容。" },
            sourceQuestion = question,
            reasoningSummary = partialReasoning,
            messageId = assistantMessageId,
            messageTime = assistantMessageTime
          )
          finishRequest { current ->
            appendAssistantMessageState(
              current = current,
              assistantMessage = recoveredMessage,
              toastMessage = "网络连接中断，已保留已生成内容",
              knowledgeTexts = listOf(partialReply.ifBlank { partialReasoning.orEmpty() })
            )
          }
          return@launchTokenAwareRequest
        }

        handleRequestFailure(
          throwable = throwable,
          onError = { current, errorHint ->
            val rolledBackPlaceholder = rollbackQueuedUserMessageState(
              current = current,
              messageId = assistantMessageId
            )
            rollbackQueuedUserMessageState(
              current = rolledBackPlaceholder,
              messageId = userMessage.id,
              restoredInput = question,
              toastMessage = "回答失败：$errorHint（问题已保留，可重发）"
            )
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

    sessionRegistry.replaceAll(
      listOf(
        toStoredSessionSnapshot(
          state = initialState,
          title = "主界面",
          createdAt = now,
          updatedAt = now
        )
      )
    )

    _uiState.value = initialState
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

    sessionRegistry.replaceAll(listOf(updated))

  }

  private fun restoreSessionsFromStorage() {
    val store = sessionStorage ?: return
    val restored = store.load()
    if (restored == null) {
      sessionRegistry.clear()
      resetConversation()
      return
    }

    if (restored.sessions.isEmpty()) {
      sessionRegistry.clear()
      _uiState.update { current ->
        current.copy(
          settings = restored.settings,
          settingsDraft = restored.settings
        )
      }
      resetConversation()
      return
    }

    val preferredSession = restored.sessions.firstOrNull { session -> session.id == restored.activeSessionId }
      ?: restored.sessions.firstOrNull()
      ?: run {
        sessionRegistry.clear()
        resetConversation()
        return
      }

    val settings = restored.settings
    val sharedAnkiCards = mergeGlobalAnkiCards(restored.sessions)
    sessionRegistry.replaceAll(listOf(preferredSession))
    activateSession(
      session = preferredSession,
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
    val requestId = latestPersistRequestId.incrementAndGet()

    viewModelScope.launch(Dispatchers.IO) {
      persistMutex.withLock {
        if (requestId < latestPersistRequestId.get()) {
          return@withLock
        }

        val result = store.save(payload)
        if (result.isFailure && requestId == latestPersistRequestId.get()) {
          val message = result.exceptionOrNull()?.message.orEmpty().ifBlank { "未知错误" }
          launch {
            postToast("本地保存失败：$message")
          }
        }
      }
    }
  }

  private fun buildSavedQuestionSnapshot(
    state: ChatUiState,
    messageId: String
  ): SavedQuestion? {
    val assistantIndex = state.messages.indexOfFirst { message ->
      message is ChatMessage.Assistant && message.id == messageId
    }
    val assistantMessage = state.messages.getOrNull(assistantIndex) as? ChatMessage.Assistant ?: return null
    val sourceUser = state.messages
      .take(assistantIndex)
      .asReversed()
      .filterIsInstance<ChatMessage.User>()
      .firstOrNull()

    val question = sourceUser?.text?.trim().orEmpty().ifBlank {
      assistantMessage.mainSpan?.sourceQuestion?.trim()
        .orEmpty()
        .ifBlank { assistantMessage.spans.firstOrNull()?.sourceQuestion?.trim().orEmpty() }
    }
    if (question.isBlank()) {
      return null
    }

    val answer = assistantMessage.fullAnswerText()
    if (answer.isBlank()) {
      return null
    }

    val historyCount = assistantMessage.interactiveSpans().sumOf { span ->
      state.histories[span.id].orEmpty().size
    }
    val tags = inferKnowledgePoints(listOf(question, answer).joinToString(separator = "\n"))
      .distinct()
      .take(6)

    return SavedQuestion(
      id = "saved-${System.currentTimeMillis()}-${messageId}",
      sourceMessageId = assistantMessage.id,
      question = question,
      answer = answer,
      sourceTime = assistantMessage.time,
      savedAt = System.currentTimeMillis(),
      followupCount = historyCount,
      knowledgeTags = tags
    )
  }

  private fun syncSavedQuestionSnapshot(
    current: ChatUiState,
    assistantMessage: ChatMessage.Assistant
  ): ChatUiState {
    val existing = current.savedQuestions.firstOrNull { saved -> saved.sourceMessageId == assistantMessage.id } ?: return current
    val refreshed = buildSavedQuestionSnapshot(current, assistantMessage.id) ?: return current
    val merged = refreshed.copy(id = existing.id, savedAt = existing.savedAt)
    return current.copy(
      savedQuestions = current.savedQuestions.map { saved ->
        if (saved.sourceMessageId == assistantMessage.id) merged else saved
      }
    )
  }

  private fun createAssistantMessage(
    content: String,
    sourceQuestion: String,
    reasoningSummary: String? = null,
    messageId: String = nextMessageId(),
    messageTime: String = currentTime()
  ): ChatMessage.Assistant {
    val normalizedContent = content.trim()
    val mainSpan = SpanData(
      id = nextSpanId(),
      content = normalizedContent,
      sourceQuestion = sourceQuestion
    )
    val spans = splitParagraphs(content).map { paragraph ->
      SpanData(id = nextSpanId(), content = paragraph, sourceQuestion = sourceQuestion)
    }

    return ChatMessage.Assistant(
      id = messageId,
      time = messageTime,
      spans = spans,
      mainSpan = mainSpan,
      reasoningSummary = reasoningSummary?.trim()?.ifBlank { null }
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
    val stateSnapshot = _uiState.value
    val settingsSnapshot = stateSnapshot.settings
    val profileSnapshot = stateSnapshot.profile
    val knowledgeSnapshot = stateSnapshot.knowledgePoints
    val knowledgeGapSnapshot = buildKnowledgeGapInsights(
      messages = stateSnapshot.messages,
      histories = stateSnapshot.histories,
      knowledgePoints = stateSnapshot.knowledgePoints
    )
    val deckSnapshot = detectExistingDeckCategories(stateSnapshot.ankiCards)

    viewModelScope.launch {
      val result = requestCoordinator.generateAnkiPayload(
        AnkiGenerationInput(
          mode = mode,
          spanContent = spanContent,
          question = question,
          answer = answer,
          profile = profileSnapshot,
          knowledgePoints = knowledgeSnapshot,
          knowledgeGapInsights = knowledgeGapSnapshot,
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
