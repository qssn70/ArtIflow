package com.studysuit.aiqa.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studysuit.aiqa.BuildConfig
import com.studysuit.aiqa.data.ArkApiClient
import com.studysuit.aiqa.data.ArkRequestMessage
import com.studysuit.aiqa.data.ArkRuntimeConfig
import com.studysuit.aiqa.data.OpenSpeechAsrClient
import com.studysuit.aiqa.data.OpenSpeechRuntimeConfig
import com.studysuit.aiqa.data.PcmWavRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

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

@Composable
private fun SettingsDialog(
  settings: RuntimeSettings,
  onDismiss: () -> Unit,
  onSave: () -> Unit,
  onReset: () -> Unit,
  onSettingsChanged: (RuntimeSettings) -> Unit
) {
  val scrollState = rememberScrollState()

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFFF6FBF7),
    shape = RoundedCornerShape(18.dp),
    title = {
      Text(
        text = "设置",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF255E4D)
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 440.dp)
          .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(text = "Ark（豆包）", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))

        SettingsTextField(
          label = "ARK_API_KEY",
          value = settings.arkApiKey,
          onValueChange = { value -> onSettingsChanged(settings.copy(arkApiKey = value)) }
        )
        SettingsTextField(
          label = "ARK_MODEL",
          value = settings.arkModel,
          onValueChange = { value -> onSettingsChanged(settings.copy(arkModel = value)) }
        )
        SettingsTextField(
          label = "ARK_BASE_URL",
          value = settings.arkBaseUrl,
          onValueChange = { value -> onSettingsChanged(settings.copy(arkBaseUrl = value)) }
        )
        SettingsTextField(
          label = "ARK_ENDPOINT",
          value = settings.arkEndpoint,
          onValueChange = { value -> onSettingsChanged(settings.copy(arkEndpoint = value)) }
        )
        SettingsTextField(
          label = "ARK_SYSTEM_PROMPT",
          value = settings.arkSystemPrompt,
          minLines = 3,
          onValueChange = { value -> onSettingsChanged(settings.copy(arkSystemPrompt = value)) }
        )
        SettingsTextField(
          label = "图片搜题提示词",
          value = settings.imagePrompt,
          minLines = 3,
          onValueChange = { value -> onSettingsChanged(settings.copy(imagePrompt = value)) }
        )

        HorizontalDivider(color = Color(0x1633564B))

        Text(text = "OpenSpeech", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
        SettingsTextField(
          label = "OPENSPEECH_API_KEY",
          value = settings.openSpeechApiKey,
          onValueChange = { value -> onSettingsChanged(settings.copy(openSpeechApiKey = value)) }
        )
        SettingsTextField(
          label = "OPENSPEECH_RESOURCE_ID",
          value = settings.openSpeechResourceId,
          onValueChange = { value -> onSettingsChanged(settings.copy(openSpeechResourceId = value)) }
        )
        SettingsTextField(
          label = "OPENSPEECH_SUBMIT_URL",
          value = settings.openSpeechSubmitUrl,
          onValueChange = { value -> onSettingsChanged(settings.copy(openSpeechSubmitUrl = value)) }
        )
        SettingsTextField(
          label = "OPENSPEECH_QUERY_URL",
          value = settings.openSpeechQueryUrl,
          onValueChange = { value -> onSettingsChanged(settings.copy(openSpeechQueryUrl = value)) }
        )
        SettingsTextField(
          label = "OPENSPEECH_UID",
          value = settings.openSpeechUid,
          onValueChange = { value -> onSettingsChanged(settings.copy(openSpeechUid = value)) }
        )
      }
    },
    confirmButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onReset) {
          Text(text = "恢复默认", color = Color(0xFF4A665C))
        }
        Button(onClick = onSave, shape = RoundedCornerShape(10.dp)) {
          Text(text = "保存")
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "取消", color = Color(0xFF4A665C))
      }
    }
  )
}

@Composable
private fun SettingsTextField(
  label: String,
  value: String,
  minLines: Int = 1,
  onValueChange: (String) -> Unit
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    modifier = Modifier.fillMaxWidth(),
    minLines = minLines,
    maxLines = if (minLines == 1) 1 else 8,
    shape = RoundedCornerShape(10.dp),
    textStyle = MaterialTheme.typography.bodySmall
  )
}

@Composable
private fun SessionListDialog(
  sessions: List<SessionSummary>,
  activeSessionId: String,
  onDismiss: () -> Unit,
  onSelect: (String) -> Unit,
  onDelete: (String) -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFFF6FBF7),
    shape = RoundedCornerShape(18.dp),
    title = {
      Text(
        text = "历史会话",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF255E4D)
      )
    },
    text = {
      if (sessions.isEmpty()) {
        Text(text = "暂无历史会话", color = Color(0xFF5D7069))
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(sessions, key = { it.id }) { session ->
            val isActive = session.id == activeSessionId
            Surface(
              color = if (isActive) Color(0xFFE8F5EF) else Color(0xFFFBFEFC),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x1F3A5A4F), RoundedCornerShape(10.dp))
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                  Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2F433C),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = "${formatSessionTime(session.updatedAt)} · ${session.messageCount}条",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF668078)
                  )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  TextButton(onClick = { onSelect(session.id) }) {
                    Text(text = if (isActive) "当前" else "打开")
                  }
                  TextButton(onClick = { onDelete(session.id) }) {
                    Text(text = "删除", color = Color(0xFF8E4D4D))
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "关闭", color = Color(0xFF2D6F5D))
      }
    }
  )
}

@Composable
private fun AnkiWorkspace(
  knowledgePoints: Map<String, Int>,
  cards: List<AnkiCard>,
  onSwitchToChat: () -> Unit,
  onUpdateCard: (cardId: String, front: String, back: String, tags: List<String>) -> Unit,
  onDeleteCard: (cardId: String) -> Unit
) {
  var currentIndex by remember(cards.size) { mutableStateOf(0) }
  val activeCard = cards.getOrNull(currentIndex.coerceIn(0, (cards.size - 1).coerceAtLeast(0)))
  var showAnswer by remember(activeCard?.id) { mutableStateOf(false) }
  var liked by remember(activeCard?.id) { mutableStateOf(false) }
  var bookmarked by remember(activeCard?.id) { mutableStateOf(false) }
  var commentCount by remember(activeCard?.id) { mutableStateOf(0) }
  var isManageDialogOpen by remember(activeCard?.id) { mutableStateOf(false) }
  var editFront by remember(activeCard?.id) { mutableStateOf(activeCard?.front.orEmpty()) }
  var editBack by remember(activeCard?.id) { mutableStateOf(activeCard?.back.orEmpty()) }
  var editTagsText by remember(activeCard?.id) {
    mutableStateOf(activeCard?.tags?.joinToString(separator = "，").orEmpty())
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(top = 2.dp)
  ) {
    if (activeCard == null) {
      Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = "还没有卡片。左滑讲解或段落追问后会自动生成测验卡。",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF5D7069)
        )
        OutlinedButton(onClick = onSwitchToChat) {
          Text(text = "去聊天里生成卡片")
        }
      }
    } else {
      val topicHeat = activeCard.tags.firstOrNull()?.let { tag -> knowledgePoints[tag] ?: 0 } ?: 0
      val likeCount = topicHeat.coerceAtLeast(1) + if (liked) 1 else 0
      val bookmarkCount = activeCard.tags.size + if (bookmarked) 1 else 0

      Surface(
        color = Color(0xFFFBFEFC),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
          .align(Alignment.Center)
          .fillMaxWidth(0.86f)
          .heightIn(min = 390.dp, max = 560.dp)
          .border(1.dp, Color(0x1F3A5A4F), RoundedCornerShape(16.dp))
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 18.dp),
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = if (showAnswer) "A(答案面)" else "Q(测验面)",
              style = MaterialTheme.typography.labelMedium,
              color = Color(0xFF53756A)
            )
            TextButton(
              onClick = {
                editFront = activeCard.front
                editBack = activeCard.back
                editTagsText = activeCard.tags.joinToString(separator = "，")
                isManageDialogOpen = true
              }
            ) {
              Text(text = "管理", color = Color(0xFF2D6F5D))
            }
          }

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            contentAlignment = Alignment.Center
          ) {
            MarkdownCardText(
              markdown = if (showAnswer) activeCard.back else activeCard.front,
              modifier = Modifier.fillMaxWidth()
            )
          }

          Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
              text = "${activeCard.source} · ${formatSessionTime(activeCard.createdAt)}",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF688179)
            )
            Text(
              text = if (activeCard.tags.isEmpty()) "标签：未设置" else "标签：${activeCard.tags.joinToString(separator = " · ")}",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF60756E),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { showAnswer = !showAnswer }) {
                Text(text = if (showAnswer) "回题面" else "看答案")
              }
              OutlinedButton(
                onClick = {
                  currentIndex = if (cards.isEmpty()) {
                    0
                  } else {
                    (currentIndex + 1) % cards.size
                  }
                  showAnswer = false
                }
              ) {
                Text(text = "换一张")
              }
            }
          }
        }
      }

      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 2.dp, bottom = 10.dp)
      ) {
        TikTokActionButton(
          symbol = if (liked) "赞+" else "赞",
          label = "点赞",
          count = likeCount,
          onClick = { liked = !liked }
        )
        TikTokActionButton(
          symbol = if (bookmarked) "藏+" else "藏",
          label = "收藏",
          count = bookmarkCount,
          onClick = { bookmarked = !bookmarked }
        )
        TikTokActionButton(
          symbol = "转",
          label = "转发",
          count = 0,
          onClick = {}
        )
        TikTokActionButton(
          symbol = "评",
          label = "评论",
          count = commentCount,
          onClick = { commentCount += 1 }
        )
      }
    }

    if (activeCard != null && isManageDialogOpen) {
      val manageScroll = rememberScrollState()
      AlertDialog(
        onDismissRequest = { isManageDialogOpen = false },
        containerColor = Color(0xFFF6FBF7),
        shape = RoundedCornerShape(18.dp),
        title = {
          Text(
            text = "管理 Anki 卡片",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF255E4D)
          )
        },
        text = {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 420.dp)
              .verticalScroll(manageScroll),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            OutlinedTextField(
              value = editFront,
              onValueChange = { value -> editFront = value },
              label = { Text("题面（Q）", style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.fillMaxWidth(),
              minLines = 2,
              maxLines = 5,
              shape = RoundedCornerShape(10.dp),
              textStyle = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
              value = editBack,
              onValueChange = { value -> editBack = value },
              label = { Text("答案（A）", style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.fillMaxWidth(),
              minLines = 3,
              maxLines = 7,
              shape = RoundedCornerShape(10.dp),
              textStyle = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
              value = editTagsText,
              onValueChange = { value -> editTagsText = value },
              label = { Text("标签（可自定义）", style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.fillMaxWidth(),
              minLines = 1,
              maxLines = 3,
              shape = RoundedCornerShape(10.dp),
              textStyle = MaterialTheme.typography.bodySmall
            )
            Text(
              text = "标签支持中文逗号/英文逗号分隔；可留空。",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF617771)
            )
          }
        },
        confirmButton = {
          Button(
            onClick = {
              onUpdateCard(
                activeCard.id,
                editFront,
                editBack,
                parseAnkiTagsInput(editTagsText)
              )
              isManageDialogOpen = false
            },
            shape = RoundedCornerShape(10.dp)
          ) {
            Text(text = "保存修改")
          }
        },
        dismissButton = {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
              onClick = {
                onDeleteCard(activeCard.id)
                isManageDialogOpen = false
              }
            ) {
              Text(text = "删除", color = Color(0xFF8E4D4D))
            }
            TextButton(onClick = { isManageDialogOpen = false }) {
              Text(text = "取消", color = Color(0xFF4A665C))
            }
          }
        }
      )
    }
  }
}

@Composable
private fun MarkdownCardText(markdown: String, modifier: Modifier = Modifier) {
  MarkdownFormattedText(
    markdown = markdown,
    textStyle = MaterialTheme.typography.headlineSmall,
    textColor = Color(0xFF2F433C),
    textAlign = TextAlign.Center,
    modifier = modifier
  )
}

@Composable
private fun MarkdownBodyText(markdown: String, modifier: Modifier = Modifier) {
  MarkdownFormattedText(
    markdown = markdown,
    textStyle = MaterialTheme.typography.bodyMedium,
    textColor = Color(0xFF2F433C),
    textAlign = TextAlign.Start,
    modifier = modifier
  )
}

@Composable
private fun MarkdownFormattedText(
  markdown: String,
  textStyle: androidx.compose.ui.text.TextStyle,
  textColor: Color,
  textAlign: TextAlign,
  modifier: Modifier = Modifier
) {
  val effectiveLineHeight = remember(textStyle.fontSize) { markdownLineHeightFor(textStyle.fontSize) }
  val renderedStyle = remember(textStyle, effectiveLineHeight) {
    textStyle.copy(lineHeight = effectiveLineHeight)
  }
  val annotated = remember(markdown, textStyle.fontSize) {
    markdownToAnnotatedString(markdown, baseFontSize = textStyle.fontSize)
  }
  Text(
    text = annotated,
    style = renderedStyle,
    color = textColor,
    textAlign = textAlign,
    modifier = modifier
  )
}

@Composable
private fun TikTokActionButton(
  symbol: String,
  label: String,
  count: Int,
  onClick: () -> Unit
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp)
  ) {
    Surface(
      color = Color(0xFFF2F7F4),
      shape = CircleShape,
      modifier = Modifier
        .size(42.dp)
        .border(1.dp, Color(0x173B5D52), CircleShape)
    ) {
      IconButton(onClick = onClick) {
        Text(
          text = symbol,
          style = MaterialTheme.typography.labelLarge,
          color = Color(0xFF2E5F50)
        )
      }
    }
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF5C736B))
    Text(text = count.toString(), style = MaterialTheme.typography.labelSmall, color = Color(0xFF6B8079))
  }
}

@Composable
private fun WorkspaceSwipeStrip(
  activePage: WorkspacePage,
  onSwitch: (WorkspacePage) -> Unit
) {
  var dragOffset by remember { mutableFloatStateOf(0f) }
  val threshold = 68f

  Surface(
    color = Color(0xFFF2F7F3),
    shape = RoundedCornerShape(999.dp),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 28.dp, max = 28.dp)
      .border(1.dp, Color(0x153A5A4F), RoundedCornerShape(999.dp))
      .draggable(
        orientation = Orientation.Horizontal,
        state = rememberDraggableState { delta ->
          dragOffset += delta
        },
        onDragStopped = {
          when {
            dragOffset <= -threshold -> onSwitch(WorkspacePage.ANKI)
            dragOffset >= threshold -> onSwitch(WorkspacePage.CHAT)
          }
          dragOffset = 0f
        }
      )
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = if (activePage == WorkspacePage.CHAT) "在此左右滑动切换到 Anki 复习区" else "在此左右滑动返回聊天区",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF5A7169)
      )
    }
  }
}

@Composable
private fun UserBubble(message: ChatMessage.User) {
  val previewBitmap = remember(message.imagePreviewBytes) {
    message.imagePreviewBytes?.let { bytes ->
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
  }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.End
  ) {
    Surface(
      color = Color(0xFF2F7C67),
      contentColor = Color.White,
      shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
      shadowElevation = 2.dp
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (previewBitmap != null) {
          Image(
            bitmap = previewBitmap.asImageBitmap(),
            contentDescription = "题目图片",
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 90.dp, max = 180.dp)
              .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop
          )
        }

        Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
        Text(
          text = message.time,
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFFEAF7F2),
          modifier = Modifier.align(Alignment.End)
        )
      }
    }
  }
}

@Composable
private fun AssistantLoadingBubble() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Start
  ) {
    Surface(
      color = Color(0xFFF9F8F3),
      shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
      modifier = Modifier
        .fillMaxWidth(0.56f)
        .border(1.dp, Color(0x1D385149), RoundedCornerShape(16.dp))
    ) {
      Text(
        text = "正在思考中...",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF61736C),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
      )
    }
  }
}

@Composable
private fun AssistantBubble(
  message: ChatMessage.Assistant,
  histories: Map<String, List<SpanDetail>>,
  processingSpanIds: Set<String>,
  onAutoExplain: (String) -> Unit,
  onVoiceFollowup: (String) -> Unit,
  onOpenDetails: (String) -> Unit,
  onVoiceCaptureStart: (String) -> Unit,
  onVoiceCaptureCancel: (String) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Start
  ) {
    Surface(
      color = Color(0xFFFFFEFB),
      shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
      shadowElevation = 1.dp,
      modifier = Modifier
        .fillMaxWidth(0.94f)
        .border(1.dp, Color(0x1F3D564E), RoundedCornerShape(16.dp))
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        message.spans.forEach { span ->
          val historyCount = histories[span.id]?.size ?: 0
          InteractiveSpanCard(
            span = span,
            historyCount = historyCount,
            isProcessing = processingSpanIds.contains(span.id),
            onAutoExplain = onAutoExplain,
            onVoiceFollowup = onVoiceFollowup,
            onOpenDetails = onOpenDetails,
            onVoiceCaptureStart = onVoiceCaptureStart,
            onVoiceCaptureCancel = onVoiceCaptureCancel
          )
        }

        Text(
          text = message.time,
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF788881),
          modifier = Modifier.align(Alignment.End)
        )
      }
    }
  }
}

@Composable
private fun InteractiveSpanCard(
  span: SpanData,
  historyCount: Int,
  isProcessing: Boolean,
  onAutoExplain: (String) -> Unit,
  onVoiceFollowup: (String) -> Unit,
  onOpenDetails: (String) -> Unit,
  onVoiceCaptureStart: (String) -> Unit,
  onVoiceCaptureCancel: (String) -> Unit
) {
  var offsetX by remember(span.id) { mutableFloatStateOf(0f) }
  val animatedOffset by animateFloatAsState(targetValue = offsetX, label = "span_offset")
  var isDragging by remember(span.id) { mutableStateOf(false) }
  var recording by remember(span.id) { mutableStateOf(false) }
  var recordingCanceledInGesture by remember(span.id) { mutableStateOf(false) }
  var holdJob by remember(span.id) { mutableStateOf<Job?>(null) }
  val scope = rememberCoroutineScope()

  val holdStartThreshold = -108f
  val holdCancelThreshold = -46f
  val recordingCancelThreshold = -58f
  val processingPulse by rememberInfiniteTransition(label = "span_processing_transition").animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 820),
      repeatMode = RepeatMode.Reverse
    ),
    label = "span_processing_pulse"
  )

  val borderColor = when {
    recording -> Color(0xFFDD8258)
    isDragging -> Color(0x802E8B72)
    isProcessing -> lerp(Color(0x2B51635C), Color(0x9151635C), processingPulse)
    else -> Color(0x22354C45)
  }

  val dragModifier = Modifier.draggable(
    state = rememberDraggableState { delta ->
      offsetX = (offsetX + delta).coerceIn(-220f, 170f)

      if (offsetX <= holdStartThreshold && holdJob == null && !recording && !recordingCanceledInGesture) {
        holdJob = scope.launch {
          delay(620)
          recording = true
          onVoiceCaptureStart(span.id)
        }
      }

      if (offsetX > holdCancelThreshold && !recording) {
        holdJob?.cancel()
        holdJob = null
      }

      if (recording && offsetX > recordingCancelThreshold) {
        recording = false
        recordingCanceledInGesture = true
        holdJob?.cancel()
        holdJob = null
        onVoiceCaptureCancel(span.id)
      }
    },
    orientation = Orientation.Horizontal,
    onDragStarted = {
      isDragging = true
      recording = false
      recordingCanceledInGesture = false
      holdJob?.cancel()
      holdJob = null
    },
    onDragStopped = {
      val finalOffset = offsetX
      val shouldSendVoiceFollowup = recording
      holdJob?.cancel()
      holdJob = null
      isDragging = false
      offsetX = 0f
      recording = false
      recordingCanceledInGesture = false

      if (shouldSendVoiceFollowup) {
        onVoiceFollowup(span.id)
      } else if (finalOffset <= -132f) {
        onAutoExplain(span.id)
      } else if (finalOffset >= 96f) {
        onOpenDetails(span.id)
      }
    }
  )

  Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (recording) Color(0xFFFFF4EA) else Color(0xFFFCFAF4)
    ),
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { translationX = animatedOffset }
      .border(1.dp, borderColor, RoundedCornerShape(12.dp))
  ) {
    Box(modifier = Modifier.fillMaxWidth().then(dragModifier)) {
      Column(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
      ) {
        MarkdownBodyText(markdown = span.content)

        AnimatedVisibility(
          visible = recording,
          enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
          exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
          Text(
            text = "录音中... 松手提交，回滑取消",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFB45932)
          )
        }

        HorizontalDivider(color = Color(0x14324F45))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "左滑讲解 / 长按语音 / 右滑详解",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF647670),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.76f)
          )

          Text(
            text = "详解 $historyCount 条",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF4F625B)
          )
        }
      }
    }
  }
}

@Composable
private fun ComposerBar(
  input: String,
  isLoading: Boolean,
  onInputChanged: (String) -> Unit,
  onSend: () -> Unit,
  onCameraSearch: () -> Unit,
  onGallerySearch: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Surface(
      color = Color(0xFFEAF6EF),
      shape = CircleShape,
      modifier = Modifier.size(38.dp)
    ) {
      IconButton(onClick = onCameraSearch) {
        Icon(
          imageVector = Icons.Rounded.PhotoCamera,
          contentDescription = "拍照搜题",
          tint = Color(0xFF2D7A63)
        )
      }
    }

    Surface(
      color = Color(0xFFF2F7FB),
      shape = CircleShape,
      modifier = Modifier.size(38.dp)
    ) {
      IconButton(onClick = onGallerySearch) {
        Icon(
          imageVector = Icons.Rounded.Add,
          contentDescription = "相册搜题",
          tint = Color(0xFF3D6E93)
        )
      }
    }

    OutlinedTextField(
      value = input,
      onValueChange = onInputChanged,
      modifier = Modifier
        .weight(1f)
        .heightIn(min = 44.dp, max = 44.dp),
      shape = RoundedCornerShape(12.dp),
      enabled = true,
      singleLine = true,
      textStyle = MaterialTheme.typography.bodySmall,
      placeholder = { Text(text = "输入问题", style = MaterialTheme.typography.bodySmall) }
    )

    Button(
      onClick = onSend,
      shape = RoundedCornerShape(12.dp),
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
      enabled = input.isNotBlank(),
      modifier = Modifier.heightIn(min = 40.dp, max = 40.dp)
    ) {
      Text(text = if (isLoading) "并发中" else "发送", style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun SpanDetailDialog(
  span: SpanData,
  history: List<SpanDetail>,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFFF6FBF7),
    shape = RoundedCornerShape(18.dp),
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "关闭", color = Color(0xFF2D6F5D))
      }
    },
    title = {
      Text(
        text = "段落详解",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF255E4D)
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "原始段落",
          style = MaterialTheme.typography.labelMedium,
          color = Color(0xFF4B6058)
        )
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color(0x183A5A4F), RoundedCornerShape(10.dp)),
          color = Color(0xFFF4F9F3)
        ) {
          MarkdownFormattedText(
            markdown = span.content,
            textStyle = MaterialTheme.typography.bodySmall,
            textColor = Color(0xFF2F433C),
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(10.dp),
          )
        }

        Text(
          text = "追问/讲解记录",
          style = MaterialTheme.typography.labelMedium,
          color = Color(0xFF4B6058)
        )

        if (history.isEmpty()) {
          Text(
            text = "这段还没有记录。左滑松手自动讲解，长按松手语音追问，右滑查看。",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF5D7069)
          )
        } else {
          LazyColumn(
            modifier = Modifier.heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            items(history, key = { it.id }) { detail ->
              Surface(
                color = Color(0xFFFBFEFC),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .border(1.dp, Color(0x1F3A5A4F), RoundedCornerShape(10.dp))
              ) {
                Column(
                  modifier = Modifier.padding(9.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Text(
                    text = "${detail.mode} · ${detail.time}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF67817A)
                  )
                  detail.question?.takeIf { question -> question.isNotBlank() }?.let { question ->
                    Text(
                      text = "追问",
                      style = MaterialTheme.typography.labelSmall,
                      color = Color(0xFF5F7A71)
                    )
                    MarkdownFormattedText(
                      markdown = question,
                      textStyle = MaterialTheme.typography.bodySmall,
                      textColor = Color(0xFF355249),
                      textAlign = TextAlign.Start
                    )
                  }
                  Text(
                    text = if (detail.question.isNullOrBlank()) "讲解" else "回答",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5F7A71)
                  )
                  MarkdownFormattedText(
                    markdown = detail.answer,
                    textStyle = MaterialTheme.typography.bodySmall,
                    textColor = Color(0xFF2F433C),
                    textAlign = TextAlign.Start
                  )
                }
              }
            }
          }
        }
      }
    }
  )
}

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

private fun findSpanById(messages: List<ChatMessage>, spanId: String?): SpanData? {
  if (spanId == null) {
    return null
  }

  messages.forEach { message ->
    if (message is ChatMessage.Assistant) {
      message.spans.firstOrNull { span -> span.id == spanId }?.let { found ->
        return found
      }
    }
  }

  return null
}

private fun ProfileState.updateWith(text: String, isFollowup: Boolean, isVoice: Boolean): ProfileState {
  val hits = topicHits.toMutableMap()
  val topics = detectTopicsForProfile(text)
  topics.forEach { topic ->
    hits[topic] = (hits[topic] ?: 0) + 1
  }

  return copy(
    topicHits = hits,
    followups = followups + if (isFollowup) 1 else 0,
    voiceFollowups = voiceFollowups + if (isVoice) 1 else 0
  )
}

private fun detectTopicsForProfile(text: String): List<String> {
  val normalized = text.lowercase(Locale.getDefault())
  val matched = topicRules.filter { rule ->
    rule.keywords.any { keyword -> normalized.contains(keyword) }
  }.map { it.topic }

  return if (matched.isEmpty()) listOf("通用方法") else matched
}

private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
  val stream = ByteArrayOutputStream()
  if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
    return ByteArray(0)
  }
  return stream.toByteArray()
}

private fun transcodeImageToJpeg(rawBytes: ByteArray): ByteArray {
  if (rawBytes.isEmpty()) {
    return ByteArray(0)
  }

  val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
  val jpegBytes = bitmapToJpeg(bitmap)
  return if (jpegBytes.isEmpty()) rawBytes else jpegBytes
}

private fun readImageBytes(context: Context, uri: Uri): ByteArray {
  val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
    input.readBytes()
  }
  return bytes ?: ByteArray(0)
}

private data class TopicRule(
  val topic: String,
  val keywords: List<String>
)

private data class KnowledgeRule(
  val point: String,
  val keywords: List<String>
)

private val topicRules = listOf(
  TopicRule(topic = "函数", keywords = listOf("函数", "顶点", "最值", "导数", "单调")),
  TopicRule(topic = "几何", keywords = listOf("几何", "三角形", "圆", "向量", "角度")),
  TopicRule(topic = "概率", keywords = listOf("概率", "随机", "独立", "期望", "方差")),
  TopicRule(topic = "物理", keywords = listOf("力", "加速度", "电场", "磁场", "电流")),
  TopicRule(topic = "化学", keywords = listOf("氧化", "还原", "反应", "离子", "平衡"))
)

private val knowledgeRules = listOf(
  KnowledgeRule(point = "函数与图像", keywords = listOf("函数", "图像", "抛物线", "导数", "单调", "最值")),
  KnowledgeRule(point = "方程与不等式", keywords = listOf("方程", "不等式", "根", "判别式", "配方", "二次")),
  KnowledgeRule(point = "几何证明", keywords = listOf("几何", "三角形", "圆", "向量", "相似", "全等")),
  KnowledgeRule(point = "概率统计", keywords = listOf("概率", "随机", "期望", "方差", "排列", "组合")),
  KnowledgeRule(point = "力学", keywords = listOf("受力", "牛顿", "加速度", "速度", "位移", "动量")),
  KnowledgeRule(point = "电磁学", keywords = listOf("电场", "电势", "电流", "电阻", "磁场", "感应")),
  KnowledgeRule(point = "化学反应", keywords = listOf("氧化", "还原", "离子", "平衡", "反应", "浓度"))
)

private const val LEGACY_ARK_SYSTEM_PROMPT =
  "你是一个有用的AI学习辅导助手，擅长把复杂知识点讲清楚，优先给步骤化解释。"

private const val DEFAULT_ARK_SYSTEM_PROMPT =
  "你是中学学习辅导助手。回答简洁明了：先给结论，再给关键点；默认3-6行；不要套话，不要长篇大论。"

private const val ANKI_CARD_SYSTEM_PROMPT =
  "你是Anki制卡助手。根据学习交互自选最合适卡型，输出可直接测验的卡片；内容简洁准确，不套模板。"

private const val LEGACY_IMAGE_PROMPT =
  "你是一名中学学科辅导老师。请先识别图片中的题干，再按步骤讲解并给出最终答案。" +
    "如果图片里有多个小题，请按小题编号分别作答。输出格式：\n" +
    "1) 题目识别\n2) 解题思路\n3) 详细步骤\n4) 最终答案"

private const val DEFAULT_IMAGE_PROMPT =
  "你是一名中学学科辅导老师。请识别题目并简洁作答：先给结论，再给2-4条必要步骤；" +
    "多小题按编号回答；不要套话和长篇大论。"

data class RuntimeSettings(
  val arkApiKey: String,
  val arkModel: String,
  val arkBaseUrl: String,
  val arkEndpoint: String,
  val arkSystemPrompt: String,
  val imagePrompt: String,
  val openSpeechApiKey: String,
  val openSpeechResourceId: String,
  val openSpeechSubmitUrl: String,
  val openSpeechQueryUrl: String,
  val openSpeechUid: String
) {
  companion object {
    fun defaults(): RuntimeSettings {
      return RuntimeSettings(
        arkApiKey = BuildConfig.ARK_API_KEY,
        arkModel = BuildConfig.ARK_MODEL,
        arkBaseUrl = BuildConfig.ARK_BASE_URL,
        arkEndpoint = BuildConfig.ARK_ENDPOINT,
        arkSystemPrompt = BuildConfig.ARK_SYSTEM_PROMPT,
        imagePrompt = DEFAULT_IMAGE_PROMPT,
        openSpeechApiKey = BuildConfig.OPENSPEECH_API_KEY,
        openSpeechResourceId = BuildConfig.OPENSPEECH_RESOURCE_ID,
        openSpeechSubmitUrl = BuildConfig.OPENSPEECH_SUBMIT_URL,
        openSpeechQueryUrl = BuildConfig.OPENSPEECH_QUERY_URL,
        openSpeechUid = BuildConfig.OPENSPEECH_UID
      )
    }
  }
}

private fun RuntimeSettings.toArkRuntimeConfig(): ArkRuntimeConfig {
  return ArkRuntimeConfig(
    apiKey = arkApiKey,
    model = arkModel,
    baseUrl = arkBaseUrl,
    endpoint = arkEndpoint,
    systemPrompt = normalizeSystemPrompt(arkSystemPrompt)
  )
}

private fun RuntimeSettings.toOpenSpeechRuntimeConfig(): OpenSpeechRuntimeConfig {
  return OpenSpeechRuntimeConfig(
    apiKey = openSpeechApiKey,
    resourceId = openSpeechResourceId,
    submitUrl = openSpeechSubmitUrl,
    queryUrl = openSpeechQueryUrl,
    uid = openSpeechUid
  )
}

data class ChatUiState(
  val messages: List<ChatMessage> = emptyList(),
  val histories: Map<String, List<SpanDetail>> = emptyMap(),
  val profile: ProfileState = ProfileState(level = "高二 · 进阶冲刺"),
  val input: String = "",
  val selectedSpanId: String? = null,
  val activePage: WorkspacePage = WorkspacePage.CHAT,
  val knowledgePoints: Map<String, Int> = emptyMap(),
  val ankiCards: List<AnkiCard> = emptyList(),
  val activeSessionId: String = "",
  val sessionSummaries: List<SessionSummary> = emptyList(),
  val isSessionsOpen: Boolean = false,
  val toastMessage: String? = null,
  val processingSpanIds: Set<String> = emptySet(),
  val isLoading: Boolean = false,
  val isSettingsOpen: Boolean = false,
  val settings: RuntimeSettings = RuntimeSettings.defaults(),
  val settingsDraft: RuntimeSettings = RuntimeSettings.defaults()
)

data class SessionSummary(
  val id: String,
  val title: String,
  val updatedAt: Long,
  val messageCount: Int
)

data class ProfileState(
  val level: String,
  val topicHits: Map<String, Int> = emptyMap(),
  val followups: Int = 0,
  val voiceFollowups: Int = 0
)

sealed interface ChatMessage {
  val id: String
  val time: String

  data class User(
    override val id: String,
    override val time: String,
    val text: String,
    val imagePreviewBytes: ByteArray? = null
  ) : ChatMessage

  data class Assistant(
    override val id: String,
    override val time: String,
    val spans: List<SpanData>
  ) : ChatMessage
}

data class SpanData(
  val id: String,
  val content: String,
  val sourceQuestion: String
)

data class SpanDetail(
  val id: String,
  val mode: String,
  val time: String,
  val question: String? = null,
  val answer: String
)

enum class WorkspacePage {
  CHAT,
  ANKI
}

data class AnkiCard(
  val id: String,
  val front: String,
  val back: String,
  val tags: List<String>,
  val source: String,
  val createdAt: Long
)

private data class StoredSession(
  val id: String,
  val title: String,
  val createdAt: Long,
  val updatedAt: Long,
  val messages: List<ChatMessage>,
  val histories: Map<String, List<SpanDetail>>,
  val profile: ProfileState,
  val input: String,
  val activePage: WorkspacePage,
  val knowledgePoints: Map<String, Int>,
  val ankiCards: List<AnkiCard>
)

private data class PersistedSessions(
  val activeSessionId: String,
  val settings: RuntimeSettings,
  val sessions: List<StoredSession>
)

private class SessionStorage(private val context: Context) {
  private val storageFile = File(context.filesDir, "study_suit_sessions_v1.json")

  fun save(payload: PersistedSessions) {
    runCatching {
      val root = JSONObject()
        .put("version", 1)
        .put("activeSessionId", payload.activeSessionId)
        .put("settings", payload.settings.toJson())
        .put("sessions", JSONArray().apply {
          payload.sessions.forEach { session ->
            put(session.toJson())
          }
        })

      storageFile.parentFile?.mkdirs()
      storageFile.writeText(root.toString(), Charsets.UTF_8)
    }
  }

  fun load(): PersistedSessions? {
    if (!storageFile.exists()) {
      return null
    }

    return runCatching {
      val root = JSONObject(storageFile.readText(Charsets.UTF_8))
      val activeSessionId = root.optString("activeSessionId").trim()
      val settings = root.optJSONObject("settings")?.toRuntimeSettings() ?: RuntimeSettings.defaults()
      val sessions = root.optJSONArray("sessions")
        ?.let { array ->
          buildList {
            for (index in 0 until array.length()) {
              val item = array.optJSONObject(index) ?: continue
              item.toStoredSession()?.let { session -> add(session) }
            }
          }
        }
        .orEmpty()

      if (sessions.isEmpty()) {
        null
      } else {
        PersistedSessions(
          activeSessionId = activeSessionId,
          settings = settings,
          sessions = sessions
        )
      }
    }.getOrNull()
  }
}

private fun RuntimeSettings.toJson(): JSONObject {
  return JSONObject()
    .put("arkApiKey", arkApiKey)
    .put("arkModel", arkModel)
    .put("arkBaseUrl", arkBaseUrl)
    .put("arkEndpoint", arkEndpoint)
    .put("arkSystemPrompt", arkSystemPrompt)
    .put("imagePrompt", imagePrompt)
    .put("openSpeechApiKey", openSpeechApiKey)
    .put("openSpeechResourceId", openSpeechResourceId)
    .put("openSpeechSubmitUrl", openSpeechSubmitUrl)
    .put("openSpeechQueryUrl", openSpeechQueryUrl)
    .put("openSpeechUid", openSpeechUid)
}

private fun JSONObject.toRuntimeSettings(): RuntimeSettings {
  val defaults = RuntimeSettings.defaults()
  return RuntimeSettings(
    arkApiKey = optString("arkApiKey", defaults.arkApiKey),
    arkModel = optString("arkModel", defaults.arkModel),
    arkBaseUrl = optString("arkBaseUrl", defaults.arkBaseUrl),
    arkEndpoint = optString("arkEndpoint", defaults.arkEndpoint),
    arkSystemPrompt = optString("arkSystemPrompt", defaults.arkSystemPrompt),
    imagePrompt = optString("imagePrompt", defaults.imagePrompt),
    openSpeechApiKey = optString("openSpeechApiKey", defaults.openSpeechApiKey),
    openSpeechResourceId = optString("openSpeechResourceId", defaults.openSpeechResourceId),
    openSpeechSubmitUrl = optString("openSpeechSubmitUrl", defaults.openSpeechSubmitUrl),
    openSpeechQueryUrl = optString("openSpeechQueryUrl", defaults.openSpeechQueryUrl),
    openSpeechUid = optString("openSpeechUid", defaults.openSpeechUid)
  )
}

private fun StoredSession.toJson(): JSONObject {
  return JSONObject()
    .put("id", id)
    .put("title", title)
    .put("createdAt", createdAt)
    .put("updatedAt", updatedAt)
    .put("input", input)
    .put("activePage", activePage.name)
    .put("profile", profile.toJson())
    .put("messages", JSONArray().apply {
      messages.forEach { message -> put(message.toJson()) }
    })
    .put("knowledgePoints", knowledgePoints.toKnowledgePointsJson())
    .put("ankiCards", ankiCards.toJson())
    .put("histories", histories.toJson())
}

private fun JSONObject.toStoredSession(): StoredSession? {
  val id = optString("id").trim()
  if (id.isBlank()) {
    return null
  }

  val title = optString("title").ifBlank { "历史会话" }
  val createdAt = optLong("createdAt", System.currentTimeMillis())
  val updatedAt = optLong("updatedAt", createdAt)
  val input = optString("input")

  val profile = optJSONObject("profile")?.toProfileState() ?: ProfileState(level = "高二 · 进阶冲刺")
  val messages = optJSONArray("messages")?.toChatMessages().orEmpty()
  val histories = optJSONObject("histories")?.toHistories().orEmpty()
  val activePage = optString("activePage").toWorkspacePageOrDefault()
  val knowledgePoints = optJSONObject("knowledgePoints")?.toKnowledgePoints().orEmpty()
  val ankiCards = optJSONArray("ankiCards")?.toAnkiCards().orEmpty()

  return StoredSession(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messages = messages,
    histories = histories,
    profile = profile,
    input = input,
    activePage = activePage,
    knowledgePoints = knowledgePoints,
    ankiCards = ankiCards
  )
}

private fun ProfileState.toJson(): JSONObject {
  return JSONObject()
    .put("level", level)
    .put("followups", followups)
    .put("voiceFollowups", voiceFollowups)
    .put(
      "topicHits",
      JSONObject().apply {
        topicHits.forEach { (topic, count) ->
          put(topic, count)
        }
      }
    )
}

private fun JSONObject.toProfileState(): ProfileState {
  val topicHitsObj = optJSONObject("topicHits")
  val topicHits = mutableMapOf<String, Int>()
  topicHitsObj?.keys()?.let { iterator ->
    while (iterator.hasNext()) {
      val key = iterator.next()
      topicHits[key] = topicHitsObj.optInt(key)
    }
  }

  return ProfileState(
    level = optString("level", "高二 · 进阶冲刺"),
    topicHits = topicHits,
    followups = optInt("followups", 0),
    voiceFollowups = optInt("voiceFollowups", 0)
  )
}

private fun ChatMessage.toJson(): JSONObject {
  return when (this) {
    is ChatMessage.User -> JSONObject()
      .put("type", "user")
      .put("id", id)
      .put("time", time)
      .put("text", text)
      .put("image", imagePreviewBytes?.let { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) } ?: JSONObject.NULL)

    is ChatMessage.Assistant -> JSONObject()
      .put("type", "assistant")
      .put("id", id)
      .put("time", time)
      .put("spans", JSONArray().apply {
        spans.forEach { span ->
          put(
            JSONObject()
              .put("id", span.id)
              .put("content", span.content)
              .put("sourceQuestion", span.sourceQuestion)
          )
        }
      })
  }
}

private fun JSONArray.toChatMessages(): List<ChatMessage> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      when (item.optString("type")) {
        "user" -> {
          val encodedImage = item.optString("image")
          val imageBytes = if (encodedImage.isBlank() || encodedImage == "null") {
            null
          } else {
            runCatching { Base64.decode(encodedImage, Base64.DEFAULT) }.getOrNull()
          }
          add(
            ChatMessage.User(
              id = item.optString("id"),
              time = item.optString("time"),
              text = item.optString("text"),
              imagePreviewBytes = imageBytes
            )
          )
        }

        "assistant" -> {
          val spans = item.optJSONArray("spans")?.let { array ->
            buildList {
              for (spanIndex in 0 until array.length()) {
                val spanObj = array.optJSONObject(spanIndex) ?: continue
                add(
                  SpanData(
                    id = spanObj.optString("id"),
                    content = spanObj.optString("content"),
                    sourceQuestion = spanObj.optString("sourceQuestion")
                  )
                )
              }
            }
          }.orEmpty()

          add(
            ChatMessage.Assistant(
              id = item.optString("id"),
              time = item.optString("time"),
              spans = spans
            )
          )
        }
      }
    }
  }
}

private fun Map<String, List<SpanDetail>>.toJson(): JSONObject {
  return JSONObject().apply {
    forEach { (spanId, details) ->
      put(
        spanId,
        JSONArray().apply {
          details.forEach { detail ->
            put(
              JSONObject()
                .put("id", detail.id)
                .put("mode", detail.mode)
                .put("time", detail.time)
                .put("question", detail.question ?: JSONObject.NULL)
                .put("answer", detail.answer)
            )
          }
        }
      )
    }
  }
}

private fun JSONObject.toHistories(): Map<String, List<SpanDetail>> {
  val histories = mutableMapOf<String, List<SpanDetail>>()
  val iterator = keys()
  while (iterator.hasNext()) {
    val spanId = iterator.next()
    val detailsArray = optJSONArray(spanId) ?: continue
    val details = buildList {
      for (index in 0 until detailsArray.length()) {
        val detailObj = detailsArray.optJSONObject(index) ?: continue
        add(
          SpanDetail(
            id = detailObj.optString("id"),
            mode = detailObj.optString("mode"),
            time = detailObj.optString("time"),
            question = detailObj.optString("question").takeIf { it.isNotBlank() && it != "null" },
            answer = detailObj.optString("answer")
          )
        )
      }
    }
    histories[spanId] = details
  }
  return histories
}

private fun Map<String, Int>.toKnowledgePointsJson(): JSONObject {
  return JSONObject().apply {
    forEach { (point, count) ->
      put(point, count)
    }
  }
}

private fun JSONObject.toKnowledgePoints(): Map<String, Int> {
  val points = linkedMapOf<String, Int>()
  val iterator = keys()
  while (iterator.hasNext()) {
    val key = iterator.next()
    points[key] = optInt(key, 0)
  }
  return points
}

private fun List<AnkiCard>.toJson(): JSONArray {
  return JSONArray().apply {
    forEach { card -> put(card.toJson()) }
  }
}

private fun AnkiCard.toJson(): JSONObject {
  return JSONObject()
    .put("id", id)
    .put("front", front)
    .put("back", back)
    .put("tags", JSONArray(tags))
    .put("source", source)
    .put("createdAt", createdAt)
}

private fun JSONArray.toAnkiCards(): List<AnkiCard> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      val id = item.optString("id").ifBlank { "card-${System.currentTimeMillis()}-$index" }
      val tags = item.optJSONArray("tags")?.let { array ->
        buildList {
          for (tagIndex in 0 until array.length()) {
            val tag = array.optString(tagIndex).trim()
            if (tag.isNotBlank()) {
              add(tag)
            }
          }
        }
      }.orEmpty()

      add(
        AnkiCard(
          id = id,
          front = item.optString("front"),
          back = item.optString("back"),
          tags = tags,
          source = item.optString("source"),
          createdAt = item.optLong("createdAt", System.currentTimeMillis())
        )
      )
    }
  }
}

private fun String.toWorkspacePageOrDefault(): WorkspacePage {
  return runCatching { WorkspacePage.valueOf(this) }.getOrDefault(WorkspacePage.CHAT)
}

private fun markdownToAnnotatedString(markdown: String, baseFontSize: TextUnit): AnnotatedString {
  val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
  val lines = normalized.split('\n')
  val codeStyle = SpanStyle(
    fontFamily = FontFamily.Monospace,
    background = Color(0x153B5D52)
  )

  return buildAnnotatedString {
    var inCodeBlock = false

    lines.forEachIndexed { index, rawLine ->
      val trimmed = rawLine.trimStart()
      if (trimmed.startsWith("```")) {
        inCodeBlock = !inCodeBlock
      } else if (inCodeBlock) {
        withStyle(codeStyle) {
          append(rawLine)
        }
      } else {
        val headingInfo = markdownHeading(trimmed)
        when {
          headingInfo != null -> {
            withStyle(
              SpanStyle(
                fontWeight = FontWeight.Bold,
                fontSize = markdownHeadingSize(headingInfo.second, baseFontSize)
              )
            ) {
              appendMarkdownInline(this, headingInfo.first)
            }
          }

          markdownBulletRegex.matches(trimmed) -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
              append("• ")
            }
            appendMarkdownInline(this, trimmed.replaceFirst(markdownBulletRegex, ""))
          }

          markdownOrderedRegex.matches(trimmed) -> {
            val match = markdownOrderedRegex.find(trimmed)
            if (match != null) {
              append(match.groupValues[1])
              append(". ")
              appendMarkdownInline(this, trimmed.substring(match.range.last + 1).trimStart())
            } else {
              appendMarkdownInline(this, trimmed)
            }
          }

          trimmed.startsWith(">") -> {
            withStyle(SpanStyle(color = Color(0xFF5A7269))) {
              append("▎ ")
            }
            appendMarkdownInline(this, trimmed.removePrefix(">").trimStart())
          }

          else -> appendMarkdownInline(this, rawLine)
        }
      }

      if (index < lines.lastIndex) {
        append('\n')
      }
    }
  }
}

private fun markdownHeading(line: String): Pair<String, Int>? {
  return when {
    line.startsWith("### ") -> line.removePrefix("### ") to 3
    line.startsWith("## ") -> line.removePrefix("## ") to 2
    line.startsWith("# ") -> line.removePrefix("# ") to 1
    else -> null
  }
}

private fun markdownHeadingSize(level: Int, baseFontSize: TextUnit): TextUnit {
  val base = markdownFontSizeValue(baseFontSize)
  val delta = when (level) {
    1 -> 6f
    2 -> 4f
    else -> 2f
  }
  return (base + delta).sp
}

private fun markdownLineHeightFor(baseFontSize: TextUnit): TextUnit {
  val base = markdownFontSizeValue(baseFontSize)
  val headingPeak = base + 6f
  val lineHeight = max(base * 1.55f, headingPeak * 1.35f)
  return lineHeight.sp
}

private fun markdownFontSizeValue(baseFontSize: TextUnit): Float {
  return if (baseFontSize == TextUnit.Unspecified) 16f else baseFontSize.value
}

private fun appendMarkdownInline(builder: AnnotatedString.Builder, text: String) {
  var cursor = 0

  while (cursor < text.length) {
    when {
      text.startsWith("**", cursor) -> {
        val end = text.indexOf("**", cursor + 2)
        if (end > cursor + 2) {
          builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendMarkdownInline(this, text.substring(cursor + 2, end))
          }
          cursor = end + 2
        } else {
          builder.append("**")
          cursor += 2
        }
      }

      text.startsWith("*", cursor) -> {
        val end = text.indexOf("*", cursor + 1)
        if (end > cursor + 1) {
          builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendMarkdownInline(this, text.substring(cursor + 1, end))
          }
          cursor = end + 1
        } else {
          builder.append('*')
          cursor += 1
        }
      }

      text.startsWith("_", cursor) -> {
        val end = text.indexOf("_", cursor + 1)
        if (end > cursor + 1) {
          builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendMarkdownInline(this, text.substring(cursor + 1, end))
          }
          cursor = end + 1
        } else {
          builder.append('_')
          cursor += 1
        }
      }

      text.startsWith("~~", cursor) -> {
        val end = text.indexOf("~~", cursor + 2)
        if (end > cursor + 2) {
          builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendMarkdownInline(this, text.substring(cursor + 2, end))
          }
          cursor = end + 2
        } else {
          builder.append("~~")
          cursor += 2
        }
      }

      text.startsWith("`", cursor) -> {
        val end = text.indexOf('`', cursor + 1)
        if (end > cursor + 1) {
          builder.withStyle(
            SpanStyle(
              fontFamily = FontFamily.Monospace,
              background = Color(0x153B5D52)
            )
          ) {
            append(text.substring(cursor + 1, end))
          }
          cursor = end + 1
        } else {
          builder.append('`')
          cursor += 1
        }
      }

      text.startsWith("[", cursor) -> {
        val closeBracket = text.indexOf(']', cursor + 1)
        val hasParen = closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '('
        if (hasParen) {
          val closeParen = text.indexOf(')', closeBracket + 2)
          if (closeParen > closeBracket + 2) {
            val label = text.substring(cursor + 1, closeBracket)
            builder.withStyle(
              SpanStyle(
                color = Color(0xFF2F6D93),
                textDecoration = TextDecoration.Underline
              )
            ) {
              appendMarkdownInline(this, label)
            }
            cursor = closeParen + 1
          } else {
            builder.append('[')
            cursor += 1
          }
        } else {
          builder.append('[')
          cursor += 1
        }
      }

      else -> {
        builder.append(text[cursor])
        cursor += 1
      }
    }
  }
}

private val markdownBulletRegex = Regex("^[-*+]\\s+")
private val markdownOrderedRegex = Regex("^(\\d+)\\.\\s+")

private fun normalizeSystemPrompt(prompt: String): String {
  val trimmed = prompt.trim()
  return when {
    trimmed.isBlank() -> DEFAULT_ARK_SYSTEM_PROMPT
    trimmed == LEGACY_ARK_SYSTEM_PROMPT -> DEFAULT_ARK_SYSTEM_PROMPT
    else -> trimmed
  }
}

private fun normalizeImagePrompt(prompt: String): String {
  val trimmed = prompt.trim()
  return when {
    trimmed.isBlank() -> DEFAULT_IMAGE_PROMPT
    trimmed == LEGACY_IMAGE_PROMPT -> DEFAULT_IMAGE_PROMPT
    else -> trimmed
  }
}

private fun parseAnkiTagsInput(input: String): List<String> {
  return input
    .split(Regex("[,，;；\\n]+"))
    .asSequence()
    .map { token -> token.trim() }
    .filter { token -> token.isNotEmpty() }
    .distinct()
    .take(10)
    .toList()
}

private fun formatSessionTime(updatedAt: Long): String {
  val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
  return formatter.format(Date(updatedAt))
}
