package com.studysuit.aiqa.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun UserBubble(message: ChatMessage.User) {
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
internal fun AssistantLoadingBubble() {
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
internal fun AssistantBubble(
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
internal fun ComposerBar(
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

    Surface(
      color = Color(0xFFFDFEFD),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .weight(1f)
        .heightIn(min = 40.dp, max = 96.dp)
        .border(1.dp, Color(0x233E6357), RoundedCornerShape(12.dp))
    ) {
      BasicTextField(
        value = input,
        onValueChange = onInputChanged,
        maxLines = 4,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF1F3A32)),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { innerTextField ->
          if (input.isBlank()) {
            Text(
              text = "输入问题",
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFF6A8077)
            )
          }
          innerTextField()
        }
      )
    }

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
