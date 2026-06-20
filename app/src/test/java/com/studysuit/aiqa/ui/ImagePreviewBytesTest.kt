package com.studysuit.aiqa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePreviewBytesTest {
  @Test
  fun calculateImagePreviewSampleSizeKeepsSmallImagesAtFullResolution() {
    assertEquals(1, calculateImagePreviewSampleSize(width = 800, height = 600, maxDimension = 960))
  }

  @Test
  fun calculateImagePreviewSampleSizeDownsamplesVeryLargeImages() {
    val sampleSize = calculateImagePreviewSampleSize(width = 6000, height = 4000, maxDimension = 960)

    assertTrue(sampleSize > 1)
    assertEquals(0, sampleSize and (sampleSize - 1))
  }
}
