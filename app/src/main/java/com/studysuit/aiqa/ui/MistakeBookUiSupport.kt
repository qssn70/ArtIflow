package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.MistakeBookItem
import com.studysuit.aiqa.data.MistakeSrsEngine
import com.studysuit.aiqa.data.MistakeStatus
import com.studysuit.aiqa.data.MistakeType
import kotlin.math.roundToInt

internal enum class MistakeBookTab(val label: String) {
  DUE("待复习"),
  ALL("全部错题"),
  DRAFTS("待完善"),
  COMPLETED("已完成"),
  STATS("统计")
}

internal data class MistakeBookStats(
  val totalCount: Int,
  val dueCount: Int,
  val draftCount: Int,
  val completedCount: Int,
  val reviewAttemptCount: Int,
  val recentAccuracyPercent: Int?,
  val weakLabels: List<String>
)

internal data class MistakeBookFilters(
  val subject: String = "",
  val knowledgeTag: String = "",
  val mistakeType: MistakeType? = null
) {
  val hasAny: Boolean
    get() = subject.isNotBlank() || knowledgeTag.isNotBlank() || mistakeType != null
}

internal data class MistakeBookFilterOptions(
  val subjects: List<String>,
  val knowledgeTags: List<String>,
  val mistakeTypes: List<MistakeType>
)

internal data class MistakeCoachSignals(
  val summary: String,
  val topWeakLabels: List<String>
)

internal fun buildMistakeBookStats(
  items: List<MistakeBookItem>,
  now: Long = System.currentTimeMillis()
): MistakeBookStats {
  val dueIds = MistakeSrsEngine.dueMistakes(items, now = now).map { item -> item.id }.toSet()
  val attempts = items
    .flatMap { item -> item.reviewAttempts }
    .sortedByDescending { attempt -> attempt.reviewedAt }
    .take(20)
  val accuracy = attempts.takeIf { it.isNotEmpty() }?.let { recent ->
    (recent.count { attempt -> attempt.isCorrect }.toDouble() / recent.size.toDouble() * 100.0).roundToInt()
  }

  return MistakeBookStats(
    totalCount = items.size,
    dueCount = items.count { item -> item.id in dueIds || item.status == MistakeStatus.DUE },
    draftCount = items.count { item -> item.status == MistakeStatus.DRAFT },
    completedCount = items.count { item -> item.status == MistakeStatus.COMPLETED },
    reviewAttemptCount = items.sumOf { item -> item.reviewAttempts.size },
    recentAccuracyPercent = accuracy,
    weakLabels = buildMistakeCoachSignals(items).topWeakLabels
  )
}

internal fun filterMistakeBookItems(
  items: List<MistakeBookItem>,
  tab: MistakeBookTab,
  searchQuery: String,
  now: Long = System.currentTimeMillis(),
  filters: MistakeBookFilters = MistakeBookFilters()
): List<MistakeBookItem> {
  val dueIds = MistakeSrsEngine.dueMistakes(items, now = now).map { item -> item.id }.toSet()
  return items
    .asSequence()
    .filter { item ->
      when (tab) {
        MistakeBookTab.DUE -> item.id in dueIds || item.status == MistakeStatus.DUE
        MistakeBookTab.DRAFTS -> item.status == MistakeStatus.DRAFT
        MistakeBookTab.COMPLETED -> item.status == MistakeStatus.COMPLETED
        MistakeBookTab.ALL,
        MistakeBookTab.STATS -> true
      }
    }
    .filter { item -> item.matchesMistakeSearch(searchQuery) }
    .filter { item -> item.matchesMistakeFilters(filters) }
    .toList()
}

internal fun buildMistakeBookFilterOptions(items: List<MistakeBookItem>): MistakeBookFilterOptions {
  return MistakeBookFilterOptions(
    subjects = items
      .map { item -> item.subject.trim() }
      .filter(String::isNotBlank)
      .distinct()
      .sorted(),
    knowledgeTags = items
      .flatMap { item -> item.knowledgeTags }
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .sorted(),
    mistakeTypes = MistakeType.values()
      .filter { type -> items.any { item -> item.mistakeType == type } }
  )
}

internal fun buildMistakeCoachSignals(items: List<MistakeBookItem>): MistakeCoachSignals {
  if (items.isEmpty()) {
    return MistakeCoachSignals(summary = "错题本暂无记录", topWeakLabels = emptyList())
  }

  val scores = linkedMapOf<String, Int>()
  val reasons = linkedMapOf<String, Int>()
  items.forEach { item ->
    val wrongAttempts = item.reviewAttempts.count { attempt -> !attempt.isCorrect }
    val weight = 1 + wrongAttempts * 2 + if (item.status == MistakeStatus.DUE) 1 else 0
    val labels = buildList {
      addAll(item.knowledgeTags)
      item.subject.takeIf(String::isNotBlank)?.let(::add)
      item.mistakeType?.let { type -> add(mistakeTypeLabel(type)) }
    }.map(String::trim).filter(String::isNotBlank)

    labels.forEach { label ->
      scores[label] = (scores[label] ?: 0) + weight
    }
    item.mistakeReason.trim().takeIf(String::isNotBlank)?.let { reason ->
      reasons[reason] = (reasons[reason] ?: 0) + maxOf(1, wrongAttempts)
    }
  }

  val topLabels = scores.entries
    .sortedWith(compareByDescending<Map.Entry<String, Int>> { entry -> entry.value }.thenBy { entry -> entry.key })
    .map { entry -> entry.key }
    .take(5)
  val topReasons = reasons.entries
    .sortedWith(compareByDescending<Map.Entry<String, Int>> { entry -> entry.value }.thenBy { entry -> entry.key })
    .map { entry -> entry.key }
    .take(2)

  val summary = buildString {
    append("错题本重点：")
    append(topLabels.take(3).joinToString(separator = "、").ifBlank { "暂无集中标签" })
    if (topReasons.isNotEmpty()) {
      append("；高频错因：")
      append(topReasons.joinToString(separator = "、"))
    }
  }

  return MistakeCoachSignals(summary = summary, topWeakLabels = topLabels)
}

internal fun mistakeStatusLabel(status: MistakeStatus): String {
  return when (status) {
    MistakeStatus.DRAFT -> "待完善"
    MistakeStatus.ACTIVE -> "复习中"
    MistakeStatus.DUE -> "待复习"
    MistakeStatus.COMPLETED -> "已完成"
    MistakeStatus.ARCHIVED -> "已归档"
  }
}

internal fun mistakeTypeLabel(type: MistakeType): String {
  return when (type) {
    MistakeType.CONCEPT_ERROR -> "概念错误"
    MistakeType.CALCULATION_ERROR -> "计算错误"
    MistakeType.READING_ERROR -> "审题错误"
    MistakeType.METHOD_ERROR -> "方法错误"
  }
}

private fun MistakeBookItem.matchesMistakeSearch(searchQuery: String): Boolean {
  val query = searchQuery.trim()
  if (query.isBlank()) {
    return true
  }
  val haystack = buildString {
    append(question)
    append('\n')
    append(correctAnswer)
    append('\n')
    append(explanation)
    append('\n')
    append(mistakeReason)
    append('\n')
    append(subject)
    append('\n')
    append(questionType)
    append('\n')
    append(knowledgeTags.joinToString(separator = " "))
    append('\n')
    append(mistakeStatusLabel(status))
    mistakeType?.let { type ->
      append('\n')
      append(mistakeTypeLabel(type))
    }
  }
  return haystack.contains(query, ignoreCase = true)
}

private fun MistakeBookItem.matchesMistakeFilters(filters: MistakeBookFilters): Boolean {
  if (!filters.hasAny) {
    return true
  }
  val subjectMatches = filters.subject.trim().let { selected ->
    selected.isBlank() || subject.equals(selected, ignoreCase = true)
  }
  val tagMatches = filters.knowledgeTag.trim().let { selected ->
    selected.isBlank() || knowledgeTags.any { tag -> tag.equals(selected, ignoreCase = true) }
  }
  val typeMatches = filters.mistakeType?.let { selected -> mistakeType == selected } ?: true
  return subjectMatches && tagMatches && typeMatches
}
