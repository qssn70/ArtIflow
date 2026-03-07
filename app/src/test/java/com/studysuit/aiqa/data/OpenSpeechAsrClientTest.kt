package com.studysuit.aiqa.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenSpeechAsrClientTest {

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
