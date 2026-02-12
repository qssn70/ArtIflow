package com.studysuit.aiqa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.studysuit.aiqa.ui.StudyChatApp
import com.studysuit.aiqa.ui.theme.StudySuitTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      StudySuitTheme {
        StudyChatApp()
      }
    }
  }
}
