package com.studysuit.aiqa

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class BuildConfigContractTest {

  @Test
  fun buildConfig_doesNotBakeRuntimeSettings() {
    val fieldNames = BuildConfig::class.java.fields.map { field -> field.name }.toSet()

    listOf(
      "ARK_API_KEY",
      "ARK_MODEL",
      "ARK_BASE_URL",
      "ARK_ENDPOINT",
      "ARK_SYSTEM_PROMPT",
      "OPENSPEECH_API_KEY",
      "OPENSPEECH_RESOURCE_ID",
      "OPENSPEECH_SUBMIT_URL",
      "OPENSPEECH_QUERY_URL",
      "OPENSPEECH_UID",
      "FLOWSTUDY_SERVER_URL"
    ).forEach { fieldName ->
      assertTrue("Unexpected baked BuildConfig field: $fieldName", fieldName !in fieldNames)
    }
  }

  @Test
  fun jankStats_isDebugOnlyAndNotDirectlyReferencedByMainSources() {
    val repoRoot = findRepoRoot()
    val mainActivity = repoRoot.resolve("app/src/main/java/com/studysuit/aiqa/MainActivity.kt").readText()
    val appGradle = repoRoot.resolve("app/build.gradle.kts").readText()

    assertTrue(
      "Main sources must not directly reference JankStats; use reflection so release has no dependency on it.",
      "import androidx.metrics.performance.JankStats" !in mainActivity &&
        "JankStats?" !in mainActivity
    )
    assertTrue(
      "JankStats should stay on debugImplementation so it is not packaged in release.",
      "debugImplementation(\"androidx.metrics:metrics-performance" in appGradle
    )
    assertTrue(
      "JankStats must not be on implementation because release does not need it.",
      "implementation(\"androidx.metrics:metrics-performance" !in appGradle
    )
  }

  private fun findRepoRoot(): Path {
    var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (!current.resolve("settings.gradle.kts").exists()) {
      current = current.parent ?: error("Could not locate repository root")
    }
    return current
  }
}
