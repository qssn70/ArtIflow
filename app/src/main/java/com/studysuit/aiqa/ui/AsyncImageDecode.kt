package com.studysuit.aiqa.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberDecodedImageBitmaps(imageBytes: List<ByteArray>): State<List<Bitmap>> {
  return produceState(initialValue = emptyList(), imageBytes) {
    value = withContext(Dispatchers.Default) {
      imageBytes.mapNotNull { bytes ->
        if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      }
    }
  }
}
