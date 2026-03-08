package com.studysuit.aiqa.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StudyChatMediaUtilsTest {

  @Test
  fun detectImageMimeType_recognizesCommonFormats() {
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val png = byteArrayOf(
      0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x00, 0x00, 0x00, 0x00
    )
    val webp = byteArrayOf(
      0x52, 0x49, 0x46, 0x46,
      0x00, 0x00, 0x00, 0x00,
      0x57, 0x45, 0x42, 0x50
    )
    val heic = byteArrayOf(
      0x00, 0x00, 0x00, 0x18,
      0x66, 0x74, 0x79, 0x70,
      0x68, 0x65, 0x69, 0x63
    )

    assertEquals("image/jpeg", detectImageMimeType(jpeg))
    assertEquals("image/png", detectImageMimeType(png))
    assertEquals("image/webp", detectImageMimeType(webp))
    assertEquals("image/heic", detectImageMimeType(heic))
  }

  @Test
  fun toImagePayloads_filtersEmptyImagesAndInfersMimeType() {
    val png = byteArrayOf(
      0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x00, 0x00, 0x00, 0x00
    )

    val payloads = toImagePayloads(listOf(ByteArray(0), png))

    assertEquals(1, payloads.size)
    assertEquals("image/png", payloads.first().mimeType)
    assertEquals(png.toList(), payloads.first().bytes.toList())
  }
}
