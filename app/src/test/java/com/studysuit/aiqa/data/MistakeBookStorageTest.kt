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
  fun obsidianStorageSavesItemsAsMarkdownNotesAndIndex() {
    val storage = ObsidianMistakeBookStorage(vaultDir = tempFolder.root)
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "函数题",
      correctAnswer = "看顶点",
      imageRefs = listOf("assets/mistake-1.jpg"),
      subject = "数学",
      knowledgeTags = listOf("函数与图像"),
      createdAt = 1_000L
    )

    storage.save(listOf(item)).getOrThrow()

    val noteFile = File(tempFolder.root, "ArtIflow/错题本/items/mistake-1.md")
    val indexFile = File(tempFolder.root, "ArtIflow/错题本/错题索引.md")
    val noteRaw = noteFile.readText(Charsets.UTF_8)
    val indexRaw = indexFile.readText(Charsets.UTF_8)

    assertTrue(noteFile.exists())
    assertTrue(indexFile.exists())
    assertTrue(noteRaw.contains("```json"))
    assertTrue(noteRaw.contains("\"id\":\"mistake-1\""))
    assertTrue(noteRaw.contains("![图片 1](../assets/mistake-1.jpg)"))
    assertTrue(indexRaw.contains("[函数题](items/mistake-1.md)"))
    assertEquals(item, storage.load().single())
  }

  @Test
  fun obsidianStorageUpsertsAndDeletesMarkdownItems() {
    val storage = ObsidianMistakeBookStorage(vaultDir = tempFolder.root)
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "旧题干",
      correctAnswer = "旧答案",
      createdAt = 1_000L
    )

    storage.upsert(item).getOrThrow()
    storage.upsert(item.copy(question = "新题干", updatedAt = 2_000L)).getOrThrow()

    assertEquals(listOf("新题干"), storage.load().map { it.question })
    assertTrue(File(tempFolder.root, "ArtIflow/错题本/items/mistake-1.md").readText(Charsets.UTF_8).contains("新题干"))

    storage.delete("mistake-1").getOrThrow()

    assertTrue(storage.load().isEmpty())
    assertFalse(File(tempFolder.root, "ArtIflow/错题本/items/mistake-1.md").exists())
  }

  @Test
  fun obsidianStorageWritesImagesToAssetsAndUsesRelativeMarkdownLinks() {
    val storage = ObsidianMistakeBookStorage(vaultDir = tempFolder.root)

    val imageRef = storage.saveImageBytes(
      bytes = byteArrayOf(1, 2, 3),
      fileNameHint = "capture one.jpg"
    ).getOrThrow()
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "图片题",
      correctAnswer = "答案",
      imageRefs = listOf(imageRef),
      createdAt = 1_000L
    )

    storage.save(listOf(item)).getOrThrow()

    val noteRaw = File(tempFolder.root, "ArtIflow/错题本/items/mistake-1.md").readText(Charsets.UTF_8)

    assertEquals("assets/capture-one.jpg", imageRef)
    assertTrue(File(tempFolder.root, "ArtIflow/错题本/assets/capture-one.jpg").exists())
    assertTrue(noteRaw.contains("![图片 1](../assets/capture-one.jpg)"))
    assertFalse(noteRaw.contains("data:image"))
    assertFalse(noteRaw.contains("base64"))
  }

  @Test
  fun obsidianStorageLoadsImagesFromAssetsForMigration() {
    val storage = ObsidianMistakeBookStorage(vaultDir = tempFolder.root)
    val imageRef = storage.saveImageBytes(
      bytes = byteArrayOf(7, 8, 9),
      fileNameHint = "capture one.jpg"
    ).getOrThrow()

    assertEquals(byteArrayOf(7, 8, 9).toList(), storage.loadImageBytes(imageRef).getOrThrow().toList())
    assertEquals(byteArrayOf(7, 8, 9).toList(), storage.loadImageBytes("../$imageRef").getOrThrow().toList())
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
  fun localStorageLoadsImagesFromPrivateMistakeImagesDirectoryForMigration() {
    val storage = MistakeBookStorage(baseDir = tempFolder.root)
    val ref = storage.saveImageBytes(
      bytes = byteArrayOf(4, 5, 6),
      fileNameHint = "capture one.jpg"
    ).getOrThrow()

    assertEquals(byteArrayOf(4, 5, 6).toList(), storage.loadImageBytes(ref).getOrThrow().toList())
  }

  @Test
  fun localStorageRejectsImageRefsOutsidePrivateMistakeImagesDirectory() {
    val storage = MistakeBookStorage(baseDir = tempFolder.root)
    File(tempFolder.root, "study_suit_sessions_v1.json").writeText("secret", Charsets.UTF_8)

    assertTrue(storage.loadImageBytes("study_suit_sessions_v1.json").isFailure)
  }

  @Test
  fun obsidianStorageRejectsImageRefsOutsideAssetsDirectory() {
    val storage = ObsidianMistakeBookStorage(vaultDir = tempFolder.root)
    val itemsDir = File(tempFolder.root, "ArtIflow/错题本/items").apply { mkdirs() }
    File(itemsDir, "leak.jpg").writeBytes(byteArrayOf(9, 9, 9))

    assertTrue(storage.loadImageBytes("items/leak.jpg").isFailure)
  }

  @Test
  fun obsidianStorageKeepsUnparseableNotesWhenSavingVisibleItems() {
    val storage = ObsidianMistakeBookStorage(vaultDir = tempFolder.root)
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "可解析错题",
      correctAnswer = "保留",
      createdAt = 1_000L
    )
    storage.save(listOf(item)).getOrThrow()
    val manualNote = File(tempFolder.root, "ArtIflow/错题本/items/manual-note.md")
    manualNote.writeText("# 手工补充\n\n这不是 ArtIflow JSON。", Charsets.UTF_8)

    storage.save(listOf(item.copy(question = "更新题干", updatedAt = 2_000L))).getOrThrow()

    assertTrue(manualNote.exists())
    assertEquals(listOf("更新题干"), storage.load().map { it.question })
  }

  @Test
  fun obsidianStorageKeepsParseableStaleNotesWhenSaveFails() {
    val storage = ObsidianMistakeBookStorage(vaultDir = tempFolder.root)
    val stale = MistakeBookItem.create(
      id = "stale",
      question = "已有 vault 错题",
      correctAnswer = "不能被失败保存删除",
      createdAt = 1_000L
    )
    val itemsDir = File(tempFolder.root, "ArtIflow/错题本/items").apply { mkdirs() }
    val staleNote = File(itemsDir, "stale.md").apply {
      writeText(renderMistakeBookMarkdown(stale), Charsets.UTF_8)
    }
    File(itemsDir, "blocked.md").mkdir()
    val blocked = MistakeBookItem.create(
      id = "blocked",
      question = "这次保存会失败",
      correctAnswer = "因为目标路径是目录",
      createdAt = 2_000L
    )

    val result = storage.save(listOf(blocked))

    assertTrue(result.isFailure)
    assertTrue(staleNote.exists())
    assertEquals(listOf("已有 vault 错题"), storage.load().map { it.question })
  }

  @Test
  fun obsidianStorageLoadsMachineJsonWhenQuestionContainsJsonFence() {
    val storage = ObsidianMistakeBookStorage(vaultDir = tempFolder.root)
    val item = MistakeBookItem.create(
      id = "mistake-json-question",
      question = """
        观察下面代码：
        ```json
        {"not":"artiflow"}
        ```
        求输出。
      """.trimIndent(),
      correctAnswer = "读取最后的机器数据",
      createdAt = 1_000L
    )

    storage.save(listOf(item)).getOrThrow()

    assertEquals(item, storage.load().single())
  }

  @Test
  fun obsidianStorageUsesCollisionSafeNoteFileNames() {
    val storage = ObsidianMistakeBookStorage(vaultDir = tempFolder.root)
    val slashId = MistakeBookItem.create(
      id = "a/b",
      question = "斜杠 ID",
      correctAnswer = "一",
      createdAt = 1_000L
    )
    val colonId = MistakeBookItem.create(
      id = "a:b",
      question = "冒号 ID",
      correctAnswer = "二",
      createdAt = 2_000L
    )

    storage.save(listOf(slashId, colonId)).getOrThrow()

    val loaded = storage.load()
    assertEquals(setOf("a/b", "a:b"), loaded.map { it.id }.toSet())
    assertEquals(2, File(tempFolder.root, "ArtIflow/错题本/items").listFiles { file -> file.extension == "md" }?.size)
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
