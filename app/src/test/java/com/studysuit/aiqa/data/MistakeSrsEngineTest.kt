package com.studysuit.aiqa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeSrsEngineTest {

  @Test
  fun newActiveMistakeIsDueImmediately() {
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "已知 x + 1 = 3，求 x。",
      correctAnswer = "2",
      createdAt = 1_000L
    )

    assertEquals(MistakeStatus.DUE, item.status)
    assertEquals(1_000L, item.reviewState.nextReviewAt)
    assertEquals(listOf("mistake-1"), MistakeSrsEngine.dueMistakes(listOf(item), now = 1_000L).map { it.id })
  }

  @Test
  fun wrongAnswerResetsStreakAndSchedulesTenMinuteReview() {
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "q",
      correctAnswer = "a",
      createdAt = 1_000L
    ).copy(
      reviewState = MistakeReviewState(correctStreak = 2, easeFactor = 2.5)
    )

    val reviewed = MistakeSrsEngine.recordReview(
      item = item,
      isCorrect = false,
      reviewedAt = 2_000L,
      userAnswer = "wrong"
    )

    assertEquals(MistakeStatus.ACTIVE, reviewed.status)
    assertEquals(1, reviewed.reviewState.reviewCount)
    assertEquals(0, reviewed.reviewState.correctStreak)
    assertEquals(2_000L, reviewed.reviewState.lastReviewedAt)
    assertEquals(2_000L + 10L * 60L * 1000L, reviewed.reviewState.nextReviewAt)
    assertTrue(reviewed.reviewState.easeFactor < 2.5)
    assertTrue(reviewed.reviewState.easeFactor >= MistakeSrsEngine.MIN_EASE_FACTOR)
    assertEquals(false, reviewed.reviewAttempts.single().isCorrect)
    assertEquals("wrong", reviewed.reviewAttempts.single().userAnswer)
  }

  @Test
  fun correctReviewsUseOneDaySevenDayThenCompleteWhenSpacedByAWeek() {
    val day = 24L * 60L * 60L * 1000L
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "q",
      correctAnswer = "a",
      createdAt = 0L
    )

    val first = MistakeSrsEngine.recordReview(item, isCorrect = true, reviewedAt = 0L)
    val second = MistakeSrsEngine.recordReview(first, isCorrect = true, reviewedAt = day)
    val third = MistakeSrsEngine.recordReview(second, isCorrect = true, reviewedAt = 8L * day)

    assertEquals(1, first.reviewState.correctStreak)
    assertEquals(day, first.reviewState.currentIntervalMillis)
    assertEquals(day, first.reviewState.nextReviewAt)

    assertEquals(2, second.reviewState.correctStreak)
    assertEquals(7L * day, second.reviewState.currentIntervalMillis)
    assertEquals(8L * day, second.reviewState.nextReviewAt)

    assertEquals(MistakeStatus.COMPLETED, third.status)
    assertEquals(3, third.reviewState.correctStreak)
    assertEquals(3, third.reviewState.reviewCount)
    assertEquals(8L * day, third.reviewState.completedAt)
    assertNull(third.reviewState.nextReviewAt)
  }

  @Test
  fun thirdCorrectTooSoonStaysInReviewQueue() {
    val day = 24L * 60L * 60L * 1000L
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "q",
      correctAnswer = "a",
      createdAt = 0L
    )

    val first = MistakeSrsEngine.recordReview(item, isCorrect = true, reviewedAt = 0L)
    val second = MistakeSrsEngine.recordReview(first, isCorrect = true, reviewedAt = day)
    val third = MistakeSrsEngine.recordReview(second, isCorrect = true, reviewedAt = 4L * day)

    assertEquals(MistakeStatus.ACTIVE, third.status)
    assertEquals(3, third.reviewState.correctStreak)
    assertTrue(third.reviewState.nextReviewAt!! > 4L * day)
    assertNull(third.reviewState.completedAt)
  }

  @Test
  fun wrongAnswerAfterCorrectStreakStartsTheStreakOver() {
    val day = 24L * 60L * 60L * 1000L
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "q",
      correctAnswer = "a",
      createdAt = 0L
    )

    val first = MistakeSrsEngine.recordReview(item, isCorrect = true, reviewedAt = 0L)
    val wrong = MistakeSrsEngine.recordReview(first, isCorrect = false, reviewedAt = day)
    val correctAgain = MistakeSrsEngine.recordReview(wrong, isCorrect = true, reviewedAt = day + 10L * 60L * 1000L)

    assertEquals(0, wrong.reviewState.correctStreak)
    assertEquals(1, correctAgain.reviewState.correctStreak)
    assertEquals(day + 10L * 60L * 1000L + day, correctAgain.reviewState.nextReviewAt)
  }
}
