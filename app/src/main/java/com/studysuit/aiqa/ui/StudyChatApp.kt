package com.studysuit.aiqa.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun StudyChatApp(viewModel: StudyChatViewModel = viewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val appScope = rememberCoroutineScope()
  val context = LocalContext.current
  val wavRecorder = remember(context) { PcmWavRecorder(context.cacheDir) }
  var activeVoiceSpanId by remember { mutableStateOf<String?>(null) }
  var activeVoiceSession by remember { mutableStateOf<PcmWavRecorder.Session?>(null) }
  var pendingPermissionSpanId by remember { mutableStateOf<String?>(null) }
  var pendingCameraCapture by remember { mutableStateOf(false) }
  var isDeckArchiveOpen by remember { mutableStateOf(false) }
  var lastBackPressedAt by remember { mutableLongStateOf(0L) }
  var pendingImageDrafts by remember { mutableStateOf<List<ComposerImageDraft>>(emptyList()) }

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

  val cameraImageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicturePreview()
  ) { bitmap ->
    if (bitmap == null) {
      viewModel.showImageSearchCanceled()
    } else {
      val imageBytes = runCatching { bitmapToJpeg(bitmap) }.getOrNull()
      if (imageBytes == null || imageBytes.isEmpty()) {
        viewModel.showImageReadFailed("拍照结果为空")
      } else {
        appendPendingImage("拍照搜题", imageBytes)
      }
    }
  }

  val galleryImageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri ->
    if (uri == null) {
      viewModel.showImageSearchCanceled()
    } else {
      val imageBytes = runCatching {
        val rawBytes = readImageBytes(context, uri)
        transcodeImageToJpeg(rawBytes)
      }.getOrNull()
      if (imageBytes == null || imageBytes.isEmpty()) {
        viewModel.showImageReadFailed("相册图片读取失败")
      } else {
        appendPendingImage("相册搜题", imageBytes)
      }
    }
  }

  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (!pendingCameraCapture) {
      return@rememberLauncherForActivityResult
    }

    pendingCameraCapture = false
    if (granted) {
      cameraImageLauncher.launch(null)
    } else {
      viewModel.showCameraPermissionDenied()
    }
  }

  val onCameraQuestionSearch: () -> Unit = {
    if (activeVoiceSession != null) {
      viewModel.showRecordingBusy()
    } else {
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
      ) == PackageManager.PERMISSION_GRANTED

      if (granted) {
        cameraImageLauncher.launch(null)
      } else {
        pendingCameraCapture = true
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
      }
    }
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

  LaunchedEffect(Unit) {
    viewModel.ensureStorage(context)
  }

  LaunchedEffect(uiState.toastMessage) {
    val message = uiState.toastMessage ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(message)
    viewModel.consumeToast()
  }

  val selectedSpan = remember(uiState.messages, uiState.selectedSpanId) {
    findSpanById(uiState.messages, uiState.selectedSpanId)
  }

  val selectedHistory = remember(uiState.histories, selectedSpan) {
    if (selectedSpan == null) {
      emptyList()
    } else {
      uiState.histories[selectedSpan.id].orEmpty()
    }
  }

  val ankiDeckSummaries = remember(uiState.ankiCards) {
    buildAnkiDeckSummaries(uiState.ankiCards)
  }
  val dueCardsToday = remember(uiState.ankiCards) {
    dueReviewCards(uiState.ankiCards)
  }
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

  val hasModalDialog = uiState.isSettingsOpen || uiState.isSessionsOpen || selectedSpan != null
  BackHandler(enabled = !hasModalDialog) {
    when {
      isDeckArchiveOpen -> {
        isDeckArchiveOpen = false
      }

      uiState.activePage != WorkspacePage.CHAT -> {
        viewModel.switchWorkspacePage(
          if (uiState.activePage == WorkspacePage.PROFILE) WorkspacePage.ANKI else WorkspacePage.CHAT
        )
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
        if (uiState.activePage == WorkspacePage.CHAT) {
          HeaderBar(
            onNewChat = viewModel::startNewChat,
            onOpenSettings = viewModel::openSettings,
            onOpenSessions = viewModel::openSessions
          )
        } else if (uiState.activePage == WorkspacePage.ANKI) {
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
        } else {
          ProfileHeaderBar(onOpenSettings = viewModel::openSettings)
        }

        LazyColumn(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          if (uiState.activePage == WorkspacePage.CHAT) {
            items(uiState.messages, key = { it.id }) { message ->
              when (message) {
                is ChatMessage.User -> UserBubble(message)
                is ChatMessage.Assistant -> AssistantBubble(
                  message = message,
                  histories = uiState.histories,
                  processingSpanIds = uiState.processingSpanIds,
                  onAutoExplain = viewModel::autoExplain,
                  onVoiceFollowup = onVoiceCaptureSubmit,
                  onOpenDetails = viewModel::openDetails,
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
          } else if (uiState.activePage == WorkspacePage.ANKI) {
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
                  onOpenDueReview = viewModel::openDueReviewQueue,
                  onOpenDeckArchive = { viewModel.switchWorkspacePage(WorkspacePage.ANKI); isDeckArchiveOpen = true },
                  onOpenSettings = viewModel::openSettings
                )
              }
            }
          }
        }

        if (uiState.activePage == WorkspacePage.CHAT) {
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
                  val mergedImages = mergeImagesForUpload(pendingImageDrafts.map { draft -> draft.bytes })
                  if (mergedImages.isEmpty()) {
                    viewModel.showImageReadFailed("图片处理失败")
                  } else {
                    val note = uiState.input.trim().ifBlank { null }
                    val source = if (pendingImageDrafts.size > 1) {
                      "多图搜题"
                    } else {
                      pendingImageDrafts.first().source
                    }
                    viewModel.submitImageQuestion(
                      imageBytes = mergedImages,
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

        if (!WindowInsets.isImeVisible) {
          WorkspaceSwipeStrip(
            activePage = uiState.activePage,
            onSwitch = viewModel::switchWorkspacePage
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

  if (selectedSpan != null) {
    SpanDetailDialog(
      span = selectedSpan,
      history = selectedHistory,
      onDismiss = viewModel::closeDetails
    )
  }

  if (uiState.isSettingsOpen) {
    SettingsDialog(
      settings = uiState.settingsDraft,
      onDismiss = viewModel::closeSettings,
      onSave = viewModel::saveSettings,
      onReset = viewModel::resetSettingsDraft,
      onSettingsChanged = viewModel::setSettingsDraft
    )
  }

  if (uiState.isSessionsOpen) {
    SessionListDialog(
      sessions = uiState.sessionSummaries,
      activeSessionId = uiState.activeSessionId,
      onDismiss = viewModel::closeSessions,
      onSelect = viewModel::switchSession,
      onDelete = viewModel::deleteSession
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
  onNewChat: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenSessions: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "StudySuit · AI学习问答",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF235E4E),
        fontWeight = FontWeight.Bold
      )
      Text(
        text = "左滑讲解 · 长按语音 · 右滑详解",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF60756E),
        maxLines = 1
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onOpenSessions) {
        Icon(
          imageVector = Icons.Rounded.History,
          contentDescription = "会话",
          tint = Color(0xFF2C6756)
        )
      }

      IconButton(onClick = onOpenSettings) {
        Icon(
          imageVector = Icons.Rounded.Settings,
          contentDescription = "设置",
          tint = Color(0xFF2C6756)
        )
      }

      OutlinedButton(
        onClick = onNewChat,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.heightIn(min = 32.dp)
      ) {
        Text(text = "新对话", style = MaterialTheme.typography.labelSmall, maxLines = 1)
      }
    }
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
