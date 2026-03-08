package com.studysuit.aiqa.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.studysuit.aiqa.data.ImagePayload
import java.io.File

internal fun readImageBytes(context: Context, uri: Uri): ByteArray {
  val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
    input.readBytes()
  }
  return bytes ?: ByteArray(0)
}

internal fun resolveImageMimeType(context: Context, uri: Uri, rawBytes: ByteArray): String {
  val contentType = context.contentResolver.getType(uri)
    ?.trim()
    ?.lowercase()
    ?.takeIf { mimeType -> mimeType.startsWith("image/") }

  return contentType ?: detectImageMimeType(rawBytes)
}

internal fun detectImageMimeType(rawBytes: ByteArray, fallback: String = "image/jpeg"): String {
  if (rawBytes.size < 12) {
    return fallback
  }

  return when {
    rawBytes.matchesSignature(0xFF, 0xD8, 0xFF) -> "image/jpeg"
    rawBytes.matchesSignature(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
    rawBytes.matchesSignature(0x47, 0x49, 0x46, 0x38) -> "image/gif"
    rawBytes.matchesSignature(0x42, 0x4D) -> "image/bmp"
    rawBytes.matchesAscii("RIFF") && rawBytes.matchesAscii("WEBP", offset = 8) -> "image/webp"
    rawBytes.matchesAscii("ftyp", offset = 4) -> {
      when (rawBytes.readAscii(8, 4)) {
        "heic", "heix", "hevc", "hevx", "heim", "heis" -> "image/heic"
        "mif1", "msf1" -> "image/heif"
        "avif", "avis" -> "image/avif"
        else -> fallback
      }
    }

    else -> fallback
  }
}

internal fun toImagePayloads(imageBytesList: List<ByteArray>): List<ImagePayload> {
  return imageBytesList
    .asSequence()
    .filter { bytes -> bytes.isNotEmpty() }
    .map { bytes -> ImagePayload(bytes = bytes, mimeType = detectImageMimeType(bytes)) }
    .toList()
}

internal fun createCameraCaptureUri(context: Context): Uri {
  val captureDirectory = File(context.cacheDir, "captured_images").apply {
    mkdirs()
  }
  val imageFile = File.createTempFile(
    "capture-${System.currentTimeMillis()}-",
    ".jpg",
    captureDirectory
  )
  return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
}

internal fun deleteCapturedImage(context: Context, uri: Uri?) {
  if (uri == null) {
    return
  }

  runCatching {
    context.contentResolver.delete(uri, null, null)
  }
}

private fun ByteArray.matchesSignature(vararg signature: Int, offset: Int = 0): Boolean {
  if (size < offset + signature.size) {
    return false
  }

  return signature.indices.all { index ->
    this[offset + index].toInt() and 0xFF == signature[index]
  }
}

private fun ByteArray.matchesAscii(value: String, offset: Int = 0): Boolean {
  val bytes = value.encodeToByteArray()
  if (size < offset + bytes.size) {
    return false
  }

  return bytes.indices.all { index ->
    this[offset + index] == bytes[index]
  }
}

private fun ByteArray.readAscii(offset: Int, length: Int): String {
  if (size < offset + length) {
    return ""
  }

  return decodeToString(startIndex = offset, endIndex = offset + length)
}
