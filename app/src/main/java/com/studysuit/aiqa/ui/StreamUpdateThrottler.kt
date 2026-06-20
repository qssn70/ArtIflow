package com.studysuit.aiqa.ui

internal class StreamUpdateThrottler(
  private val intervalMillis: Long,
  private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
  private var lastPublishedAt: Long? = null

  fun shouldPublish(): Boolean {
    val now = nowMillis()
    val previous = lastPublishedAt
    if (previous == null || now - previous >= intervalMillis) {
      lastPublishedAt = now
      return true
    }
    return false
  }
}
