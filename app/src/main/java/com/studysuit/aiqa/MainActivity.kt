package com.studysuit.aiqa

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.studysuit.aiqa.data.MistakeReviewNotification
import com.studysuit.aiqa.ui.BENCHMARK_SCENARIO_EXTRA
import com.studysuit.aiqa.ui.StudyChatApp
import com.studysuit.aiqa.ui.theme.StudySuitTheme
import java.lang.reflect.Proxy

class MainActivity : ComponentActivity() {
  private var mistakeReviewOpenRequest by mutableIntStateOf(0)
  private var benchmarkScenarioName by mutableStateOf<String?>(null)
  private var jankStats: Any? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    installDebugJankStats()
    consumeMistakeReviewIntent(intent)
    consumeBenchmarkScenarioIntent(intent)

    setContent {
      StudySuitTheme {
        StudyChatApp(
          mistakeReviewOpenRequest = mistakeReviewOpenRequest,
          benchmarkScenarioName = benchmarkScenarioName
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    consumeMistakeReviewIntent(intent)
    consumeBenchmarkScenarioIntent(intent)
  }

  private fun consumeMistakeReviewIntent(intent: Intent?) {
    if (MistakeReviewNotification.isOpenMistakeReviewIntent(intent)) {
      mistakeReviewOpenRequest += 1
      intent?.setAction(null)
    }
  }

  private fun consumeBenchmarkScenarioIntent(intent: Intent?) {
    benchmarkScenarioName = intent?.getStringExtra(BENCHMARK_SCENARIO_EXTRA)
  }

  private fun installDebugJankStats() {
    if (!BuildConfig.DEBUG) {
      return
    }
    runCatching {
      val jankStatsClass = Class.forName("androidx.metrics.performance.JankStats")
      val listenerClass = Class.forName("androidx.metrics.performance.JankStats\$OnFrameListener")
      val listener = Proxy.newProxyInstance(
        listenerClass.classLoader,
        arrayOf(listenerClass)
      ) { _, _, args ->
        val frameData = args?.firstOrNull()
        if (frameData != null && frameData.booleanProperty("isJank")) {
          Log.d(
            TAG,
            "Jank frame: duration=${frameData.longProperty("frameDurationUiNanos")}ns states=${frameData.anyProperty("states")}"
          )
        }
        null
      }
      jankStats = jankStatsClass
        .getMethod("createAndTrack", android.view.Window::class.java, listenerClass)
        .invoke(null, window, listener)
    }.onFailure { error ->
      Log.d(TAG, "Debug JankStats unavailable: ${error.message}")
    }
  }

  private companion object {
    private const val TAG = "StudySuitJank"
  }
}

private fun Any.booleanProperty(name: String): Boolean {
  return anyProperty(name) as? Boolean ?: false
}

private fun Any.longProperty(name: String): Long {
  return anyProperty(name) as? Long ?: 0L
}

private fun Any.anyProperty(name: String): Any? {
  val capitalized = name.replaceFirstChar { char -> char.uppercaseChar() }
  return runCatching { javaClass.getMethod(name).invoke(this) }
    .recoverCatching { javaClass.getMethod("get$capitalized").invoke(this) }
    .getOrNull()
}
