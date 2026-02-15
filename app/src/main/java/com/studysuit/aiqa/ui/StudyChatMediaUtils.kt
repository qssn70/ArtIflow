package com.studysuit.aiqa.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import java.io.ByteArrayOutputStream

internal fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
  val stream = ByteArrayOutputStream()
  if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
    return ByteArray(0)
  }
  return stream.toByteArray()
}

internal fun transcodeImageToJpeg(rawBytes: ByteArray): ByteArray {
  if (rawBytes.isEmpty()) {
    return ByteArray(0)
  }

  val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
  val jpegBytes = bitmapToJpeg(bitmap)
  return if (jpegBytes.isEmpty()) rawBytes else jpegBytes
}

internal fun readImageBytes(context: Context, uri: Uri): ByteArray {
  val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
    input.readBytes()
  }
  return bytes ?: ByteArray(0)
}

internal fun mergeImagesForUpload(imageBytesList: List<ByteArray>): ByteArray {
  val normalized = imageBytesList
    .asSequence()
    .map { raw -> transcodeImageToJpeg(raw) }
    .filter { bytes -> bytes.isNotEmpty() }
    .toList()

  if (normalized.isEmpty()) {
    return ByteArray(0)
  }

  if (normalized.size == 1) {
    return normalized.first()
  }

  val bitmaps = normalized.mapNotNull { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
  if (bitmaps.isEmpty()) {
    return ByteArray(0)
  }

  val targetWidth = bitmaps.maxOf { bitmap -> bitmap.width }.coerceAtMost(1440)
  val scaledBitmaps = bitmaps.map { bitmap ->
    if (bitmap.width == targetWidth) {
      bitmap
    } else {
      val targetHeight = ((bitmap.height.toFloat() * targetWidth) / bitmap.width).toInt().coerceAtLeast(1)
      Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
  }

  val gap = 6
  val totalHeight = scaledBitmaps.sumOf { bitmap -> bitmap.height } + gap * (scaledBitmaps.size - 1)
  val mergedBitmap = Bitmap.createBitmap(targetWidth, totalHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
  val canvas = Canvas(mergedBitmap)
  canvas.drawColor(Color.WHITE)

  var offsetY = 0f
  scaledBitmaps.forEachIndexed { index, bitmap ->
    canvas.drawBitmap(bitmap, 0f, offsetY, null)
    offsetY += bitmap.height
    if (index < scaledBitmaps.lastIndex) {
      offsetY += gap
    }
  }

  val mergedBytes = bitmapToJpeg(mergedBitmap)
  return if (mergedBytes.isEmpty()) normalized.first() else mergedBytes
}
