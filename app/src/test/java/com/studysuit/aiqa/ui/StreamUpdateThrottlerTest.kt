package com.studysuit.aiqa.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUpdateThrottlerTest {
  @Test
  fun shouldPublishImmediatelyThenThrottleUntilIntervalElapses() {
    var now = 0L
    val throttler = StreamUpdateThrottler(
      intervalMillis = 80L,
      nowMillis = { now }
    )

    assertTrue(throttler.shouldPublish())

    now = 30L
    assertFalse(throttler.shouldPublish())

    now = 79L
    assertFalse(throttler.shouldPublish())

    now = 80L
    assertTrue(throttler.shouldPublish())
  }
}
