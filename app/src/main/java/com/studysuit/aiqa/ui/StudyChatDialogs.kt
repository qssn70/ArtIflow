package com.studysuit.aiqa.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SettingsDialog(
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
internal fun SessionListDialog(
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
internal fun DeckManagerDialog(
  decks: List<AnkiDeckSummary>,
  onDismiss: () -> Unit,
  onRenameDeck: (deckName: String, newDeckName: String) -> Unit,
  onArchiveDeck: (deckName: String) -> Unit
) {
  var editingDeck by remember(decks) { mutableStateOf<String?>(null) }
  var renameInput by remember(decks) { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFFF6FBF7),
    shape = RoundedCornerShape(18.dp),
    title = {
      Text(
        text = "卡组管理",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF255E4D)
      )
    },
    text = {
      if (decks.isEmpty()) {
        Text(text = "暂无卡组", color = Color(0xFF5D7069))
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(decks, key = { it.name }) { deck ->
            Surface(
              color = Color(0xFFFBFEFC),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x1F3A5A4F), RoundedCornerShape(10.dp))
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
                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                      text = deck.name,
                      style = MaterialTheme.typography.bodySmall,
                      color = Color(0xFF2F433C),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Text(
                      text = "${deck.cardCount} 张卡片",
                      style = MaterialTheme.typography.labelSmall,
                      color = Color(0xFF668078)
                    )
                  }

                  if (deck.name != DEFAULT_ANKI_DECK_NAME) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                      TextButton(onClick = {
                        editingDeck = deck.name
                        renameInput = deck.name
                      }) {
                        Text(text = "重命名")
                      }
                      TextButton(onClick = { onArchiveDeck(deck.name) }) {
                        Text(text = "归档", color = Color(0xFF8E4D4D))
                      }
                    }
                  }
                }

                if (editingDeck == deck.name) {
                  OutlinedTextField(
                    value = renameInput,
                    onValueChange = { value -> renameInput = value },
                    label = { Text("新卡组名", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 1,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                  )
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                      onRenameDeck(deck.name, renameInput)
                      editingDeck = null
                    }) {
                      Text(text = "保存", color = Color(0xFF2D6F5D))
                    }
                    TextButton(onClick = {
                      editingDeck = null
                      renameInput = ""
                    }) {
                      Text(text = "取消", color = Color(0xFF4A665C))
                    }
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
internal fun DeckPracticeSummaryDialog(
  summary: DeckPracticeSummary,
  onRestart: () -> Unit,
  onDismiss: () -> Unit,
  onExit: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFFF6FBF7),
    shape = RoundedCornerShape(18.dp),
    title = {
      Text(
        text = "专练完成 · ${summary.deckName}",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF255E4D)
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "本轮已复习 ${summary.reviewedCards}/${summary.totalCards} 张",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF536A62)
        )
        Text(
          text = "生疏 ${summary.needsWorkCount} · 一般 ${summary.familiarCount} · 熟练 ${summary.proficientCount}",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF5F756D)
        )
      }
    },
    confirmButton = {
      TextButton(onClick = {
        onRestart()
      }) {
        Text(text = "再来一轮", color = Color(0xFF2D6F5D))
      }
    },
    dismissButton = {
      TextButton(onClick = {
        onExit()
      }) {
        Text(text = "返回卡组", color = Color(0xFF4A665C))
      }
    }
  )
}

@Composable
internal fun SpanDetailDialog(
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
      LazyColumn(
        modifier = Modifier.heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        item(key = "source-title") {
          Text(
            text = "原始段落",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF4B6058)
          )
        }

        item(key = "source-content") {
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
        }

        item(key = "history-title") {
          Text(
            text = "追问/讲解记录",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF4B6058)
          )
        }

        if (history.isEmpty()) {
          item(key = "history-empty") {
            Text(
              text = "这段还没有记录。左滑松手自动讲解，长按松手语音追问，右滑松手看弹窗，右滑停留进快捷追问。",
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFF5D7069)
            )
          }
        } else {
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
  )
}

internal fun formatSessionTime(updatedAt: Long): String {
  val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
  return formatter.format(Date(updatedAt))
}
