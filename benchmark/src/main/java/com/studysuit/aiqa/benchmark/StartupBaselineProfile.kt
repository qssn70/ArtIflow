package com.studysuit.aiqa.benchmark

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBaselineProfile {
  @get:Rule
  val baselineProfileRule = BaselineProfileRule()

  @Test
  fun startupAndPrimaryScroll() {
    baselineProfileRule.collect(packageName = PACKAGE_NAME) {
      startScenarioAndWait(null, "错题本")
      swipeUp()
      startScenarioAndWait(CHAT_100_SCROLL, "第 100 条聊天消息")
      repeat(3) { swipeUp() }
      startScenarioAndWait(MISTAKE_200_SEARCH, "第 200 道错题")
      repeat(2) { swipeUp() }
      device.findObject(By.textContains("搜索题干"))?.apply {
        click()
        setText("错题筛选-199")
      }
      device.wait(Until.hasObject(By.textContains("第 199 道错题")), STARTUP_TIMEOUT_MS)
      startScenarioAndWait(THREE_IMAGE_PREVIEW, "三图搜题样本")
      startScenarioAndWait(FORMULA_IMAGE_HEAVY, "含图片与公式消息打开样本")
      repeat(2) { swipeUp() }
    }
  }

  private fun androidx.benchmark.macro.MacrobenchmarkScope.startScenarioAndWait(
    scenario: String?,
    markerText: String
  ) {
    pressHome()
    startActivityAndWait(
      Intent(Intent.ACTION_MAIN).apply {
        setPackage(PACKAGE_NAME)
        addCategory(Intent.CATEGORY_LAUNCHER)
        scenario?.let { putExtra(BENCHMARK_SCENARIO_EXTRA, it) }
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

  private companion object {
    private const val PACKAGE_NAME = "com.studysuit.aiqa"
    private const val BENCHMARK_SCENARIO_EXTRA = "com.studysuit.aiqa.BENCHMARK_SCENARIO"
    private const val CHAT_100_SCROLL = "chat_100_scroll"
    private const val MISTAKE_200_SEARCH = "mistake_200_search"
    private const val THREE_IMAGE_PREVIEW = "three_image_preview"
    private const val FORMULA_IMAGE_HEAVY = "formula_image_heavy"
    private const val STARTUP_TIMEOUT_MS = 5_000L
  }
}
