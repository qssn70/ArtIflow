package com.studysuit.aiqa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class OpenSpeechAsrClientTest {

  @Test
  fun transcribeWithoutConfigPointsUserToInAppSettings(): Unit = runBlocking {
    val client = OpenSpeechAsrClient()

    val result = client.transcribeByAudioData(
      audioBytes = byteArrayOf(1, 2, 3),
      config = OpenSpeechRuntimeConfig(apiKey = "", resourceId = "")
    )

    assertTrue(result.isFailure)
    assertEquals("请先在设置中配置 OpenSpeech 参数", result.exceptionOrNull()?.message)
  }

  @Test
  fun parseOpenSpeechTranscriptPrefersResultText() {
    val transcript = parseOpenSpeechTranscript(
      """{"result":{"text":"这是最终识别文本"}}"""
    )

    assertEquals("这是最终识别文本", transcript)
  }

  @Test
  fun parseOpenSpeechTranscriptFallsBackToUtteranceList() {
    val transcript = parseOpenSpeechTranscript(
      """{"result":{"utterances":[{"text":"第一句"},{"text":"第二句"}]}}"""
    )

    assertEquals("第一句\n第二句", transcript)
  }

  @Test
  fun parseOpenSpeechTranscriptInvalidJsonReturnsBlank() {
    val transcript = parseOpenSpeechTranscript("not-json")

    assertEquals("", transcript)
  }
}
