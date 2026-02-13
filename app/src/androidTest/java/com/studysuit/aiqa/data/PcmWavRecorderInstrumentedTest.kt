package com.studysuit.aiqa.data

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcmWavRecorderInstrumentedTest {

  @get:Rule
  val recordAudioPermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

  @Test
  fun stopAfterHold_producesValidWavPayload() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val recorder = PcmWavRecorder(context.cacheDir)

    val session = recorder.start().getOrElse { error ->
      throw AssertionError("Recorder start failed: ${error.message}", error)
    }

    Thread.sleep(1200)

    val audioBytes = session.stop(discard = false).getOrElse { error ->
      throw AssertionError("Recorder stop failed: ${error.message}", error)
    }

    assertTrue("WAV payload should contain header + data", audioBytes.size > 44)
    assertArrayEquals("RIFF".toByteArray(), audioBytes.copyOfRange(0, 4))
    assertArrayEquals("WAVE".toByteArray(), audioBytes.copyOfRange(8, 12))
  }

  @Test
  fun discardThenRestart_secondSessionStillProducesValidWavPayload() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val recorder = PcmWavRecorder(context.cacheDir)

    val firstSession = recorder.start().getOrElse { error ->
      throw AssertionError("First recorder start failed: ${error.message}", error)
    }

    Thread.sleep(450)

    val discardResult = firstSession.stop(discard = true)
    assertTrue("Discard should return cancellation failure", discardResult.isFailure)

    val secondSession = recorder.start().getOrElse { error ->
      throw AssertionError("Second recorder start failed: ${error.message}", error)
    }

    Thread.sleep(1200)

    val secondAudioBytes = secondSession.stop(discard = false).getOrElse { error ->
      throw AssertionError("Second recorder stop failed: ${error.message}", error)
    }

    assertTrue("Second WAV payload should contain header + data", secondAudioBytes.size > 44)
    assertArrayEquals("RIFF".toByteArray(), secondAudioBytes.copyOfRange(0, 4))
    assertArrayEquals("WAVE".toByteArray(), secondAudioBytes.copyOfRange(8, 12))
  }

  @Test
  fun stopTwice_returnsSessionClosedFailure() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val recorder = PcmWavRecorder(context.cacheDir)

    val session = recorder.start().getOrElse { error ->
      throw AssertionError("Recorder start failed: ${error.message}", error)
    }

    Thread.sleep(1200)

    val firstStopResult = session.stop(discard = false)
    assertTrue("First stop should succeed", firstStopResult.isSuccess)

    val secondStopResult = session.stop(discard = false)
    assertTrue("Second stop should fail", secondStopResult.isFailure)
    val message = secondStopResult.exceptionOrNull()?.message.orEmpty()
    assertTrue("Second stop should indicate closed session", message.contains("录音会话已结束"))
  }
}
