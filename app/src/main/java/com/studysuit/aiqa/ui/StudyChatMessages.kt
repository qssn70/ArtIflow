package com.studysuit.aiqa.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun UserBubble(message: ChatMessage.User) {
  val previewByteList = remember(message.imagePreviewList, message.imagePreviewBytes) {
    if (message.imagePreviewList.isNotEmpty()) {
      message.imagePreviewList
    } else {
      message.imagePreviewBytes?.let { bytes -> listOf(bytes) }.orEmpty()
    }
  }
  val previewBitmaps = remember(previewByteList) {
    previewByteList.mapNotNull { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
  }
  var previewDialogIndex by remember(message.id) { mutableStateOf<Int?>(null) }
  val singlePreviewBitmap = previewBitmaps.firstOrNull()
  val previewUsesFit = remember(singlePreviewBitmap) {
    singlePreviewBitmap != null && singlePreviewBitmap.height >= singlePreviewBitmap.width * 2
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
        if (previewBitmaps.isNotEmpty()) {
          if (previewBitmaps.size == 1 && singlePreviewBitmap != null) {
            Image(
              bitmap = singlePreviewBitmap.asImageBitmap(),
              contentDescription = "题目图片",
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp, max = if (previewUsesFit) 360.dp else 180.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { previewDialogIndex = 0 },
              contentScale = if (previewUsesFit) ContentScale.Fit else ContentScale.Crop
            )
          } else {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              previewBitmaps.forEachIndexed { index, bitmap ->
                Image(
                  bitmap = bitmap.asImageBitmap(),
                  contentDescription = "题目图片 ${index + 1}",
                  modifier = Modifier
                    .size(82.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { previewDialogIndex = index },
                  contentScale = ContentScale.Crop
                )
              }
            }
            Text(
              text = "共 ${previewBitmaps.size} 张，左右滑动查看",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFFEAF7F2)
            )
          }
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

  previewDialogIndex?.let { index ->
    if (previewBitmaps.isNotEmpty()) {
      ImagePreviewDialog(
        previewBitmaps = previewBitmaps,
        initialIndex = index,
        onDismiss = { previewDialogIndex = null }
      )
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
        modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
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
    shape = RoundedCornerShape(10.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (recording) Color(0xFFFFF4EA) else Color(0xFFFCFAF4)
    ),
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { translationX = animatedOffset }
      .border(1.dp, borderColor, RoundedCornerShape(10.dp))
  ) {
    Box(modifier = Modifier.fillMaxWidth().then(dragModifier)) {
      if (historyCount > 0) {
        DetailDiamondBadge(
          count = historyCount,
          onClick = { onOpenDetails(span.id) },
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 8.dp, top = 2.dp)
        )
      }

      Column(
        modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
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
      }
    }
  }
}

@Composable
private fun DetailDiamondBadge(
  count: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val markerColor = Color(0xFF2F6F5D)
  val rows = remember(count) {
    val normalized = count.coerceAtLeast(0)
    if (normalized == 0) {
      emptyList()
    } else {
      List((normalized - 1) / 8 + 1) { rowIndex ->
        val remaining = normalized - rowIndex * 8
        remaining.coerceAtMost(8)
      }
    }
  }

  Column(
    modifier = modifier.clickable(onClick = onClick),
    verticalArrangement = Arrangement.spacedBy(2.dp)
  ) {
    rows.forEach { diamonds ->
      Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(diamonds) {
          Surface(
            color = markerColor,
            shape = RoundedCornerShape(1.dp),
            modifier = Modifier
              .size(6.dp)
              .graphicsLayer { rotationZ = 45f }
          ) {}
        }
      }
    }
  }
}

@Composable
internal fun ComposerBar(
  input: String,
  pendingImagePreviews: List<ByteArray>,
  isLoading: Boolean,
  onInputChanged: (String) -> Unit,
  onSend: () -> Unit,
  onCameraSearch: () -> Unit,
  onGallerySearch: () -> Unit,
  onRemovePendingImage: (Int) -> Unit,
  onClearPendingImages: () -> Unit
) {
  var pendingPreviewDialogIndex by remember(pendingImagePreviews) { mutableStateOf<Int?>(null) }
  val pendingPreviewBitmaps = remember(pendingImagePreviews) {
    pendingImagePreviews.mapNotNull { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
  }

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    if (pendingImagePreviews.isNotEmpty()) {
      Surface(
        color = Color(0xFFF5FAF7),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, Color(0x173B5D52), RoundedCornerShape(12.dp))
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "待上传图片 ${pendingImagePreviews.size} 张",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF5F756D)
            )
            TextButton(onClick = onClearPendingImages) {
              Text(text = "清空", style = MaterialTheme.typography.labelSmall)
            }
          }

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            pendingPreviewBitmaps.forEachIndexed { index, previewBitmap ->
              Box(modifier = Modifier.size(62.dp)) {
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier
                    .size(62.dp)
                    .border(1.dp, Color(0x233E6357), RoundedCornerShape(8.dp))
                    .clickable { pendingPreviewDialogIndex = index }
                ) {
                  Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "待上传图片缩略图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                  )
                }

                Surface(
                  color = Color(0xD8222F2A),
                  shape = CircleShape,
                  modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                ) {
                  IconButton(
                    onClick = { onRemovePendingImage(index) },
                    modifier = Modifier.size(18.dp)
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.Close,
                      contentDescription = "移除图片",
                      tint = Color.White,
                      modifier = Modifier.size(10.dp)
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

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
                text = if (pendingImagePreviews.isNotEmpty()) "可补充文字说明，再点击上传" else "输入问题",
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
        enabled = input.isNotBlank() || pendingImagePreviews.isNotEmpty(),
        modifier = Modifier.heightIn(min = 40.dp, max = 40.dp)
      ) {
        Text(
          text = when {
            isLoading -> "并发中"
            pendingImagePreviews.isNotEmpty() -> "上传"
            else -> "发送"
          },
          style = MaterialTheme.typography.bodySmall
        )
      }
    }

    pendingPreviewDialogIndex?.let { index ->
      if (pendingPreviewBitmaps.isNotEmpty()) {
        ImagePreviewDialog(
          previewBitmaps = pendingPreviewBitmaps,
          initialIndex = index,
          onDismiss = { pendingPreviewDialogIndex = null }
        )
      }
    }
  }
}

@Composable
private fun ImagePreviewDialog(
  previewBitmaps: List<Bitmap>,
  initialIndex: Int,
  onDismiss: () -> Unit
) {
  var currentIndex by remember(previewBitmaps, initialIndex) {
    mutableStateOf(initialIndex.coerceIn(0, previewBitmaps.lastIndex.coerceAtLeast(0)))
  }
  val currentBitmap = previewBitmaps.getOrNull(currentIndex)

  if (currentBitmap == null) {
    return
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFFF8FCF9),
    shape = RoundedCornerShape(14.dp),
    title = {
      Text(
        text = "图片预览 ${currentIndex + 1}/${previewBitmaps.size}",
        style = MaterialTheme.typography.titleSmall,
        color = Color(0xFF2A5146)
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(
          bitmap = currentBitmap.asImageBitmap(),
          contentDescription = "预览大图",
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 420.dp)
            .clip(RoundedCornerShape(10.dp)),
          contentScale = ContentScale.Fit
        )

        if (previewBitmaps.size > 1) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            TextButton(
              onClick = { currentIndex = (currentIndex - 1).coerceAtLeast(0) },
              enabled = currentIndex > 0
            ) {
              Text(text = "上一张", style = MaterialTheme.typography.labelSmall)
            }
            Text(
              text = "左右翻看",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF5F756D)
            )
            TextButton(
              onClick = { currentIndex = (currentIndex + 1).coerceAtMost(previewBitmaps.lastIndex) },
              enabled = currentIndex < previewBitmaps.lastIndex
            ) {
              Text(text = "下一张", style = MaterialTheme.typography.labelSmall)
            }
          }

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            previewBitmaps.forEachIndexed { index, bitmap ->
              Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "缩略图 ${index + 1}",
                modifier = Modifier
                  .size(54.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .border(
                    1.dp,
                    if (index == currentIndex) Color(0xFF2D7A63) else Color(0x223B5D52),
                    RoundedCornerShape(8.dp)
                  )
                  .clickable { currentIndex = index },
                contentScale = ContentScale.Crop
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "关闭", style = MaterialTheme.typography.labelSmall)
      }
    }
  )
}
