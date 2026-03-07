package com.studysuit.aiqa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studysuit.aiqa.data.GroupByType
import com.studysuit.aiqa.data.MistakeQuestion
import com.studysuit.aiqa.data.MistakeType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 错题分组视图
 * 根据不同的分组方式展示错题列表
 */
@Composable
fun MistakeGroupView(
  mistakes: List<MistakeQuestion>,
  groupBy: GroupByType,
  modifier: Modifier = Modifier
) {
  val groupedMistakes = when (groupBy) {
    GroupByType.BY_SUBJECT -> mistakes.groupBy { it.subject }
    GroupByType.BY_MISTAKE_TYPE -> mistakes.groupBy { it.mistakeType }
    GroupByType.BY_DATE -> mistakes.groupBy { 
      formatDate(it.createdAt) 
    }
    GroupByType.BY_MASTERY -> mistakes.groupBy { 
      getMasteryRange(it.masteryLevel) 
    }
  }

  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    groupedMistakes.forEach { (groupKey, groupMistakes) ->
      item {
        GroupHeader(
          title = formatGroupTitle(groupKey, groupBy),
          count = groupMistakes.size,
          avgMastery = groupMistakes.map { it.masteryLevel }.average().toInt()
        )
      }
      
      items(items = groupMistakes, key = { it.id }) { mistake ->
        MistakeCard(
          mistake = mistake,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
      }
      
      item { 
        Spacer(modifier = Modifier.height(8.dp)) 
      }
    }
  }
}

/**
 * 分组标题
 * 显示分组名称和统计信息
 */
@Composable
fun GroupHeader(
  title: String,
  count: Int,
  avgMastery: Int,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
    shape = RoundedCornerShape(8.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          StatChip(
            label = "$count 道题",
            color = MaterialTheme.colorScheme.primary
          )
          StatChip(
            label = "平均掌握度 $avgMastery%",
            color = getMasteryColor(avgMastery)
          )
        }
      }
    }
  }
}

/**
 * 错题卡片
 * 展示单个错题的详细信息
 */
@Composable
fun MistakeCard(
  mistake: MistakeQuestion,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      // 顶部：题型和掌握度
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = mistake.questionType,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.secondary
        )
        MasteryIndicator(
          mastery = mistake.masteryLevel,
          starSize = 16.dp
        )
      }
      
      Spacer(modifier = Modifier.height(8.dp))
      
      // 题目内容
      Text(
        text = mistake.questionText,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface
      )
      
      Spacer(modifier = Modifier.height(8.dp))
      
      // 错误类型和原因
      Surface(
        color = getMistakeTypeColor(mistake.mistakeType).copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp)
      ) {
        Text(
          text = getMistakeTypeLabel(mistake.mistakeType),
          style = MaterialTheme.typography.labelSmall,
          color = getMistakeTypeColor(mistake.mistakeType),
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
      }
      
      Spacer(modifier = Modifier.height(4.dp))
      
      Text(
        text = "错误原因：${mistake.mistakeReason}",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF666666),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      
      Spacer(modifier = Modifier.height(8.dp))
      
      // 知识点标签
      if (mistake.knowledgePoints.isNotEmpty()) {
        KnowledgePointChips(points = mistake.knowledgePoints)
      }
      
      // 底部：复习次数和时间
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "已复习 ${mistake.reviewCount} 次",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        mistake.nextReviewAt?.let {
          Text(
            text = "下次复习：${formatDate(it)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
        }
      }
    }
  }
}

/**
 * 统计标签
 * 用于显示简短的统计信息
 */
@Composable
fun StatChip(
  label: String,
  color: Color,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier,
    color = color.copy(alpha = 0.15f),
    shape = RoundedCornerShape(12.dp)
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = color,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
  }
}

/**
 * 掌握度指示器
 * 5星制显示掌握程度
 */
@Composable
fun MasteryIndicator(
  mastery: Int,
  modifier: Modifier = Modifier,
  starSize: androidx.compose.ui.unit.Dp = 20.dp
) {
  val filledStars = (mastery / 20).coerceIn(0, 5)
  
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    repeat(5) { index ->
      Text(
        text = if (index < filledStars) "★" else "☆",
        style = MaterialTheme.typography.bodyMedium,
        color = if (index < filledStars) {
          Color(0xFFFFB800)
        } else {
          Color(0xFFD0D0D0)
        },
        modifier = Modifier.size(starSize)
      )
    }
    Spacer(modifier = Modifier.width(4.dp))
    Text(
      text = "$mastery%",
      style = MaterialTheme.typography.labelSmall,
      color = getMasteryColor(mastery)
    )
  }
}

/**
 * 知识点标签组
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnowledgePointChips(
  points: List<String>,
  modifier: Modifier = Modifier
) {
  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    points.take(3).forEach { point ->
      Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(4.dp)
      ) {
        Text(
          text = point,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
      }
    }
    if (points.size > 3) {
      Text(
        text = "+${points.size - 3}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
      )
    }
  }
}

// ============ 辅助函数 ============

private fun formatDate(timestamp: Long): String {
  val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
  return sdf.format(Date(timestamp))
}

private fun formatGroupTitle(key: Any, groupBy: GroupByType): String {
  return when (groupBy) {
    GroupByType.BY_SUBJECT -> key.toString()
    GroupByType.BY_MISTAKE_TYPE -> getMistakeTypeLabel(key as MistakeType)
    GroupByType.BY_DATE -> key.toString()
    GroupByType.BY_MASTERY -> key.toString()
  }
}

private fun getMasteryRange(mastery: Int): String {
  return when {
    mastery < 20 -> "未掌握 (0-19%)"
    mastery < 40 -> "了解 (20-39%)"
    mastery < 60 -> "理解 (40-59%)"
    mastery < 80 -> "掌握 (60-79%)"
    else -> "熟练 (80-100%)"
  }
}

private fun getMasteryColor(mastery: Int): Color {
  return when {
    mastery < 20 -> Color(0xFFE53935)
    mastery < 40 -> Color(0xFFFF9800)
    mastery < 60 -> Color(0xFFFFC107)
    mastery < 80 -> Color(0xFF8BC34A)
    else -> Color(0xFF4CAF50)
  }
}

private fun getMistakeTypeLabel(type: MistakeType): String {
  return when (type) {
    MistakeType.CONCEPT_ERROR -> "概念错误"
    MistakeType.CALCULATION_ERROR -> "计算错误"
    MistakeType.READING_ERROR -> "审题错误"
    MistakeType.METHOD_ERROR -> "方法错误"
  }
}

private fun getMistakeTypeColor(type: MistakeType): Color {
  return when (type) {
    MistakeType.CONCEPT_ERROR -> Color(0xFFE91E63)
    MistakeType.CALCULATION_ERROR -> Color(0xFFFF5722)
    MistakeType.READING_ERROR -> Color(0xFF9C27B0)
    MistakeType.METHOD_ERROR -> Color(0xFF673AB7)
  }
}
