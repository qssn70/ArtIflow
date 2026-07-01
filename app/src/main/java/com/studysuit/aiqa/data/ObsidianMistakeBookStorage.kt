package com.studysuit.aiqa.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ObsidianMistakeBookStorage(
  private val vaultDir: File,
  private val folder: String = DEFAULT_OBSIDIAN_MISTAKE_FOLDER
) : MistakeBookRepository {
  private val rootDir: File
    get() = resolveFolder(vaultDir, folder)

  private val itemsDir: File
    get() = File(rootDir, ITEMS_DIR_NAME)

  private val assetsDir: File
    get() = File(rootDir, ASSETS_DIR_NAME)

  private val indexFile: File
    get() = File(rootDir, INDEX_FILE_NAME)

  override fun load(): List<MistakeBookItem> {
    val notes = itemsDir.listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
      ?.sortedByDescending { file -> file.lastModified() }
      .orEmpty()
    if (notes.isEmpty()) {
      return emptyList()
    }

    return notes.flatMap { note ->
      runCatching { parseMistakeBookMarkdown(note.readText(Charsets.UTF_8)) }
        .onFailure { error -> safeLogWarning(TAG, "Failed to parse Obsidian mistake note: ${note.name}", error) }
        .getOrDefault(emptyList())
    }
  }

  override fun save(items: List<MistakeBookItem>): Result<Unit> {
    return runCatching {
      rootDir.mkdirs()
      itemsDir.mkdirs()
      assetsDir.mkdirs()

      val expectedFileById = items.associate { item -> item.id to noteFileName(item.id) }
      val staleFiles = itemsDir.listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
        .orEmpty()
        .filter { file ->
          val parsedItems = runCatching { parseMistakeBookMarkdown(file.readText(Charsets.UTF_8)) }
            .getOrDefault(emptyList())
          val isCanonicalExpectedNote = parsedItems.size == 1 &&
            expectedFileById[parsedItems.single().id] == file.name
          parsedItems.isNotEmpty() && !isCanonicalExpectedNote
        }

      val tempFiles = mutableListOf<File>()
      try {
        items.forEach { item ->
          val target = File(itemsDir, noteFileName(item.id))
          val temp = writeTextToSiblingTempFile(target, renderMistakeBookMarkdown(item))
          tempFiles += temp
          replaceFileWithTemp(temp, target)
          tempFiles -= temp
        }
        val indexTemp = writeTextToSiblingTempFile(indexFile, renderMistakeBookIndex(items))
        tempFiles += indexTemp
        replaceFileWithTemp(indexTemp, indexFile)
        tempFiles -= indexTemp
      } finally {
        tempFiles.forEach { temp -> temp.delete() }
      }

      staleFiles.forEach { file ->
        require(file.delete() || !file.exists()) { "无法删除旧 Obsidian 笔记：${file.name}" }
      }
    }.onFailure { error -> safeLogWarning(TAG, "Failed to persist Obsidian mistake book", error) }
  }

  override fun upsert(item: MistakeBookItem): Result<List<MistakeBookItem>> {
    val current = load()
    val exists = current.any { existing -> existing.id == item.id }
    val next = if (exists) {
      current.map { existing -> if (existing.id == item.id) item else existing }
    } else {
      listOf(item) + current
    }
    return save(next).map { next }
  }

  override fun delete(itemId: String): Result<List<MistakeBookItem>> {
    val next = load().filterNot { item -> item.id == itemId }
    return save(next).map {
      val noteFile = File(itemsDir, noteFileName(itemId))
      require(noteFile.delete() || !noteFile.exists()) { "无法删除 Obsidian 笔记：${noteFile.name}" }
      next
    }
  }

  override fun saveImageBytes(bytes: ByteArray, fileNameHint: String): Result<String> {
    return runCatching {
      require(bytes.isNotEmpty()) { "图片数据为空" }
      assetsDir.mkdirs()
      var candidate = File(assetsDir, sanitizeObsidianImageFileName(fileNameHint))
      if (candidate.exists()) {
        val stem = candidate.nameWithoutExtension
        val extension = candidate.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        var index = 1
        while (candidate.exists()) {
          candidate = File(assetsDir, "$stem-$index$extension")
          index += 1
        }
      }
      candidate.writeBytes(bytes)
      "$ASSETS_DIR_NAME/${candidate.name}"
    }.onFailure { error -> safeLogWarning(TAG, "Failed to persist Obsidian mistake image", error) }
  }

  override fun loadImageBytes(ref: String): Result<ByteArray> {
    return runCatching {
      val file = resolveObsidianStoredFile(rootDir = rootDir, ref = ref)
      require(file.isFile) { "图片不存在：$ref" }
      file.readBytes()
    }.onFailure { error -> safeLogWarning(TAG, "Failed to read Obsidian mistake image: $ref", error) }
  }

  private companion object {
    private const val TAG = "ObsidianMistakeBook"
  }
}

class AndroidObsidianMistakeBookStorage(
  context: Context,
  vaultTreeUri: String,
  private val folder: String = DEFAULT_OBSIDIAN_MISTAKE_FOLDER
) : MistakeBookRepository {
  private val resolver = context.applicationContext.contentResolver
  private val treeUri = Uri.parse(vaultTreeUri)
  private val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

  override fun load(): List<MistakeBookItem> {
    return runCatching {
      val root = findFolderRoot(createMissing = false) ?: return@runCatching emptyList()
      val items = findChild(root.documentId, ITEMS_DIR_NAME)?.takeIf { child -> child.isDirectory }
        ?: return@runCatching emptyList()
      listChildren(items.documentId)
        .filter { child -> !child.isDirectory && child.name.endsWith(".md", ignoreCase = true) }
        .sortedBy { child -> child.name }
        .flatMap { note ->
          runCatching {
            resolver.openInputStream(note.uri)
              ?.bufferedReader(Charsets.UTF_8)
              ?.use { reader -> parseMistakeBookMarkdown(reader.readText()) }
              .orEmpty()
          }.onFailure { error -> safeLogWarning(TAG, "Failed to parse SAF Obsidian note: ${note.name}", error) }
            .getOrDefault(emptyList())
        }
    }.onFailure { error -> safeLogWarning(TAG, "Failed to load SAF Obsidian mistake book", error) }
      .getOrDefault(emptyList())
  }

  override fun save(items: List<MistakeBookItem>): Result<Unit> {
    return runCatching {
      val root = findFolderRoot(createMissing = true) ?: error("无法访问 Obsidian 仓库目录")
      val itemsFolder = ensureDirectory(root.documentId, ITEMS_DIR_NAME)
      ensureDirectory(root.documentId, ASSETS_DIR_NAME)

      val expectedFileById = items.associate { item -> item.id to noteFileName(item.id) }
      val staleNotes = listChildren(itemsFolder.documentId)
        .filter { child -> !child.isDirectory && child.name.endsWith(".md", ignoreCase = true) }
        .filter { child ->
          val parsedItems = readTextFile(child)
            ?.let { text -> parseMistakeBookMarkdown(text) }
            .orEmpty()
          val isCanonicalExpectedNote = parsedItems.size == 1 &&
            expectedFileById[parsedItems.single().id] == child.name
          parsedItems.isNotEmpty() && !isCanonicalExpectedNote
        }

      val stagedDocuments = mutableListOf<ObsidianDocument>()
      val replacements = mutableListOf<ObsidianReplacement>()
      try {
        items.forEach { item ->
          val targetName = noteFileName(item.id)
          val temp = writeTempTextFile(
            parentDocumentId = itemsFolder.documentId,
            targetDisplayName = targetName,
            mimeType = MARKDOWN_MIME_TYPE,
            text = renderMistakeBookMarkdown(item)
          )
          stagedDocuments += temp
          replacements += replaceDocumentWithTemp(
            parentDocumentId = itemsFolder.documentId,
            targetDisplayName = targetName,
            tempDocument = temp
          )
          stagedDocuments -= temp
        }
        val indexTemp = writeTempTextFile(
          parentDocumentId = root.documentId,
          targetDisplayName = INDEX_FILE_NAME,
          mimeType = MARKDOWN_MIME_TYPE,
          text = renderMistakeBookIndex(items)
        )
        stagedDocuments += indexTemp
        replacements += replaceDocumentWithTemp(
          parentDocumentId = root.documentId,
          targetDisplayName = INDEX_FILE_NAME,
          tempDocument = indexTemp
        )
        stagedDocuments -= indexTemp

        staleNotes.forEach(::deleteDocumentOrThrow)
        replacements.forEach { replacement -> replacement.backup?.let(::deleteDocumentOrThrow) }
      } catch (error: Throwable) {
        replacements.asReversed().forEach(::rollbackReplacement)
        stagedDocuments.forEach { temp -> runCatching { deleteDocumentOrThrow(temp) } }
        throw error
      }
    }.onFailure { error -> safeLogWarning(TAG, "Failed to persist SAF Obsidian mistake book", error) }
  }

  override fun upsert(item: MistakeBookItem): Result<List<MistakeBookItem>> {
    val current = load()
    val exists = current.any { existing -> existing.id == item.id }
    val next = if (exists) {
      current.map { existing -> if (existing.id == item.id) item else existing }
    } else {
      listOf(item) + current
    }
    return save(next).map { next }
  }

  override fun delete(itemId: String): Result<List<MistakeBookItem>> {
    val next = load().filterNot { item -> item.id == itemId }
    return save(next).map {
      val root = findFolderRoot(createMissing = false)
      val itemsFolder = root?.let { findChild(it.documentId, ITEMS_DIR_NAME) }?.takeIf { child -> child.isDirectory }
      val note = itemsFolder?.let { findChild(it.documentId, noteFileName(itemId)) }?.takeUnless { child -> child.isDirectory }
      if (note != null) {
        deleteDocumentOrThrow(note)
      }
      next
    }
  }

  override fun saveImageBytes(bytes: ByteArray, fileNameHint: String): Result<String> {
    return runCatching {
      require(bytes.isNotEmpty()) { "图片数据为空" }
      val root = findFolderRoot(createMissing = true) ?: error("无法访问 Obsidian 仓库目录")
      val assets = ensureDirectory(root.documentId, ASSETS_DIR_NAME)
      val baseName = sanitizeObsidianImageFileName(fileNameHint)
      var candidateName = baseName
      if (findChild(assets.documentId, candidateName) != null) {
        val stem = baseName.substringBeforeLast('.', missingDelimiterValue = baseName)
        val extension = baseName.substringAfterLast('.', missingDelimiterValue = "")
          .takeIf(String::isNotBlank)
          ?.let { ext -> ".$ext" }
          .orEmpty()
        var index = 1
        while (findChild(assets.documentId, candidateName) != null) {
          candidateName = "$stem-$index$extension"
          index += 1
        }
      }
      val created = createDocument(
        parentDocumentId = assets.documentId,
        mimeType = IMAGE_MIME_TYPE,
        displayName = candidateName
      )
      resolver.openOutputStream(created.uri, "w")?.use { output -> output.write(bytes) }
        ?: error("无法写入 Obsidian 图片")
      "$ASSETS_DIR_NAME/$candidateName"
    }.onFailure { error -> safeLogWarning(TAG, "Failed to persist SAF Obsidian mistake image", error) }
  }

  override fun loadImageBytes(ref: String): Result<ByteArray> {
    return runCatching {
      val root = findFolderRoot(createMissing = false) ?: error("无法访问 Obsidian 仓库目录")
      val imageDocument = findPath(root.documentId, ref.toSafeObsidianImageSegments())
        ?.takeUnless { document -> document.isDirectory }
        ?: error("图片不存在：$ref")
      resolver.openInputStream(imageDocument.uri)?.use { input -> input.readBytes() }
        ?: error("无法读取 Obsidian 图片：$ref")
    }.onFailure { error -> safeLogWarning(TAG, "Failed to read SAF Obsidian mistake image: $ref", error) }
  }

  private fun findFolderRoot(createMissing: Boolean): ObsidianDocument? {
    var current = ObsidianDocument(
      name = "",
      documentId = rootDocumentId,
      uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId),
      isDirectory = true
    )
    for (segment in folder.toSafeObsidianFolderSegments()) {
      current = if (createMissing) {
        ensureDirectory(current.documentId, segment)
      } else {
        findChild(current.documentId, segment)?.takeIf { child -> child.isDirectory } ?: return null
      }
    }
    return current
  }

  private fun writeTempTextFile(
    parentDocumentId: String,
    targetDisplayName: String,
    mimeType: String,
    text: String
  ): ObsidianDocument {
    val tempName = ".artiflow-${System.nanoTime()}-${sanitizeObsidianPathName(targetDisplayName, "note")}.tmp"
    val temp = createDocument(parentDocumentId, mimeType, tempName)
    resolver.openOutputStream(temp.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
      writer.write(text)
    } ?: error("无法写入 Obsidian 文件：$targetDisplayName")
    return temp
  }

  private fun replaceDocumentWithTemp(
    parentDocumentId: String,
    targetDisplayName: String,
    tempDocument: ObsidianDocument
  ): ObsidianReplacement {
    val existing = findChild(parentDocumentId, targetDisplayName)
    require(existing?.isDirectory != true) { "Obsidian 目标路径是目录：$targetDisplayName" }
    val backup = existing?.let { document ->
      renameDocumentOrThrow(
        document = document,
        displayName = ".artiflow-backup-${System.nanoTime()}-${sanitizeObsidianPathName(targetDisplayName, "note")}.bak"
      )
    }
    return try {
      val current = renameDocumentOrThrow(tempDocument, targetDisplayName)
      ObsidianReplacement(
        parentDocumentId = parentDocumentId,
        targetDisplayName = targetDisplayName,
        current = current,
        backup = backup
      )
    } catch (error: Throwable) {
      backup?.let { backedUp -> runCatching { renameDocumentOrThrow(backedUp, targetDisplayName) } }
      throw error
    }
  }

  private fun rollbackReplacement(replacement: ObsidianReplacement) {
    runCatching { deleteDocumentOrThrow(replacement.current) }
    replacement.backup?.let { backup ->
      runCatching { renameDocumentOrThrow(backup, replacement.targetDisplayName) }
    }
  }

  private fun renameDocumentOrThrow(document: ObsidianDocument, displayName: String): ObsidianDocument {
    val renamedUri = DocumentsContract.renameDocument(resolver, document.uri, displayName)
      ?: error("无法重命名 Obsidian 文件：${document.name}")
    return document.copy(
      name = displayName,
      documentId = DocumentsContract.getDocumentId(renamedUri),
      uri = renamedUri
    )
  }

  private fun readTextFile(document: ObsidianDocument): String? {
    return runCatching {
      resolver.openInputStream(document.uri)
        ?.bufferedReader(Charsets.UTF_8)
        ?.use { reader -> reader.readText() }
    }.getOrNull()
  }

  private fun deleteDocumentOrThrow(document: ObsidianDocument) {
    require(DocumentsContract.deleteDocument(resolver, document.uri)) {
      "无法删除 Obsidian 文件：${document.name}"
    }
  }

  private fun ensureDirectory(parentDocumentId: String, displayName: String): ObsidianDocument {
    val existing = findChild(parentDocumentId, displayName)?.takeIf { child -> child.isDirectory }
    if (existing != null) {
      return existing
    }
    return createDocument(
      parentDocumentId = parentDocumentId,
      mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
      displayName = displayName
    )
  }

  private fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): ObsidianDocument {
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
    val uri = DocumentsContract.createDocument(resolver, parentUri, mimeType, displayName)
      ?: error("无法创建 Obsidian 文件：$displayName")
    return ObsidianDocument(
      name = displayName,
      documentId = DocumentsContract.getDocumentId(uri),
      uri = uri,
      isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    )
  }

  private fun findChild(parentDocumentId: String, displayName: String): ObsidianDocument? {
    return listChildren(parentDocumentId).firstOrNull { child -> child.name == displayName }
  }

  private fun findPath(parentDocumentId: String, segments: List<String>): ObsidianDocument? {
    if (segments.isEmpty()) {
      return null
    }
    var currentDocumentId = parentDocumentId
    var current: ObsidianDocument? = null
    for (segment in segments) {
      current = findChild(currentDocumentId, segment) ?: return null
      currentDocumentId = current.documentId
    }
    return current
  }

  private fun listChildren(parentDocumentId: String): List<ObsidianDocument> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    val projection = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE
    )
    return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
      val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
      if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
        return@use emptyList()
      }
      buildList {
        while (cursor.moveToNext()) {
          val documentId = cursor.getString(idIndex).orEmpty()
          if (documentId.isBlank()) {
            continue
          }
          val name = cursor.getString(nameIndex).orEmpty()
          val mimeType = cursor.getString(mimeIndex).orEmpty()
          add(
            ObsidianDocument(
              name = name,
              documentId = documentId,
              uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
              isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            )
          )
        }
      }
    }.orEmpty()
  }

  private companion object {
    private const val TAG = "AndroidObsidianBook"
    private const val MARKDOWN_MIME_TYPE = "text/markdown"
    private const val IMAGE_MIME_TYPE = "image/jpeg"
  }
}

private data class ObsidianDocument(
  val name: String,
  val documentId: String,
  val uri: Uri,
  val isDirectory: Boolean
)

private data class ObsidianReplacement(
  val parentDocumentId: String,
  val targetDisplayName: String,
  val current: ObsidianDocument,
  val backup: ObsidianDocument?
)

internal const val OBSIDIAN_ITEMS_DIR_NAME = "items"
internal const val OBSIDIAN_ASSETS_DIR_NAME = "assets"
internal const val OBSIDIAN_INDEX_FILE_NAME = "错题索引.md"

private const val ITEMS_DIR_NAME = OBSIDIAN_ITEMS_DIR_NAME
private const val ASSETS_DIR_NAME = OBSIDIAN_ASSETS_DIR_NAME
private const val INDEX_FILE_NAME = OBSIDIAN_INDEX_FILE_NAME

internal fun renderMistakeBookMarkdown(item: MistakeBookItem): String {
  val itemJson = buildMistakeBookJson(listOf(item)).toString()
  val title = item.question.ifBlank { item.id }.lineSequence().firstOrNull().orEmpty().ifBlank { item.id }
  val tags = item.knowledgeTags.joinToString(" ") { tag -> "#${tag.replace(Regex("\\s+"), "-")}" }
  val images = item.imageRefs.mapIndexedNotNull { index, ref ->
    ref.toObsidianMarkdownImageRef()?.let { relativeRef -> "![图片 ${index + 1}]($relativeRef)" }
  }

  return buildString {
    appendLine("---")
    appendLine("artiflow: mistake-book-item")
    appendLine("id: ${item.id}")
    appendLine("status: ${item.status.name}")
    appendLine("createdAt: ${item.createdAt}")
    appendLine("updatedAt: ${item.updatedAt}")
    appendLine("---")
    appendLine()
    appendLine("# $title")
    appendLine()
    if (item.subject.isNotBlank() || item.questionType.isNotBlank() || item.knowledgeTags.isNotEmpty()) {
      appendLine("- 学科：${item.subject.ifBlank { "未填写" }}")
      appendLine("- 题型：${item.questionType.ifBlank { "未填写" }}")
      appendLine("- 标签：${tags.ifBlank { "未填写" }}")
      appendLine()
    }
    if (item.question.isNotBlank()) {
      appendLine("## 题目")
      appendLine(item.question)
      appendLine()
    }
    if (images.isNotEmpty()) {
      appendLine("## 图片")
      images.forEach { image -> appendLine(image) }
      appendLine()
    }
    if (item.studentAnswer.isNotBlank()) {
      appendLine("## 我的答案")
      appendLine(item.studentAnswer)
      appendLine()
    }
    if (item.correctAnswer.isNotBlank()) {
      appendLine("## 正确答案")
      appendLine(item.correctAnswer)
      appendLine()
    }
    if (item.explanation.isNotBlank()) {
      appendLine("## 解析")
      appendLine(item.explanation)
      appendLine()
    }
    if (item.mistakeReason.isNotBlank()) {
      appendLine("## 错因")
      appendLine(item.mistakeReason)
      appendLine()
    }
    appendLine("## ArtIflow 数据")
    appendLine("```json")
    appendLine(itemJson)
    appendLine("```")
  }
}

internal fun renderMistakeBookIndex(items: List<MistakeBookItem>): String {
  return buildString {
    appendLine("# 错题索引")
    appendLine()
    if (items.isEmpty()) {
      appendLine("暂无错题。")
    } else {
      items.sortedByDescending { item -> item.updatedAt }.forEach { item ->
        val title = item.question.lineSequence().firstOrNull().orEmpty().trim().ifBlank { item.id }
        appendLine("- [${escapeMarkdownLinkText(title)}]($ITEMS_DIR_NAME/${noteFileName(item.id)}) · ${item.status.name}")
      }
    }
  }
}

internal fun parseMistakeBookMarkdown(raw: String): List<MistakeBookItem> {
  val json = raw.extractJsonFence() ?: return emptyList()
  return parseMistakeBookJson(json).getOrDefault(emptyList())
}

internal fun noteFileName(itemId: String): String {
  return "${encodeObsidianFileStem(itemId, defaultName = "mistake")}.md"
}

internal fun sanitizeObsidianImageFileName(raw: String): String {
  val normalized = sanitizeObsidianPathName(raw, defaultName = "mistake-image.jpg")
  val hasExtension = normalized.substringAfterLast('.', missingDelimiterValue = "").isNotBlank() &&
    normalized.substringAfterLast('.').length in 2..5
  return if (hasExtension) normalized else "$normalized.jpg"
}

private fun sanitizeObsidianPathName(raw: String, defaultName: String): String {
  val trimmed = raw.trim().ifBlank { defaultName }
  return trimmed
    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "-")
    .replace(Regex("\\s+"), "-")
    .replace(Regex("-+"), "-")
    .trim('-', '.', '_')
    .ifBlank { defaultName }
}

private fun encodeObsidianFileStem(raw: String, defaultName: String): String {
  val source = raw.trim().ifBlank { defaultName }
  val encoded = buildString {
    source.toByteArray(Charsets.UTF_8).forEach { byte ->
      val value = byte.toInt() and 0xff
      val char = value.toChar()
      if (char.isObsidianFileStemSafe()) {
        append(char)
      } else {
        append('%')
        append(HEX_CHARS[value ushr 4])
        append(HEX_CHARS[value and 0x0f])
      }
    }
  }.ifBlank { defaultName }
  return when (encoded) {
    ".", ".." -> defaultName
    else -> encoded
  }
}

private fun Char.isObsidianFileStemSafe(): Boolean {
  return this in 'a'..'z' ||
    this in 'A'..'Z' ||
    this in '0'..'9' ||
    this == '-' ||
    this == '_' ||
    this == '.'
}

internal fun resolveFolder(root: File, folder: String): File {
  val segments = folder.toSafeObsidianFolderSegments()
  return segments.fold(root) { current, segment -> File(current, segment) }
}

internal fun String.toSafeObsidianFolderSegments(): List<String> {
  return split('/', '\\')
    .map { segment -> segment.trim() }
    .filter { segment -> segment.isNotBlank() && segment != "." && segment != ".." }
    .ifEmpty { DEFAULT_OBSIDIAN_MISTAKE_FOLDER.split('/') }
}

internal fun String.toSafeObsidianImageSegments(): List<String> {
  var normalized = trim().replace('\\', '/').trimStart('/')
  while (normalized.startsWith("../")) {
    normalized = normalized.removePrefix("../")
  }
  normalized = normalized.removePrefix("./")
  require(
    normalized.isNotBlank() &&
      !normalized.startsWith("data:", ignoreCase = true) &&
      !normalized.startsWith("content:", ignoreCase = true)
  ) { "图片路径无效" }
  val segments = normalized
    .split('/')
    .map(String::trim)
    .filter(String::isNotBlank)
  require(
    segments.size == 2 &&
      segments.first() == ASSETS_DIR_NAME &&
      segments.none { segment -> segment == "." || segment == ".." }
  ) {
    "图片路径无效"
  }
  return segments
}

internal fun String.toObsidianMarkdownImageRef(): String? {
  val normalized = trim()
  if (normalized.isBlank() ||
    normalized.startsWith("data:", ignoreCase = true) ||
    normalized.startsWith("content:", ignoreCase = true)
  ) {
    return null
  }
  return runCatching { "../${toSafeObsidianImageSegments().joinToString("/")}" }.getOrNull()
}

private fun String.extractJsonFence(): String? {
  val marker = "## ArtIflow 数据"
  val markerIndex = lastIndexOf(marker)
  if (markerIndex >= 0) {
    substring(markerIndex + marker.length).extractJsonFenceToLastClosing(useFirstOpening = true)?.let { return it }
  }
  return extractJsonFenceToLastClosing(useFirstOpening = false)
}

private fun String.extractJsonFenceToLastClosing(useFirstOpening: Boolean): String? {
  val openings = Regex("```json\\s*", RegexOption.IGNORE_CASE).findAll(this)
  val opening = if (useFirstOpening) openings.firstOrNull() else openings.lastOrNull()
  if (opening == null) {
    return null
  }
  val closing = lastIndexOf("```")
  if (closing <= opening.range.last) {
    return null
  }
  return substring(opening.range.last + 1, closing).trim().takeIf(String::isNotBlank)
}

private fun escapeMarkdownLinkText(raw: String): String {
  return raw.replace("[", "\\[").replace("]", "\\]")
}

private fun writeTextToSiblingTempFile(target: File, text: String): File {
  val parent = target.parentFile ?: error("目标文件没有父目录：${target.path}")
  require(parent.exists() || parent.mkdirs()) { "无法创建 Obsidian 目录：${parent.path}" }
  val temp = File.createTempFile(".${target.name}.", ".tmp", parent)
  return temp.apply { writeText(text, Charsets.UTF_8) }
}

private fun replaceFileWithTemp(temp: File, target: File) {
  require(temp.isFile) { "Obsidian 临时文件不存在：${temp.name}" }
  require(!target.exists() || target.isFile) { "Obsidian 目标路径是目录：${target.name}" }
  try {
    Files.move(
      temp.toPath(),
      target.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE
    )
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
}

private fun resolveObsidianStoredFile(rootDir: File, ref: String): File {
  val root = rootDir.canonicalFile
  val file = ref.toSafeObsidianImageSegments()
    .fold(root) { current, segment -> File(current, segment) }
    .canonicalFile
  val rootPath = root.path
  require(file.path == rootPath || file.path.startsWith(rootPath + File.separator)) { "图片路径越界" }
  return file
}

private const val HEX_CHARS = "0123456789abcdef"
