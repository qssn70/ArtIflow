package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.MistakeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MistakeBookConversionTest {

  @Test
  fun savedQuestionToMistakeBookItemKeepsArchiveFieldsAndStartsDue() {
    val saved = SavedQuestion(
      id = "saved-1",
      sourceMessageId = "msg-1",
      question = "已知二次函数，求最值。",
      answer = "先配方，最值在顶点。",
      sourceTime = "10:00",
      savedAt = 1_000L,
      knowledgeTags = listOf("二次函数", "函数与图像"),
      subject = "数学",
      questionType = "解答题",
      analysisSummary = "容易漏掉对称轴",
      imagePreviewList = listOf(byteArrayOf(1, 2, 3))
    )

    val item = savedQuestionToMistakeBookItem(
      saved = saved,
      id = "mistake-1",
      imageRefs = listOf("mistake_images/mistake-1-0.jpg"),
      now = 2_000L
    )

    assertEquals("mistake-1", item.id)
    assertEquals("saved-1", item.sourceSavedQuestionId)
    assertEquals(saved.question, item.question)
    assertEquals(saved.answer, item.correctAnswer)
    assertEquals(saved.knowledgeTags, item.knowledgeTags)
    assertEquals("数学", item.subject)
    assertEquals("解答题", item.questionType)
    assertEquals("容易漏掉对称轴", item.explanation)
    assertEquals(listOf("mistake_images/mistake-1-0.jpg"), item.imageRefs)
    assertEquals(MistakeStatus.DUE, item.status)
    assertEquals(2_000L, item.reviewState.nextReviewAt)
  }
}
