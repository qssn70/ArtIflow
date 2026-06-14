package com.studysuit.aiqa

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.studysuit.aiqa.data.MistakeReviewNotification
import com.studysuit.aiqa.ui.StudyChatApp
import com.studysuit.aiqa.ui.theme.StudySuitTheme

class MainActivity : ComponentActivity() {
  private var mistakeReviewOpenRequest by mutableIntStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    consumeMistakeReviewIntent(intent)

    setContent {
      StudySuitTheme {
        StudyChatApp(mistakeReviewOpenRequest = mistakeReviewOpenRequest)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    consumeMistakeReviewIntent(intent)
  }

  private fun consumeMistakeReviewIntent(intent: Intent?) {
    if (MistakeReviewNotification.isOpenMistakeReviewIntent(intent)) {
      mistakeReviewOpenRequest += 1
      intent?.setAction(null)
    }
  }
}
