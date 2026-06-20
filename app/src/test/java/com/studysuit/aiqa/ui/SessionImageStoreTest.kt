package com.studysuit.aiqa.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class SessionImageStoreTest {

  @Test
  fun saveImagesPersistsFilesAndReturnsStableRefs() {
    val baseDir = Files.createTempDirectory("session-images").toFile()
    val store = SessionImageStore(baseDir)
    val first = byteArrayOf(1, 2, 3, 4)
    val second = byteArrayOf(5, 6, 7)

    val refs = store.saveImages(ownerId = "msg/1", images = listOf(first, second))

    assertEquals(2, refs.size)
    assertEquals("image/jpeg", refs.first().mimeType)
    assertTrue(refs.all { ref -> ref.path.startsWith("session_images/") })
    assertFalse(refs.any { ref -> ref.path.contains("..") || ref.path.contains("/") && ref.path.contains("\\") })
    assertArrayEquals(first, store.readImageBytes(refs.first()))
    assertArrayEquals(second, store.readImageBytes(refs.last()))
  }

  @Test
  fun saveImagesOverwritesSameOwnerSlotsWithoutCreatingOrphanFiles() {
    val baseDir = Files.createTempDirectory("session-images-overwrite").toFile()
    val store = SessionImageStore(baseDir)
    val firstBytes = byteArrayOf(1, 2, 3)
    val updatedBytes = byteArrayOf(4, 5, 6)

    val firstRefs = store.saveImages(ownerId = "msg-1", images = listOf(firstBytes))
    val updatedRefs = store.saveImages(ownerId = "msg-1", images = listOf(updatedBytes))
    val imageFiles = File(baseDir, "session_images").listFiles().orEmpty()

    assertEquals(firstRefs.first().path, updatedRefs.first().path)
    assertEquals(1, imageFiles.size)
    assertArrayEquals(updatedBytes, store.readImageBytes(updatedRefs.first()))
  }

  @Test
  fun saveImagesKeepsExistingFileWhenBytesAreUnchanged() {
    val baseDir = Files.createTempDirectory("session-images-unchanged").toFile()
    val store = SessionImageStore(baseDir)
    val imageBytes = byteArrayOf(1, 2, 3)

    val firstRef = store.saveImages(ownerId = "msg-1", images = listOf(imageBytes)).single()
    val imageFile = File(baseDir, firstRef.path)
    imageFile.setLastModified(1_700_000_000_000L)

    val secondRef = store.saveImages(ownerId = "msg-1", images = listOf(imageBytes)).single()

    assertEquals(firstRef.path, secondRef.path)
    assertEquals(1_700_000_000_000L, imageFile.lastModified())
    assertArrayEquals(imageBytes, store.readImageBytes(secondRef))
  }

  @Test
  fun saveImagesRecordsKnownImageDimensions() {
    val baseDir = Files.createTempDirectory("session-images-dimensions").toFile()
    val store = SessionImageStore(baseDir)
    val pngBytes = Base64.getDecoder()
      .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lG0H+QAAAABJRU5ErkJggg==")

    val ref = store.saveImages(ownerId = "msg-1", images = listOf(pngBytes)).single()

    assertEquals(1, ref.width)
    assertEquals(1, ref.height)
  }

  @Test
  fun readImageRefsIgnoresPathsOutsideSessionImageDirectory() {
    val baseDir = Files.createTempDirectory("session-images-guard").toFile()
    File(baseDir, "secret.txt").writeBytes(byteArrayOf(9, 9, 9))
    val store = SessionImageStore(baseDir)
    val unsafeRef = StoredImageRef(
      id = "unsafe",
      path = "../secret.txt",
      mimeType = "image/jpeg",
      width = null,
      height = null,
      thumbnailPath = null
    )

    assertEquals(0, store.readImageBytesList(listOf(unsafeRef)).size)
  }
}
