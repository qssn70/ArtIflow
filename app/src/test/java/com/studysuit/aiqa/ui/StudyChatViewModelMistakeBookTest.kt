package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.MistakeRecognitionDraft
import com.studysuit.aiqa.data.MistakeRecognitionStatus
import com.studysuit.aiqa.data.MistakeAnswerJudgement
import com.studysuit.aiqa.data.MistakeReviewJudgementSource
import com.studysuit.aiqa.data.MistakeStatus
import com.studysuit.aiqa.data.MistakeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyChatViewModelMistakeBookTest {

  @Test
  fun confirmMistakeDraftCreatesDueMistakeItem() {
    val viewModel = StudyChatViewModel()
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "2",
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )

    viewModel.saveMistakeRecognitionDraft(draft)
    viewModel.confirmMistakeDraft("draft-1")

    val state = viewModel.uiState.value
    assertEquals(WorkspacePage.MISTAKES, state.activePage)
    assertEquals(1, state.mistakeItems.size)
    assertEquals(MistakeStatus.DUE, state.mistakeItems.first().status)
    assertEquals("draft-1", state.mistakeItems.first().recognitionDraftId)
  }

  @Test
  fun confirmMistakeDraftUpdatesExistingItemForSameDraft() {
    val viewModel = StudyChatViewModel()
    val incompleteDraft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "",
      status = MistakeRecognitionStatus.OCR_READY,
      createdAt = 1L,
      updatedAt = 1L
    )
    val completedDraft = incompleteDraft.copy(
      correctAnswer = "2",
      status = MistakeRecognitionStatus.AI_READY,
      updatedAt = 2L
    )

    viewModel.saveMistakeRecognitionDraft(incompleteDraft)
    viewModel.confirmMistakeDraft("draft-1")
    val firstItemId = viewModel.uiState.value.mistakeItems.first().id

    viewModel.confirmMistakeDraft(completedDraft)

    val state = viewModel.uiState.value
    assertEquals(1, state.mistakeItems.size)
    assertEquals(firstItemId, state.mistakeItems.first().id)
    assertEquals(MistakeStatus.DUE, state.mistakeItems.first().status)
    assertEquals("2", state.mistakeItems.first().correctAnswer)
  }

  @Test
  fun updateMistakeSearchQueryStoresQueryInState() {
    val viewModel = StudyChatViewModel()

    viewModel.updateMistakeSearchQuery("函数")

    assertEquals("函数", viewModel.uiState.value.mistakeSearchQuery)
  }

  @Test
  fun recordMistakeReviewUpdatesItemInState() {
    val viewModel = StudyChatViewModel()
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "2",
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )
    viewModel.saveMistakeRecognitionDraft(draft)
    viewModel.confirmMistakeDraft("draft-1")
    val itemId = viewModel.uiState.value.mistakeItems.first().id

    viewModel.recordMistakeReview(itemId = itemId, isCorrect = false, userAnswer = "1")

    val reviewed = viewModel.uiState.value.mistakeItems.first()
    assertEquals(1, reviewed.reviewAttempts.size)
    assertEquals(false, reviewed.reviewAttempts.first().isCorrect)
    assertTrue(reviewed.reviewState.nextReviewAt!! > reviewed.createdAt)
  }

  @Test
  fun confirmMistakeAnswerJudgementRecordsUserConfirmedModelAttempt() {
    val viewModel = StudyChatViewModel()
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "2",
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )
    viewModel.saveMistakeRecognitionDraft(draft)
    viewModel.confirmMistakeDraft("draft-1")
    val itemId = viewModel.uiState.value.mistakeItems.first().id

    viewModel.applyMistakeAnswerJudgement(
      itemId = itemId,
      userAnswer = "2",
      judgement = MistakeAnswerJudgement(
        isCorrect = true,
        confidence = 0.95,
        reason = "与标准答案一致",
        suggestedScore = 100
      )
    )
    viewModel.confirmMistakeAnswerJudgement(itemId = itemId, finalIsCorrect = true)

    val attempt = viewModel.uiState.value.mistakeItems.first().reviewAttempts.single()
    assertEquals(true, attempt.isCorrect)
    assertEquals(MistakeReviewJudgementSource.USER_CONFIRMED_MODEL, attempt.judgementSource)
    assertTrue(attempt.modelSuggestion.contains("与标准答案一致"))
    assertEquals(null, viewModel.uiState.value.activeMistakeReviewSuggestion)
  }

  @Test
  fun addMistakeToAnkiCreatesReviewCardFromMistake() {
    val viewModel = StudyChatViewModel()
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 f(x)=x^2，求 f'(x)。",
      subject = "数学",
      questionType = "解答题",
      knowledgeTags = listOf("导数与应用"),
      correctAnswer = "f'(x)=2x",
      explanation = "幂函数求导公式：x^n 的导数是 nx^(n-1)。",
      mistakeReason = "把平方函数导数记成常数。",
      mistakeType = MistakeType.CONCEPT_ERROR,
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )
    viewModel.saveMistakeRecognitionDraft(draft)
    viewModel.confirmMistakeDraft("draft-1")
    val itemId = viewModel.uiState.value.mistakeItems.first().id

    viewModel.addMistakeToAnki(itemId)

    val card = viewModel.uiState.value.ankiCards.single()
    assertTrue(card.front.contains("f(x)=x^2"))
    assertTrue(card.back.contains("正确答案"))
    assertTrue(card.back.contains("f'(x)=2x"))
    assertTrue(card.back.contains("错因"))
    assertTrue(card.tags.contains("导数与应用"))
    assertTrue(card.source.contains("错题本"))
  }
}
