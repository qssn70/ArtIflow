package com.studysuit.aiqa.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max

internal fun createUiImagePreviewBytes(
  imageBytes: ByteArray,
  maxDimension: Int = 960,
  jpegQuality: Int = 82
): ByteArray {
  if (imageBytes.isEmpty()) {
    return imageBytes
  }

  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
  val width = bounds.outWidth
  val height = bounds.outHeight
  if (width <= 0 || height <= 0) {
    return imageBytes
  }

  val sampleSize = calculateImagePreviewSampleSize(
    width = width,
    height = height,
    maxDimension = maxDimension
  )
  val decodeOptions = BitmapFactory.Options().apply {
    inSampleSize = sampleSize
  }
  val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
    ?: return imageBytes

  val scaled = scaleBitmapToMaxDimension(decoded, maxDimension)
  val preview = ByteArrayOutputStream().use { output ->
    val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(40, 95), output)
    if (compressed) output.toByteArray() else ByteArray(0)
  }

  if (scaled !== decoded) {
    scaled.recycle()
  }
  decoded.recycle()

  return preview.takeIf { bytes -> bytes.isNotEmpty() && bytes.size < imageBytes.size } ?: imageBytes
}

internal fun calculateImagePreviewSampleSize(
  width: Int,
  height: Int,
  maxDimension: Int
): Int {
  val longestEdge = max(width, height)
  var sampleSize = 1
  while (longestEdge / sampleSize > maxDimension * 2) {
    sampleSize *= 2
  }
  return sampleSize
}

private fun scaleBitmapToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
  val longestEdge = max(bitmap.width, bitmap.height)
  if (longestEdge <= maxDimension) {
    return bitmap
  }

  val scale = maxDimension.toFloat() / longestEdge.toFloat()
  val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
  val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
  return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}
