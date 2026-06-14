package com.studysuit.aiqa.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studysuit.aiqa.AiGenerationForegroundService
import com.studysuit.aiqa.data.ArkApiClient
import com.studysuit.aiqa.data.ArkMistakeFusionClient
import com.studysuit.aiqa.data.ArkMistakeVisionClient
import com.studysuit.aiqa.data.FlowStudySyncClient
import com.studysuit.aiqa.data.MistakeAnswerJudgement
import com.studysuit.aiqa.data.MistakeRecognitionCoordinator
import com.studysuit.aiqa.data.MistakeBookItem
import com.studysuit.aiqa.data.MistakeBookStorage
import com.studysuit.aiqa.data.MistakeRecognitionDraft
import com.studysuit.aiqa.data.MistakeRecognitionStatus
import com.studysuit.aiqa.data.MistakeReviewJudgementSource
import com.studysuit.aiqa.data.MistakeReviewScheduler
import com.studysuit.aiqa.data.MistakeSrsEngine
import com.studysuit.aiqa.data.MlKitMistakeOcrClient
import com.studysuit.aiqa.data.OpenSpeechAsrClient
import com.studysuit.aiqa.data.UnavailableMistakeOcrClient
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
  private var mistakeSeed = 0
  private var sessionSeed = 0
  private var conversationToken = 0L
  private var inFlightRequests = 0
  private var appContext: Context? = null
  private var lastBackgroundProgressPushAt = 0L
  private var lastBackgroundProgressPreview = ""
  private var lastInFlightPersistAt = 0L
  private val requestCoordinator = StudyChatRequestCoordinator(
    arkApiClient = ArkApiClient(),
    openSpeechAsrClient = OpenSpeechAsrClient()
  )
  private val mistakeVisionClient = ArkMistakeVisionClient()
  private val secondaryMistakeVisionClient = ArkMistakeVisionClient()
  private val mistakeFusionClient = ArkMistakeFusionClient()
  private val flowStudySyncClient = FlowStudySyncClient()
  private var sessionStorage: SessionStorage? = null
  private var mistakeBookStorage: MistakeBookStorage? = null
  private val sessionRegistry = SessionRegistry()
  private val persistMutex = Mutex()
  private val latestPersistRequestId = AtomicLong(0L)

  private val _uiState = MutableStateFlow(ChatUiState())
  val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  init {
    resetConversation()
  }

  fun ensureStorage(context: Context) {
    val applicationContext = context.applicationContext
    appContext = applicationContext
    if (mistakeBookStorage == null) {
      mistakeBookStorage = MistakeBookStorage(applicationContext)
    }
    if (sessionStorage != null) {
      if (inFlightRequests > 0) {
        syncBackgroundRequestService(status = _uiState.value.toastMessage)
      }
      restoreMistakeBookItems()
      return
    }

    sessionStorage = SessionStorage(applicationContext)
    restoreSessionsFromStorage()
    restoreMistakeBookItems()
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

  fun onCoachInputChanged(value: String) {
    updateUiState(persistSession = true) { current ->
      current.copy(coachInput = value)
    }
  }

  fun onCoachPageViewed() {
    if (isDailyTrainingStale(_uiState.value.dailyTraining)) {
      updateUiState(persistSession = true) { current ->
        current.copy(dailyTraining = DailyTrainingState())
      }
    }
    ensureCoachDigestCurrent()
  }

  fun sendCoachQuickAction(prompt: String) {
    sendCoachMessageInternal(prompt)
  }

  fun askCoachAboutRecommendation(question: CoachRecommendedQuestion) {
    sendCoachMessageInternal(buildCoachRecommendationFollowupPrompt(question))
  }

  fun jumpToCoachRecommendationBasis(question: CoachRecommendedQuestion) {
    val targetSavedQuestionId = question.anchorSavedQuestionId
    if (targetSavedQuestionId.isNullOrBlank()) {
      postToast("这道题暂时还没有可跳转的依据题")
      return
    }

    updateUiState(persistSession = true) { current ->
      val exists = current.savedQuestions.any { saved -> saved.id == targetSavedQuestionId }
      if (!exists) {
        current.copy(toastMessage = "依据题暂未归档完成")
      } else {
        current.copy(
          activePage = WorkspacePage.ARCHIVE,
          archiveFocusSavedQuestionId = targetSavedQuestionId,
          toastMessage = "已跳到教练出题依据题"
        )
      }
    }
  }

  fun startCoachRecommendedTraining(question: CoachRecommendedQuestion) {
    val current = _uiState.value
    val normalizedPrompt = question.prompt.trim()
    if (normalizedPrompt.isBlank()) {
      postToast("这道推荐题暂时不可用")
      return
    }
    if (current.isLoading) {
      postToast("当前还有内容在生成，请稍等")
      return
    }

    val digest = current.coachDigest?.takeIf { it.dateKey == currentCoachDateKey() } ?: ensureCoachDigestCurrent()
    val round = question.copy(
      title = question.title.trim().ifBlank { digest.focusAreas.firstOrNull()?.point?.let { point -> "$point · 典型题" } ?: "教练推荐题" },
      reason = question.reason.trim(),
      prompt = normalizedPrompt
    )

    updateUiState(persistSession = true) { state ->
      state.copy(
        activePage = WorkspacePage.COACH,
        input = "",
        coachInput = "",
        dailyTraining = DailyTrainingState(
          dateKey = digest.dateKey,
          rounds = listOf(round),
          currentIndex = 0,
          phase = DailyTrainingPhase.ASKING_QUESTION,
          currentQuestionText = ""
        ),
        toastMessage = "已开始针对性训练 · 正在出题"
      )
    }
    launchDailyTrainingRound(rounds = listOf(round), roundIndex = 0)
  }

  fun startCoachTraining() {
    val current = _uiState.value
    if (current.isLoading) {
      postToast("当前还有内容在生成，请稍等")
      return
    }

    val activeTraining = current.dailyTraining.takeIf { training ->
      !isDailyTrainingStale(training) && training.isActive
    }
    if (activeTraining != null) {
      updateUiState(persistSession = true) { state ->
        state.copy(
          activePage = WorkspacePage.COACH,
          input = state.input,
          coachInput = state.coachInput,
          toastMessage = "已回到今日训练"
        )
      }
      return
    }

    val digest = current.coachDigest?.takeIf { it.dateKey == currentCoachDateKey() } ?: ensureCoachDigestCurrent()
    val rounds = buildCoachTrainingRounds(digest)
    if (rounds.isEmpty()) {
      postToast("今日训练题暂时不可用")
      return
    }

    updateUiState(persistSession = true) { state ->
      state.copy(
        activePage = WorkspacePage.COACH,
        input = "",
        coachInput = "",
        dailyTraining = DailyTrainingState(
          dateKey = digest.dateKey,
          rounds = rounds,
          currentIndex = 0,
          phase = DailyTrainingPhase.ASKING_QUESTION,
          currentQuestionText = ""
        ),
        toastMessage = "已开始今日训练 · 正在出第1题"
      )
    }
    launchDailyTrainingRound(rounds = rounds, roundIndex = 0)
  }

  fun sendQuestion() {
    val current = _uiState.value
    when (current.activePage) {
      WorkspacePage.QUICK_FOLLOWUP -> {
        val question = current.input.trim()
        if (question.isEmpty()) {
          return
        }
        sendQuickFollowupQuestion(question)
        return
      }

      WorkspacePage.COACH -> {
        sendCoachMessage()
        return
      }

      else -> Unit
    }

    val question = current.input.trim()
    if (question.isEmpty()) {
      return
    }

    if (isDailyTrainingStale(current.dailyTraining)) {
      updateUiState(persistSession = true) { state ->
        state.copy(dailyTraining = DailyTrainingState())
      }
    }

    enqueueQuestionAndFetch(
      question = question,
      isFollowup = false,
      isVoice = false
    )
  }

  fun sendCoachPageInput() {
    val current = _uiState.value
    val answer = current.coachInput.trim()
    val activeTraining = current.dailyTraining.takeIf { training ->
      !isDailyTrainingStale(training) && training.isActive
    }
    if (activeTraining != null) {
      when (activeTraining.phase) {
        DailyTrainingPhase.AWAITING_ANSWER -> {
          if (answer.isNotBlank()) {
            submitDailyTrainingAnswer(answer, fromCoachInput = true)
          }
          return
        }

        DailyTrainingPhase.ASKING_QUESTION,
        DailyTrainingPhase.REVIEWING_ANSWER -> {
          postToast("当前训练题还在准备或批改中，请稍等")
          return
        }

        else -> Unit
      }
    }

    sendCoachMessageInternal()
  }

  fun sendCoachMessage() {
    sendCoachMessageInternal()
  }

  private fun sendCoachMessageInternal(questionOverride: String? = null) {
    val initialState = _uiState.value
    val question = questionOverride?.trim().orEmpty().ifBlank { initialState.coachInput.trim() }
    if (question.isEmpty()) {
      return
    }

    val digest = initialState.coachDigest?.takeIf { currentDigest ->
      currentDigest.dateKey == currentCoachDateKey()
    } ?: ensureCoachDigestCurrent()
    val stateSnapshot = _uiState.value
    val requestConversationToken = conversationToken
    val settings = stateSnapshot.settings
    val profileSnapshot = stateSnapshot.profile
    val knowledgeSnapshot = stateSnapshot.knowledgePoints
    val savedQuestionsSnapshot = stateSnapshot.savedQuestions
    val mistakeItemsSnapshot = stateSnapshot.mistakeItems
    val knowledgeGapSnapshot = buildKnowledgeGapInsights(
      messages = stateSnapshot.messages,
      histories = stateSnapshot.histories,
      knowledgePoints = stateSnapshot.knowledgePoints
    )
    val userMessage = CoachChatMessage(
      id = nextMessageId(),
      role = CoachMessageRole.USER,
      time = currentTime(),
      text = question
    )
    val assistantMessageId = nextMessageId()
    val assistantMessageTime = currentTime()
    val assistantPlaceholder = CoachChatMessage(
      id = assistantMessageId,
      role = CoachMessageRole.ASSISTANT,
      time = assistantMessageTime,
      text = "正在生成教练建议..."
    )
    val streamBuffer = StringBuilder()

    val pushPartialAssistant: () -> Unit = {
      val partial = streamBuffer.toString().trimStart()
      val partialMessage = assistantPlaceholder.copy(
        text = partial.ifBlank { "正在生成教练建议..." }
      )
      updateUiState(persistSession = false) { current ->
        current.copy(
          coachMessages = upsertCoachMessage(current.coachMessages, partialMessage),
          coachDigest = current.coachDigest ?: digest
        )
      }
      publishBackgroundProgress(
        preview = partialMessage.text,
        status = "正在生成教练建议..."
      )
    }

    startRequest(clearToast = true) { current ->
      current.copy(
        activePage = WorkspacePage.COACH,
        coachInput = "",
        coachDigest = digest,
        coachMessages = current.coachMessages + listOf(userMessage, assistantPlaceholder)
      )
    }

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = {
        requestCoordinator.replyForCoachConversationStream(
          coachMessages = stateSnapshot.coachMessages + userMessage,
          digest = digest,
          profile = profileSnapshot,
          knowledgePoints = knowledgeSnapshot,
          knowledgeGapInsights = knowledgeGapSnapshot,
          savedQuestions = savedQuestionsSnapshot,
          mistakeItems = mistakeItemsSnapshot,
          settings = settings,
          onDelta = { delta ->
            if (isTokenStale(requestConversationToken, conversationToken) || delta.isBlank()) {
              return@replyForCoachConversationStream
            }

            streamBuffer.append(delta)
            pushPartialAssistant()
          }
        )
      },
      onStale = { finishRequest() },
      onSuccess = { reply ->
        val resolvedReply = reply.ifBlank { streamBuffer.toString().trim() }.ifBlank { "我先陪你把问题拆开，你可以继续追问我最卡的那一步。" }
        val assistantMessage = assistantPlaceholder.copy(text = resolvedReply)
        finishRequest { current ->
          current.copy(
            coachDigest = current.coachDigest ?: digest,
            coachMessages = upsertCoachMessage(current.coachMessages, assistantMessage)
          )
        }
      },
      onFailure = { throwable ->
        val partialReply = streamBuffer.toString().trimStart()
        if (partialReply.isNotBlank()) {
          val recovered = assistantPlaceholder.copy(text = partialReply)
          finishRequest { current ->
            current.copy(
              coachDigest = current.coachDigest ?: digest,
              coachMessages = upsertCoachMessage(current.coachMessages, recovered),
              toastMessage = "网络连接中断，已保留已生成的教练建议"
            )
          }
          return@launchTokenAwareRequest
        }

        handleRequestFailure(
          throwable = throwable,
          onError = { current, errorHint ->
            current.copy(
              coachInput = question,
              coachMessages = current.coachMessages.filterNot { message ->
                message.id == assistantMessageId || message.id == userMessage.id
              },
              toastMessage = "教练回复失败：$errorHint（问题已保留，可重发）"
            )
          }
        )
      }
    )
  }

  fun submitImageQuestion(
    imageBytesList: List<ByteArray>,
    source: String,
    note: String? = null,
    imageCount: Int = 1,
    previewImages: List<ByteArray> = emptyList()
  ) {
    val normalizedImages = imageBytesList.filter { bytes -> bytes.isNotEmpty() }
    if (normalizedImages.isEmpty()) {
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
    val previewList = previewImages.take(6).ifEmpty { normalizedImages.take(6) }
    val userMessage = ChatMessage.User(
      id = nextMessageId(),
      time = currentTime(),
      text = question,
      imagePreviewBytes = previewList.firstOrNull(),
      imagePreviewList = previewList
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
      publishBackgroundProgress(
        preview = partial.ifBlank { "正在识别题目并生成讲解..." },
        status = "$source 处理中..."
      )
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
          imageBytesList = normalizedImages,
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
        archiveFocusSavedQuestionId = saved.id,
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
          archiveFocusSavedQuestionId = current.archiveFocusSavedQuestionId.takeUnless { focusedId -> focusedId == savedQuestionId },
          toastMessage = "已移出题目归档"
        )
      }
    }
  }

  fun openSavedQuestionWorkspace(savedQuestionId: String) {
    updateUiState(persistSession = true) { current ->
      restoreSavedQuestionState(current = current, savedQuestionId = savedQuestionId)
    }
  }

  fun saveMistakeRecognitionDraft(draft: MistakeRecognitionDraft) {
    updateUiState(persistSession = false) { current ->
      saveMistakeRecognitionDraftState(current, draft)
    }
  }

  fun confirmMistakeDraft(draft: MistakeRecognitionDraft) {
    upsertMistakeRecognitionDraft(draft)
    confirmMistakeDraft(draft.id)
  }

  fun recognizeMistakeFromImages(
    imageBytesList: List<ByteArray>,
    source: String = "错题图片",
    note: String = ""
  ) {
    val normalizedImages = imageBytesList.filter { bytes -> bytes.isNotEmpty() }
    if (normalizedImages.isEmpty()) {
      postToast("图片为空，无法录入错题")
      return
    }

    val draftId = "draft-${System.currentTimeMillis()}"
    val imageRefs = saveMistakeImages(imageBytesList = normalizedImages, idPrefix = draftId)
    val settings = _uiState.value.settings
    val pipelineConfig = settings.toMistakeRecognitionPipelineConfig()
    val promptNote = listOf(source.trim(), note.trim())
      .filter(String::isNotBlank)
      .joinToString(separator = "；")
    val coordinator = MistakeRecognitionCoordinator(
      ocrClient = if (appContext != null) MlKitMistakeOcrClient() else UnavailableMistakeOcrClient,
      visionClient = mistakeVisionClient,
      additionalVisionClients = if (pipelineConfig.visionConfigs.size > 1) {
        listOf(secondaryMistakeVisionClient)
      } else {
        emptyList()
      },
      fusionClient = if (pipelineConfig.fusionConfig != null) mistakeFusionClient else null,
      idProvider = { draftId },
      clock = { System.currentTimeMillis() }
    )

    startRequest(toastMessage = "正在识别错题...") { current ->
      current.copy(activePage = WorkspacePage.MISTAKES)
    }
    viewModelScope.launch {
      val draft = coordinator.recognizeFromImages(
        imageBytesList = normalizedImages,
        imageRefs = imageRefs,
        note = promptNote,
        config = pipelineConfig.visionConfigs.firstOrNull() ?: settings.toArkRuntimeConfig(),
        pipelineConfig = pipelineConfig
      )
      finishRequest { current ->
        saveMistakeRecognitionDraftState(current, draft).copy(toastMessage = mistakeRecognitionToast(draft))
      }
    }
  }

  fun confirmMistakeDraft(draftId: String) {
    val draft = _uiState.value.mistakeRecognitionDrafts.firstOrNull { item -> item.id == draftId }
    if (draft == null) {
      postToast("未找到错题草稿")
      return
    }

    val now = System.currentTimeMillis()
    val existingItem = _uiState.value.mistakeItems.firstOrNull { item -> item.recognitionDraftId == draft.id }
    val createdItem = createMistakeBookItemFromDraft(
      draft = draft,
      itemId = existingItem?.id ?: nextMistakeItemId(),
      now = now
    )
    val item = existingItem?.let { existing ->
      when {
        !createdItem.isReadyForReview -> createdItem.copy(
          createdAt = existing.createdAt,
          updatedAt = now,
          reviewAttempts = existing.reviewAttempts
        )

        !existing.isReadyForReview -> createdItem.copy(
          createdAt = existing.createdAt,
          updatedAt = now,
          reviewAttempts = existing.reviewAttempts
        )

        else -> createdItem.copy(
          createdAt = existing.createdAt,
          updatedAt = now,
          status = existing.status,
          reviewState = existing.reviewState,
          reviewAttempts = existing.reviewAttempts
        )
      }
    } ?: createdItem
    updateMistakeItems(persistMistakes = true) { current ->
      upsertMistakeItemState(
        current = current,
        item = item,
        toastMessage = if (item.isReadyForReview) "已加入错题本" else "已保存到待完善"
      )
    }
  }

  private fun upsertMistakeRecognitionDraft(draft: MistakeRecognitionDraft) {
    updateUiState(persistSession = false) { current ->
      val updatedDraft = draft.copy(updatedAt = System.currentTimeMillis())
      current.copy(
        mistakeRecognitionDrafts = listOf(updatedDraft) + current.mistakeRecognitionDrafts.filterNot { item -> item.id == draft.id },
        activeMistakeDraftId = draft.id
      )
    }
  }

  fun addSavedQuestionToMistakeBook(savedQuestionId: String) {
    val state = _uiState.value
    val saved = state.savedQuestions.firstOrNull { question -> question.id == savedQuestionId }
    if (saved == null) {
      postToast("未找到归档题")
      return
    }

    val itemId = state.mistakeItems.firstOrNull { item -> item.sourceSavedQuestionId == saved.id }?.id ?: nextMistakeItemId()
    val imageRefs = saveSavedQuestionImages(saved = saved, itemId = itemId)
    updateMistakeItems(persistMistakes = true) { current ->
      addSavedQuestionToMistakeBookState(
        current = current,
        savedQuestionId = savedQuestionId,
        itemId = itemId,
        imageRefs = imageRefs,
        now = System.currentTimeMillis()
      )
    }
  }

  fun addMistakeToAnki(itemId: String) {
    val item = _uiState.value.mistakeItems.firstOrNull { mistake -> mistake.id == itemId }
    if (item == null) {
      postToast("未找到错题")
      return
    }
    if (!item.isReadyForReview) {
      postToast("题干和正确答案补齐后才能生成 Anki")
      return
    }

    updateUiState(persistSession = true) { current ->
      val latestItem = current.mistakeItems.firstOrNull { mistake -> mistake.id == itemId }
      if (latestItem == null) {
        current.copy(toastMessage = "未找到错题")
      } else {
        val tags = buildMistakeAnkiTags(latestItem)
        val deckName = resolveDeckNameForAutoCard(
          suggestedDeck = null,
          tags = tags,
          existingCards = current.ankiCards
        )
        val card = createAnkiCard(
          front = buildMistakeAnkiFront(latestItem),
          back = buildMistakeAnkiBack(latestItem),
          source = "错题本：${latestItem.question.take(32)}",
          tags = tags,
          deckName = deckName
        )
        current.copy(
          ankiCards = sortAnkiCardsForReview(prependAnkiCard(current.ankiCards, card)),
          toastMessage = "已生成 Anki 卡片"
        )
      }
    }
  }

  fun recordMistakeReview(
    itemId: String,
    isCorrect: Boolean,
    userAnswer: String = "",
    judgementSource: MistakeReviewJudgementSource = MistakeReviewJudgementSource.USER,
    modelSuggestion: String = "",
    note: String = ""
  ) {
    updateMistakeItems(persistMistakes = true) { current ->
      recordMistakeReviewState(
        current = current,
        itemId = itemId,
        isCorrect = isCorrect,
        reviewedAt = System.currentTimeMillis(),
        userAnswer = userAnswer,
        judgementSource = judgementSource,
        modelSuggestion = modelSuggestion,
        note = note
      )
    }
  }

  fun requestMistakeAnswerJudgement(
    itemId: String,
    userAnswer: String,
    answerImageBytesList: List<ByteArray> = emptyList()
  ) {
    val item = _uiState.value.mistakeItems.firstOrNull { mistake -> mistake.id == itemId }
    if (item == null) {
      postToast("未找到错题")
      return
    }
    if (!item.isReadyForReview) {
      postToast("题干和正确答案补齐后才能判题")
      return
    }
    val normalizedImages = answerImageBytesList.filter { bytes -> bytes.isNotEmpty() }
    if (userAnswer.isBlank() && normalizedImages.isEmpty()) {
      postToast("先输入作答或上传作答图片")
      return
    }

    val settings = _uiState.value.settings
    startRequest(toastMessage = "正在请求模型判题...") { current ->
      current.copy(
        activePage = WorkspacePage.MISTAKES,
        activeMistakeReviewId = itemId
      )
    }
    viewModelScope.launch {
      val answerOcrText = recognizeMistakeAnswerText(normalizedImages)
      val result = requestCoordinator.judgeMistakeAnswer(
        item = item,
        userAnswer = userAnswer,
        answerOcrText = answerOcrText,
        answerImageBytesList = normalizedImages,
        settings = settings
      )

      finishRequest { current ->
        val judgement = result.getOrNull()
        if (judgement == null) {
          current.copy(toastMessage = "模型暂未给出判题建议，请手动记录")
        } else {
          setMistakeReviewSuggestionState(
            current = current,
            suggestion = judgement.toMistakeReviewSuggestion(
              itemId = itemId,
              userAnswer = userAnswer,
              answerOcrText = answerOcrText
            )
          )
        }
      }
    }
  }

  fun applyMistakeAnswerJudgement(
    itemId: String,
    userAnswer: String,
    judgement: MistakeAnswerJudgement,
    answerOcrText: String = ""
  ) {
    updateUiState(persistSession = false) { current ->
      setMistakeReviewSuggestionState(
        current = current,
        suggestion = judgement.toMistakeReviewSuggestion(
          itemId = itemId,
          userAnswer = userAnswer,
          answerOcrText = answerOcrText
        )
      )
    }
  }

  fun confirmMistakeAnswerJudgement(itemId: String, finalIsCorrect: Boolean) {
    updateMistakeItems(persistMistakes = true) { current ->
      confirmMistakeReviewSuggestionState(
        current = current,
        itemId = itemId,
        finalIsCorrect = finalIsCorrect,
        reviewedAt = System.currentTimeMillis()
      )
    }
  }

  fun deleteMistakeItem(itemId: String) {
    updateMistakeItems(persistMistakes = true) { current ->
      deleteMistakeItemState(current, itemId)
    }
  }

  fun reopenMistakeItem(itemId: String) {
    updateMistakeItems(persistMistakes = true) { current ->
      reopenMistakeItemState(current = current, itemId = itemId, reopenedAt = System.currentTimeMillis())
    }
  }

  fun openDueMistakeReviewQueue() {
    val dueItems = MistakeSrsEngine.dueMistakes(_uiState.value.mistakeItems)
    if (dueItems.isEmpty()) {
      postToast("当前暂无到期错题")
      return
    }

    updateUiState(persistSession = true) { current ->
      current.copy(
        activePage = WorkspacePage.MISTAKES,
        activeMistakeReviewId = dueItems.first().id,
        activeMistakeDraftId = null,
        isMistakeDueReviewMode = true,
        toastMessage = "进入错题复习（${dueItems.size} 道）"
      )
    }
  }

  fun refreshMistakeReviewReminder() {
    val context = appContext
    if (context == null) {
      postToast("存储未初始化，暂时无法安排提醒")
      return
    }
    MistakeReviewScheduler.reschedule(context, _uiState.value.mistakeItems)
    postToast("错题复习提醒已更新")
  }

  fun updateMistakeSearchQuery(query: String) {
    updateUiState(persistSession = false) { current ->
      current.copy(mistakeSearchQuery = query)
    }
  }

  fun switchWorkspacePage(page: WorkspacePage) {
    updateUiState(persistSession = true) { current ->
      val quickSourceMessageId = if (page == WorkspacePage.QUICK_FOLLOWUP) {
        findAssistantMessageById(current.messages, current.quickFollowupSourceMessageId)?.id
      } else {
        current.quickFollowupSourceMessageId
      }
      val quickSourceMessage = findAssistantMessageById(current.messages, quickSourceMessageId)
      val quickSpanId = if (page == WorkspacePage.QUICK_FOLLOWUP) {
        findSpanById(current.messages, current.quickFollowupSpanId)?.id
          ?: quickSourceMessage?.let { message -> (message.mainSpan ?: message.spans.lastOrNull())?.id }
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
        quickFollowupSourceMessageId = quickSourceMessageId,
        archiveFocusSavedQuestionId = if (page == WorkspacePage.ARCHIVE) current.archiveFocusSavedQuestionId else null,
        activeMistakeDraftId = if (page == WorkspacePage.MISTAKES) current.activeMistakeDraftId else null,
        isDueReviewMode = false,
        isMistakeDueReviewMode = false,
        focusedDeckName = null
      )
      if (current.activePage == page && !current.isDueReviewMode && current.focusedDeckName == null) {
        current
      } else {
        target
      }
    }

    if (page == WorkspacePage.COACH) {
      ensureCoachDigestCurrent()
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
          current.quickFollowupSourceMessageId == null &&
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
            quickFollowupSourceMessageId = null,
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
    val normalizedTags = filterToHighSchoolKnowledgeTags(tags, maxSize = 10)

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
          publishBackgroundProgress(
            preview = partialDetail.answer,
            status = "正在生成该段讲解..."
          )
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
      publishBackgroundProgress(
        preview = partial.ifBlank { "正在刷新回答..." },
        status = "正在刷新整条回复..."
      )
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
    val normalizedImages = imageBytesList.filter { bytes -> bytes.isNotEmpty() }
    if (normalizedImages.isEmpty()) {
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
      publishBackgroundProgress(
        preview = partial.ifBlank { "正在刷新图片回答..." },
        status = "正在刷新整条回复..."
      )
    }

    startRequest(toastMessage = "正在刷新整条回复...") { current ->
      val reset = clearAssistantReplyArtifacts(current, assistantMessage)
      upsertAssistantMessageState(reset, assistantPlaceholder)
    }

    launchTokenAwareRequest(
      requestConversationToken = requestConversationToken,
      request = {
        requestCoordinator.replyForImageQuestionStream(
          imageBytesList = normalizedImages,
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
    val shouldResetQuickSource = current.quickFollowupSourceMessageId == assistantMessage.id
    val shouldResetSelected = current.selectedSpanId in removedSpanIds

    return current.copy(
      histories = nextHistories,
      processingSpanIds = current.processingSpanIds - removedSpanIds,
      quickFollowupSpanId = if (shouldResetQuick) null else current.quickFollowupSpanId,
      quickFollowupDetailId = if (shouldResetQuick) null else current.quickFollowupDetailId,
      quickFollowupSourceMessageId = if (shouldResetQuickSource) null else current.quickFollowupSourceMessageId,
      selectedSpanId = if (shouldResetSelected) null else current.selectedSpanId,
      selectedDetailId = if (shouldResetSelected) null else current.selectedDetailId,
      activePage = if ((shouldResetQuick || shouldResetQuickSource) && current.activePage == WorkspacePage.QUICK_FOLLOWUP) {
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
          publishBackgroundProgress(
            preview = partialDetail.answer,
            status = "正在生成追问回答..."
          )
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
      ?: findAssistantMessageById(state.messages, state.quickFollowupSourceMessageId)
        ?.let { message -> message.mainSpan ?: message.spans.lastOrNull() }
      ?: findLatestAssistantQuestionSpan(state.messages)
  }

  private fun ensureCoachDigestCurrent(): CoachDailyDigest {
    val todayKey = currentCoachDateKey()
    val current = _uiState.value
    current.coachDigest?.takeIf { digest -> digest.dateKey == todayKey }?.let { digest ->
      return digest
    }

    val digest = buildCoachDailyDigest(
      messages = current.messages,
      histories = current.histories,
      savedQuestions = current.savedQuestions,
      knowledgePoints = current.knowledgePoints,
      mistakeItems = current.mistakeItems
    )
    updateUiState(persistSession = true) { state ->
      if (state.coachDigest?.dateKey == digest.dateKey) {
        state
      } else {
        state.copy(coachDigest = digest)
      }
    }
    return digest
  }

  private fun isDailyTrainingStale(training: DailyTrainingState): Boolean {
    return training.dateKey.isNotBlank() && training.dateKey != currentCoachDateKey()
  }

  private fun launchDailyTrainingRound(
    rounds: List<CoachRecommendedQuestion>,
    roundIndex: Int
  ) {
    val round = rounds.getOrNull(roundIndex)
    if (round == null) {
      updateUiState(persistSession = true) { current ->
        current.copy(
          dailyTraining = DailyTrainingState(
            dateKey = currentCoachDateKey(),
            rounds = rounds,
            currentIndex = rounds.lastIndex.coerceAtLeast(0),
            phase = DailyTrainingPhase.COMPLETED,
            currentQuestionText = ""
          ),
          toastMessage = "今日训练已完成"
        )
      }
      return
    }

    val requestConversationToken = conversationToken
    val settings = _uiState.value.settings
    val displayUserMessage = ChatMessage.User(
      id = nextMessageId(),
      time = currentTime(),
      text = buildTrainingRoundDisplayText(round, roundIndex, rounds.size)
    )
    val assistantMessageId = nextMessageId()
    val assistantMessageTime = currentTime()
    val requestMessages = listOf(
      ChatMessage.User(
        id = "train-request-$assistantMessageId",
        time = assistantMessageTime,
        text = round.prompt
      )
    )
    val streamBuffer = StringBuilder()
    val reasoningBuffer = StringBuilder()
    val placeholderText = "正在生成第${roundIndex + 1}题..."
    val assistantPlaceholder = createAssistantMessage(
      content = placeholderText,
      sourceQuestion = round.prompt,
      reasoningSummary = null,
      messageId = assistantMessageId,
      messageTime = assistantMessageTime
    )
    val coachAssistantPlaceholder = CoachChatMessage(
      id = assistantMessageId,
      role = CoachMessageRole.ASSISTANT,
      time = assistantMessageTime,
      text = placeholderText
    )

    val pushPartialAssistant: () -> Unit = {
      val partial = streamBuffer.toString().trimStart()
      val reasoning = reasoningBuffer.toString().trim().ifBlank { null }
      val partialText = partial.ifBlank { placeholderText }
      val partialMessage = createAssistantMessage(
        content = partialText,
        sourceQuestion = round.prompt,
        reasoningSummary = reasoning,
        messageId = assistantMessageId,
        messageTime = assistantMessageTime
      )
      val coachPartialMessage = coachAssistantPlaceholder.copy(text = partialText)
      updateUiState(persistSession = false) { current ->
        upsertAssistantMessageState(current, partialMessage).copy(
          coachMessages = upsertCoachMessage(current.coachMessages, coachPartialMessage)
        )
      }
      publishBackgroundProgress(
        preview = partialText,
        status = "正在生成今日训练第${roundIndex + 1}题..."
      )
    }

    startRequest(toastMessage = "正在生成今日训练第${roundIndex + 1}题...") { current ->
      val queued = current.copy(
        activePage = WorkspacePage.COACH,
        input = current.input,
        coachInput = "",
        coachMessages = current.coachMessages + coachAssistantPlaceholder,
        messages = current.messages + displayUserMessage,
        profile = current.profile.updateWith(text = round.prompt, isFollowup = false, isVoice = false),
        knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(round.prompt)),
        dailyTraining = DailyTrainingState(
          dateKey = currentCoachDateKey(),
          rounds = rounds,
          currentIndex = roundIndex,
          phase = DailyTrainingPhase.ASKING_QUESTION,
          currentQuestionText = ""
        )
      )
      upsertAssistantMessageState(queued, assistantPlaceholder)
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
        val assistantMessage = createAssistantMessage(
          content = resolvedReply,
          sourceQuestion = round.prompt,
          reasoningSummary = resolvedReasoning,
          messageId = assistantMessageId,
          messageTime = assistantMessageTime
        )
        val coachAssistantMessage = coachAssistantPlaceholder.copy(text = assistantMessage.fullAnswerText())
        finishRequest { current ->
          val updated = appendAssistantMessageState(
            current = current,
            assistantMessage = assistantMessage,
            toastMessage = "第${roundIndex + 1}题已准备好，直接在教练页作答",
            knowledgeTexts = listOf(resolvedReply)
          )
          updated.copy(
            coachMessages = upsertCoachMessage(updated.coachMessages, coachAssistantMessage),
            dailyTraining = DailyTrainingState(
              dateKey = currentCoachDateKey(),
              rounds = rounds,
              currentIndex = roundIndex,
              phase = DailyTrainingPhase.AWAITING_ANSWER,
              currentQuestionText = assistantMessage.fullAnswerText()
            )
          )
        }
      },
      onFailure = { throwable ->
        val partialReply = streamBuffer.toString().trimStart()
        val partialReasoning = reasoningBuffer.toString().trim().ifBlank { null }
        if (partialReply.isNotBlank() || partialReasoning != null) {
          val recoveredMessage = createAssistantMessage(
            content = partialReply.ifBlank { "网络波动，已保留当前训练题的已生成内容。" },
            sourceQuestion = round.prompt,
            reasoningSummary = partialReasoning,
            messageId = assistantMessageId,
            messageTime = assistantMessageTime
          )
          val coachRecoveredMessage = coachAssistantPlaceholder.copy(text = recoveredMessage.fullAnswerText())
          finishRequest { current ->
            val updated = appendAssistantMessageState(
              current = current,
              assistantMessage = recoveredMessage,
              toastMessage = "网络连接中断，已保留当前训练题",
              knowledgeTexts = listOf(partialReply.ifBlank { partialReasoning.orEmpty() })
            )
            updated.copy(
              coachMessages = upsertCoachMessage(updated.coachMessages, coachRecoveredMessage),
              dailyTraining = DailyTrainingState(
                dateKey = currentCoachDateKey(),
                rounds = rounds,
                currentIndex = roundIndex,
                phase = DailyTrainingPhase.AWAITING_ANSWER,
                currentQuestionText = recoveredMessage.fullAnswerText()
              )
            )
          }
          return@launchTokenAwareRequest
        }

        handleRequestFailure(
          throwable = throwable,
          onError = { current, errorHint ->
            val rolledBackPlaceholder = rollbackQueuedUserMessageState(current, assistantMessageId)
            rollbackQueuedUserMessageState(
              current = rolledBackPlaceholder,
              messageId = displayUserMessage.id,
              toastMessage = "今日训练启动失败：$errorHint"
            ).copy(
              coachMessages = current.coachMessages.filterNot { message -> message.id == assistantMessageId },
              dailyTraining = DailyTrainingState()
            )
          }
        )
      }
    )
  }

  private fun submitDailyTrainingAnswer(answer: String, fromCoachInput: Boolean = false) {
    val stateSnapshot = _uiState.value
    val training = stateSnapshot.dailyTraining
    if (isDailyTrainingStale(training)) {
      updateUiState(persistSession = true) { current ->
        current.copy(dailyTraining = DailyTrainingState(), toastMessage = "今日训练已过期，请重新开始")
      }
      return
    }

    val round = training.currentRound
    val trainingQuestion = training.currentQuestionText.trim()
    if (round == null || training.phase != DailyTrainingPhase.AWAITING_ANSWER || trainingQuestion.isBlank()) {
      postToast("当前没有可提交答案的训练题")
      return
    }

    val requestConversationToken = conversationToken
    val settings = stateSnapshot.settings
    val roundIndex = training.currentIndex.coerceAtLeast(0)
    val requestPrompt = buildTrainingEvaluationPrompt(round, trainingQuestion, answer)
    val displayUserMessage = ChatMessage.User(
      id = nextMessageId(),
      time = currentTime(),
      text = answer
    )
    val assistantMessageId = nextMessageId()
    val assistantMessageTime = currentTime()
    val requestMessages = listOf(
      ChatMessage.User(
        id = "train-review-$assistantMessageId",
        time = assistantMessageTime,
        text = requestPrompt
      )
    )
    val streamBuffer = StringBuilder()
    val reasoningBuffer = StringBuilder()
    val placeholderText = "正在批改第${roundIndex + 1}题..."
    val assistantPlaceholder = createAssistantMessage(
      content = placeholderText,
      sourceQuestion = trainingQuestion,
      reasoningSummary = null,
      messageId = assistantMessageId,
      messageTime = assistantMessageTime
    )
    val coachUserMessage = CoachChatMessage(
      id = displayUserMessage.id,
      role = CoachMessageRole.USER,
      time = displayUserMessage.time,
      text = answer
    )
    val coachAssistantPlaceholder = CoachChatMessage(
      id = assistantMessageId,
      role = CoachMessageRole.ASSISTANT,
      time = assistantMessageTime,
      text = placeholderText
    )

    val pushPartialAssistant: () -> Unit = {
      val partial = streamBuffer.toString().trimStart()
      val reasoning = reasoningBuffer.toString().trim().ifBlank { null }
      val partialText = partial.ifBlank { placeholderText }
      val partialMessage = createAssistantMessage(
        content = partialText,
        sourceQuestion = trainingQuestion,
        reasoningSummary = reasoning,
        messageId = assistantMessageId,
        messageTime = assistantMessageTime
      )
      val coachPartialMessage = coachAssistantPlaceholder.copy(text = partialText)
      updateUiState(persistSession = false) { current ->
        upsertAssistantMessageState(current, partialMessage).copy(
          coachMessages = upsertCoachMessage(current.coachMessages, coachPartialMessage)
        )
      }
      publishBackgroundProgress(
        preview = partialText,
        status = "正在批改第${roundIndex + 1}题..."
      )
    }

    startRequest(toastMessage = "正在批改第${roundIndex + 1}题...") { current ->
      val queued = current.copy(
        activePage = WorkspacePage.COACH,
        input = if (fromCoachInput) current.input else "",
        coachInput = if (fromCoachInput) "" else current.coachInput,
        coachMessages = current.coachMessages + listOf(coachUserMessage, coachAssistantPlaceholder),
        messages = current.messages + displayUserMessage,
        profile = current.profile.updateWith(text = answer, isFollowup = false, isVoice = false),
        knowledgePoints = mergeKnowledgePoints(current.knowledgePoints, listOf(answer, trainingQuestion)),
        dailyTraining = current.dailyTraining.copy(phase = DailyTrainingPhase.REVIEWING_ANSWER)
      )
      upsertAssistantMessageState(queued, assistantPlaceholder)
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
        val assistantMessage = createAssistantMessage(
          content = resolvedReply,
          sourceQuestion = trainingQuestion,
          reasoningSummary = resolvedReasoning,
          messageId = assistantMessageId,
          messageTime = assistantMessageTime
        )
        val hasNextRound = roundIndex + 1 < training.rounds.size
        val coachAssistantMessage = coachAssistantPlaceholder.copy(text = assistantMessage.fullAnswerText())
        finishRequest { current ->
          val updated = appendAssistantMessageState(
            current = current,
            assistantMessage = assistantMessage,
            toastMessage = if (hasNextRound) {
              "第${roundIndex + 1}题完成，正在进入第${roundIndex + 2}题"
            } else {
              "今日训练完成"
            },
            knowledgeTexts = listOf(resolvedReply)
          )
          updated.copy(
            coachMessages = upsertCoachMessage(updated.coachMessages, coachAssistantMessage),
            dailyTraining = if (hasNextRound) {
              current.dailyTraining.copy(
                phase = DailyTrainingPhase.ASKING_QUESTION,
                currentIndex = roundIndex + 1,
                currentQuestionText = ""
              )
            } else {
              current.dailyTraining.copy(
                phase = DailyTrainingPhase.COMPLETED,
                currentQuestionText = ""
              )
            }
          )
        }
        if (hasNextRound) {
          launchDailyTrainingRound(training.rounds, roundIndex + 1)
        }
      },
      onFailure = { throwable ->
        val partialReply = streamBuffer.toString().trimStart()
        val partialReasoning = reasoningBuffer.toString().trim().ifBlank { null }
        val hasNextRound = roundIndex + 1 < training.rounds.size
        if (partialReply.isNotBlank() || partialReasoning != null) {
          val recoveredMessage = createAssistantMessage(
            content = partialReply.ifBlank { "网络波动，已保留本次批改的已生成内容。" },
            sourceQuestion = trainingQuestion,
            reasoningSummary = partialReasoning,
            messageId = assistantMessageId,
            messageTime = assistantMessageTime
          )
          val coachRecoveredMessage = coachAssistantPlaceholder.copy(text = recoveredMessage.fullAnswerText())
          finishRequest { current ->
            val updated = appendAssistantMessageState(
              current = current,
              assistantMessage = recoveredMessage,
              toastMessage = if (hasNextRound) {
                "第${roundIndex + 1}题批改部分完成，继续进入下一题"
              } else {
                "今日训练完成（已保留本题批改内容）"
              },
              knowledgeTexts = listOf(partialReply.ifBlank { partialReasoning.orEmpty() })
            )
            updated.copy(
              coachMessages = upsertCoachMessage(updated.coachMessages, coachRecoveredMessage),
              dailyTraining = if (hasNextRound) {
                current.dailyTraining.copy(
                  phase = DailyTrainingPhase.ASKING_QUESTION,
                  currentIndex = roundIndex + 1,
                  currentQuestionText = ""
                )
              } else {
                current.dailyTraining.copy(
                  phase = DailyTrainingPhase.COMPLETED,
                  currentQuestionText = ""
                )
              }
            )
          }
          if (hasNextRound) {
            launchDailyTrainingRound(training.rounds, roundIndex + 1)
          }
          return@launchTokenAwareRequest
        }

        handleRequestFailure(
          throwable = throwable,
          onError = { current, errorHint ->
            val rolledBackPlaceholder = rollbackQueuedUserMessageState(current, assistantMessageId)
            rollbackQueuedUserMessageState(
              current = rolledBackPlaceholder,
              messageId = displayUserMessage.id,
              restoredInput = if (fromCoachInput) rolledBackPlaceholder.input else answer,
              toastMessage = "第${roundIndex + 1}题批改失败：$errorHint（答案已保留，可重发）"
            ).copy(
              coachInput = if (fromCoachInput) answer else current.coachInput,
              coachMessages = current.coachMessages.filterNot { message ->
                message.id == assistantMessageId || message.id == displayUserMessage.id
              },
              dailyTraining = current.dailyTraining.copy(
                phase = DailyTrainingPhase.AWAITING_ANSWER,
                currentQuestionText = trainingQuestion
              )
            )
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
      publishBackgroundProgress(
        preview = partial.ifBlank { "正在生成回答..." },
        status = "正在生成回答..."
      )
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
    ).copy(
      mistakeItems = _uiState.value.mistakeItems,
      mistakeRecognitionDrafts = _uiState.value.mistakeRecognitionDrafts
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
    if (inFlightRequests == 1) {
      resetBackgroundProgressTracking()
    }

    var latestStatus: String? = null
    updateUiState(persistSession = true) { current ->
      val base = transform(current)
      val updated = base.copy(
        isLoading = inFlightRequests > 0,
        toastMessage = when {
          toastMessage != null -> toastMessage
          clearToast -> null
          else -> base.toastMessage
        }
      )
      latestStatus = updated.toastMessage
      updated
    }
    syncBackgroundRequestService(status = latestStatus)
  }

  private fun finishRequest(transform: (ChatUiState) -> ChatUiState = { state -> state }) {
    inFlightRequests = (inFlightRequests - 1).coerceAtLeast(0)
    var latestStatus: String? = null
    updateUiState(persistSession = true) { current ->
      val base = transform(current)
      val updated = base.copy(isLoading = inFlightRequests > 0)
      latestStatus = updated.toastMessage
      updated
    }
    if (inFlightRequests == 0) {
      resetBackgroundProgressTracking()
    }
    syncBackgroundRequestService(status = latestStatus)
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

  private fun updateMistakeItems(
    persistMistakes: Boolean,
    transform: (ChatUiState) -> ChatUiState
  ) {
    updateUiState(persistSession = false, transform = transform)
    rescheduleMistakeReviewReminder(_uiState.value.mistakeItems)
    if (persistMistakes) {
      persistMistakeBookAsync(_uiState.value.mistakeItems)
    }
  }

  private fun restoreMistakeBookItems() {
    val store = mistakeBookStorage ?: return
    val restored = refreshMistakeDueStatuses(store.load(), now = System.currentTimeMillis())
    mistakeSeed = deriveMistakeSeed(restored)
    _uiState.update { current ->
      current.copy(mistakeItems = restored)
    }
    rescheduleMistakeReviewReminder(restored)
  }

  private fun rescheduleMistakeReviewReminder(items: List<MistakeBookItem>) {
    val context = appContext ?: return
    MistakeReviewScheduler.reschedule(context, items)
  }

  private fun persistMistakeBookAsync(items: List<MistakeBookItem>) {
    val store = mistakeBookStorage ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val result = store.save(items)
      if (result.isFailure) {
        val message = result.exceptionOrNull()?.message.orEmpty().ifBlank { "未知错误" }
        launch {
          postToast("错题本保存失败：$message")
        }
      }
    }
  }

  private fun saveSavedQuestionImages(saved: SavedQuestion, itemId: String): List<String> {
    val store = mistakeBookStorage ?: return emptyList()
    val images = if (saved.imagePreviewList.isNotEmpty()) {
      saved.imagePreviewList
    } else {
      saved.imagePreviewBytes?.let(::listOf).orEmpty()
    }
    return images.mapIndexedNotNull { index, bytes ->
      store.saveImageBytes(bytes, "$itemId-$index.jpg").getOrNull()
    }
  }

  private fun saveMistakeImages(imageBytesList: List<ByteArray>, idPrefix: String): List<String> {
    val store = mistakeBookStorage ?: return emptyList()
    return imageBytesList.mapIndexedNotNull { index, bytes ->
      store.saveImageBytes(bytes, "$idPrefix-$index.jpg").getOrNull()
    }
  }

  private suspend fun recognizeMistakeAnswerText(imageBytesList: List<ByteArray>): String {
    val normalizedImages = imageBytesList.filter { bytes -> bytes.isNotEmpty() }
    if (normalizedImages.isEmpty()) {
      return ""
    }
    val ocrClient = if (appContext != null) MlKitMistakeOcrClient() else UnavailableMistakeOcrClient
    return ocrClient.recognizeText(normalizedImages).getOrNull().orEmpty().trim()
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

  private fun syncBackgroundRequestService(
    status: String? = _uiState.value.toastMessage,
    preview: String? = null
  ) {
    val context = appContext ?: return
    runCatching {
      if (inFlightRequests > 0) {
        AiGenerationForegroundService.startOrUpdate(
          context = context,
          activeCount = inFlightRequests,
          status = status,
          preview = preview
        )
      } else {
        AiGenerationForegroundService.stop(context)
      }
    }
  }

  private fun publishBackgroundProgress(preview: String, status: String? = null) {
    if (inFlightRequests <= 0) {
      return
    }

    val normalizedPreview = preview.trim()
    if (normalizedPreview.isBlank()) {
      return
    }

    val now = System.currentTimeMillis()
    val previewGrowth = normalizedPreview.length - lastBackgroundProgressPreview.length
    val shouldPushNotification = normalizedPreview != lastBackgroundProgressPreview && (
      now - lastBackgroundProgressPushAt >= BACKGROUND_PROGRESS_PUSH_INTERVAL_MS ||
        previewGrowth >= BACKGROUND_PROGRESS_MIN_CHARS
      )

    if (shouldPushNotification) {
      lastBackgroundProgressPushAt = now
      lastBackgroundProgressPreview = normalizedPreview
      syncBackgroundRequestService(status = status, preview = normalizedPreview)
    }

    if (now - lastInFlightPersistAt >= IN_FLIGHT_PERSIST_INTERVAL_MS) {
      lastInFlightPersistAt = now
      val state = _uiState.value
      syncActiveSessionSnapshot(state)
      persistSessionsAsync()
    }
  }

  private fun resetBackgroundProgressTracking() {
    lastBackgroundProgressPushAt = 0L
    lastBackgroundProgressPreview = ""
    lastInFlightPersistAt = 0L
  }

  private fun restoreSessionsFromStorage() {
    val store = sessionStorage ?: return
    val restored = store.load()
    if (restored == null) {
      sessionRegistry.clear()
      resetConversation()
      return
    }

    val sanitized = sanitizePersistedSessions(restored)

    if (sanitized.sessions.isEmpty()) {
      sessionRegistry.clear()
      _uiState.update { current ->
        current.copy(
          settings = sanitized.settings,
          settingsDraft = sanitized.settings
        )
      }
      resetConversation()
      return
    }

    val preferredSession = sanitized.sessions.firstOrNull { session -> session.id == sanitized.activeSessionId }
      ?: sanitized.sessions.firstOrNull()
      ?: run {
        sessionRegistry.clear()
        resetConversation()
        return
      }

    val shouldPersistCleanedState = sanitized != restored ||
      sanitized.activeSessionId != preferredSession.id ||
      sanitized.sessions.size != 1

    val settings = sanitized.settings
    val sharedAnkiCards = mergeGlobalAnkiCards(sanitized.sessions)
    sessionRegistry.replaceAll(listOf(preferredSession))
    activateSession(
      session = preferredSession,
      ankiCards = sharedAnkiCards,
      settings = settings,
      toastMessage = null,
      persistSession = false
    )
    if (shouldPersistCleanedState) {
      persistSessionsAsync()
    }
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

    val sourceQuestion = sourceUser?.text?.trim().orEmpty().ifBlank {
      assistantMessage.mainSpan?.sourceQuestion?.trim()
        .orEmpty()
        .ifBlank { assistantMessage.spans.firstOrNull()?.sourceQuestion?.trim().orEmpty() }
    }
    if (sourceQuestion.isBlank()) {
      return null
    }

    val answer = assistantMessage.fullAnswerText()
    if (answer.isBlank()) {
      return null
    }

    val imagePreviewList = sourceUser?.let { user ->
      if (user.imagePreviewList.isNotEmpty()) {
        user.imagePreviewList
      } else {
        user.imagePreviewBytes?.let { bytes -> listOf(bytes) }.orEmpty()
      }
    }.orEmpty()
    val resolvedQuestion = if (imagePreviewList.isNotEmpty()) {
      buildFallbackSavedQuestionTitle(sourceQuestion = sourceQuestion, answer = answer)
    } else {
      sourceQuestion
    }
    val historyCount = assistantMessage.interactiveSpans().sumOf { span ->
      state.histories[span.id].orEmpty().size
    }
    val tagged = com.studysuit.aiqa.data.QuestionTagger.autoTag(listOf(resolvedQuestion, answer).joinToString(separator = "\n"))
    val tags = filterToHighSchoolKnowledgeTags(
      inferKnowledgePoints(listOf(resolvedQuestion, answer).joinToString(separator = "\n")),
      maxSize = 6
    )

    return SavedQuestion(
      id = "saved-${System.currentTimeMillis()}-${messageId}",
      sourceMessageId = assistantMessage.id,
      question = resolvedQuestion,
      answer = answer,
      sourceTime = assistantMessage.time,
      savedAt = System.currentTimeMillis(),
      followupCount = historyCount,
      knowledgeTags = tags,
      subject = tagged.subject.takeUnless { subject -> subject == "其他" }.orEmpty(),
      questionType = tagged.questionType.takeUnless { type -> type == "其他" }.orEmpty(),
      imagePreviewBytes = imagePreviewList.firstOrNull(),
      imagePreviewList = imagePreviewList
    )
  }

  private fun mergeSavedQuestionSnapshots(
    existing: SavedQuestion,
    refreshed: SavedQuestion
  ): SavedQuestion {
    val refreshedQuestion = refreshed.question.trim()
    val existingQuestion = existing.question.trim()
    val mergedQuestion = when {
      refreshedQuestion.isBlank() -> existingQuestion
      isGenericImageSearchQuestion(refreshedQuestion) && existingQuestion.isNotBlank() -> existingQuestion
      refreshedQuestion == "图片题目归档" && existingQuestion.isNotBlank() -> existingQuestion
      else -> refreshedQuestion
    }
    val previewList = if (refreshed.imagePreviewList.isNotEmpty()) {
      refreshed.imagePreviewList
    } else if (existing.imagePreviewList.isNotEmpty()) {
      existing.imagePreviewList
    } else {
      refreshed.imagePreviewBytes?.let { bytes -> listOf(bytes) }
        ?: existing.imagePreviewBytes?.let { bytes -> listOf(bytes) }
        ?: emptyList()
    }

    return refreshed.copy(
      id = existing.id,
      question = mergedQuestion.ifBlank { existingQuestion },
      savedAt = existing.savedAt,
      knowledgeTags = if (refreshed.knowledgeTags.isNotEmpty()) refreshed.knowledgeTags else existing.knowledgeTags,
      subject = refreshed.subject.ifBlank { existing.subject },
      questionType = refreshed.questionType.ifBlank { existing.questionType },
      analysisSummary = refreshed.analysisSummary.ifBlank { existing.analysisSummary },
      imagePreviewBytes = previewList.firstOrNull(),
      imagePreviewList = previewList
    )
  }

  private fun upsertSavedQuestionState(
    current: ChatUiState,
    saved: SavedQuestion,
    focusSavedQuestionId: String? = current.archiveFocusSavedQuestionId,
    toastMessage: String? = current.toastMessage
  ): ChatUiState {
    val existing = current.savedQuestions.firstOrNull { question -> question.sourceMessageId == saved.sourceMessageId }
    val merged = existing?.let { previous ->
      mergeSavedQuestionSnapshots(previous, saved.copy(id = previous.id, savedAt = previous.savedAt))
    } ?: saved
    val updated = listOf(merged) + current.savedQuestions.filterNot { question -> question.sourceMessageId == saved.sourceMessageId }
    return current.copy(
      savedQuestions = updated,
      archiveFocusSavedQuestionId = focusSavedQuestionId,
      toastMessage = toastMessage
    )
  }

  private fun syncSavedQuestionSnapshot(
    current: ChatUiState,
    assistantMessage: ChatMessage.Assistant
  ): ChatUiState {
    val existing = current.savedQuestions.firstOrNull { saved -> saved.sourceMessageId == assistantMessage.id } ?: return current
    val refreshed = buildSavedQuestionSnapshot(current, assistantMessage.id) ?: return current
    return current.copy(
      savedQuestions = current.savedQuestions.map { saved ->
        if (saved.sourceMessageId == assistantMessage.id) mergeSavedQuestionSnapshots(existing, refreshed) else saved
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
    val effectiveTags = filterToHighSchoolKnowledgeTags(tags, maxSize = 10)

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

  private fun buildMistakeAnkiFront(item: MistakeBookItem): String {
    return buildString {
      append("错题复习\n\n")
      append(item.question)
      val labels = buildMistakeAnkiTags(item).take(3)
      if (labels.isNotEmpty()) {
        append("\n\n标签：")
        append(labels.joinToString(separator = " / "))
      }
    }
  }

  private fun buildMistakeAnkiBack(item: MistakeBookItem): String {
    return buildString {
      append("正确答案：")
      append(item.correctAnswer)
      if (item.studentAnswer.isNotBlank()) {
        append("\n\n原作答：")
        append(item.studentAnswer)
      }
      if (item.explanation.isNotBlank()) {
        append("\n\n解析：")
        append(item.explanation)
      }
      if (item.mistakeReason.isNotBlank()) {
        append("\n\n错因：")
        append(item.mistakeReason)
      }
      item.mistakeType?.let { type ->
        append("\n\n错误类型：")
        append(mistakeTypeLabel(type))
      }
    }
  }

  private fun buildMistakeAnkiTags(item: MistakeBookItem): List<String> {
    return buildList {
      addAll(item.knowledgeTags)
      if (item.subject.isNotBlank()) {
        add(item.subject)
      }
      if (item.questionType.isNotBlank()) {
        add(item.questionType)
      }
      item.mistakeType?.let { type -> add(mistakeTypeLabel(type)) }
    }
  }

  private fun MistakeAnswerJudgement.toMistakeReviewSuggestion(
    itemId: String,
    userAnswer: String,
    answerOcrText: String
  ): MistakeReviewSuggestion {
    return MistakeReviewSuggestion(
      itemId = itemId,
      userAnswer = userAnswer,
      isCorrect = isCorrect,
      confidence = confidence,
      reason = reason,
      suggestedScore = suggestedScore,
      answerOcrText = answerOcrText
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
    resetBackgroundProgressTracking()
    syncBackgroundRequestService()
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

  override fun onCleared() {
    resetRequestTracking()
    super.onCleared()
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

  private fun nextMistakeItemId(): String {
    mistakeSeed += 1
    return "mistake-$mistakeSeed"
  }

  private fun deriveCardSeed(cards: List<AnkiCard>): Int {
    return cards
      .mapNotNull { card -> card.id.removePrefix("card-").toIntOrNull() }
      .maxOrNull() ?: 0
  }

  private fun deriveMistakeSeed(items: List<MistakeBookItem>): Int {
    return items
      .mapNotNull { item -> item.id.removePrefix("mistake-").toIntOrNull() }
      .maxOrNull() ?: 0
  }

  private fun refreshMistakeDueStatuses(items: List<MistakeBookItem>, now: Long): List<MistakeBookItem> {
    val dueIds = MistakeSrsEngine.dueMistakes(items, now = now).map { item -> item.id }.toSet()
    return sortMistakeItems(
      items.map { item ->
        if (item.id in dueIds) {
          item.copy(status = com.studysuit.aiqa.data.MistakeStatus.DUE)
        } else {
          item
        }
      }
    )
  }

  private fun mistakeRecognitionToast(draft: MistakeRecognitionDraft): String {
    return when (draft.status) {
      MistakeRecognitionStatus.AI_READY -> "已识别错题，可确认加入复习"
      MistakeRecognitionStatus.OCR_READY -> "模型暂不可用，已保留 OCR 草稿"
      MistakeRecognitionStatus.FAILED -> "识别失败，请手动补全错题"
      MistakeRecognitionStatus.MANUAL -> "已保存手动草稿"
      MistakeRecognitionStatus.PENDING -> "错题草稿待处理"
    }
  }

  private fun currentTime(): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.SIMPLIFIED_CHINESE)
    return formatter.format(Date())
  }

  private fun currentDateTime(): String {
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
    return formatter.format(Date())
  }

  private companion object {
    private const val BACKGROUND_PROGRESS_PUSH_INTERVAL_MS = 1200L
    private const val BACKGROUND_PROGRESS_MIN_CHARS = 48
    private const val IN_FLIGHT_PERSIST_INTERVAL_MS = 3000L
  }
}
