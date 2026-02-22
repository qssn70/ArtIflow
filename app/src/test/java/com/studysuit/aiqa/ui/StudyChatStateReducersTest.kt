package com.studysuit.aiqa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyChatStateReducersTest {

  @Test
  fun queueImageQuestionState_appendsMessageAndUpdatesProfile() {
    val base = ChatUiState(input = "before")
    val userMessage = ChatMessage.User(id = "msg-1", time = "10:00", text = "拍照问题")

    val updated = queueImageQuestionState(
      current = base,
      userMessage = userMessage,
      question = "函数题怎么做",
      source = "拍照搜题"
    )

    assertEquals(1, updated.messages.size)
    assertEquals("msg-1", (updated.messages.first() as ChatMessage.User).id)
    assertFalse(updated.knowledgePoints.isEmpty())
    assertEquals(0, updated.profile.followups)
  }

  @Test
  fun queueQuestionState_handlesInputAndFollowupFlags() {
    val base = ChatUiState(input = "原输入")
    val userMessage = ChatMessage.User(id = "msg-2", time = "10:01", text = "追问")

    val updated = queueQuestionState(
      current = base,
      userMessage = userMessage,
      question = "导数最值怎么求",
      isFollowup = true,
      isVoice = false,
      clearInput = true
    )

    assertEquals("", updated.input)
    assertEquals(1, updated.messages.size)
    assertEquals(1, updated.profile.followups)
    assertEquals(0, updated.profile.voiceFollowups)
  }

  @Test
  fun queueSpanFollowupState_marksProcessingAndVoiceCounters() {
    val base = ChatUiState(input = "待发送输入")

    val updated = queueSpanFollowupState(
      current = base,
      spanId = "span-9",
      question = "这个力学题为什么这么列式",
      isVoice = true,
      clearInput = true
    )

    assertEquals("", updated.input)
    assertTrue(updated.processingSpanIds.contains("span-9"))
    assertEquals(1, updated.profile.followups)
    assertEquals(1, updated.profile.voiceFollowups)
  }

  @Test
  fun appendAssistantMessageState_appendsMessageAndToast() {
    val base = ChatUiState(
      messages = listOf(ChatMessage.User(id = "msg-1", time = "10:00", text = "问题"))
    )
    val assistant = ChatMessage.Assistant(
      id = "msg-2",
      time = "10:01",
      spans = listOf(SpanData(id = "span-1", content = "答案", sourceQuestion = "问题"))
    )

    val updated = appendAssistantMessageState(
      current = base,
      assistantMessage = assistant,
      toastMessage = "完成",
      knowledgeTexts = listOf("导数与最值")
    )

    assertEquals(2, updated.messages.size)
    assertEquals("完成", updated.toastMessage)
    assertFalse(updated.knowledgePoints.isEmpty())
  }

  @Test
  fun upsertAssistantMessageState_replacesMessageWithSameId() {
    val base = ChatUiState(
      messages = listOf(
        ChatMessage.User(id = "msg-1", time = "10:00", text = "问题"),
        ChatMessage.Assistant(
          id = "msg-2",
          time = "10:01",
          spans = listOf(SpanData(id = "span-1", content = "旧内容", sourceQuestion = "问题"))
        )
      )
    )
    val updatedAssistant = ChatMessage.Assistant(
      id = "msg-2",
      time = "10:01",
      spans = listOf(SpanData(id = "span-2", content = "新内容", sourceQuestion = "问题"))
    )

    val updated = upsertAssistantMessageState(
      current = base,
      assistantMessage = updatedAssistant
    )

    assertEquals(2, updated.messages.size)
    val assistant = updated.messages[1] as ChatMessage.Assistant
    assertEquals("新内容", assistant.spans.first().content)
  }

  @Test
  fun rollbackQueuedUserMessageState_removesFailedMessageAndRestoresInputWhenEmpty() {
    val base = ChatUiState(
      input = "",
      messages = listOf(
        ChatMessage.User(id = "msg-1", time = "10:00", text = "上一条"),
        ChatMessage.Assistant(
          id = "msg-2",
          time = "10:01",
          spans = listOf(SpanData(id = "span-1", content = "上一条回答", sourceQuestion = "上一条"))
        ),
        ChatMessage.User(id = "msg-3", time = "10:02", text = "这条超时")
      )
    )

    val updated = rollbackQueuedUserMessageState(
      current = base,
      messageId = "msg-3",
      restoredInput = "这条超时",
      toastMessage = "回答失败：timeout"
    )

    assertEquals(2, updated.messages.size)
    assertTrue(updated.messages.none { message -> message.id == "msg-3" })
    assertEquals("这条超时", updated.input)
    assertEquals("回答失败：timeout", updated.toastMessage)
  }

  @Test
  fun rollbackQueuedUserMessageState_doesNotOverrideExistingInput() {
    val base = ChatUiState(
      input = "正在输入新问题",
      messages = listOf(
        ChatMessage.User(id = "msg-1", time = "10:00", text = "超时问题")
      )
    )

    val updated = rollbackQueuedUserMessageState(
      current = base,
      messageId = "msg-1",
      restoredInput = "超时问题"
    )

    assertEquals("正在输入新问题", updated.input)
    assertTrue(updated.messages.isEmpty())
  }
}
