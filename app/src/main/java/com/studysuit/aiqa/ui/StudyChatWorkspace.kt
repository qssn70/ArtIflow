package com.studysuit.aiqa.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
  cards: List<AnkiCard>,
  isDueReviewMode: Boolean,
  onSwitchToChat: () -> Unit,
  onExitDueReviewMode: () -> Unit,
  onUpdateCard: (cardId: String, front: String, back: String, tags: List<String>) -> Unit,
  onDeleteCard: (cardId: String) -> Unit,
  onSetCardMastery: (cardId: String, mastery: CardMasteryLevel) -> Unit
) {
  var currentIndex by remember(cards.size) { mutableStateOf(0) }
  var cardDragOffset by remember(cards.size) { mutableFloatStateOf(0f) }
  val activeCard = cards.getOrNull(currentIndex.coerceIn(0, (cards.size - 1).coerceAtLeast(0)))
  val activeMastery = activeCard?.mastery ?: CardMasteryLevel.UNRATED
  var showAnswer by remember(activeCard?.id) { mutableStateOf(false) }
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
          text = if (isDueReviewMode) "今日待复习已完成，做得很好。" else "还没有卡片。左滑讲解或段落追问后会自动生成测验卡。",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF5D7069)
        )
        if (isDueReviewMode) {
          OutlinedButton(onClick = onExitDueReviewMode) {
            Text(text = "返回全部卡片")
          }
        } else {
          OutlinedButton(onClick = onSwitchToChat) {
            Text(text = "去聊天里生成卡片")
          }
        }
      }
    } else {
      Surface(
        color = Color(0xFFFBFEFC),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
          .align(Alignment.Center)
          .fillMaxWidth(0.86f)
          .heightIn(min = 390.dp, max = 560.dp)
          .draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { delta ->
              cardDragOffset += delta
            },
            onDragStopped = {
              val threshold = 72f
              if (cards.size > 1) {
                when {
                  cardDragOffset <= -threshold -> {
                    currentIndex = (currentIndex + 1) % cards.size
                    showAnswer = false
                  }

                  cardDragOffset >= threshold -> {
                    currentIndex = if (currentIndex <= 0) cards.lastIndex else currentIndex - 1
                    showAnswer = false
                  }
                }
              }
              cardDragOffset = 0f
            }
          )
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
              .weight(1f)
              .clickable {
                showAnswer = !showAnswer
              },
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
            Text(
              text = "卡组：${activeCard.deckName}",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF60756E),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "熟练度：${activeMastery.label}",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF5E746D)
            )
            Text(
              text = if (showAnswer) "当前答案面，点击可回题面" else "点击卡片显示答案",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF5E746D)
            )
            Text(
              text = if (cards.size > 1) "上滑下一张，下滑上一张" else "当前仅 1 张卡片",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF6A8179)
            )
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
        MasteryActionButton(
          badgeText = "熟",
          label = CardMasteryLevel.PROFICIENT.label,
          fillColor = Color(0xFF2E6E5A),
          badgeTextColor = Color(0xFFF4FAF7),
          isSelected = activeMastery == CardMasteryLevel.PROFICIENT,
          onClick = {
            onSetCardMastery(activeCard.id, CardMasteryLevel.PROFICIENT)
          }
        )
        MasteryActionButton(
          badgeText = "中",
          label = CardMasteryLevel.FAMILIAR.label,
          fillColor = Color(0xFF6F9F8D),
          badgeTextColor = Color(0xFFF3F8F6),
          isSelected = activeMastery == CardMasteryLevel.FAMILIAR,
          onClick = {
            onSetCardMastery(activeCard.id, CardMasteryLevel.FAMILIAR)
          }
        )
        MasteryActionButton(
          badgeText = "生",
          label = CardMasteryLevel.NEEDS_WORK.label,
          fillColor = Color(0xFFC9DED6),
          badgeTextColor = Color(0xFF2F5F51),
          isSelected = activeMastery == CardMasteryLevel.NEEDS_WORK,
          onClick = {
            onSetCardMastery(activeCard.id, CardMasteryLevel.NEEDS_WORK)
          }
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
private fun MasteryActionButton(
  badgeText: String,
  label: String,
  fillColor: Color,
  badgeTextColor: Color,
  isSelected: Boolean,
  onClick: () -> Unit
) {
  val buttonColor = if (isSelected) fillColor else fillColor.copy(alpha = 0.82f)
  val borderColor = if (isSelected) Color(0xFF1F5947) else Color(0x173B5D52)
  val labelColor = if (isSelected) Color(0xFF2C6350) else Color(0xFF5C736B)

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp)
  ) {
    Surface(
      color = buttonColor,
      shape = CircleShape,
      modifier = Modifier
        .size(42.dp)
        .border(1.dp, borderColor, CircleShape)
    ) {
      IconButton(onClick = onClick) {
        Text(
          text = badgeText,
          style = MaterialTheme.typography.labelLarge,
          color = badgeTextColor
        )
      }
    }
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = labelColor)
    Text(
      text = if (isSelected) "当前" else "",
      style = MaterialTheme.typography.labelSmall,
      color = Color(0xFF6B8079)
    )
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
