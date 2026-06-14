package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.MistakeRecognitionDraft
import com.studysuit.aiqa.data.MistakeRecognitionStatus
import com.studysuit.aiqa.data.MistakeReviewJudgementSource
import com.studysuit.aiqa.data.MistakeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeBookStateReducersTest {

  @Test
  fun saveMistakeRecognitionDraftStateStoresDraftAndOpensMistakePage() {
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "二次函数最小值",
      correctAnswer = "-1",
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )

    val updated = saveMistakeRecognitionDraftState(ChatUiState(activePage = WorkspacePage.CHAT), draft)

    assertEquals(WorkspacePage.MISTAKES, updated.activePage)
    assertEquals("draft-1", updated.activeMistakeDraftId)
    assertEquals(1, updated.mistakeRecognitionDrafts.size)
    assertEquals("已生成错题草稿", updated.toastMessage)
  }

  @Test
  fun createMistakeBookItemFromDraftKeepsIncompleteDraftOutOfReviewQueue() {
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "只识别出题干",
      correctAnswer = "",
      status = MistakeRecognitionStatus.OCR_READY,
      createdAt = 1L,
      updatedAt = 1L
    )

    val item = createMistakeBookItemFromDraft(draft = draft, itemId = "mistake-1", now = 10L)

    assertEquals(MistakeStatus.DRAFT, item.status)
    assertEquals(null, item.reviewState.nextReviewAt)
    assertFalse(item.isReadyForReview)
  }

  @Test
  fun upsertMistakeItemStateAddsReadyItemToDueQueue() {
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "2",
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )
    val item = createMistakeBookItemFromDraft(draft = draft, itemId = "mistake-1", now = 10L)

    val updated = upsertMistakeItemState(ChatUiState(), item, toastMessage = "已加入错题本")

    assertEquals(1, updated.mistakeItems.size)
    assertEquals(MistakeStatus.DUE, updated.mistakeItems.first().status)
    assertEquals(10L, updated.mistakeItems.first().reviewState.nextReviewAt)
    assertEquals("已加入错题本", updated.toastMessage)
  }

  @Test
  fun recordMistakeReviewStateUpdatesCorrectnessAndCompletion() {
    val item = createMistakeBookItemFromDraft(
      draft = MistakeRecognitionDraft(
        id = "draft-1",
        question = "已知 x+1=3，求 x。",
        correctAnswer = "2",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      ),
      itemId = "mistake-1",
      now = 1L
    )
    val base = ChatUiState(mistakeItems = listOf(item))

    val updated = recordMistakeReviewState(
      current = base,
      itemId = "mistake-1",
      isCorrect = false,
      reviewedAt = 1_000L,
      userAnswer = "1",
      judgementSource = MistakeReviewJudgementSource.USER,
      modelSuggestion = "",
      note = ""
    )

    val reviewed = updated.mistakeItems.first()
    assertEquals(1, reviewed.reviewAttempts.size)
    assertEquals(false, reviewed.reviewAttempts.first().isCorrect)
    assertEquals(0, reviewed.reviewState.correctStreak)
    assertTrue(reviewed.reviewState.nextReviewAt!! > 1_000L)
    assertEquals("已记录：做错，10分钟后再看", updated.toastMessage)
  }

  @Test
  fun setMistakeReviewSuggestionStateStoresModelJudgement() {
    val item = createMistakeBookItemFromDraft(
      draft = MistakeRecognitionDraft(
        id = "draft-1",
        question = "已知 x+1=3，求 x。",
        correctAnswer = "2",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      ),
      itemId = "mistake-1",
      now = 1L
    )
    val suggestion = MistakeReviewSuggestion(
      itemId = "mistake-1",
      userAnswer = "2",
      isCorrect = true,
      confidence = 0.92,
      reason = "与标准答案一致",
      suggestedScore = 100,
      answerOcrText = ""
    )

    val updated = setMistakeReviewSuggestionState(ChatUiState(mistakeItems = listOf(item)), suggestion)

    assertEquals(suggestion, updated.activeMistakeReviewSuggestion)
    assertEquals("mistake-1", updated.activeMistakeReviewId)
    assertEquals("模型已给出判题建议", updated.toastMessage)
  }

  @Test
  fun confirmMistakeReviewSuggestionStateRecordsUserConfirmedModelResult() {
    val item = createMistakeBookItemFromDraft(
      draft = MistakeRecognitionDraft(
        id = "draft-1",
        question = "已知 x+1=3，求 x。",
        correctAnswer = "2",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      ),
      itemId = "mistake-1",
      now = 1L
    )
    val suggestion = MistakeReviewSuggestion(
      itemId = "mistake-1",
      userAnswer = "2",
      isCorrect = true,
      confidence = 0.92,
      reason = "与标准答案一致",
      suggestedScore = 100,
      answerOcrText = ""
    )
    val base = ChatUiState(
      mistakeItems = listOf(item),
      activeMistakeReviewSuggestion = suggestion
    )

    val updated = confirmMistakeReviewSuggestionState(
      current = base,
      itemId = "mistake-1",
      finalIsCorrect = true,
      reviewedAt = 1_000L
    )

    val attempt = updated.mistakeItems.first().reviewAttempts.single()
    assertEquals(true, attempt.isCorrect)
    assertEquals(MistakeReviewJudgementSource.USER_CONFIRMED_MODEL, attempt.judgementSource)
    assertTrue(attempt.modelSuggestion.contains("与标准答案一致"))
    assertEquals(null, updated.activeMistakeReviewSuggestion)
  }

  @Test
  fun addSavedQuestionToMistakeBookStateConvertsArchiveItem() {
    val saved = SavedQuestion(
      id = "saved-1",
      sourceMessageId = "msg-1",
      question = "函数题",
      answer = "答案",
      sourceTime = "10:00",
      savedAt = 1L,
      knowledgeTags = listOf("函数")
    )

    val updated = addSavedQuestionToMistakeBookState(
      current = ChatUiState(savedQuestions = listOf(saved)),
      savedQuestionId = "saved-1",
      itemId = "mistake-1",
      imageRefs = listOf("mistake_images/one.jpg"),
      now = 100L
    )

    assertEquals(1, updated.mistakeItems.size)
    assertEquals("saved-1", updated.mistakeItems.first().sourceSavedQuestionId)
    assertEquals(listOf("mistake_images/one.jpg"), updated.mistakeItems.first().imageRefs)
    assertEquals(WorkspacePage.MISTAKES, updated.activePage)
  }
}
