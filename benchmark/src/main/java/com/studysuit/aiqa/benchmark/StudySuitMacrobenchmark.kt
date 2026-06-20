package com.studysuit.aiqa.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudySuitMacrobenchmark {
  @get:Rule
  val benchmarkRule = MacrobenchmarkRule()

  @Test
  fun coldStartup() {
    benchmarkRule.measureRepeated(
      packageName = PACKAGE_NAME,
      metrics = listOf(StartupTimingMetric()),
      compilationMode = CompilationMode.Partial(),
      startupMode = StartupMode.COLD,
      iterations = 5,
      setupBlock = { pressHome() }
    ) {
      startActivityAndWait()
      device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), STARTUP_TIMEOUT_MS)
    }
  }

  @Test
  fun primaryScrollFrames() {
    benchmarkRule.measureRepeated(
      packageName = PACKAGE_NAME,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = CompilationMode.Partial(),
      startupMode = StartupMode.WARM,
      iterations = 5,
      setupBlock = {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), STARTUP_TIMEOUT_MS)
      }
    ) {
      repeat(4) {
        device.swipe(
          device.displayWidth / 2,
          (device.displayHeight * 0.82f).toInt(),
          device.displayWidth / 2,
          (device.displayHeight * 0.28f).toInt(),
          12
        )
        device.waitForIdle()
      }
    }
  }

  @Test
  fun chatOneHundredMessagesScrollFrames() {
    benchmarkRule.measureRepeated(
      packageName = PACKAGE_NAME,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = CompilationMode.Partial(),
      startupMode = StartupMode.WARM,
      iterations = 5,
      setupBlock = { startScenarioAndWait(CHAT_100_SCROLL, "第 100 条聊天消息") }
    ) {
      repeat(5) {
        swipeUp()
      }
      repeat(5) {
        swipeDown()
      }
    }
  }

  @Test
  fun mistakeTwoHundredItemsSearchFrames() {
    benchmarkRule.measureRepeated(
      packageName = PACKAGE_NAME,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = CompilationMode.Partial(),
      startupMode = StartupMode.WARM,
      iterations = 5,
      setupBlock = { startScenarioAndWait(MISTAKE_200_SEARCH, "第 200 道错题") }
    ) {
      repeat(4) {
        swipeUp()
      }
      device.findObject(By.textContains("搜索题干"))?.apply {
        click()
        setText("错题筛选-199")
      }
      device.wait(Until.hasObject(By.textContains("第 199 道错题")), STARTUP_TIMEOUT_MS)
    }
  }

  @Test
  fun threeImagePreviewFrames() {
    benchmarkRule.measureRepeated(
      packageName = PACKAGE_NAME,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = CompilationMode.Partial(),
      startupMode = StartupMode.WARM,
      iterations = 5,
      setupBlock = { startScenarioAndWait(THREE_IMAGE_PREVIEW, "三图搜题样本") }
    ) {
      device.wait(Until.hasObject(By.textContains("共 3 张")), STARTUP_TIMEOUT_MS)
      repeat(3) {
        swipeUp()
        swipeDown()
      }
    }
  }

  @Test
  fun formulaImageHeavyOpenFrames() {
    benchmarkRule.measureRepeated(
      packageName = PACKAGE_NAME,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = CompilationMode.Partial(),
      startupMode = StartupMode.WARM,
      iterations = 5,
      setupBlock = { startScenarioAndWait(FORMULA_IMAGE_HEAVY, "含图片与公式消息打开样本") }
    ) {
      repeat(4) {
        swipeUp()
        device.waitForIdle()
        swipeDown()
        device.waitForIdle()
      }
    }
  }

  @Test
  fun streamingTwoMinutesFrames() {
    benchmarkRule.measureRepeated(
      packageName = PACKAGE_NAME,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = CompilationMode.Partial(),
      startupMode = StartupMode.WARM,
      iterations = 1,
      setupBlock = { startScenarioAndWait(STREAM_2_MINUTES, "流式回答性能样本") }
    ) {
      device.wait(Until.hasObject(By.textContains("第 100 步")), STREAM_TIMEOUT_MS)
      device.wait(Until.hasObject(By.textContains("第 1200 步")), STREAM_TIMEOUT_MS)
    }
  }

  private fun androidx.benchmark.macro.MacrobenchmarkScope.startScenarioAndWait(
    scenario: String,
    markerText: String
  ) {
    pressHome()
    startActivityAndWait(
      Intent(Intent.ACTION_MAIN).apply {
        setPackage(PACKAGE_NAME)
        addCategory(Intent.CATEGORY_LAUNCHER)
        putExtra(BENCHMARK_SCENARIO_EXTRA, scenario)
      }
    )
    device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), STARTUP_TIMEOUT_MS)
    device.wait(Until.hasObject(By.textContains(markerText)), STARTUP_TIMEOUT_MS)
  }

  private fun androidx.benchmark.macro.MacrobenchmarkScope.swipeUp() {
    device.swipe(
      device.displayWidth / 2,
      (device.displayHeight * 0.82f).toInt(),
      device.displayWidth / 2,
      (device.displayHeight * 0.28f).toInt(),
      12
    )
    device.waitForIdle()
  }

  private fun androidx.benchmark.macro.MacrobenchmarkScope.swipeDown() {
    device.swipe(
      device.displayWidth / 2,
      (device.displayHeight * 0.28f).toInt(),
      device.displayWidth / 2,
      (device.displayHeight * 0.82f).toInt(),
      12
    )
    device.waitForIdle()
  }

  private companion object {
    private const val PACKAGE_NAME = "com.studysuit.aiqa"
    private const val BENCHMARK_SCENARIO_EXTRA = "com.studysuit.aiqa.BENCHMARK_SCENARIO"
    private const val CHAT_100_SCROLL = "chat_100_scroll"
    private const val MISTAKE_200_SEARCH = "mistake_200_search"
    private const val THREE_IMAGE_PREVIEW = "three_image_preview"
    private const val STREAM_2_MINUTES = "stream_2_minutes"
    private const val FORMULA_IMAGE_HEAVY = "formula_image_heavy"
    private const val STARTUP_TIMEOUT_MS = 5_000L
    private const val STREAM_TIMEOUT_MS = 125_000L
  }
}
