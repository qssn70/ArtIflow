package com.studysuit.aiqa.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
