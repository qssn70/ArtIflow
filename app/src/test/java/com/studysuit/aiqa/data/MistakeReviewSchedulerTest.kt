package com.studysuit.aiqa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

  @Test
  fun repositorySelectionUsesObsidianRepositoryWhenVaultIsAuthorized() {
    val local = RecordingMistakeBookRepository()
    val obsidian = RecordingMistakeBookRepository()

    val selection = selectMistakeBookRepository(
      localRepository = local,
      storageLocation = MistakeBookStorageLocation.OBSIDIAN,
      obsidianVaultTreeUri = "content://vault",
      obsidianMistakeFolder = "School/错题",
      createObsidianRepository = { vaultUri, folder ->
        assertEquals("content://vault", vaultUri)
        assertEquals("School/错题", folder)
        obsidian
      }
    ).getOrThrow()

    assertSame(obsidian, selection.repository)
    assertEquals("obsidian:content://vault:School/错题", selection.key)
    assertEquals(false, selection.isPendingObsidianAuthorization)
  }

  @Test
  fun repositorySelectionUsesPendingLocalOnlyWhenObsidianVaultIsMissing() {
    val local = RecordingMistakeBookRepository()

    val selection = selectMistakeBookRepository(
      localRepository = local,
      storageLocation = MistakeBookStorageLocation.OBSIDIAN,
      obsidianVaultTreeUri = " ",
      obsidianMistakeFolder = DEFAULT_OBSIDIAN_MISTAKE_FOLDER,
      createObsidianRepository = { _, _ -> error("vault should not be opened before authorization") }
    ).getOrThrow()

    assertSame(local, selection.repository)
    assertEquals("obsidian-pending-local", selection.key)
    assertEquals(true, selection.isPendingObsidianAuthorization)
  }

  @Test
  fun repositorySelectionFailsWhenAuthorizedObsidianVaultCannotOpen() {
    val selection = selectMistakeBookRepository(
      localRepository = RecordingMistakeBookRepository(),
      storageLocation = MistakeBookStorageLocation.OBSIDIAN,
      obsidianVaultTreeUri = "content://bad-vault",
      obsidianMistakeFolder = DEFAULT_OBSIDIAN_MISTAKE_FOLDER,
      createObsidianRepository = { _, _ -> error("无法访问 Obsidian 仓库目录") }
    )

    assertTrue(selection.isFailure)
  }

  private class RecordingMistakeBookRepository : MistakeBookRepository {
    override fun load(): List<MistakeBookItem> = emptyList()

    override fun save(items: List<MistakeBookItem>): Result<Unit> = Result.success(Unit)

    override fun upsert(item: MistakeBookItem): Result<List<MistakeBookItem>> = Result.success(listOf(item))

    override fun delete(itemId: String): Result<List<MistakeBookItem>> = Result.success(emptyList())

    override fun saveImageBytes(bytes: ByteArray, fileNameHint: String): Result<String> {
      return Result.success("assets/$fileNameHint")
    }

    override fun loadImageBytes(ref: String): Result<ByteArray> = Result.success(ByteArray(0))
  }
}
