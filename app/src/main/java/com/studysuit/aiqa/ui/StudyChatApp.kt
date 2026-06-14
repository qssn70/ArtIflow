package com.studysuit.aiqa.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studysuit.aiqa.data.PcmWavRecorder
import kotlinx.coroutines.launch

private data class ComposerImageDraft(
  val id: String,
  val source: String,
  val bytes: ByteArray
)

private data class FollowupTreeExportDraft(
  val fileName: String,
  val content: String
)

private data class PendingMistakeAnswerJudgement(
  val itemId: String,
  val userAnswer: String
)

private enum class ImageInputTarget {
  CHAT,
  MISTAKE,
  MISTAKE_ANSWER
}

private fun writeFollowupTreeExportToUri(context: Context, uri: Uri, content: String): Boolean {
  return runCatching {
    context.contentResolver.openOutputStream(uri)?.use { stream ->
      stream.write(content.toByteArray(Charsets.UTF_8))
    } != null
  }.getOrDefault(false)
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun StudyChatApp(
  viewModel: StudyChatViewModel = viewModel(),
  mistakeReviewOpenRequest: Int = 0
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val appScope = rememberCoroutineScope()
  val context = LocalContext.current
  val wavRecorder = remember(context) { PcmWavRecorder(context.cacheDir) }
  var activeVoiceSpanId by remember { mutableStateOf<String?>(null) }
  var activeVoiceSession by remember { mutableStateOf<PcmWavRecorder.Session?>(null) }
  var pendingPermissionSpanId by remember { mutableStateOf<String?>(null) }
  var pendingCameraTarget by remember { mutableStateOf<ImageInputTarget?>(null) }
  var pendingCameraImageUri by remember { mutableStateOf<Uri?>(null) }
  var pendingMistakeAnswerJudgement by remember { mutableStateOf<PendingMistakeAnswerJudgement?>(null) }
  var isDeckArchiveOpen by remember { mutableStateOf(false) }
  var isFollowupTreeOpen by remember { mutableStateOf(false) }
  var lastBackPressedAt by remember { mutableLongStateOf(0L) }
  var pendingImageDrafts by remember { mutableStateOf<List<ComposerImageDraft>>(emptyList()) }
  var pendingFollowupTreeExport by remember { mutableStateOf<FollowupTreeExportDraft?>(null) }
  var isWorkspaceJumpOpen by remember { mutableStateOf(false) }
  val knowledgeGapInsights = remember(uiState.messages, uiState.histories, uiState.knowledgePoints) {
    buildKnowledgeGapInsights(
      messages = uiState.messages,
      histories = uiState.histories,
      knowledgePoints = uiState.knowledgePoints
    )
  }
  val savedQuestionMessageIds = remember(uiState.savedQuestions) {
    uiState.savedQuestions.map { saved -> saved.sourceMessageId }.toSet()
  }

  val appendPendingImage: (String, ByteArray) -> Unit = { source, imageBytes ->
    if (imageBytes.isEmpty()) {
      viewModel.showImageReadFailed("图片读取失败")
    } else if (pendingImageDrafts.size >= 6) {
      Toast.makeText(context, "最多添加 6 张图片", Toast.LENGTH_SHORT).show()
    } else {
      val draft = ComposerImageDraft(
        id = "draft-${System.currentTimeMillis()}-${pendingImageDrafts.size}",
        source = source,
        bytes = imageBytes
      )
      pendingImageDrafts = pendingImageDrafts + draft
      Toast.makeText(context, "已加入待上传（${pendingImageDrafts.size}）", Toast.LENGTH_SHORT).show()
    }
  }

  val recognizeMistakeImages: (String, List<ByteArray>) -> Unit = { source, images ->
    val validImages = images.filter { bytes -> bytes.isNotEmpty() }
    if (validImages.isEmpty()) {
      viewModel.showImageReadFailed("图片读取失败")
    } else {
      viewModel.recognizeMistakeFromImages(
        imageBytesList = validImages,
        source = source
      )
    }
  }

  val judgeMistakeAnswerImages: (List<ByteArray>) -> Unit = { images ->
    val pending = pendingMistakeAnswerJudgement
    pendingMistakeAnswerJudgement = null
    val validImages = images.filter { bytes -> bytes.isNotEmpty() }
    when {
      pending == null -> viewModel.showImageReadFailed("未找到待判题错题")
      validImages.isEmpty() -> viewModel.showImageReadFailed("图片读取失败")
      else -> viewModel.requestMistakeAnswerJudgement(
        itemId = pending.itemId,
        userAnswer = pending.userAnswer,
        answerImageBytesList = validImages
      )
    }
  }

  val cameraImageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture()
  ) { captured ->
    val captureUri = pendingCameraImageUri
    val target = pendingCameraTarget ?: ImageInputTarget.CHAT
    pendingCameraImageUri = null
    pendingCameraTarget = null
    if (!captured || captureUri == null) {
      if (target == ImageInputTarget.MISTAKE_ANSWER) {
        pendingMistakeAnswerJudgement = null
      }
      viewModel.showImageSearchCanceled()
    } else {
      val imageBytes = runCatching { readImageBytes(context, captureUri) }
        .onFailure { deleteCapturedImage(context, captureUri) }
        .getOrNull()
      deleteCapturedImage(context, captureUri)
      if (imageBytes == null || imageBytes.isEmpty()) {
        if (target == ImageInputTarget.MISTAKE_ANSWER) {
          pendingMistakeAnswerJudgement = null
        }
        viewModel.showImageReadFailed("拍照结果为空")
      } else {
        when (target) {
          ImageInputTarget.CHAT -> appendPendingImage("拍照搜题", imageBytes)
          ImageInputTarget.MISTAKE -> recognizeMistakeImages("拍照录入错题", listOf(imageBytes))
          ImageInputTarget.MISTAKE_ANSWER -> judgeMistakeAnswerImages(listOf(imageBytes))
        }
      }
    }
  }

  fun launchFullQualityCamera(target: ImageInputTarget) {
    val captureUri = runCatching { createCameraCaptureUri(context) }.getOrNull()
    if (captureUri == null) {
      pendingCameraTarget = null
      viewModel.showImageReadFailed("无法创建拍照文件")
    } else {
      pendingCameraTarget = target
      pendingCameraImageUri = captureUri
      cameraImageLauncher.launch(captureUri)
    }
  }

  val galleryImageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri ->
    if (uri == null) {
      viewModel.showImageSearchCanceled()
    } else {
      val imageBytes = runCatching { readImageBytes(context, uri) }.getOrNull()
      if (imageBytes == null || imageBytes.isEmpty()) {
        viewModel.showImageReadFailed("相册图片读取失败")
      } else {
        appendPendingImage("相册搜题", imageBytes)
      }
    }
  }

  val mistakeGalleryImageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(6)
  ) { uris ->
    if (uris.isEmpty()) {
      viewModel.showImageSearchCanceled()
    } else {
      val imageBytesList = uris.mapNotNull { uri ->
        runCatching { readImageBytes(context, uri) }.getOrNull()?.takeIf { bytes -> bytes.isNotEmpty() }
      }
      recognizeMistakeImages("相册录入错题", imageBytesList)
    }
  }

  val mistakeAnswerGalleryImageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(3)
  ) { uris ->
    if (uris.isEmpty()) {
      pendingMistakeAnswerJudgement = null
      viewModel.showImageSearchCanceled()
    } else {
      val imageBytesList = uris.mapNotNull { uri ->
        runCatching { readImageBytes(context, uri) }.getOrNull()?.takeIf { bytes -> bytes.isNotEmpty() }
      }
      judgeMistakeAnswerImages(imageBytesList)
    }
  }

  val followupTreeExportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/markdown")
  ) { uri ->
    val exportDraft = pendingFollowupTreeExport
    pendingFollowupTreeExport = null
    if (exportDraft == null) {
      return@rememberLauncherForActivityResult
    }

    if (uri == null) {
      Toast.makeText(context, "已取消导出", Toast.LENGTH_SHORT).show()
      return@rememberLauncherForActivityResult
    }

    val exported = writeFollowupTreeExportToUri(context, uri, exportDraft.content)
    if (exported) {
      Toast.makeText(context, "图谱已导出为文件", Toast.LENGTH_SHORT).show()
    } else {
      Toast.makeText(context, "导出失败，请重试", Toast.LENGTH_SHORT).show()
    }
  }

  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    val target = pendingCameraTarget ?: return@rememberLauncherForActivityResult
    if (!granted) {
      pendingCameraTarget = null
      if (target == ImageInputTarget.MISTAKE_ANSWER) {
        pendingMistakeAnswerJudgement = null
      }
      viewModel.showCameraPermissionDenied()
      return@rememberLauncherForActivityResult
    }

    launchFullQualityCamera(target)
  }

  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) {
      viewModel.refreshMistakeReviewReminder()
    } else {
      Toast.makeText(context, "未开启通知权限，错题仍会保存在复习队列", Toast.LENGTH_SHORT).show()
    }
  }

  fun requestCameraCapture(target: ImageInputTarget) {
    if (activeVoiceSession != null) {
      viewModel.showRecordingBusy()
    } else {
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
      ) == PackageManager.PERMISSION_GRANTED

      if (granted) {
        launchFullQualityCamera(target)
      } else {
        pendingCameraTarget = target
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
      }
    }
  }

  val onCameraQuestionSearch: () -> Unit = {
    requestCameraCapture(ImageInputTarget.CHAT)
  }

  val onGalleryQuestionSearch: () -> Unit = {
    if (activeVoiceSession != null) {
      viewModel.showRecordingBusy()
    } else {
      galleryImageLauncher.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
      )
    }
  }

  val onCameraMistakeCapture: () -> Unit = {
    requestCameraCapture(ImageInputTarget.MISTAKE)
  }

  val onGalleryMistakePick: () -> Unit = {
    if (activeVoiceSession != null) {
      viewModel.showRecordingBusy()
    } else {
      mistakeGalleryImageLauncher.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
      )
    }
  }

  val onCameraMistakeAnswerCapture: (String, String) -> Unit = { itemId, userAnswer ->
    if (activeVoiceSession != null) {
      viewModel.showRecordingBusy()
    } else {
      pendingMistakeAnswerJudgement = PendingMistakeAnswerJudgement(itemId = itemId, userAnswer = userAnswer)
      requestCameraCapture(ImageInputTarget.MISTAKE_ANSWER)
    }
  }

  val onGalleryMistakeAnswerPick: (String, String) -> Unit = { itemId, userAnswer ->
    if (activeVoiceSession != null) {
      viewModel.showRecordingBusy()
    } else {
      pendingMistakeAnswerJudgement = PendingMistakeAnswerJudgement(itemId = itemId, userAnswer = userAnswer)
      mistakeAnswerGalleryImageLauncher.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
      )
    }
  }

  val onRefreshMistakeReminder: () -> Unit = {
    val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
      ) != PackageManager.PERMISSION_GRANTED
    if (needsNotificationPermission) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      viewModel.refreshMistakeReviewReminder()
    }
  }

  LaunchedEffect(Unit) {
    viewModel.ensureStorage(context)
  }

  LaunchedEffect(mistakeReviewOpenRequest) {
    if (mistakeReviewOpenRequest > 0) {
      viewModel.ensureStorage(context)
      viewModel.openDueMistakeReviewQueue()
    }
  }

  LaunchedEffect(uiState.toastMessage) {
    val message = uiState.toastMessage ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(message)
    viewModel.consumeToast()
  }

  val quickFollowupSourceMessage = remember(uiState.messages, uiState.quickFollowupSourceMessageId) {
    findAssistantMessageById(uiState.messages, uiState.quickFollowupSourceMessageId)
  }
  val questionWorkspaceReplySpanId = remember(quickFollowupSourceMessage) {
    quickFollowupSourceMessage?.mainSpan?.id ?: quickFollowupSourceMessage?.spans?.lastOrNull()?.id
  }
  val quickFollowupSpan = remember(
    uiState.messages,
    uiState.quickFollowupSpanId,
    questionWorkspaceReplySpanId
  ) {
    findSpanById(uiState.messages, uiState.quickFollowupSpanId)
      ?: questionWorkspaceReplySpanId?.let { spanId -> findSpanById(uiState.messages, spanId) }
      ?: findLatestAssistantQuestionSpan(uiState.messages)
  }
  val quickFollowupHistory = remember(uiState.histories, quickFollowupSpan) {
    if (quickFollowupSpan == null) {
      emptyList()
    } else {
      uiState.histories[quickFollowupSpan.id].orEmpty()
    }
  }
  val quickFollowupHistoryForTree = remember(quickFollowupHistory) {
    normalizeHistoryForTree(quickFollowupHistory)
  }
  val isQuestionWorkspaceLayout = remember(
    quickFollowupSourceMessage,
    quickFollowupSpan,
    questionWorkspaceReplySpanId,
    uiState.quickFollowupDetailId
  ) {
    quickFollowupSourceMessage != null &&
      uiState.quickFollowupDetailId == null &&
      quickFollowupSpan?.id == questionWorkspaceReplySpanId
  }
  val quickFollowupSourceUser = remember(
    uiState.messages,
    quickFollowupSpan,
    quickFollowupSourceMessage,
    isQuestionWorkspaceLayout
  ) {
    if (isQuestionWorkspaceLayout) {
      findSourceUserMessageForAssistant(uiState.messages, quickFollowupSourceMessage)
    } else {
      findSourceUserMessageForSpan(uiState.messages, quickFollowupSpan)
    }
  }
  val quickFollowupHistoriesForUi = remember(
    isQuestionWorkspaceLayout,
    quickFollowupSourceMessage,
    uiState.histories,
    quickFollowupSpan,
    quickFollowupHistoryForTree
  ) {
    if (isQuestionWorkspaceLayout) {
      buildQuestionWorkspaceHistories(quickFollowupSourceMessage, uiState.histories)
    } else {
      buildQuickFollowupHistories(quickFollowupSpan, quickFollowupHistoryForTree)
    }
  }
  val rawFollowupTreeScopes = remember(uiState.messages, uiState.histories) {
    buildFollowupTreeScopes(uiState.messages, uiState.histories)
  }
  val followupTreeScopes = remember(
    rawFollowupTreeScopes,
    uiState.activePage,
    quickFollowupSpan,
    quickFollowupSourceMessage
  ) {
    filterFollowupTreeScopesForWorkspace(
      scopes = rawFollowupTreeScopes,
      activePage = uiState.activePage,
      activeSpanId = quickFollowupSpan?.id,
      questionSpanIds = quickFollowupSourceMessage
        ?.interactiveSpans()
        ?.map { span -> span.id }
        ?.toSet()
        .orEmpty()
    )
  }
  val triggerFollowupTreeExport: () -> Unit = {
    if (followupTreeScopes.isEmpty()) {
      Toast.makeText(context, "暂无可导出的图谱", Toast.LENGTH_SHORT).show()
    } else {
      val exportDraft = FollowupTreeExportDraft(
        fileName = buildFollowupTreeExportFileName(),
        content = buildFollowupTreeExportMarkdown(followupTreeScopes)
      )
      pendingFollowupTreeExport = exportDraft
      runCatching {
        followupTreeExportLauncher.launch(exportDraft.fileName)
      }.onFailure {
        pendingFollowupTreeExport = null
        Toast.makeText(context, "导出入口不可用", Toast.LENGTH_SHORT).show()
      }
    }
  }
  val quickFollowupMessages = remember(
    isQuestionWorkspaceLayout,
    quickFollowupSourceMessage,
    quickFollowupSpan,
    quickFollowupHistoryForTree,
    uiState.quickFollowupDetailId,
    quickFollowupSourceUser
  ) {
    if (isQuestionWorkspaceLayout) {
      buildQuestionWorkspaceMessages(
        assistantMessage = quickFollowupSourceMessage,
        sourceUserMessage = quickFollowupSourceUser
      )
    } else {
      buildQuickFollowupMessages(
        span = quickFollowupSpan,
        history = quickFollowupHistoryForTree,
        activeDetailId = uiState.quickFollowupDetailId,
        sourceUserMessage = quickFollowupSourceUser
      )
    }
  }
  val selectedSpan = remember(uiState.messages, quickFollowupMessages, uiState.selectedSpanId) {
    findSpanById(uiState.messages, uiState.selectedSpanId)
      ?: findSpanById(quickFollowupMessages, uiState.selectedSpanId)
  }

  val selectedHistory = remember(
    uiState.histories,
    quickFollowupHistoriesForUi,
    selectedSpan,
    uiState.selectedDetailId
  ) {
    if (selectedSpan == null) {
      emptyList()
    } else {
      val allHistory = uiState.histories[selectedSpan.id].orEmpty().ifEmpty {
        quickFollowupHistoriesForUi[selectedSpan.id].orEmpty()
      }

      val selectedDetailId = uiState.selectedDetailId
      if (selectedDetailId.isNullOrBlank()) {
        allHistory
      } else {
        allHistory.filter { detail -> detail.id == selectedDetailId }
      }
    }
  }
  val selectedSpanForDialog = remember(selectedSpan, selectedHistory, uiState.selectedDetailId) {
    if (selectedSpan == null) {
      null
    } else {
      val selectedDetailId = uiState.selectedDetailId
      val detail = if (selectedDetailId.isNullOrBlank()) {
        null
      } else {
        selectedHistory.firstOrNull { item -> item.id == selectedDetailId }
      }

      if (detail == null) {
        selectedSpan
      } else {
        selectedSpan.copy(content = detail.answer)
      }
    }
  }
  val ankiDeckSummaries = remember(uiState.ankiCards) {
    buildAnkiDeckSummaries(uiState.ankiCards)
  }
  val dueCardsToday = remember(uiState.ankiCards) {
    dueReviewCards(uiState.ankiCards)
  }
  val chatListState = rememberLazyListState()
  val quickFollowupListState = rememberLazyListState()
  val coachListState = rememberLazyListState()
  val workspaceListState = rememberLazyListState()
  val visibleAnkiCards = remember(uiState.ankiCards, uiState.isDueReviewMode, uiState.focusedDeckName) {
    when {
      uiState.isDueReviewMode -> dueReviewCards(uiState.ankiCards)
      uiState.focusedDeckName != null -> uiState.ankiCards.filter { card ->
        (normalizeDeckName(card.deckName) ?: DEFAULT_ANKI_DECK_NAME) == uiState.focusedDeckName
      }
      else -> uiState.ankiCards
    }
  }
  val deckPracticeSummary = remember(uiState.focusedDeckName, uiState.ankiCards, uiState.deckPracticeSelections) {
    val deckName = uiState.focusedDeckName ?: return@remember null
    val deckCards = uiState.ankiCards.filter { card ->
      (normalizeDeckName(card.deckName) ?: DEFAULT_ANKI_DECK_NAME) == deckName
    }
    buildDeckPracticeSummary(deckName, deckCards, uiState.deckPracticeSelections)
  }

  val hasModalDialog = uiState.isSettingsOpen || selectedSpan != null || isFollowupTreeOpen || isWorkspaceJumpOpen
  val isQuestionPage = uiState.activePage == WorkspacePage.CHAT ||
    uiState.activePage == WorkspacePage.QUICK_FOLLOWUP
  val isCoachPage = uiState.activePage == WorkspacePage.COACH
  val activeCoachTraining = remember(uiState.dailyTraining) {
    uiState.dailyTraining.takeIf { training ->
      training.dateKey == currentCoachDateKey() && training.isActive
    }
  }
  val coachComposerPlaceholder = remember(activeCoachTraining) {
    when (activeCoachTraining?.phase) {
      DailyTrainingPhase.AWAITING_ANSWER -> "直接写这题答案，发出后自动批改"
      DailyTrainingPhase.ASKING_QUESTION -> "正在出题，等题目出来后再作答"
      DailyTrainingPhase.REVIEWING_ANSWER -> "正在批改，稍等一下"
      else -> "问教练：我到底漏了哪个知识点？"
    }
  }
  val coachSendButtonText = remember(activeCoachTraining) {
    when (activeCoachTraining?.phase) {
      DailyTrainingPhase.AWAITING_ANSWER -> "交答案"
      DailyTrainingPhase.ASKING_QUESTION,
      DailyTrainingPhase.REVIEWING_ANSWER -> "稍等"
      else -> "发送"
    }
  }
  val coachSendEnabled = remember(uiState.coachInput, activeCoachTraining) {
    when (activeCoachTraining?.phase) {
      DailyTrainingPhase.AWAITING_ANSWER -> uiState.coachInput.isNotBlank()
      DailyTrainingPhase.ASKING_QUESTION,
      DailyTrainingPhase.REVIEWING_ANSWER -> false
      else -> uiState.coachInput.isNotBlank()
    }
  }
  val latestCoachAssistantId = remember(uiState.coachMessages, uiState.isLoading) {
    if (uiState.isLoading) {
      null
    } else {
      uiState.coachMessages
        .asReversed()
        .firstOrNull { message -> message.role == CoachMessageRole.ASSISTANT }
        ?.id
    }
  }
  val coachTurns = remember(uiState.coachMessages) {
    buildCoachConversationTurns(uiState.coachMessages)
  }

  LaunchedEffect(uiState.activePage, uiState.messages.size, uiState.isLoading) {
    if (uiState.activePage != WorkspacePage.CHAT) {
      return@LaunchedEffect
    }

    val totalItems = uiState.messages.size + if (uiState.isLoading) 1 else 0
    if (totalItems > 0) {
      chatListState.scrollToItem(totalItems - 1)
    }
  }

  LaunchedEffect(uiState.activePage) {
    if (uiState.activePage == WorkspacePage.COACH) {
      viewModel.onCoachPageViewed()
    }
  }

  LaunchedEffect(uiState.activePage, uiState.coachMessages.size, uiState.coachDigest?.dateKey, uiState.isLoading) {
    if (uiState.activePage != WorkspacePage.COACH) {
      return@LaunchedEffect
    }

    val totalItems = 1 + uiState.coachMessages.size
    if (totalItems > 0) {
      coachListState.scrollToItem(0)
    }
  }
  BackHandler(enabled = !hasModalDialog) {
    when {
      isDeckArchiveOpen -> {
        isDeckArchiveOpen = false
      }

      isWorkspaceJumpOpen -> {
        isWorkspaceJumpOpen = false
      }

      uiState.activePage == WorkspacePage.QUICK_FOLLOWUP && viewModel.stepBackQuickFollowupLayer() -> {
        Unit
      }

      uiState.activePage != WorkspacePage.CHAT -> {
        viewModel.switchWorkspacePage(WorkspacePage.CHAT)
      }

      else -> {
        val now = System.currentTimeMillis()
        if (now - lastBackPressedAt <= 1500L) {
          (context as? Activity)?.finish()
        } else {
          lastBackPressedAt = now
          Toast.makeText(context, "再按一次退出应用", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  val beginVoiceCapture: (String) -> Unit = { spanId ->
    if (activeVoiceSession != null) {
      viewModel.showRecordingBusy()
    } else {
      val startResult = wavRecorder.start()
      startResult.onSuccess { session ->
        activeVoiceSpanId = spanId
        activeVoiceSession = session
        viewModel.showRecordingHint()
      }.onFailure { throwable ->
        val message = throwable.message?.take(80).orEmpty().ifBlank { "初始化失败" }
        viewModel.showRecordingStartFailed(message)
      }
    }
  }

  val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    val spanId = pendingPermissionSpanId
    pendingPermissionSpanId = null
    if (spanId == null) {
      return@rememberLauncherForActivityResult
    }

    if (granted) {
      beginVoiceCapture(spanId)
    } else {
      viewModel.showMicrophonePermissionDenied()
    }
  }

  val onVoiceCaptureStart: (String) -> Unit = { spanId ->
    val granted = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    if (granted) {
      beginVoiceCapture(spanId)
    } else {
      pendingPermissionSpanId = spanId
      recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  val onVoiceCaptureCancel: (String) -> Unit = { spanId ->
    if (pendingPermissionSpanId == spanId) {
      pendingPermissionSpanId = null
    }

    if (activeVoiceSpanId == spanId) {
      val session = activeVoiceSession
      activeVoiceSpanId = null
      activeVoiceSession = null
      if (session != null) {
        appScope.launch {
          runCatching {
            session.stop(discard = true)
          }
        }
      }
    }

    viewModel.showRecordingCanceled()
  }

  val onVoiceCaptureSubmit: (String) -> Unit = { spanId ->
    when {
      pendingPermissionSpanId == spanId -> Unit
      activeVoiceSpanId == spanId && activeVoiceSession != null -> {
        val session = activeVoiceSession
        activeVoiceSpanId = null
        activeVoiceSession = null

        if (session == null) {
          viewModel.showVoiceCaptureInvalid("未处于录音状态")
        } else {
          appScope.launch {
            val audioResult = runCatching {
              session.stop(discard = false)
            }.getOrElse { throwable ->
              Result.failure(throwable)
            }

            audioResult.onSuccess { audioBytes ->
              viewModel.submitVoiceFollowupAudio(spanId = spanId, audioBytes = audioBytes)
            }.onFailure { throwable ->
              val message = throwable.message?.take(80).orEmpty().ifBlank { "未采集到有效录音" }
              viewModel.showVoiceCaptureInvalid(message)
            }
          }
        }
      }

      else -> {
        viewModel.showVoiceCaptureInvalid("未处于录音状态")
      }
    }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
  ) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          brush = Brush.linearGradient(
            colors = listOf(
              Color(0xFFF6FAF3),
              Color(0xFFFDF4E8),
              Color(0xFFF3F8F1)
            )
          )
        )
        .padding(paddingValues)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        AnimatedContent(
          targetState = uiState.activePage,
          transitionSpec = { workspacePageTransition() },
          label = "workspace_header"
        ) { page ->
          when (page) {
            WorkspacePage.CHAT,
            WorkspacePage.QUICK_FOLLOWUP -> {
              HeaderBar(
                onOpenSettings = viewModel::openSettings,
                onOpenFollowupTree = { isFollowupTreeOpen = true },
                title = if (page == WorkspacePage.QUICK_FOLLOWUP) {
                  "StudySuit · 精细追问"
                } else {
                  "StudySuit · AI学习问答"
                },
                subtitle = if (page == WorkspacePage.QUICK_FOLLOWUP) {
                  "主界面同款 · 仅当前追问链路"
                } else {
                  "整题卡/段落卡都可滑动 · 题目之间上下文独立"
                }
              )
            }

            WorkspacePage.ARCHIVE -> {
              ArchiveHeaderBar(
                savedCount = uiState.savedQuestions.size,
                onOpenSettings = viewModel::openSettings
              )
            }

            WorkspacePage.MISTAKES -> {
              HeaderBar(
                onOpenSettings = viewModel::openSettings,
                onOpenFollowupTree = { isWorkspaceJumpOpen = true },
                title = "StudySuit · 错题本",
                subtitle = "拍照录入、按记忆曲线复习、记录做对做错"
              )
            }

            WorkspacePage.ANKI -> {
              AnkiHeaderBar(
                cardCount = visibleAnkiCards.size,
                deckCount = ankiDeckSummaries.size,
                isDueReviewMode = uiState.isDueReviewMode,
                focusedDeckName = uiState.focusedDeckName,
                dueReviewCount = dueCardsToday.size,
                onOpenDueReview = viewModel::openDueReviewQueue,
                onExitDueReviewMode = viewModel::closeDueReviewMode,
                onExitDeckFocusedPractice = viewModel::closeDeckFocusedPractice,
                onOpenDeckPracticeSummary = viewModel::openDeckPracticeSummary,
                onOpenDeckManager = { isDeckArchiveOpen = true }
              )
            }

            WorkspacePage.COACH -> {
              CoachHeaderBar(onOpenSettings = viewModel::openSettings)
            }

            WorkspacePage.PROFILE -> {
              ProfileHeaderBar(onOpenSettings = viewModel::openSettings)
            }
          }
        }

        AnimatedContent(
          targetState = uiState.activePage,
          transitionSpec = { workspacePageTransition() },
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
          label = "workspace_body"
        ) { page ->
          LazyColumn(
            state = when (page) {
              WorkspacePage.CHAT -> chatListState
              WorkspacePage.QUICK_FOLLOWUP -> quickFollowupListState
              WorkspacePage.COACH -> coachListState
              else -> workspaceListState
            },
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            if (page == WorkspacePage.CHAT) {
              items(uiState.messages, key = { it.id }) { message ->
                when (message) {
                  is ChatMessage.User -> UserBubble(message)
                  is ChatMessage.Assistant -> AssistantBubble(
                    message = message,
                    histories = uiState.histories,
                    processingSpanIds = uiState.processingSpanIds,
                    onAutoExplain = viewModel::autoExplain,
                    onWholeReplyFollowup = { spanId, detailId -> viewModel.openQuickFollowup(spanId, detailId) },
                    onRefreshReply = viewModel::refreshAssistantReply,
                    onToggleSavedQuestion = viewModel::toggleSavedQuestion,
                    refreshEnabled = !uiState.isLoading,
                    showSaveAction = true,
                    isSavedQuestion = message.id in savedQuestionMessageIds,
                    onVoiceFollowup = onVoiceCaptureSubmit,
                    onRightSwipeOpenDetails = { spanId, detailId -> viewModel.openDetails(spanId, detailId) },
                    onRightSwipeHoldFollowup = { spanId, detailId -> viewModel.openQuickFollowup(spanId, detailId) },
                    onOpenDetails = { spanId, detailId -> viewModel.openDetails(spanId, detailId) },
                    onVoiceCaptureStart = onVoiceCaptureStart,
                    onVoiceCaptureCancel = onVoiceCaptureCancel
                  )
                }
              }

              if (uiState.isLoading) {
                item(key = "assistant-loading") {
                  AssistantLoadingBubble()
                }
              }
            } else if (page == WorkspacePage.QUICK_FOLLOWUP) {
              if (quickFollowupMessages.isEmpty()) {
                item(key = "quick-followup-empty") {
                  AssistantLoadingBubble()
                }
              } else {
                items(quickFollowupMessages, key = { it.id }) { message ->
                  when (message) {
                    is ChatMessage.User -> UserBubble(message)
                    is ChatMessage.Assistant -> AssistantBubble(
                      message = message,
                      histories = quickFollowupHistoriesForUi,
                      processingSpanIds = uiState.processingSpanIds,
                      onAutoExplain = viewModel::autoExplain,
                      onWholeReplyFollowup = { spanId, detailId -> viewModel.openQuickFollowup(spanId, detailId) },
                      onRefreshReply = viewModel::refreshAssistantReply,
                      onToggleSavedQuestion = viewModel::toggleSavedQuestion,
                      refreshEnabled = !uiState.isLoading,
                      showSaveAction = false,
                      isSavedQuestion = false,
                      onVoiceFollowup = onVoiceCaptureSubmit,
                      onRightSwipeOpenDetails = { spanId, detailId -> viewModel.openDetails(spanId, detailId) },
                      onRightSwipeHoldFollowup = { spanId, detailId -> viewModel.openQuickFollowup(spanId, detailId) },
                      onOpenDetails = { spanId, detailId -> viewModel.openDetails(spanId, detailId) },
                      onVoiceCaptureStart = onVoiceCaptureStart,
                      onVoiceCaptureCancel = onVoiceCaptureCancel
                    )
                  }
                }
              }

              if (uiState.isLoading) {
                item(key = "quick-followup-loading") {
                  AssistantLoadingBubble()
                }
              }
            } else if (page == WorkspacePage.COACH) {
              item(key = "coach-digest") {
                CoachDigestCard(
                  digest = uiState.coachDigest,
                  hasActiveTraining = uiState.dailyTraining.isActive && uiState.dailyTraining.dateKey == currentCoachDateKey(),
                  onStartTraining = viewModel::startCoachTraining,
                  onStartRecommendedTraining = viewModel::startCoachRecommendedTraining,
                  onAskAboutRecommendation = viewModel::askCoachAboutRecommendation,
                  onJumpToRecommendationBasis = viewModel::jumpToCoachRecommendationBasis,
                  onSendQuickAction = viewModel::sendCoachQuickAction
                )
              }
              if (uiState.coachMessages.isEmpty()) {
                item(key = "coach-empty") {
                  CoachEmptyState()
                }
              } else {
                items(coachTurns.asReversed(), key = { it.id }) { turn ->
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    turn.messages.forEach { message ->
                      val quickActions = if (message.id == latestCoachAssistantId) {
                        buildCoachReplyQuickActions(
                          message = message,
                          digest = uiState.coachDigest,
                          training = uiState.dailyTraining
                        )
                      } else {
                        emptyList()
                      }
                      CoachMessageBubble(
                        message = message,
                        showQuickActions = message.id == latestCoachAssistantId,
                        quickActions = quickActions,
                        onQuickAction = viewModel::sendCoachQuickAction
                      )
                    }
                  }
                }
              }
            } else if (page == WorkspacePage.ARCHIVE) {
              item(key = "archive-workspace") {
                Box(modifier = Modifier.fillParentMaxSize()) {
                  QuestionArchiveWorkspace(
                    savedQuestions = uiState.savedQuestions,
                    focusedSavedQuestionId = uiState.archiveFocusSavedQuestionId,
                    onSwitchToChat = { viewModel.switchWorkspacePage(WorkspacePage.CHAT) },
                    onOpenQuestionWorkspace = viewModel::openSavedQuestionWorkspace,
                    onAddToMistakeBook = viewModel::addSavedQuestionToMistakeBook,
                    onRemoveQuestion = viewModel::removeSavedQuestion
                  )
                }
              }
            } else if (page == WorkspacePage.MISTAKES) {
              item(key = "mistake-workspace") {
                Box(modifier = Modifier.fillParentMaxSize()) {
                  MistakeBookWorkspace(
                    items = uiState.mistakeItems,
                    drafts = uiState.mistakeRecognitionDrafts,
                    activeDraftId = uiState.activeMistakeDraftId,
                    activeReviewId = uiState.activeMistakeReviewId,
                    activeReviewSuggestion = uiState.activeMistakeReviewSuggestion,
                    isDueReviewMode = uiState.isMistakeDueReviewMode,
                    searchQuery = uiState.mistakeSearchQuery,
                    onSearchQueryChange = viewModel::updateMistakeSearchQuery,
                    onCameraCapture = onCameraMistakeCapture,
                    onGalleryPick = onGalleryMistakePick,
                    onOpenDueQueue = viewModel::openDueMistakeReviewQueue,
                    onRefreshReminder = onRefreshMistakeReminder,
                    onConfirmDraft = viewModel::confirmMistakeDraft,
                    onRecordReview = { itemId, isCorrect, userAnswer ->
                      viewModel.recordMistakeReview(
                        itemId = itemId,
                        isCorrect = isCorrect,
                        userAnswer = userAnswer
                      )
                    },
                    onRequestJudgement = { itemId, userAnswer ->
                      viewModel.requestMistakeAnswerJudgement(
                        itemId = itemId,
                        userAnswer = userAnswer
                      )
                    },
                    onCameraAnswerCapture = onCameraMistakeAnswerCapture,
                    onGalleryAnswerPick = onGalleryMistakeAnswerPick,
                    onConfirmJudgement = viewModel::confirmMistakeAnswerJudgement,
                    onAddToAnki = viewModel::addMistakeToAnki,
                    onDeleteItem = viewModel::deleteMistakeItem,
                    onReopenItem = viewModel::reopenMistakeItem
                  )
                }
              }
            } else if (page == WorkspacePage.ANKI) {
              item(key = "anki-workspace") {
                Box(modifier = Modifier.fillParentMaxSize()) {
                  AnkiWorkspace(
                    cards = visibleAnkiCards,
                    isDueReviewMode = uiState.isDueReviewMode,
                    focusedDeckName = uiState.focusedDeckName,
                    onSwitchToChat = { viewModel.switchWorkspacePage(WorkspacePage.CHAT) },
                    onExitDueReviewMode = viewModel::closeDueReviewMode,
                    onExitDeckFocusedPractice = viewModel::closeDeckFocusedPractice,
                    onUpdateCard = viewModel::updateAnkiCard,
                    onDeleteCard = viewModel::deleteAnkiCard,
                    onSetCardMastery = viewModel::setAnkiCardMastery
                  )
                }
              }
            } else {
              item(key = "profile-workspace") {
                Box(modifier = Modifier.fillParentMaxSize()) {
                  ProfileWorkspace(
                    profile = uiState.profile,
                    cards = uiState.ankiCards,
                    dueCount = dueCardsToday.size,
                    knowledgeGapInsights = knowledgeGapInsights,
                    onOpenDueReview = viewModel::openDueReviewQueue,
                    onOpenDeckArchive = { viewModel.switchWorkspacePage(WorkspacePage.ANKI); isDeckArchiveOpen = true },
                    onOpenSettings = viewModel::openSettings
                  )
                }
              }
            }
          }
        }

        AnimatedVisibility(
          visible = isQuestionPage,
          enter = fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 40)) +
            slideInVertically(animationSpec = tween(durationMillis = 260)) { height -> height / 3 },
          exit = fadeOut(animationSpec = tween(durationMillis = 140)) +
            slideOutVertically(animationSpec = tween(durationMillis = 180)) { height -> height / 3 }
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .imePadding()
          ) {
            ComposerBar(
              input = uiState.input,
              pendingImagePreviews = pendingImageDrafts.map { draft -> draft.bytes },
              isLoading = uiState.isLoading,
              onInputChanged = viewModel::onInputChanged,
              onSend = {
                if (pendingImageDrafts.isEmpty()) {
                  viewModel.sendQuestion()
                } else {
                  val imageBytesList = pendingImageDrafts.map { draft -> draft.bytes }.filter { bytes -> bytes.isNotEmpty() }
                  if (imageBytesList.isEmpty()) {
                    viewModel.showImageReadFailed("图片处理失败")
                  } else {
                    val note = uiState.input.trim().ifBlank { null }
                    val source = if (pendingImageDrafts.size > 1) {
                      "多图搜题"
                    } else {
                      pendingImageDrafts.first().source
                    }
                    viewModel.submitImageQuestion(
                      imageBytesList = imageBytesList,
                      source = source,
                      note = note,
                      imageCount = pendingImageDrafts.size,
                      previewImages = pendingImageDrafts.map { draft -> draft.bytes }
                    )
                    pendingImageDrafts = emptyList()
                    if (uiState.input.isNotBlank()) {
                      viewModel.onInputChanged("")
                    }
                  }
                }
              },
              onCameraSearch = onCameraQuestionSearch,
              onGallerySearch = onGalleryQuestionSearch,
              onRemovePendingImage = { index ->
                pendingImageDrafts = pendingImageDrafts.filterIndexed { i, _ -> i != index }
              },
              onClearPendingImages = { pendingImageDrafts = emptyList() }
            )
          }
        }

        AnimatedVisibility(
          visible = isCoachPage,
          enter = fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 40)) +
            slideInVertically(animationSpec = tween(durationMillis = 260)) { height -> height / 3 },
          exit = fadeOut(animationSpec = tween(durationMillis = 140)) +
            slideOutVertically(animationSpec = tween(durationMillis = 180)) { height -> height / 3 }
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .imePadding()
          ) {
            CoachComposerBar(
              input = uiState.coachInput,
              isLoading = uiState.isLoading,
              placeholderText = coachComposerPlaceholder,
              sendButtonText = coachSendButtonText,
              sendEnabled = coachSendEnabled,
              onInputChanged = viewModel::onCoachInputChanged,
              onSend = viewModel::sendCoachPageInput
            )
          }
        }
        if (!WindowInsets.isImeVisible) {
          WorkspaceSwipeStrip(
            activePage = uiState.activePage,
            onSwitch = viewModel::switchWorkspacePage,
            onOpenJumpPanel = { isWorkspaceJumpOpen = true }
          )
        }
      }

      if (uiState.activePage == WorkspacePage.ANKI && isDeckArchiveOpen) {
        AnkiDeckArchiveScreen(
          decks = ankiDeckSummaries,
          cards = uiState.ankiCards,
          onClose = { isDeckArchiveOpen = false },
          onRenameDeck = viewModel::renameAnkiDeck,
          onArchiveDeck = viewModel::archiveAnkiDeck,
          onUpdateCard = viewModel::updateAnkiCard,
          onDeckPractice = { deckName ->
            isDeckArchiveOpen = false
            viewModel.openDeckFocusedPractice(deckName)
          }
        )
      }
    }
  }

  if (selectedSpanForDialog != null) {
    SpanDetailDialog(
      span = selectedSpanForDialog,
      history = selectedHistory,
      onDismiss = viewModel::closeDetails
    )
  }

  if (uiState.isSettingsOpen) {
    SettingsDialog(
      settings = uiState.settingsDraft,
      isFollowupTreeExportEnabled = followupTreeScopes.isNotEmpty(),
      onDismiss = viewModel::closeSettings,
      onSave = viewModel::saveSettings,
      onReset = viewModel::resetSettingsDraft,
      onResetMainThread = viewModel::startNewChat,
      onExportFollowupTree = triggerFollowupTreeExport,
      onPairFlowStudy = viewModel::pairFlowStudy,
      onPushSessionsToFlowStudy = viewModel::pushSessionsToFlowStudy,
      onSettingsChanged = viewModel::setSettingsDraft
    )
  }

  if (isWorkspaceJumpOpen) {
    WorkspaceJumpDialog(
      activePage = uiState.activePage,
      onDismiss = { isWorkspaceJumpOpen = false },
      onSwitch = viewModel::switchWorkspacePage
    )
  }

  if (isFollowupTreeOpen) {
    FollowupTreeDialog(
      scopes = followupTreeScopes,
      activeSpanId = uiState.quickFollowupSpanId,
      activeDetailId = uiState.quickFollowupDetailId,
      onDismiss = { isFollowupTreeOpen = false },
      onOpenBranch = { spanId, detailId ->
        isFollowupTreeOpen = false
        viewModel.openQuickFollowup(spanId, detailId)
      }
    )
  }

  if (uiState.showDeckPracticeSummary && deckPracticeSummary != null) {
    DeckPracticeSummaryDialog(
      summary = deckPracticeSummary,
      onRestart = viewModel::restartDeckPracticeRound,
      onDismiss = viewModel::dismissDeckPracticeSummary,
      onExit = viewModel::closeDeckFocusedPractice
    )
  }

}

private fun AnimatedContentTransitionScope<WorkspacePage>.workspacePageTransition(): ContentTransform {
  val direction = workspaceTransitionDirection(initialState, targetState)
  return when {
    direction > 0 -> (
      fadeIn(animationSpec = tween(durationMillis = 240, delayMillis = 50)) +
        slideInHorizontally(animationSpec = tween(durationMillis = 320)) { width -> width / 5 }
      ).togetherWith(
        fadeOut(animationSpec = tween(durationMillis = 180)) +
          slideOutHorizontally(animationSpec = tween(durationMillis = 240)) { width -> -width / 6 }
      ).using(SizeTransform(clip = false))

    direction < 0 -> (
      fadeIn(animationSpec = tween(durationMillis = 240, delayMillis = 50)) +
        slideInHorizontally(animationSpec = tween(durationMillis = 320)) { width -> -width / 5 }
      ).togetherWith(
        fadeOut(animationSpec = tween(durationMillis = 180)) +
          slideOutHorizontally(animationSpec = tween(durationMillis = 240)) { width -> width / 6 }
      ).using(SizeTransform(clip = false))

    else -> (
      fadeIn(animationSpec = tween(durationMillis = 180)).togetherWith(
        fadeOut(animationSpec = tween(durationMillis = 120))
      ).using(SizeTransform(clip = false))
    )
  }
}

@Composable
private fun ArchiveHeaderBar(
  savedCount: Int,
  onOpenSettings: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "题目归档",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF235E4E),
        fontWeight = FontWeight.Bold
      )
      Text(
        text = "已收藏 $savedCount 题 · 可进入题目专属界面继续练",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF60756E),
        maxLines = 1
      )
    }

    IconButton(onClick = onOpenSettings) {
      Icon(
        imageVector = Icons.Rounded.Settings,
        contentDescription = "打开设置",
        tint = Color(0xFF2C6756)
      )
    }
  }
}

@Composable
private fun CoachHeaderBar(
  onOpenSettings: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "AI教练",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF235E4E),
        fontWeight = FontWeight.Bold
      )
      Text(
        text = "每天首次自动复盘 · 直接聊你真正漏掉的点",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF60756E),
        maxLines = 1
      )
    }

    IconButton(onClick = onOpenSettings) {
      Icon(
        imageVector = Icons.Rounded.Settings,
        contentDescription = "打开设置",
        tint = Color(0xFF2C6756)
      )
    }
  }
}

@Composable
private fun ProfileHeaderBar(
  onOpenSettings: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "用户中心",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF235E4E),
        fontWeight = FontWeight.Bold
      )
      Text(
        text = "学习数据与复习入口",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF60756E),
        maxLines = 1
      )
    }

    IconButton(onClick = onOpenSettings) {
      Icon(
        imageVector = Icons.Rounded.Settings,
        contentDescription = "打开设置",
        tint = Color(0xFF2C6756)
      )
    }
  }
}

@Composable
private fun HeaderBar(
  onOpenSettings: () -> Unit,
  onOpenFollowupTree: () -> Unit,
  title: String,
  subtitle: String
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(end = 4.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF235E4E),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF60756E),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onOpenFollowupTree, modifier = Modifier.size(40.dp)) {
        Icon(
          imageVector = Icons.Rounded.AccountTree,
          contentDescription = "追问图谱",
          tint = Color(0xFF2C6756)
        )
      }

      IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
        Icon(
          imageVector = Icons.Rounded.Settings,
          contentDescription = "设置",
          tint = Color(0xFF2C6756)
        )
      }
    }
  }
}

private fun normalizeHistoryForTree(history: List<SpanDetail>): List<SpanDetail> {
  return history.map { detail ->
    if (detail.summary.isNullOrBlank()) {
      detail.copy(summary = buildDetailCardSummary(question = detail.question, answer = detail.answer))
    } else {
      detail
    }
  }
}

private fun findSourceUserMessageForSpan(
  messages: List<ChatMessage>,
  span: SpanData?
): ChatMessage.User? {
  val targetSpanId = span?.id ?: return null
  val assistantIndex = messages.indexOfFirst { message ->
    message is ChatMessage.Assistant && message.findSpan(targetSpanId) != null
  }
  if (assistantIndex <= 0) {
    return null
  }

  for (index in assistantIndex - 1 downTo 0) {
    val message = messages[index] as? ChatMessage.User ?: continue
    val hasImages = message.imagePreviewList.isNotEmpty() || message.imagePreviewBytes != null
    if (message.text.isNotBlank() || hasImages) {
      return message
    }
  }
  return null
}

private fun findSourceUserMessageForAssistant(
  messages: List<ChatMessage>,
  assistantMessage: ChatMessage.Assistant?
): ChatMessage.User? {
  val targetMessageId = assistantMessage?.id ?: return null
  val assistantIndex = messages.indexOfFirst { message ->
    message is ChatMessage.Assistant && message.id == targetMessageId
  }
  if (assistantIndex <= 0) {
    return null
  }

  for (index in assistantIndex - 1 downTo 0) {
    val message = messages[index] as? ChatMessage.User ?: continue
    val hasImages = message.imagePreviewList.isNotEmpty() || message.imagePreviewBytes != null
    if (message.text.isNotBlank() || hasImages) {
      return message
    }
  }
  return null
}

internal fun buildQuestionWorkspaceMessages(
  assistantMessage: ChatMessage.Assistant?,
  sourceUserMessage: ChatMessage.User?
): List<ChatMessage> {
  val sourceAssistant = assistantMessage ?: return emptyList()
  val sourceQuestion = sourceAssistant.mainSpan?.sourceQuestion?.trim()
    .orEmpty()
    .ifBlank { sourceAssistant.spans.firstOrNull()?.sourceQuestion?.trim().orEmpty() }

  return buildList {
    when {
      sourceUserMessage != null -> add(sourceUserMessage)
      sourceQuestion.isNotBlank() -> {
        add(
          ChatMessage.User(
            id = "quick-root-user-${sourceAssistant.id}",
            time = "原题",
            text = sourceQuestion
          )
        )
      }
    }

    add(sourceAssistant)
  }
}

private fun buildQuickFollowupMessages(
  span: SpanData?,
  history: List<SpanDetail>,
  activeDetailId: String?,
  sourceUserMessage: ChatMessage.User?
): List<ChatMessage> {
  if (span == null) {
    return emptyList()
  }

  val sourceQuestion = span.sourceQuestion.trim()
  val rootAnswerSpan = span.copy(detailId = null, sourceQuestion = sourceQuestion)
  val parentDetail = findDetailById(history, activeDetailId)
  val parentSpan = parentDetail?.let { detail ->
    span.copy(
      content = detail.answer,
      sourceQuestion = detail.question ?: sourceQuestion,
      detailId = detail.id
    )
  }
  val parentId = parentDetail?.id
  val children = history.asReversed().filter { detail -> detail.parentDetailId == parentId }

  return buildList {
    if (sourceUserMessage != null) {
      add(
        sourceUserMessage.copy(
          id = "quick-root-user-${sourceUserMessage.id}",
          time = sourceUserMessage.time.ifBlank { "原题" }
        )
      )
    } else if (sourceQuestion.isNotBlank()) {
      add(
        ChatMessage.User(
          id = "quick-root-user-${span.id}",
          time = "原题",
          text = sourceQuestion
        )
      )
    }

    add(
      ChatMessage.Assistant(
        id = "quick-root-assistant-${span.id}",
        time = if (history.isEmpty()) "追问起点" else history.last().time,
        spans = listOf(rootAnswerSpan)
      )
    )

    parentDetail?.question?.takeIf { question -> question.isNotBlank() }?.let { question ->
      add(
        ChatMessage.User(
          id = "quick-parent-user-${parentDetail.id}",
          time = parentDetail.time,
          text = question
        )
      )
    }

    parentSpan?.let { resolvedParentSpan ->
      add(
        ChatMessage.Assistant(
          id = "quick-parent-assistant-${parentId}",
          time = parentDetail.time,
          spans = listOf(resolvedParentSpan)
        )
      )
    }

    children.forEach { detail ->
      detail.question?.takeIf { question -> question.isNotBlank() }?.let { question ->
        add(
          ChatMessage.User(
            id = "quick-user-${detail.id}",
            time = detail.time,
            text = question
          )
        )
      }

      add(
        ChatMessage.Assistant(
          id = "quick-assistant-${detail.id}",
          time = detail.time,
          spans = listOf(
            span.copy(
              content = detail.answer,
              sourceQuestion = detail.question ?: (parentSpan?.sourceQuestion ?: sourceQuestion),
              detailId = detail.id
            )
          )
        )
      )
    }
  }
}

private fun buildQuickFollowupHistories(
  span: SpanData?,
  history: List<SpanDetail>
): Map<String, List<SpanDetail>> {
  if (span == null) {
    return emptyMap()
  }

  return mapOf(span.id to history)
}

internal fun buildQuestionWorkspaceHistories(
  assistantMessage: ChatMessage.Assistant?,
  histories: Map<String, List<SpanDetail>>
): Map<String, List<SpanDetail>> {
  val sourceAssistant = assistantMessage ?: return emptyMap()
  return buildMap {
    sourceAssistant.interactiveSpans().forEach { span ->
      val normalized = normalizeHistoryForTree(histories[span.id].orEmpty())
      if (normalized.isNotEmpty()) {
        put(span.id, normalized)
      }
    }
  }
}

internal fun filterFollowupTreeScopesForWorkspace(
  scopes: List<FollowupTreeScope>,
  activePage: WorkspacePage,
  activeSpanId: String?,
  questionSpanIds: Set<String>
): List<FollowupTreeScope> {
  if (activePage != WorkspacePage.QUICK_FOLLOWUP) {
    return scopes
  }

  val allowedSpanIds = when {
    questionSpanIds.isNotEmpty() -> questionSpanIds
    !activeSpanId.isNullOrBlank() -> setOf(activeSpanId)
    else -> emptySet()
  }
  if (allowedSpanIds.isEmpty()) {
    return emptyList()
  }

  return scopes.filter { scope -> scope.spanId in allowedSpanIds }
}

private fun buildFollowupTreeScopes(
  messages: List<ChatMessage>,
  histories: Map<String, List<SpanDetail>>
): List<FollowupTreeScope> {
  if (histories.isEmpty()) {
    return emptyList()
  }

  val spanById = LinkedHashMap<String, SpanData>()
  messages.asReversed().forEach { message ->
    if (message !is ChatMessage.Assistant) {
      return@forEach
    }
    message.interactiveSpans().forEach { span ->
      spanById.putIfAbsent(span.id, span)
    }
  }

  val spanOrder = mutableListOf<String>()
  spanById.keys.forEach { spanId ->
    if (!histories[spanId].isNullOrEmpty()) {
      spanOrder += spanId
    }
  }
  histories.keys
    .filter { spanId -> spanId !in spanById && !histories[spanId].isNullOrEmpty() }
    .sorted()
    .forEach { spanId -> spanOrder += spanId }

  return spanOrder.map { spanId ->
    val details = normalizeHistoryForTree(histories[spanId].orEmpty())
    val span = spanById[spanId]
    val fallbackSource = details.firstOrNull()?.question.orEmpty().ifBlank { "历史追问" }
    FollowupTreeScope(
      spanId = spanId,
      spanContent = span?.content ?: details.firstOrNull()?.answer.orEmpty().ifBlank { "历史段落" },
      sourceQuestion = span?.sourceQuestion ?: fallbackSource,
      details = details
    )
  }
}

@Composable
private fun AnkiHeaderBar(
  cardCount: Int,
  deckCount: Int,
  isDueReviewMode: Boolean,
  focusedDeckName: String?,
  dueReviewCount: Int,
  onOpenDueReview: () -> Unit,
  onExitDueReviewMode: () -> Unit,
  onExitDeckFocusedPractice: () -> Unit,
  onOpenDeckPracticeSummary: () -> Unit,
  onOpenDeckManager: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "Anki 测验区",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF235E4E),
        fontWeight = FontWeight.Bold
      )
      Text(
        text = if (isDueReviewMode) {
          "今日待复习 · 剩余 $cardCount 张（共 $dueReviewCount 张）"
        } else if (!focusedDeckName.isNullOrBlank()) {
          "卡组专练 · $focusedDeckName · $cardCount 张"
        } else {
          "AI 自动制卡 · ${deckCount}组 / ${cardCount}张"
        },
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF5F756D)
      )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      if (!isDueReviewMode) {
        TextButton(onClick = onOpenDueReview) {
          Text(text = "待复习 $dueReviewCount", style = MaterialTheme.typography.labelSmall)
        }
      }
      if (isDueReviewMode) {
        TextButton(onClick = onExitDueReviewMode) {
          Text(text = "退出待复习", style = MaterialTheme.typography.labelSmall)
        }
      } else if (!focusedDeckName.isNullOrBlank()) {
        TextButton(onClick = onOpenDeckPracticeSummary) {
          Text(text = "小结", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onExitDeckFocusedPractice) {
          Text(text = "退出专练", style = MaterialTheme.typography.labelSmall)
        }
      }
      IconButton(onClick = onOpenDeckManager) {
        Icon(
          imageVector = Icons.Rounded.Archive,
          contentDescription = "管理卡组",
          tint = Color(0xFF2C6756)
        )
      }
    }
  }
}
