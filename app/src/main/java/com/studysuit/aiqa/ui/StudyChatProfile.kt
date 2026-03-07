package com.studysuit.aiqa.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun ProfileWorkspace(
  profile: ProfileState,
  cards: List<AnkiCard>,
  dueCount: Int,
  knowledgeGapInsights: List<KnowledgeGapInsight>,
  onOpenDueReview: () -> Unit,
  onOpenDeckArchive: () -> Unit,
  onOpenSettings: () -> Unit
) {
  val needsWorkCount = cards.count { card -> card.mastery == CardMasteryLevel.NEEDS_WORK }
  val familiarCount = cards.count { card -> card.mastery == CardMasteryLevel.FAMILIAR }
  val proficientCount = cards.count { card -> card.mastery == CardMasteryLevel.PROFICIENT }
  val deckCount = cards
    .asSequence()
    .map { card -> normalizeDeckName(card.deckName) ?: DEFAULT_ANKI_DECK_NAME }
    .distinct()
    .count()
  val activityHeatmapCells = remember(cards) {
    buildActivityHeatmap(cards)
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 4.dp, vertical = 2.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Surface(
      color = Color(0xFFF7FBF8),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Color(0x173B5D52), RoundedCornerShape(14.dp))
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
      ) {
        Text(
          text = "学习档案",
          style = MaterialTheme.typography.titleSmall,
          color = Color(0xFF295C4D)
        )
        Text(
          text = "当前档位：${profile.level}",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF5B716A)
        )
        Text(
          text = "追问 ${profile.followups} 次 · 语音追问 ${profile.voiceFollowups} 次",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF637A72)
        )
      }
    }

    Surface(
      color = Color(0xFFF7FBF8),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Color(0x173B5D52), RoundedCornerShape(14.dp))
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "活动热力图（近10周）",
          style = MaterialTheme.typography.labelMedium,
          color = Color(0xFF2C6251)
        )
        ActivityHeatmap(data = activityHeatmapCells)
        Text(
          text = "统计规则：制卡 + 复习都会计入当日活跃度。",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF647A73)
        )
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      ProfileStatCard(
        title = "今日待复习",
        value = dueCount.toString(),
        modifier = Modifier.weight(1f)
      )
      ProfileStatCard(
        title = "卡组数量",
        value = deckCount.toString(),
        modifier = Modifier.weight(1f)
      )
      ProfileStatCard(
        title = "总卡片",
        value = cards.size.toString(),
        modifier = Modifier.weight(1f)
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      ProfileStatCard(
        title = "生疏",
        value = needsWorkCount.toString(),
        modifier = Modifier.weight(1f)
      )
      ProfileStatCard(
        title = "一般",
        value = familiarCount.toString(),
        modifier = Modifier.weight(1f)
      )
      ProfileStatCard(
        title = "熟练",
        value = proficientCount.toString(),
        modifier = Modifier.weight(1f)
      )
    }

    Surface(
      color = Color(0xFFF7FBF8),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Color(0x173B5D52), RoundedCornerShape(14.dp))
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "高频知识点",
          style = MaterialTheme.typography.labelMedium,
          color = Color(0xFF2C6251)
        )

        val topTopics = profile.topicHits.entries
          .sortedByDescending { entry -> entry.value }
          .take(6)

        if (topTopics.isEmpty()) {
          Text(
            text = "还没有学习画像，先在聊天区多练几道题吧。",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF647A73)
          )
        } else {
          topTopics.forEach { (topic, count) ->
            Text(
              text = "• $topic（$count）",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF4D655D),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }

    Surface(
      color = Color(0xFFF7FBF8),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Color(0x173B5D52), RoundedCornerShape(14.dp))
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "AI洞察 · 薄弱点",
          style = MaterialTheme.typography.labelMedium,
          color = Color(0xFF2C6251)
        )

        if (knowledgeGapInsights.isEmpty()) {
          Text(
            text = "当前还没有明显的知识漏洞信号；继续做题和追问后，这里会自动归纳“不会的点”。",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF647A73)
          )
        } else {
          knowledgeGapInsights.take(3).forEach { insight ->
            KnowledgeGapInsightCard(insight = insight)
          }
        }
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = onOpenDueReview, modifier = Modifier.weight(1f)) {
        Text(text = "今日待复习")
      }
      Button(onClick = onOpenDeckArchive, modifier = Modifier.weight(1f)) {
        Text(text = "卡组管理")
      }
      Button(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
        Text(text = "模型设置")
      }
    }
  }
}

@Composable
private fun ActivityHeatmap(data: HeatmapData) {
  val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = "",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF60756E),
        modifier = Modifier.size(14.dp)
      )
      data.monthLabels.forEach { label ->
        Text(
          text = label ?: "",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF60756E),
          modifier = Modifier.size(12.dp)
        )
      }
    }

    data.rows.forEachIndexed { rowIndex, row ->
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = dayLabels[rowIndex],
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF60756E),
          modifier = Modifier.size(14.dp)
        )
        row.forEachIndexed { columnIndex, level ->
          val date = data.columns.getOrNull(columnIndex)?.getOrNull(rowIndex)
          val isToday = date == data.today
          Surface(
            color = heatLevelColor(level),
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier
              .size(12.dp)
              .border(
                1.dp,
                if (isToday) Color(0xFF1D5E49) else Color(0x183B5D52),
                RoundedCornerShape(3.dp)
              )
          ) {
            Box(modifier = Modifier.fillMaxSize())
          }
        }
      }
    }
  }
}

private fun heatLevelColor(level: Int): Color {
  return when (level.coerceIn(0, 4)) {
    0 -> Color(0xFFEFF5F1)
    1 -> Color(0xFFD7EADF)
    2 -> Color(0xFFB9DCCB)
    3 -> Color(0xFF89C1A7)
    else -> Color(0xFF4B9B79)
  }
}

private data class HeatmapData(
  val rows: List<List<Int>>,
  val columns: List<List<LocalDate>>,
  val monthLabels: List<String?>,
  val today: LocalDate
)

private fun buildActivityHeatmap(cards: List<AnkiCard>): HeatmapData {
  val zone = ZoneId.systemDefault()
  val today = LocalDate.now(zone)
  val startDate = today.minusWeeks(10).with(DayOfWeek.MONDAY)
  val endDate = today

  val dayScoreMap = mutableMapOf<LocalDate, Int>()
  cards.forEach { card ->
    val createdDate = Instant.ofEpochMilli(card.createdAt).atZone(zone).toLocalDate()
    dayScoreMap[createdDate] = (dayScoreMap[createdDate] ?: 0) + 2

    val reviewDate = card.lastReviewedAt?.let { millis ->
      Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    }
    if (reviewDate != null) {
      dayScoreMap[reviewDate] = (dayScoreMap[reviewDate] ?: 0) + card.reviewCount.coerceAtLeast(1)
    }
  }

  val allScores = dayScoreMap.values
  val maxScore = allScores.maxOrNull()?.coerceAtLeast(1) ?: 1

  val columns = mutableListOf<List<Int>>()
  val dateColumns = mutableListOf<List<LocalDate>>()
  val monthLabels = mutableListOf<String?>()
  var columnStart = startDate
  while (!columnStart.isAfter(endDate)) {
    val column = mutableListOf<Int>()
    val dateColumn = mutableListOf<LocalDate>()
    val monthLabel = if (columns.isEmpty() || columnStart.month != columnsMonth(columns.size - 1, startDate)) {
      columnStart.month.getDisplayName(TextStyle.SHORT, Locale.CHINA)
    } else {
      null
    }

    for (offset in 0..6) {
      val date = columnStart.plusDays(offset.toLong())
      dateColumn += date
      if (date.isAfter(endDate)) {
        column += 0
      } else {
        val score = dayScoreMap[date] ?: 0
        val level = when {
          score <= 0 -> 0
          score * 4 < maxScore -> 1
          score * 4 < maxScore * 2 -> 2
          score * 4 < maxScore * 3 -> 3
          else -> 4
        }
        column += level
      }
    }
    columns += column
    dateColumns += dateColumn
    monthLabels += monthLabel
    columnStart = columnStart.plusWeeks(1)
  }

  return HeatmapData(
    rows = (0..6).map { rowIndex -> columns.map { column -> column[rowIndex] } },
    columns = dateColumns,
    monthLabels = monthLabels,
    today = today
  )
}

private fun columnsMonth(columnIndex: Int, startDate: LocalDate) = startDate.plusWeeks(columnIndex.toLong()).month

@Composable
private fun ProfileStatCard(
  title: String,
  value: String,
  modifier: Modifier = Modifier
) {
  Surface(
    color = Color(0xFFF7FBF8),
    shape = RoundedCornerShape(12.dp),
    modifier = modifier
      .border(1.dp, Color(0x173B5D52), RoundedCornerShape(12.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF60756E)
      )
      Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF295C4D)
      )
    }
  }
}

@Composable
private fun KnowledgeGapInsightCard(insight: KnowledgeGapInsight) {
  val accent = when (insight.level) {
    KnowledgeGapLevel.HIGH -> Color(0xFFB95E45)
    KnowledgeGapLevel.MEDIUM -> Color(0xFFB48733)
    KnowledgeGapLevel.LOW -> Color(0xFF4A7B69)
  }
  val background = when (insight.level) {
    KnowledgeGapLevel.HIGH -> Color(0xFFFFF4EE)
    KnowledgeGapLevel.MEDIUM -> Color(0xFFFFF9ED)
    KnowledgeGapLevel.LOW -> Color(0xFFF2FAF6)
  }

  Surface(
    color = background,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = insight.point,
          style = MaterialTheme.typography.labelMedium,
          color = accent,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = insight.level.label,
          style = MaterialTheme.typography.labelSmall,
          color = accent
        )
      }
      Text(
        text = insight.evidence,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF5C7068),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = insight.diagnosis,
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF3F5A52),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = "建议：${insight.action}",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF5C7068),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}
