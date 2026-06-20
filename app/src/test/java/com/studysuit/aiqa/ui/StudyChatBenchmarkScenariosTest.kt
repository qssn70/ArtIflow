package com.studysuit.aiqa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyChatBenchmarkScenariosTest {

  @Test
  fun chatScrollScenarioBuildsOneHundredMessages() {
    val state = buildBenchmarkScenarioState(BenchmarkScenario.CHAT_100_SCROLL)

    assertEquals(WorkspacePage.CHAT, state.activePage)
    assertEquals(100, state.messages.size)
    assertTrue(state.messages.last() is ChatMessage.Assistant)
    assertTrue((state.messages.last() as ChatMessage.Assistant).fullAnswerText().contains("第 100 条聊天消息"))
  }

  @Test
  fun mistakeSearchScenarioBuildsTwoHundredMistakes() {
    val state = buildBenchmarkScenarioState(BenchmarkScenario.MISTAKE_200_SEARCH)

    assertEquals(WorkspacePage.MISTAKES, state.activePage)
    assertEquals(200, state.mistakeItems.size)
    assertTrue(state.mistakeItems.last().question.contains("第 200 道错题"))
  }

  @Test
  fun threeImageScenarioUsesOriginalUiPreviewBytes() {
    val state = buildBenchmarkScenarioState(BenchmarkScenario.THREE_IMAGE_PREVIEW)
    val user = state.messages.first() as ChatMessage.User

    assertEquals(WorkspacePage.CHAT, state.activePage)
    assertEquals(3, user.imagePreviewList.size)
    assertTrue(user.imagePreviewList.all { bytes -> bytes.isNotEmpty() })
  }

  @Test
  fun streamScenarioStartsWithStreamingAssistantMessage() {
    val state = buildBenchmarkScenarioState(BenchmarkScenario.STREAM_2_MINUTES)
    val assistant = state.messages.last() as ChatMessage.Assistant

    assertEquals(WorkspacePage.CHAT, state.activePage)
    assertTrue(state.isLoading)
    assertTrue(assistant.fullAnswerText().contains("流式回答性能样本"))
  }
}
