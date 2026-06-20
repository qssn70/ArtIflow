package com.studysuit.aiqa.ui

import java.io.File

internal data class StoredImageRef(
  val id: String,
  val path: String,
  val mimeType: String,
  val width: Int? = null,
  val height: Int? = null,
  val thumbnailPath: String? = null
)

internal class SessionImageStore(private val baseDir: File) {
  private val imageDir = File(baseDir, IMAGE_DIR_NAME)

  fun saveImages(ownerId: String, images: List<ByteArray>): List<StoredImageRef> {
    val safeOwnerId = sanitizeImageOwnerId(ownerId)
    return images
      .filter { bytes -> bytes.isNotEmpty() }
      .mapIndexedNotNull { index, bytes ->
        runCatching {
          imageDir.mkdirs()
          val mimeType = detectImageMimeType(bytes)
          val extension = extensionForMimeType(mimeType)
          val file = stableImageFile("$safeOwnerId-$index$extension")
          file.writeBytesIfChanged(bytes)
          val dimensions = detectImageDimensions(bytes)
          StoredImageRef(
            id = "$safeOwnerId-$index",
            path = "$IMAGE_DIR_NAME/${file.name}",
            mimeType = mimeType,
            width = dimensions?.width,
            height = dimensions?.height
          )
        }.getOrNull()
      }
  }

  fun readImageBytes(ref: StoredImageRef): ByteArray? {
    val file = resolveSafeImageFile(ref.path) ?: return null
    if (!file.exists() || !file.isFile) {
      return null
    }
    return runCatching { file.readBytes() }
      .getOrNull()
      ?.takeIf { bytes -> bytes.isNotEmpty() }
  }

  fun readImageBytesList(refs: List<StoredImageRef>): List<ByteArray> {
    return refs.mapNotNull(::readImageBytes)
  }

  private fun stableImageFile(fileName: String): File {
    return File(imageDir, sanitizeImageFileName(fileName))
  }

  private fun resolveSafeImageFile(path: String): File? {
    val normalizedPath = path.replace('\\', '/').trim()
    if (!normalizedPath.startsWith("$IMAGE_DIR_NAME/") || ".." in normalizedPath) {
      return null
    }

    val root = imageDir.canonicalFile
    val candidate = File(baseDir, normalizedPath).canonicalFile
    return candidate.takeIf { file -> file.path.startsWith(root.path + File.separator) }
  }

  private companion object {
    private const val IMAGE_DIR_NAME = "session_images"
  }
}

private fun File.writeBytesIfChanged(bytes: ByteArray) {
  if (exists() && length() == bytes.size.toLong()) {
    val currentBytes = runCatching { readBytes() }.getOrNull()
    if (currentBytes?.contentEquals(bytes) == true) {
      return
    }
  }
  writeBytes(bytes)
}

private fun sanitizeImageOwnerId(raw: String): String {
  return raw
    .trim()
    .replace(Regex("[^A-Za-z0-9._-]+"), "-")
    .trim('-', '.', '_')
    .ifBlank { "image" }
    .take(64)
}

private fun sanitizeImageFileName(raw: String): String {
  val sanitized = raw
    .replace('\\', '-')
    .replace('/', '-')
    .replace(Regex("[^A-Za-z0-9._-]+"), "-")
    .trim('-', '.', '_')
    .ifBlank { "image.jpg" }
  return if ('.' in sanitized) sanitized.take(96) else "${sanitized.take(88)}.jpg"
}

private fun extensionForMimeType(mimeType: String): String {
  return when (mimeType.lowercase()) {
    "image/png" -> ".png"
    "image/gif" -> ".gif"
    "image/bmp" -> ".bmp"
    "image/webp" -> ".webp"
    "image/heic" -> ".heic"
    "image/heif" -> ".heif"
    "image/avif" -> ".avif"
    else -> ".jpg"
  }
}

private data class ImageDimensions(val width: Int, val height: Int)

private fun detectImageDimensions(bytes: ByteArray): ImageDimensions? {
  return detectPngDimensions(bytes)
    ?: detectJpegDimensions(bytes)
    ?: detectGifDimensions(bytes)
    ?: detectWebpDimensions(bytes)
}

private fun detectPngDimensions(bytes: ByteArray): ImageDimensions? {
  if (bytes.size < 24 ||
    bytes[0] != 0x89.toByte() ||
    bytes[1] != 'P'.code.toByte() ||
    bytes[2] != 'N'.code.toByte() ||
    bytes[3] != 'G'.code.toByte()
  ) {
    return null
  }

  return imageDimensions(
    width = bytes.readIntBigEndian(offset = 16),
    height = bytes.readIntBigEndian(offset = 20)
  )
}

private fun detectJpegDimensions(bytes: ByteArray): ImageDimensions? {
  if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
    return null
  }

  var offset = 2
  while (offset + 9 < bytes.size) {
    while (offset < bytes.size && bytes[offset] != 0xFF.toByte()) {
      offset++
    }
    if (offset + 1 >= bytes.size) {
      return null
    }

    val marker = bytes[offset + 1].toInt() and 0xFF
    offset += 2
    if (marker == 0xD9 || marker == 0xDA) {
      return null
    }
    if (marker == 0x01 || marker in 0xD0..0xD7) {
      continue
    }
    if (offset + 2 > bytes.size) {
      return null
    }

    val segmentLength = bytes.readUnsignedShortBigEndian(offset)
    if (segmentLength < 2 || offset + segmentLength > bytes.size) {
      return null
    }
    if (marker.isJpegStartOfFrameMarker() && segmentLength >= 7) {
      return imageDimensions(
        width = bytes.readUnsignedShortBigEndian(offset + 5),
        height = bytes.readUnsignedShortBigEndian(offset + 3)
      )
    }
    offset += segmentLength
  }

  return null
}

private fun detectGifDimensions(bytes: ByteArray): ImageDimensions? {
  if (bytes.size < 10 ||
    bytes[0] != 'G'.code.toByte() ||
    bytes[1] != 'I'.code.toByte() ||
    bytes[2] != 'F'.code.toByte()
  ) {
    return null
  }

  return imageDimensions(
    width = bytes.readUnsignedShortLittleEndian(offset = 6),
    height = bytes.readUnsignedShortLittleEndian(offset = 8)
  )
}

private fun detectWebpDimensions(bytes: ByteArray): ImageDimensions? {
  if (bytes.size < 30 ||
    bytes[0] != 'R'.code.toByte() ||
    bytes[1] != 'I'.code.toByte() ||
    bytes[2] != 'F'.code.toByte() ||
    bytes[3] != 'F'.code.toByte() ||
    bytes[8] != 'W'.code.toByte() ||
    bytes[9] != 'E'.code.toByte() ||
    bytes[10] != 'B'.code.toByte() ||
    bytes[11] != 'P'.code.toByte()
  ) {
    return null
  }

  val chunk = String(bytes, 12, 4, Charsets.US_ASCII)
  return when (chunk) {
    "VP8 " -> {
      if (bytes.size < 30 || bytes[23] != 0x9D.toByte() || bytes[24] != 0x01.toByte() || bytes[25] != 0x2A.toByte()) {
        null
      } else {
        imageDimensions(
          width = bytes.readUnsignedShortLittleEndian(26) and 0x3FFF,
          height = bytes.readUnsignedShortLittleEndian(28) and 0x3FFF
        )
      }
    }

    "VP8L" -> {
      if (bytes.size < 25 || bytes[20] != 0x2F.toByte()) {
        null
      } else {
        val b1 = bytes[21].toInt() and 0xFF
        val b2 = bytes[22].toInt() and 0xFF
        val b3 = bytes[23].toInt() and 0xFF
        val b4 = bytes[24].toInt() and 0xFF
        imageDimensions(
          width = 1 + (((b2 and 0x3F) shl 8) or b1),
          height = 1 + ((b4 shl 6) or (b3 shl 2) or ((b2 and 0xC0) shr 6))
        )
      }
    }

    "VP8X" -> {
      if (bytes.size < 30) {
        null
      } else {
        imageDimensions(
          width = 1 + bytes.readUnsigned24LittleEndian(24),
          height = 1 + bytes.readUnsigned24LittleEndian(27)
        )
      }
    }

    else -> null
  }
}

private fun imageDimensions(width: Int, height: Int): ImageDimensions? {
  return if (width > 0 && height > 0) ImageDimensions(width, height) else null
}

private fun Int.isJpegStartOfFrameMarker(): Boolean {
  return this in 0xC0..0xCF && this !in setOf(0xC4, 0xC8, 0xCC)
}

private fun ByteArray.readIntBigEndian(offset: Int): Int {
  if (offset + 4 > size) {
    return -1
  }
  return ((this[offset].toInt() and 0xFF) shl 24) or
    ((this[offset + 1].toInt() and 0xFF) shl 16) or
    ((this[offset + 2].toInt() and 0xFF) shl 8) or
    (this[offset + 3].toInt() and 0xFF)
}

private fun ByteArray.readUnsignedShortBigEndian(offset: Int): Int {
  if (offset + 2 > size) {
    return -1
  }
  return ((this[offset].toInt() and 0xFF) shl 8) or
    (this[offset + 1].toInt() and 0xFF)
}

private fun ByteArray.readUnsignedShortLittleEndian(offset: Int): Int {
  if (offset + 2 > size) {
    return -1
  }
  return (this[offset].toInt() and 0xFF) or
    ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.readUnsigned24LittleEndian(offset: Int): Int {
  if (offset + 3 > size) {
    return -1
  }
  return (this[offset].toInt() and 0xFF) or
    ((this[offset + 1].toInt() and 0xFF) shl 8) or
    ((this[offset + 2].toInt() and 0xFF) shl 16)
}
