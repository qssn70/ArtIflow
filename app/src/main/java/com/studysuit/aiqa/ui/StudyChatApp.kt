package com.studysuit.aiqa.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studysuit.aiqa.data.ArkApiClient
import com.studysuit.aiqa.data.ArkRequestMessage
import com.studysuit.aiqa.data.OpenSpeechAsrClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StudyChatApp(viewModel: StudyChatViewModel = viewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  val speechRecognizer = remember(context) {
    if (SpeechRecognizer.isRecognitionAvailable(context)) {
      SpeechRecognizer.createSpeechRecognizer(context)
    } else {
      null
    }
  }
  var activeVoiceSpanId by remember { mutableStateOf<String?>(null) }
  var pendingPermissionSpanId by remember { mutableStateOf<String?>(null) }
  var cancelRequested by remember { mutableStateOf(false) }
  var recognizerReady by remember { mutableStateOf(false) }
  var latestTranscript by remember { mutableStateOf("") }
  var voiceCaptureStartMs by remember { mutableStateOf(0L) }
  var pendingStopSpanId by remember { mutableStateOf<String?>(null) }

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
    if (speechRecognizer == null) {
      viewModel.showSpeechRecognizerUnavailable()
    } else {
      cancelRequested = false
      recognizerReady = false
      latestTranscript = ""
      voiceCaptureStartMs = System.currentTimeMillis()
      pendingStopSpanId = null
      activeVoiceSpanId = spanId
      viewModel.showRecordingHint()

      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      }

      runCatching {
        speechRecognizer.cancel()
        speechRecognizer.startListening(intent)
      }.onFailure {
        recognizerReady = false
        latestTranscript = ""
        pendingStopSpanId = null
        activeVoiceSpanId = null
        viewModel.showSpeechRecognizerUnavailable()
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

  DisposableEffect(speechRecognizer) {
    if (speechRecognizer == null) {
      onDispose { }
    } else {
      val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
          recognizerReady = true
          val activeSpanId = activeVoiceSpanId
          if (activeSpanId != null && pendingStopSpanId == activeSpanId) {
            pendingStopSpanId = null
            runCatching {
              speechRecognizer.stopListening()
            }.onFailure {
              val fallbackTranscript = latestTranscript.trim().ifBlank { null }
              recognizerReady = false
              latestTranscript = ""
              activeVoiceSpanId = null
              viewModel.submitVoiceFollowup(spanId = activeSpanId, transcript = fallbackTranscript)
            }
          }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
          val transcript = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

          if (transcript.isNotBlank()) {
            latestTranscript = transcript
          }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
          val spanId = activeVoiceSpanId ?: return
          recognizerReady = false
          activeVoiceSpanId = null
          pendingStopSpanId = null
          cancelRequested = false

          val transcript = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .takeUnless { text -> text.isNullOrBlank() }
            ?: latestTranscript.trim()

          latestTranscript = ""

          viewModel.submitVoiceFollowup(
            spanId = spanId,
            transcript = transcript.ifBlank { null }
          )
        }

        override fun onError(error: Int) {
          val spanId = activeVoiceSpanId
          val fallbackTranscript = latestTranscript.trim()
          recognizerReady = false
          latestTranscript = ""
          pendingStopSpanId = null
          activeVoiceSpanId = null

          if (cancelRequested) {
            cancelRequested = false
            return
          }

          if (spanId != null) {
            if (fallbackTranscript.isNotBlank()) {
              viewModel.submitVoiceFollowup(spanId = spanId, transcript = fallbackTranscript)
            } else {
              viewModel.showSpeechRecognitionFailed(error)
              viewModel.submitVoiceFollowup(spanId = spanId, transcript = null)
            }
          }
        }
      }

      speechRecognizer.setRecognitionListener(listener)
      onDispose {
        speechRecognizer.destroy()
      }
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
      cancelRequested = true
      recognizerReady = false
      latestTranscript = ""
      pendingStopSpanId = null
      activeVoiceSpanId = null
      speechRecognizer?.cancel()
    }

    viewModel.showRecordingCanceled()
  }

  val onVoiceCaptureSubmit: (String) -> Unit = { spanId ->
    when {
      pendingPermissionSpanId == spanId -> Unit
      activeVoiceSpanId == spanId && speechRecognizer != null -> {
        val elapsedMs = System.currentTimeMillis() - voiceCaptureStartMs
        if (!recognizerReady && elapsedMs < 700L) {
          pendingStopSpanId = spanId
        } else {
          runCatching {
            speechRecognizer.stopListening()
          }.onFailure {
            val fallbackTranscript = latestTranscript.trim().ifBlank { null }
            recognizerReady = false
            latestTranscript = ""
            pendingStopSpanId = null
            activeVoiceSpanId = null
            viewModel.submitVoiceFollowup(spanId = spanId, transcript = fallbackTranscript)
          }
        }
      }

      else -> {
        viewModel.submitVoiceFollowup(spanId = spanId, transcript = null)
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
          .statusBarsPadding()
          .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        HeaderBar(onNewChat = viewModel::startNewChat)
        ProfileCard(profile = uiState.profile)

        LazyColumn(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          items(uiState.messages, key = { it.id }) { message ->
            when (message) {
              is ChatMessage.User -> UserBubble(message)
              is ChatMessage.Assistant -> AssistantBubble(
                message = message,
                histories = uiState.histories,
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
        }

        ComposerBar(
          input = uiState.input,
          isLoading = uiState.isLoading,
          onInputChanged = viewModel::onInputChanged,
          onSend = viewModel::sendQuestion
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
}

@Composable
private fun HeaderBar(onNewChat: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "StudySuit · AI学习问答",
        style = MaterialTheme.typography.titleLarge,
        color = Color(0xFF235E4E),
        fontWeight = FontWeight.Bold
      )
      Text(
        text = "普通聊天外观，段落级左滑右滑交互",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF556760)
      )
    }

    OutlinedButton(onClick = onNewChat) {
      Text(text = "新对话")
    }
  }
}

@Composable
private fun ProfileCard(profile: ProfileState) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF8F3)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(text = "画像等级：${profile.level}", style = MaterialTheme.typography.bodySmall)
      Text(text = "关注知识点：${profile.topicsSummary()}", style = MaterialTheme.typography.bodySmall)
      Text(
        text = "追问：${profile.followups} 次 · 语音追问：${profile.voiceFollowups} 次",
        style = MaterialTheme.typography.bodySmall
      )
    }
  }
}

@Composable
private fun UserBubble(message: ChatMessage.User) {
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
      Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
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

  val holdStartThreshold = -72f
  val holdCancelThreshold = -28f
  val recordingCancelThreshold = -32f

  val borderColor = when {
    recording -> Color(0xFFDD8258)
    isDragging -> Color(0x802E8B72)
    else -> Color(0x22354C45)
  }

  Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (recording) Color(0xFFFFF4EA) else Color(0xFFFCFAF4)
    ),
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { translationX = animatedOffset }
      .border(1.dp, borderColor, RoundedCornerShape(12.dp))
      .draggable(
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
          } else if (finalOffset <= -96f) {
            onAutoExplain(span.id)
          } else if (finalOffset >= 96f) {
            onOpenDetails(span.id)
          }
        }
      )
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
      Text(text = span.content, style = MaterialTheme.typography.bodyMedium)

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

@Composable
private fun ComposerBar(
  input: String,
  isLoading: Boolean,
  onInputChanged: (String) -> Unit,
  onSend: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    OutlinedTextField(
      value = input,
      onValueChange = onInputChanged,
      modifier = Modifier.weight(1f),
      shape = RoundedCornerShape(12.dp),
      enabled = true,
      singleLine = true,
      placeholder = { Text(text = "输入问题，比如：二次函数顶点式怎么判断最值？") }
    )

    Button(
      onClick = onSend,
      shape = RoundedCornerShape(12.dp),
      enabled = input.isNotBlank()
    ) {
      Text(text = if (isLoading) "并发中" else "发送")
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
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "关闭")
      }
    },
    title = {
      Text(text = "段落详解")
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = "原始段落", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4E5F58))
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)),
          color = Color(0xFFF7F5EF)
        ) {
          Text(
            text = span.content,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall
          )
        }

        Text(text = "追问/讲解记录", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4E5F58))

        if (history.isEmpty()) {
          Text(
            text = "这段还没有记录。左滑松手自动讲解，长按松手语音追问，右滑查看。",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF647670)
          )
        } else {
          LazyColumn(
            modifier = Modifier.heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            items(history, key = { it.id }) { detail ->
              Surface(
                color = Color(0xFFFCFBF7),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .border(1.dp, Color(0x1F324C45), RoundedCornerShape(10.dp))
              ) {
                Column(
                  modifier = Modifier.padding(9.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Text(
                    text = "${detail.mode} · ${detail.time}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF6D7D76)
                  )
                  detail.question?.takeIf { question -> question.isNotBlank() }?.let { question ->
                    Text(
                      text = "追问：$question",
                      style = MaterialTheme.typography.bodySmall,
                      color = Color(0xFF40534D)
                    )
                  }
                  Text(
                    text = if (detail.question.isNullOrBlank()) detail.answer else "回答：${detail.answer}",
                    style = MaterialTheme.typography.bodySmall
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
  private var conversationToken = 0L
  private var inFlightRequests = 0
  private val arkApiClient = ArkApiClient()
  private val openSpeechAsrClient = OpenSpeechAsrClient()

  private val _uiState = MutableStateFlow(ChatUiState())
  val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  init {
    resetConversation()
  }

  fun onInputChanged(value: String) {
    _uiState.update { current ->
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

  fun startNewChat() {
    resetConversation()
    postToast("已开始新对话")
  }

  fun autoExplain(spanId: String) {
    val span = findSpanById(_uiState.value.messages, spanId) ?: return
    val requestConversationToken = conversationToken

    startRequest(toastMessage = "正在生成该段讲解...")

    val requestMessages = listOf(
      ArkRequestMessage(role = "user", text = buildAutoExplainPrompt(span.content))
    )

    viewModelScope.launch {
      val result = arkApiClient.generateReply(requestMessages)

      if (requestConversationToken != conversationToken) {
        finishRequest()
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
            toastMessage = "已生成该段讲解，右滑可查看详解"
          )
        }
      }.onFailure { throwable ->
        if (throwable is CancellationException) {
          finishRequest()
          throw throwable
        }

        val fallback = buildSpanExplanation(span.content)
        val errorHint = throwable.message?.take(80).orEmpty().ifBlank { "网络不可用" }

        finishRequest { current ->
          val updatedHistory = current.histories.toMutableMap()
          val detail = SpanDetail(
            id = nextDetailId(),
            mode = "自动讲解",
            time = currentDateTime(),
            answer = fallback
          )
          updatedHistory[spanId] = listOf(detail) + current.histories[spanId].orEmpty()

          current.copy(
            histories = updatedHistory,
            toastMessage = "已保存本地讲解：$errorHint"
          )
        }
      }
    }
  }

  fun submitVoiceFollowup(spanId: String, transcript: String? = null) {
    val span = findSpanById(_uiState.value.messages, spanId) ?: return
    runVoiceFollowupWithAsr(span = span, transcript = transcript)
  }

  fun openDetails(spanId: String) {
    _uiState.update { current ->
      current.copy(selectedSpanId = spanId)
    }
  }

  fun closeDetails() {
    _uiState.update { current ->
      current.copy(selectedSpanId = null)
    }
  }

  fun showRecordingHint() {
    postToast("继续按住在录音，松手后提交追问")
  }

  fun showRecordingCanceled() {
    postToast("已取消语音追问")
  }

  fun showMicrophonePermissionDenied() {
    postToast("麦克风权限被拒绝，无法语音追问")
  }

  fun showSpeechRecognizerUnavailable() {
    postToast("设备语音识别不可用，将使用默认追问")
  }

  fun showSpeechRecognitionFailed(errorCode: Int) {
    val reason = when (errorCode) {
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
      SpeechRecognizer.ERROR_NO_MATCH -> "未识别到有效语音"
      SpeechRecognizer.ERROR_NETWORK,
      SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务网络异常"
      else -> "错误码 $errorCode"
    }
    postToast("语音识别失败：$reason")
  }

  fun consumeToast() {
    _uiState.update { current ->
      current.copy(toastMessage = null)
    }
  }

  private fun runVoiceFollowupWithAsr(span: SpanData, transcript: String?) {
    val fallbackQuestion = "这段“${shorten(span.content, 24)}”我没懂，能换个角度讲吗？"
    val voiceQuestion = transcript?.trim().orEmpty()

    if (voiceQuestion.isNotBlank()) {
      enqueueSpanFollowupAndFetch(
        span = span,
        followupQuestion = voiceQuestion,
        isVoice = true,
        mode = "语音追问"
      )
      return
    }

    if (!openSpeechAsrClient.isConfigured() || openSpeechAsrClient.isDemoAudioConfigured()) {
      postToast("未识别到录音内容，请按住说话后再松手")
      return
    }

    val requestConversationToken = conversationToken
    startRequest(toastMessage = "语音识别中...")

    viewModelScope.launch {
      val asrResult = openSpeechAsrClient.transcribeConfiguredAudio()
      if (requestConversationToken != conversationToken) {
        finishRequest()
        return@launch
      }

      val asrQuestion = asrResult.getOrNull()
        ?.takeIf { transcript -> transcript.isNotBlank() }
        ?.trim()
        ?: fallbackQuestion

      finishRequest()

      enqueueSpanFollowupAndFetch(
        span = span,
        followupQuestion = asrQuestion,
        isVoice = true,
        mode = "语音追问"
      )

      if (asrResult.isFailure) {
        val message = asrResult.exceptionOrNull()?.message?.take(60).orEmpty()
        postToast("语音识别失败，已使用默认追问${if (message.isBlank()) "" else ": $message"}")
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
    startRequest(clearToast = true) { current ->
      current.copy(
        profile = current.profile.updateWith(
          text = normalizedQuestion,
          isFollowup = true,
          isVoice = isVoice
        )
      )
    }

    val historyForRequest = toSpanFollowupMessages(
      span = span,
      followupQuestion = normalizedQuestion,
      details = _uiState.value.histories[span.id].orEmpty()
    )

    viewModelScope.launch {
      val result = arkApiClient.generateReply(historyForRequest)

      if (requestConversationToken != conversationToken) {
        finishRequest()
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
            toastMessage = "追问已保存，右滑查看本段详解"
          )
        }
      }.onFailure { throwable ->
        if (throwable is CancellationException) {
          finishRequest()
          throw throwable
        }

        val fallback = buildFallbackReply(normalizedQuestion)
        val errorHint = throwable.message?.take(80).orEmpty().ifBlank { "网络不可用" }

        finishRequest { current ->
          val updatedHistory = current.histories.toMutableMap()
          val detail = SpanDetail(
            id = nextDetailId(),
            mode = mode,
            time = currentDateTime(),
            question = normalizedQuestion,
            answer = fallback
          )
          updatedHistory[span.id] = listOf(detail) + current.histories[span.id].orEmpty()

          current.copy(
            histories = updatedHistory,
            toastMessage = "追问已保存（本地回答）：$errorHint"
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
    val userMessage = ChatMessage.User(id = nextMessageId(), time = currentTime(), text = question)

    startRequest(clearToast = true) { current ->
      current.copy(
        input = if (clearInput) "" else current.input,
        messages = current.messages + userMessage,
        profile = current.profile.updateWith(text = question, isFollowup = isFollowup, isVoice = isVoice)
      )
    }

    val historyForRequest = toArkMessages(_uiState.value.messages)

    viewModelScope.launch {
      val result = arkApiClient.generateReply(historyForRequest)

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

        val fallback = buildFallbackReply(question)
        val assistantMessage = createAssistantMessage(fallback, question)
        val errorHint = throwable.message?.take(80).orEmpty().ifBlank { "网络不可用" }

        finishRequest { current ->
          current.copy(
            messages = current.messages + assistantMessage,
            toastMessage = "已切换本地回答：$errorHint"
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

  private fun resetConversation() {
    conversationToken += 1
    inFlightRequests = 0
    messageSeed = 0
    spanSeed = 0
    detailSeed = 0

    val intro = listOf(
      "你好，我是你的学习搭子。这个界面看起来是普通 AI Chat，但每一段都能左滑右滑交互。",
      "左滑松手会自动讲解当前段落，不会把讲解追加到底部，而是存进段落详解记录。",
      "左滑后不松手会进入语音追问模式，松手即提交追问并更新你的学习画像。",
      "右滑可拉出详解弹窗，回看该段追问与回答；追问内容不会追加到底部聊天。"
    ).joinToString(separator = "\n\n")

    _uiState.value = ChatUiState(
      messages = listOf(createAssistantMessage(intro, "初始化引导")),
      histories = emptyMap(),
      profile = ProfileState(level = "高二 · 进阶冲刺"),
      input = "",
      selectedSpanId = null,
      toastMessage = null,
      isLoading = false
    )
  }

  private fun startRequest(
    toastMessage: String? = null,
    clearToast: Boolean = false,
    transform: (ChatUiState) -> ChatUiState = { state -> state }
  ) {
    inFlightRequests += 1
    _uiState.update { current ->
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
    _uiState.update { current ->
      val base = transform(current)
      base.copy(isLoading = inFlightRequests > 0)
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

  private fun buildFallbackReply(question: String): String {
    val topics = detectTopics(question)
    val focus = topics.firstOrNull() ?: "这个知识点"
    val extension = topics.getOrNull(1)?.let { "并且串联 $it" } ?: "并且避免审题漏条件"

    return listOf(
      "当前网络波动，先给你本地讲解：",
      "先给你结论：$focus 题最稳的做法，是把题干翻译成“已知 -> 目标 -> 校验”三步，再下手计算。",
      "做题时先写一个 20 秒框架：先列已知量和限制，再写要证什么，最后检查单位或区间，$extension。",
      "如果某段看不懂，左滑该段：松手自动讲解；左滑不松手进入语音追问；右滑呼出该段详解历史。"
    ).joinToString(separator = "\n\n")
  }

  private fun buildAutoExplainPrompt(spanContent: String): String {
    return buildString {
      append("请只针对下面这一段内容做讲解。")
      append("输出要求：")
      append("1) 用中文；2) 3~5句；3) 先说结论再说步骤；4) 给一个常见易错点。")
      append("\n\n段落内容：")
      append(spanContent)
    }
  }

  private fun buildSpanExplanation(content: String): String {
    val clean = content.trim().trimEnd('。', '！', '？', '!', '?')
    return "把这句拆成两步就容易了：先确认题目给了什么条件，再确认你要推出什么结论。$clean。做题时先写“已知/求证”两行，准确率会明显提升。"
  }

  private fun detectTopics(text: String): List<String> {
    val normalized = text.lowercase(Locale.getDefault())
    val matched = topicRules.filter { rule ->
      rule.keywords.any { keyword -> normalized.contains(keyword) }
    }.map { it.topic }

    return if (matched.isEmpty()) listOf("通用方法") else matched
  }

  private fun postToast(message: String) {
    _uiState.update { current ->
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

  private fun currentTime(): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.SIMPLIFIED_CHINESE)
    return formatter.format(Date())
  }

  private fun currentDateTime(): String {
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
    return formatter.format(Date())
  }

  private fun shorten(content: String, maxLength: Int): String {
    if (content.length <= maxLength) {
      return content
    }

    return "${content.take(maxLength)}..."
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

private fun ProfileState.topicsSummary(): String {
  val top = topicHits.entries
    .sortedByDescending { entry -> entry.value }
    .take(3)
    .joinToString(separator = "、") { entry -> "${entry.key}(${entry.value})" }

  return if (top.isBlank()) "暂无" else top
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

private data class TopicRule(
  val topic: String,
  val keywords: List<String>
)

private val topicRules = listOf(
  TopicRule(topic = "函数", keywords = listOf("函数", "顶点", "最值", "导数", "单调")),
  TopicRule(topic = "几何", keywords = listOf("几何", "三角形", "圆", "向量", "角度")),
  TopicRule(topic = "概率", keywords = listOf("概率", "随机", "独立", "期望", "方差")),
  TopicRule(topic = "物理", keywords = listOf("力", "加速度", "电场", "磁场", "电流")),
  TopicRule(topic = "化学", keywords = listOf("氧化", "还原", "反应", "离子", "平衡"))
)

data class ChatUiState(
  val messages: List<ChatMessage> = emptyList(),
  val histories: Map<String, List<SpanDetail>> = emptyMap(),
  val profile: ProfileState = ProfileState(level = "高二 · 进阶冲刺"),
  val input: String = "",
  val selectedSpanId: String? = null,
  val toastMessage: String? = null,
  val isLoading: Boolean = false
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
    val text: String
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
