package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.MistakeBookItem
import com.studysuit.aiqa.data.MistakeReviewJudgementSource
import com.studysuit.aiqa.data.MistakeSrsEngine
import com.studysuit.aiqa.data.MistakeStatus
import com.studysuit.aiqa.data.MistakeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeBookUiSupportTest {

  @Test
  fun buildMistakeBookStatsCountsDueDraftCompletedAndRecentAccuracy() {
    val now = 10_000L
    val due = MistakeBookItem.create(
      id = "mistake-1",
      question = "函数图像题",
      correctAnswer = "看对称轴",
      knowledgeTags = listOf("函数与图像"),
      createdAt = now - 100L
    )
    val completed = MistakeSrsEngine.recordReview(
      item = MistakeSrsEngine.recordReview(
        item = MistakeSrsEngine.recordReview(
          item = MistakeBookItem.create(
            id = "mistake-2",
            question = "方程题",
            correctAnswer = "配方",
            createdAt = 0L
          ),
          isCorrect = true,
          reviewedAt = 0L
        ),
        isCorrect = true,
        reviewedAt = MistakeSrsEngine.DAY_MILLIS
      ),
      isCorrect = true,
      reviewedAt = 8L * MistakeSrsEngine.DAY_MILLIS
    )
    val draft = MistakeBookItem.create(
      id = "mistake-3",
      question = "只识别题干",
      correctAnswer = "",
      createdAt = now
    )

    val stats = buildMistakeBookStats(listOf(due, completed, draft), now = now)

    assertEquals(3, stats.totalCount)
    assertEquals(1, stats.dueCount)
    assertEquals(1, stats.draftCount)
    assertEquals(1, stats.completedCount)
    assertEquals(3, stats.reviewAttemptCount)
    assertEquals(100, stats.recentAccuracyPercent)
    assertTrue(stats.weakLabels.any { label -> label.contains("函数与图像") })
  }

  @Test
  fun filterMistakeBookItemsUsesTabAndSearchQuery() {
    val active = MistakeBookItem.create(
      id = "mistake-1",
      question = "函数图像最值",
      correctAnswer = "看顶点",
      knowledgeTags = listOf("函数与图像"),
      mistakeType = MistakeType.CONCEPT_ERROR,
      createdAt = 1L
    ).copy(status = MistakeStatus.ACTIVE)
    val draft = MistakeBookItem.create(
      id = "mistake-2",
      question = "概率题待补答案",
      correctAnswer = "",
      createdAt = 2L
    )

    val activeResult = filterMistakeBookItems(
      items = listOf(active, draft),
      tab = MistakeBookTab.ALL,
      searchQuery = "图像"
    )
    val draftResult = filterMistakeBookItems(
      items = listOf(active, draft),
      tab = MistakeBookTab.DRAFTS,
      searchQuery = ""
    )

    assertEquals(listOf("mistake-1"), activeResult.map { item -> item.id })
    assertEquals(listOf("mistake-2"), draftResult.map { item -> item.id })
  }

  @Test
  fun filterMistakeBookItemsUsesExplicitSubjectTagAndMistakeTypeFilters() {
    val mathConcept = MistakeBookItem.create(
      id = "mistake-1",
      question = "函数图像最值",
      correctAnswer = "看顶点",
      subject = "数学",
      knowledgeTags = listOf("函数与图像"),
      mistakeType = MistakeType.CONCEPT_ERROR,
      createdAt = 1L
    )
    val physicsMethod = MistakeBookItem.create(
      id = "mistake-2",
      question = "受力分析",
      correctAnswer = "先画受力图",
      subject = "物理",
      knowledgeTags = listOf("力学"),
      mistakeType = MistakeType.METHOD_ERROR,
      createdAt = 2L
    )

    val result = filterMistakeBookItems(
      items = listOf(mathConcept, physicsMethod),
      tab = MistakeBookTab.ALL,
      searchQuery = "",
      filters = MistakeBookFilters(
        subject = "数学",
        knowledgeTag = "函数与图像",
        mistakeType = MistakeType.CONCEPT_ERROR
      )
    )

    assertEquals(listOf("mistake-1"), result.map { item -> item.id })
  }

  @Test
  fun buildMistakeBookFilterOptionsCollectsAvailableSubjectsTagsAndTypes() {
    val items = listOf(
      MistakeBookItem.create(
        id = "mistake-1",
        question = "函数图像最值",
        correctAnswer = "看顶点",
        subject = "数学",
        knowledgeTags = listOf("函数与图像", "二次函数"),
        mistakeType = MistakeType.CONCEPT_ERROR,
        createdAt = 1L
      ),
      MistakeBookItem.create(
        id = "mistake-2",
        question = "受力分析",
        correctAnswer = "先画受力图",
        subject = "物理",
        knowledgeTags = listOf("力学"),
        mistakeType = MistakeType.METHOD_ERROR,
        createdAt = 2L
      )
    )

    val options = buildMistakeBookFilterOptions(items)

    assertEquals(listOf("数学", "物理"), options.subjects)
    assertEquals(listOf("二次函数", "函数与图像", "力学"), options.knowledgeTags)
    assertEquals(listOf(MistakeType.CONCEPT_ERROR, MistakeType.METHOD_ERROR), options.mistakeTypes)
  }

  @Test
  fun mistakeReviewCoachSignalsPrioritizeWrongAttemptsAndReasons() {
    val item = MistakeSrsEngine.recordReview(
      item = MistakeBookItem.create(
        id = "mistake-1",
        question = "函数图像题",
        correctAnswer = "看单调性",
        knowledgeTags = listOf("函数与图像"),
        mistakeReason = "忽略定义域",
        mistakeType = MistakeType.READING_ERROR,
        createdAt = 1L
      ),
      isCorrect = false,
      reviewedAt = 2L,
      userAnswer = "直接代入",
      judgementSource = MistakeReviewJudgementSource.USER
    )

    val signals = buildMistakeCoachSignals(listOf(item))

    assertTrue(signals.summary.contains("函数与图像"))
    assertTrue(signals.summary.contains("审题错误") || signals.summary.contains("忽略定义域"))
    assertEquals("函数与图像", signals.topWeakLabels.first())
  }
}
