package com.studysuit.aiqa.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.studysuit.aiqa.TestComposeActivity
import com.studysuit.aiqa.ui.theme.StudySuitTheme
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class StudyChatMessagesInstrumentedTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<TestComposeActivity>()

  @Test
  fun userBubbleDisplaysDecodedImagePreview() {
    composeRule.setContent {
      StudySuitTheme {
        UserBubble(
          message = ChatMessage.User(
            id = "user-image",
            time = "10:00",
            text = "带图题",
            imagePreviewList = listOf(createPngBytes())
          )
        )
      }
    }

    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasContentDescription("题目图片")).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("带图题").assertIsDisplayed()
  }

  @Test
  fun chatMessageListScrollsThroughOneHundredItems() {
    val messages = (1..100).map { index ->
      ChatMessage.User(
        id = "user-$index",
        time = "10:${index.toString().padStart(2, '0')}",
        text = "第 $index 条聊天消息"
      )
    }

    composeRule.setContent {
      StudySuitTheme {
        LazyColumn(
          modifier = Modifier.testTag("chat-message-list"),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          items(
            items = messages,
            key = { message -> message.id },
            contentType = { "chat-user" }
          ) { message ->
            UserBubble(message = message)
          }
        }
      }
    }

    composeRule
      .onNode(hasText("第 1 条聊天消息"))
      .assertIsDisplayed()
    composeRule
      .onNodeWithTag("chat-message-list")
      .performScrollToNode(hasText("第 100 条聊天消息"))
    composeRule
      .onNodeWithText("第 100 条聊天消息")
      .assertIsDisplayed()
  }

  @Test
  fun assistantBubbleDisplaysFormulaMessage() {
    composeRule.setContent {
      StudySuitTheme {
        AssistantBubble(
          message = ChatMessage.Assistant(
            id = "assistant-formula",
            time = "10:01",
            spans = listOf(
              SpanData(
                id = "span-formula",
                content = "公式消息：\\(x^2 + y^2 = z^2\\)，因此可以用勾股定理。",
                sourceQuestion = "证明直角三角形公式"
              )
            )
          ),
          histories = emptyMap(),
          processingSpanIds = emptySet(),
          onAutoExplain = {},
          onWholeReplyFollowup = { _, _ -> },
          onRefreshReply = {},
          onToggleSavedQuestion = {},
          refreshEnabled = true,
          showSaveAction = true,
          isSavedQuestion = false,
          onVoiceFollowup = {},
          onRightSwipeOpenDetails = { _, _ -> },
          onRightSwipeHoldFollowup = { _, _ -> },
          onOpenDetails = { _, _ -> },
          onVoiceCaptureStart = {},
          onVoiceCaptureCancel = {}
        )
      }
    }

    composeRule
      .onNode(hasContentDescription("公式消息", substring = true))
      .assertIsDisplayed()
  }

  private fun createPngBytes(): ByteArray {
    val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(47, 124, 103))
    }
    return ByteArrayOutputStream().use { output ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
      output.toByteArray()
    }
  }
}
