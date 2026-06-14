package com.studysuit.aiqa.data

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class MlKitMistakeOcrClient : MistakeOcrClient {
  private val recognizer = TextRecognition.getClient(
    ChineseTextRecognizerOptions.Builder().build()
  )

  override suspend fun recognizeText(imageBytesList: List<ByteArray>): Result<String> {
    val normalizedImages = imageBytesList.filter { bytes -> bytes.isNotEmpty() }
    if (normalizedImages.isEmpty()) {
      return Result.failure(IllegalArgumentException("图片数据为空"))
    }

    return runCatching {
      normalizedImages
        .mapIndexed { index, bytes -> recognizeOne(bytes).trim().let { text -> index to text } }
        .filter { (_, text) -> text.isNotBlank() }
        .joinToString(separator = "\n\n") { (index, text) -> "图片${index + 1}：\n$text" }
        .trim()
    }.mapCatching { text ->
      if (text.isBlank()) {
        throw IllegalStateException("OCR 未识别到文字")
      }
      text
    }
  }

  private suspend fun recognizeOne(bytes: ByteArray): String = suspendCoroutine { continuation ->
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null) {
      continuation.resumeWithException(IllegalArgumentException("图片解码失败"))
      return@suspendCoroutine
    }

    recognizer.process(InputImage.fromBitmap(bitmap, 0))
      .addOnSuccessListener { result -> continuation.resume(result.text.orEmpty()) }
      .addOnFailureListener { error -> continuation.resumeWithException(error) }
  }
}
