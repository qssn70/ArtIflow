package com.studysuit.aiqa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MistakeReviewSchedulerTest {

  @Test
  fun nextDueReviewAtReturnsEarliestReviewTimeForActiveItems() {
    val first = MistakeBookItem.create(
      id = "mistake-1",
      question = "q1",
      correctAnswer = "a1",
      createdAt = 1_000L
    ).copy(
      status = MistakeStatus.ACTIVE,
      reviewState = MistakeReviewState(nextReviewAt = 5_000L)
    )
    val second = MistakeBookItem.create(
      id = "mistake-2",
      question = "q2",
      correctAnswer = "a2",
      createdAt = 2_000L
    ).copy(
      status = MistakeStatus.ACTIVE,
      reviewState = MistakeReviewState(nextReviewAt = 3_000L)
    )
    val completed = MistakeBookItem.create(
      id = "mistake-3",
      question = "q3",
      correctAnswer = "a3",
      createdAt = 0L
    ).copy(
      status = MistakeStatus.COMPLETED,
      reviewState = MistakeReviewState(nextReviewAt = 1L)
    )

    assertEquals(3_000L, MistakeReviewScheduler.nextDueReviewAt(listOf(first, second, completed), now = 1_000L))
  }

  @Test
  fun nextDueReviewAtReturnsNowWhenItemAlreadyDue() {
    val due = MistakeBookItem.create(
      id = "mistake-1",
      question = "q",
      correctAnswer = "a",
      createdAt = 1_000L
    )

    assertEquals(2_000L, MistakeReviewScheduler.nextDueReviewAt(listOf(due), now = 2_000L))
  }

  @Test
  fun nextDueReviewAtIgnoresDraftsAndCompletedItems() {
    val draft = MistakeBookItem.create(
      id = "mistake-1",
      question = "q",
      correctAnswer = "",
      createdAt = 1_000L
    )
    val completed = MistakeBookItem.create(
      id = "mistake-2",
      question = "q2",
      correctAnswer = "a2",
      createdAt = 2_000L
    ).copy(status = MistakeStatus.COMPLETED)

    assertNull(MistakeReviewScheduler.nextDueReviewAt(listOf(draft, completed), now = 2_000L))
  }

  @Test
  fun scheduleDecisionSchedulesEarliestReviewWithNonNegativeDelay() {
    val overdue = MistakeBookItem.create(
      id = "mistake-1",
      question = "q",
      correctAnswer = "a",
      createdAt = 1_000L
    )
    val future = MistakeBookItem.create(
      id = "mistake-2",
      question = "q2",
      correctAnswer = "a2",
      createdAt = 1_000L
    ).copy(
      status = MistakeStatus.ACTIVE,
      reviewState = MistakeReviewState(nextReviewAt = 5_000L)
    )

    assertEquals(
      MistakeReviewScheduleDecision.Schedule(delayMillis = 0L, dueAt = 2_000L),
      MistakeReviewScheduler.scheduleDecision(listOf(overdue, future), now = 2_000L)
    )
  }

  @Test
  fun scheduleDecisionCancelsWhenNoReviewableItemsRemain() {
    val draft = MistakeBookItem.create(
      id = "mistake-1",
      question = "q",
      correctAnswer = "",
      createdAt = 1_000L
    )
    val completed = MistakeBookItem.create(
      id = "mistake-2",
      question = "q2",
      correctAnswer = "a2",
      createdAt = 2_000L
    ).copy(status = MistakeStatus.COMPLETED)

    assertEquals(
      MistakeReviewScheduleDecision.Cancel,
      MistakeReviewScheduler.scheduleDecision(listOf(draft, completed), now = 2_000L)
    )
  }

  @Test
  fun scheduleDecisionChangesWhenItemsAreAddedDeletedOrCompleted() {
    val early = MistakeBookItem.create(
      id = "mistake-1",
      question = "q1",
      correctAnswer = "a1",
      createdAt = 1_000L
    ).copy(
      status = MistakeStatus.ACTIVE,
      reviewState = MistakeReviewState(nextReviewAt = 3_000L)
    )
    val later = MistakeBookItem.create(
      id = "mistake-2",
      question = "q2",
      correctAnswer = "a2",
      createdAt = 1_000L
    ).copy(
      status = MistakeStatus.ACTIVE,
      reviewState = MistakeReviewState(nextReviewAt = 9_000L)
    )

    assertEquals(
      MistakeReviewScheduleDecision.Schedule(delayMillis = 7_000L, dueAt = 9_000L),
      MistakeReviewScheduler.scheduleDecision(listOf(later), now = 2_000L)
    )
    assertEquals(
      MistakeReviewScheduleDecision.Schedule(delayMillis = 1_000L, dueAt = 3_000L),
      MistakeReviewScheduler.scheduleDecision(listOf(later, early), now = 2_000L)
    )
    assertEquals(
      MistakeReviewScheduleDecision.Schedule(delayMillis = 7_000L, dueAt = 9_000L),
      MistakeReviewScheduler.scheduleDecision(listOf(later, early.copy(status = MistakeStatus.COMPLETED)), now = 2_000L)
    )
    assertEquals(
      MistakeReviewScheduleDecision.Cancel,
      MistakeReviewScheduler.scheduleDecision(emptyList(), now = 2_000L)
    )
  }
}
