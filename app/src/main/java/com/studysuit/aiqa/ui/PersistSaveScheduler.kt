package com.studysuit.aiqa.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class PersistSaveScheduler<T>(
  scope: CoroutineScope,
  private val debounceMillis: Long,
  private val save: suspend (T) -> Unit
) {
  private val requests = Channel<T>(Channel.CONFLATED)
  private val job: Job = scope.launch {
    var latest = requests.receiveCatching().getOrNull() ?: return@launch
    while (isActive) {
      latest = receiveLatestAfterDebounce(latest) ?: return@launch
      save(latest)
      latest = requests.receiveCatching().getOrNull() ?: return@launch
    }
  }

  fun request(payload: T) {
    requests.trySend(payload)
  }

  fun cancel() {
    job.cancel()
    requests.close()
  }

  private suspend fun receiveLatestAfterDebounce(initial: T): T? {
    var latest = initial
    while (true) {
      val next = withTimeoutOrNull(debounceMillis) {
        requests.receiveCatching()
      } ?: return latest
      latest = next.getOrNull() ?: return null
    }
  }
}
