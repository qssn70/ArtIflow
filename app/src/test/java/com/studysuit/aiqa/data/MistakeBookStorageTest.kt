package com.studysuit.aiqa.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MistakeBookStorageTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun parseMistakeBookJsonUsesDefaultsForMissingFields() {
    val raw = """
      {
        "version": 1,
        "items": [
          {
            "id": "mistake-old",
            "question": "函数题",
            "correctAnswer": "看顶点",
            "createdAt": 100,
            "updatedAt": 200,
            "imageRefs": ["mistake_images/mistake-old-0.jpg"]
          }
        ]
      }
    """.trimIndent()

    val parsed = parseMistakeBookJson(raw).getOrThrow().single()

    assertEquals("mistake-old", parsed.id)
    assertEquals(MistakeStatus.DUE, parsed.status)
    assertEquals(listOf("mistake_images/mistake-old-0.jpg"), parsed.imageRefs)
    assertEquals(100L, parsed.reviewState.nextReviewAt)
    assertEquals(0, parsed.reviewState.reviewCount)
    assertTrue(parsed.reviewAttempts.isEmpty())
  }

  @Test
  fun parseMistakeBookJsonKeepsIncompleteItemsAsDrafts() {
    val raw = """
      {
        "version": 1,
        "items": [
          {
            "id": "mistake-draft",
            "question": "只有题干",
            "createdAt": 100,
            "updatedAt": 100
          }
        ]
      }
    """.trimIndent()

    val parsed = parseMistakeBookJson(raw).getOrThrow().single()

    assertEquals(MistakeStatus.DRAFT, parsed.status)
    assertEquals(null, parsed.reviewState.nextReviewAt)
  }

  @Test
  fun storageRoundTripUpsertsAndDeletesItems() {
    val storage = MistakeBookStorage(baseDir = tempFolder.root)
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "q",
      correctAnswer = "a",
      imageRefs = listOf("mistake_images/mistake-1-0.jpg"),
      createdAt = 1_000L
    )

    storage.save(listOf(item)).getOrThrow()
    val updated = item.copy(question = "updated", updatedAt = 2_000L)
    storage.upsert(updated).getOrThrow()

    val loaded = storage.load()
    val raw = File(tempFolder.root, "mistake_book_v1.json").readText(Charsets.UTF_8)

    assertEquals(listOf("updated"), loaded.map { it.question })
    assertTrue(raw.contains("mistake_images/mistake-1-0.jpg"))
    assertFalse(raw.contains("data:image"))

    storage.delete("mistake-1").getOrThrow()
    assertTrue(storage.load().isEmpty())
  }

  @Test
  fun saveImageBytesWritesIntoPrivateMistakeImagesDirectory() {
    val storage = MistakeBookStorage(baseDir = tempFolder.root)

    val ref = storage.saveImageBytes(
      bytes = byteArrayOf(1, 2, 3),
      fileNameHint = "capture one.jpg"
    ).getOrThrow()

    assertTrue(ref.startsWith("mistake_images/"))
    assertTrue(File(tempFolder.root, ref).exists())
    assertEquals(listOf("capture-one.jpg"), File(tempFolder.root, "mistake_images").list()?.toList())
  }
}
