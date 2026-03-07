package com.studysuit.aiqa.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun CoachDigestCard(
  digest: CoachDailyDigest?,
  onStartTraining: () -> Unit,
  onUseRecommendedPrompt: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    color = Color(0xFFF8FCF7),
    shape = RoundedCornerShape(16.dp),
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x183A5A4F), RoundedCornerShape(16.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        text = "今日教练复盘",
        style = MaterialTheme.typography.titleSmall,
        color = Color(0xFF275B4D),
        fontWeight = FontWeight.Bold
      )

      if (digest == null) {
        Text(
          text = "首次进入这里时，AI 教练会自动总结你今天最该补的点，并推荐几道典型题。",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF61756D)
        )
      } else {
        Surface(
          color = Color(0xFFEAF5EF),
          shape = RoundedCornerShape(12.dp)
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Text(
              text = digest.headline,
              style = MaterialTheme.typography.titleSmall,
              color = Color(0xFF205949),
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = digest.summary,
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFF52665F)
            )
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Button(onClick = onStartTraining, modifier = Modifier.weight(1f)) {
            Text(text = "开始今日训练")
          }
          OutlinedButton(onClick = { digest.recommendedQuestions.firstOrNull()?.let { question -> onUseRecommendedPrompt(question.prompt) } ?: onStartTraining() }, modifier = Modifier.weight(1f)) {
            Text(text = "只回填第1题")
          }
        }

        Text(
          text = "下方输入框可直接和 AI 教练对话，问他你到底漏了哪一块。",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF61756D)
        )

        if (digest.focusAreas.isNotEmpty()) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "今天最该盯住的点",
              style = MaterialTheme.typography.labelMedium,
              color = Color(0xFF3B6257)
            )
            digest.focusAreas.forEach { area ->
              Surface(
                color = Color(0xFFFFFEFB),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .border(1.dp, Color(0x143A5A4F), RoundedCornerShape(12.dp))
              ) {
                Column(
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Text(
                    text = "${area.point} · ${area.level.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF2F6353),
                    fontWeight = FontWeight.Medium
                  )
                  Text(
                    text = area.diagnosis,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF536760)
                  )
                  Text(
                    text = "建议：${area.action}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5E726A)
                  )
                }
              }
            }
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "教练推荐的典型题",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF3B6257)
          )
          if (digest.recommendedQuestions.isEmpty()) {
            Text(
              text = "今天先多给我一点做题样本，推荐题会更准。",
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFF637770)
            )
          } else {
            digest.recommendedQuestions.forEach { question ->
              Surface(
                color = Color(0xFFFFFEFB),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .border(1.dp, Color(0x143A5A4F), RoundedCornerShape(12.dp))
              ) {
                Column(
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Text(
                    text = question.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF2C5E4F),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = question.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF586B64)
                  )
                  TextButton(
                    onClick = { onUseRecommendedPrompt(question.prompt) },
                    contentPadding = PaddingValues(0.dp)
                  ) {
                    Text(text = "去主界面练这题")
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun CoachEmptyState(modifier: Modifier = Modifier) {
  Surface(
    color = Color(0xFFFFFEFB),
    shape = RoundedCornerShape(16.dp),
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x143A5A4F), RoundedCornerShape(16.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Text(
        text = "现在可以直接问教练",
        style = MaterialTheme.typography.titleSmall,
        color = Color(0xFF275B4D),
        fontWeight = FontWeight.Bold
      )
      Text(
        text = "比如：我真正漏掉的知识点是什么？为什么我老是想不到切入点？今天先补哪一块最划算？",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF637770)
      )
    }
  }
}

@Composable
internal fun CoachMessageBubble(
  message: CoachChatMessage,
  modifier: Modifier = Modifier
) {
  val isUser = message.role == CoachMessageRole.USER
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
  ) {
    Surface(
      color = if (isUser) Color(0xFF2F7C67) else Color(0xFFFFFEFB),
      contentColor = if (isUser) Color.White else Color(0xFF284C42),
      shape = RoundedCornerShape(
        topStart = if (isUser) 16.dp else 4.dp,
        topEnd = if (isUser) 4.dp else 16.dp,
        bottomEnd = 16.dp,
        bottomStart = 16.dp
      ),
      modifier = Modifier
        .widthIn(max = 320.dp)
        .border(
          width = if (isUser) 0.dp else 1.dp,
          color = if (isUser) Color.Transparent else Color(0x163A5A4F),
          shape = RoundedCornerShape(
            topStart = if (isUser) 16.dp else 4.dp,
            topEnd = if (isUser) 4.dp else 16.dp,
            bottomEnd = 16.dp,
            bottomStart = 16.dp
          )
        )
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Text(
          text = message.text,
          style = MaterialTheme.typography.bodySmall,
          color = if (isUser) Color.White else Color(0xFF40564F)
        )
        Text(
          text = message.time,
          style = MaterialTheme.typography.labelSmall,
          color = if (isUser) Color(0xFFE6F3ED) else Color(0xFF7B8A84),
          modifier = Modifier.align(if (isUser) Alignment.End else Alignment.Start)
        )
      }
    }
  }
}

@Composable
internal fun CoachComposerBar(
  input: String,
  isLoading: Boolean,
  onInputChanged: (String) -> Unit,
  onSend: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Surface(
      color = Color(0xFFFDFEFD),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .weight(1f)
        .heightIn(min = 40.dp, max = 108.dp)
        .border(1.dp, Color(0x233E6357), RoundedCornerShape(12.dp))
    ) {
      BasicTextField(
        value = input,
        onValueChange = onInputChanged,
        maxLines = 5,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF1F3A32)),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { innerTextField ->
          Box {
            if (input.isBlank()) {
              Text(
                text = "问教练：我到底漏了哪个知识点？",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6A8077)
              )
            }
            innerTextField()
          }
        }
      )
    }

    Button(
      onClick = onSend,
      enabled = input.isNotBlank(),
      shape = RoundedCornerShape(12.dp),
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
      modifier = Modifier.heightIn(min = 40.dp, max = 40.dp)
    ) {
      Text(
        text = if (isLoading) "生成中" else "发送",
        style = MaterialTheme.typography.bodySmall
      )
    }
  }
}
