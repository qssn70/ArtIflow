package com.studysuit.aiqa.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

@Composable
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
        viewModel.submitImageQuestion(imageBytes = imageBytes, source = "拍照搜题")
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
        viewModel.submitImageQuestion(imageBytes = imageBytes, source = "相册搜题")
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
        } else {
          AnkiHeaderBar(cardCount = uiState.ankiCards.size)
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
          } else {
            item(key = "anki-workspace") {
              Box(modifier = Modifier.fillParentMaxSize()) {
                AnkiWorkspace(
                  knowledgePoints = uiState.knowledgePoints,
                  cards = uiState.ankiCards,
                  onSwitchToChat = { viewModel.switchWorkspacePage(WorkspacePage.CHAT) },
                  onUpdateCard = viewModel::updateAnkiCard,
                  onDeleteCard = viewModel::deleteAnkiCard
                )
              }
            }
          }
        }

        if (uiState.activePage == WorkspacePage.CHAT) {
          ComposerBar(
            input = uiState.input,
            isLoading = uiState.isLoading,
            onInputChanged = viewModel::onInputChanged,
            onSend = viewModel::sendQuestion,
            onCameraSearch = onCameraQuestionSearch,
            onGallerySearch = onGalleryQuestionSearch
          )
        }

        WorkspaceSwipeStrip(
          activePage = uiState.activePage,
          onSwitch = viewModel::switchWorkspacePage
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

      OutlinedButton(onClick = onNewChat) {
        Text(text = "新对话")
      }
    }
  }
}

@Composable
private fun AnkiHeaderBar(cardCount: Int) {
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
        text = "AI 自动制卡 · 共${cardCount}张",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF5F756D)
      )
    }
  }
}
