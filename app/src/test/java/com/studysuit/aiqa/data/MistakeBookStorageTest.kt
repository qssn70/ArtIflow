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

  @Test
  fun exportTextAndImportText_roundTripMistakeBookItems() {
    val items = listOf(
      MistakeBookItem.create(
        id = "mistake-1",
        question = "函数最值",
        correctAnswer = "先配方再看顶点",
        subject = "数学",
        knowledgeTags = listOf("函数与图像"),
        createdAt = 1_000L
      )
    )

    val exported = exportMistakeBookText(items)
    val imported = importMistakeBookText(exported).getOrThrow()

    assertEquals(items, imported)
  }

  @Test
  fun mergeImportedMistakeBookItems_prefersImportedVersionAndKeepsOthers() {
    val current = listOf(
      MistakeBookItem.create(
        id = "mistake-1",
        question = "旧题干",
        correctAnswer = "旧答案",
        createdAt = 1_000L
      ),
      MistakeBookItem.create(
        id = "mistake-2",
        question = "保留题",
        correctAnswer = "保留答案",
        createdAt = 2_000L
      )
    )
    val imported = listOf(
      MistakeBookItem.create(
        id = "mistake-1",
        question = "新题干",
        correctAnswer = "新答案",
        createdAt = 3_000L
      ),
      MistakeBookItem.create(
        id = "mistake-3",
        question = "新增题",
        correctAnswer = "新增答案",
        createdAt = 4_000L
      )
    )

    val merged = mergeImportedMistakeBookItems(current = current, imported = imported)

    assertEquals(listOf("mistake-1", "mistake-3", "mistake-2"), merged.map { it.id })
    assertEquals("新题干", merged.first().question)
  }
}
