package com.studysuit.aiqa

import org.junit.Assert.assertTrue
import org.junit.Test

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
}
