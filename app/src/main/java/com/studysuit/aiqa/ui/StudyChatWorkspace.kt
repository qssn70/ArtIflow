package com.studysuit.aiqa.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AnkiWorkspace(
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
internal fun WorkspaceSwipeStrip(
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
