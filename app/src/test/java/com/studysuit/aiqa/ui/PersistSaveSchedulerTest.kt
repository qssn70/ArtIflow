package com.studysuit.aiqa.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersistSaveSchedulerTest {
  @Test
  fun requestCoalescesRapidUpdatesAndSavesLatestPayload() = runTest {
    val saved = mutableListOf<Int>()
    val scheduler = PersistSaveScheduler<Int>(
      scope = this,
      debounceMillis = 100L,
      save = { payload: Int -> saved += payload }
    )

    scheduler.request(1)
    scheduler.request(2)
    scheduler.request(3)
    advanceTimeBy(99L)

    assertEquals(emptyList<Int>(), saved)

    advanceTimeBy(1L)
    runCurrent()

    assertEquals(listOf(3), saved)
    scheduler.cancel()
  }

  @Test
  fun requestDuringSaveSchedulesOneFollowUpSaveWithLatestPayload() = runTest {
    val saved = mutableListOf<Int>()
    lateinit var scheduler: PersistSaveScheduler<Int>
    scheduler = PersistSaveScheduler(
      scope = this,
      debounceMillis = 100L,
      save = { payload ->
        saved += payload
        if (payload == 1) {
          scheduler.request(2)
          scheduler.request(3)
        }
      }
    )

    scheduler.request(1)
    advanceTimeBy(100L)
    runCurrent()
    advanceTimeBy(100L)
    runCurrent()

    assertEquals(listOf(1, 3), saved)
    scheduler.cancel()
  }
}
