package com.studysuit.aiqa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyChatViewModelSupportTest {

  @Test
  fun toArkMessages_filtersIntroAndBlankUserInput() {
    val messages = listOf(
      ChatMessage.Assistant(
        id = "msg-1",
        time = "10:00",
        spans = listOf(SpanData(id = "span-1", content = "引导", sourceQuestion = "初始化引导"))
      ),
      ChatMessage.User(id = "msg-2", time = "10:01", text = "   "),
      ChatMessage.User(id = "msg-3", time = "10:02", text = "二次函数最值怎么做"),
      ChatMessage.Assistant(
        id = "msg-4",
        time = "10:03",
        spans = listOf(
          SpanData(id = "span-2", content = "先配方", sourceQuestion = "q"),
          SpanData(id = "span-3", content = "再求顶点", sourceQuestion = "q")
        )
      )
    )

    val requestMessages = toArkMessages(messages)

    assertEquals(2, requestMessages.size)
    assertEquals("user", requestMessages[0].role)
    assertEquals("assistant", requestMessages[1].role)
    assertTrue(requestMessages[1].text.contains("先配方"))
  }

  @Test
  fun toSpanFollowupMessages_containsContextHistoryAndFollowup() {
    val span = SpanData(id = "span-1", content = "这是段落", sourceQuestion = "q")
    val details = listOf(
      SpanDetail(id = "detail-1", mode = "自动讲解", time = "10:00", question = "q1", answer = "a1"),
      SpanDetail(id = "detail-2", mode = "自动讲解", time = "10:01", question = "q2", answer = "a2")
    )

    val messages = toSpanFollowupMessages(span, "新的追问", details)

    assertTrue(messages.first().text.contains("这是段落"))
    assertEquals("新的追问", messages.last().text)
    assertTrue(messages.any { message -> message.text == "q1" })
    assertTrue(messages.any { message -> message.text == "a2" })
  }

  @Test
  fun splitParagraphs_prefersBlankLineBlocks() {
    val content = "第一段\n\n第二段\n\n第三段"

    val blocks = splitParagraphs(content)

    assertEquals(listOf("第一段", "第二段", "第三段"), blocks)
  }

  @Test
  fun prependAnkiCard_prependsAndDeduplicates() {
    val existing = listOf(
      AnkiCard(id = "card-1", front = "Q1", back = "A1", tags = emptyList(), source = "s", createdAt = 1L),
      AnkiCard(id = "card-2", front = "Q2", back = "A2", tags = emptyList(), source = "s", createdAt = 2L)
    )
    val duplicate = AnkiCard(
      id = "card-3",
      front = "Q1",
      back = "A1",
      tags = listOf("代数"),
      source = "new",
      createdAt = 3L
    )

    val merged = prependAnkiCard(existing, duplicate)

    assertEquals("card-3", merged.first().id)
    assertEquals(2, merged.size)
    assertTrue(merged.none { card -> card.id == "card-1" })
  }

  @Test
  fun buildSessionTitle_usesUserMessageOrFallbackTime() {
    val withUser = listOf(
      ChatMessage.User(id = "msg-1", time = "10:00", text = "  这是一个很长很长的问题标题会被截断  ")
    )
    val withoutUser = listOf(
      ChatMessage.Assistant(
        id = "msg-2",
        time = "10:01",
        spans = listOf(SpanData(id = "span-1", content = "内容", sourceQuestion = "q"))
      )
    )

    val titleWithUser = buildSessionTitle(withUser, "11:22")
    val fallbackTitle = buildSessionTitle(withoutUser, "11:22")

    assertEquals("这是一个很长很长的问题标题会被截断", titleWithUser)
    assertEquals("新会话 11:22", fallbackTitle)
  }

  @Test
  fun deriveSessionSeeds_returnsMaxNumericSuffixes() {
    val state = ChatUiState(
      messages = listOf(
        ChatMessage.User(id = "msg-3", time = "10:00", text = "q"),
        ChatMessage.Assistant(
          id = "msg-7",
          time = "10:01",
          spans = listOf(
            SpanData(id = "span-2", content = "a", sourceQuestion = "q"),
            SpanData(id = "span-9", content = "b", sourceQuestion = "q")
          )
        )
      ),
      histories = mapOf(
        "span-9" to listOf(
          SpanDetail(id = "detail-4", mode = "自动讲解", time = "10:02", answer = "a")
        )
      ),
      ankiCards = listOf(
        AnkiCard(id = "card-6", front = "q", back = "a", tags = emptyList(), source = "src", createdAt = 1L)
      ),
      activeSessionId = "session-1"
    )
    val session = toStoredSessionSnapshot(
      state = state,
      title = "会话",
      createdAt = 1L,
      updatedAt = 2L
    )

    val seeds = deriveSessionSeeds(session)

    assertEquals(7, seeds.messageSeed)
    assertEquals(9, seeds.spanSeed)
    assertEquals(4, seeds.detailSeed)
    assertEquals(6, seeds.cardSeed)
  }

  @Test
  fun spanProcessingHelpers_updateProcessingAndHistory() {
    val base = ChatUiState(
      histories = mapOf(
        "span-1" to listOf(SpanDetail(id = "detail-1", mode = "自动讲解", time = "10:00", answer = "old"))
      )
    )

    val marked = markSpanProcessing(base, "span-1")
    val appended = appendSpanDetailHistory(
      current = marked,
      spanId = "span-1",
      detail = SpanDetail(id = "detail-2", mode = "追问", time = "10:01", question = "q", answer = "new"),
      toastMessage = "done"
    )
    val cleared = clearSpanProcessing(appended, "span-1", toastMessage = "cleared")

    assertTrue(marked.processingSpanIds.contains("span-1"))
    assertEquals("detail-2", appended.histories["span-1"]?.firstOrNull()?.id)
    assertEquals("done", appended.toastMessage)
    assertTrue(cleared.processingSpanIds.isEmpty())
    assertEquals("cleared", cleared.toastMessage)
  }
}
