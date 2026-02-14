package com.studysuit.aiqa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.CancellationException

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
  fun sortAnkiCardsForReview_ordersByMasteryThenRecency() {
    val cards = listOf(
      AnkiCard(
        id = "card-1",
        front = "Q1",
        back = "A1",
        tags = emptyList(),
        source = "s",
        createdAt = 10L,
        mastery = CardMasteryLevel.UNRATED
      ),
      AnkiCard(
        id = "card-2",
        front = "Q2",
        back = "A2",
        tags = emptyList(),
        source = "s",
        createdAt = 50L,
        mastery = CardMasteryLevel.PROFICIENT
      ),
      AnkiCard(
        id = "card-3",
        front = "Q3",
        back = "A3",
        tags = emptyList(),
        source = "s",
        createdAt = 30L,
        mastery = CardMasteryLevel.NEEDS_WORK
      ),
      AnkiCard(
        id = "card-4",
        front = "Q4",
        back = "A4",
        tags = emptyList(),
        source = "s",
        createdAt = 40L,
        mastery = CardMasteryLevel.UNRATED
      )
    )

    val ordered = sortAnkiCardsForReview(cards)

    assertEquals(listOf("card-4", "card-1", "card-3", "card-2"), ordered.map { card -> card.id })
  }

  @Test
  fun resolveDeckNameForAutoCard_prefersExistingDeckBySuggestionOrTagOverlap() {
    val existing = listOf(
      AnkiCard(
        id = "card-1",
        front = "Q1",
        back = "A1",
        tags = listOf("函数", "导数"),
        source = "s",
        createdAt = 1L,
        deckName = "函数"
      ),
      AnkiCard(
        id = "card-2",
        front = "Q2",
        back = "A2",
        tags = listOf("电磁", "电流"),
        source = "s",
        createdAt = 2L,
        deckName = "电学"
      )
    )

    val bySuggestion = resolveDeckNameForAutoCard(
      suggestedDeck = "函数",
      tags = listOf("代数"),
      existingCards = existing
    )
    val byOverlap = resolveDeckNameForAutoCard(
      suggestedDeck = null,
      tags = listOf("电流", "欧姆定律"),
      existingCards = existing
    )

    assertEquals("函数", bySuggestion)
    assertEquals("电学", byOverlap)
  }

  @Test
  fun resolveDeckNameForAutoCard_createsDeckWhenNoExistingMatch() {
    val existing = listOf(
      AnkiCard(
        id = "card-1",
        front = "Q1",
        back = "A1",
        tags = listOf("函数"),
        source = "s",
        createdAt = 1L,
        deckName = "函数"
      )
    )

    val newDeck = resolveDeckNameForAutoCard(
      suggestedDeck = null,
      tags = listOf("化学平衡"),
      existingCards = existing
    )

    assertEquals("化学平衡卡组", newDeck)
  }

  @Test
  fun buildAnkiDeckSummaries_groupsAndSortsByCardCount() {
    val cards = listOf(
      AnkiCard(
        id = "card-1",
        front = "Q1",
        back = "A1",
        tags = emptyList(),
        source = "s",
        createdAt = 1L,
        deckName = "函数"
      ),
      AnkiCard(
        id = "card-2",
        front = "Q2",
        back = "A2",
        tags = emptyList(),
        source = "s",
        createdAt = 2L,
        deckName = "函数"
      ),
      AnkiCard(
        id = "card-3",
        front = "Q3",
        back = "A3",
        tags = emptyList(),
        source = "s",
        createdAt = 3L,
        deckName = "电学"
      )
    )

    val summaries = buildAnkiDeckSummaries(cards)

    assertEquals(listOf("函数", "电学"), summaries.map { deck -> deck.name })
    assertEquals(listOf(2, 1), summaries.map { deck -> deck.cardCount })
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
  fun buildSyncedSessionSnapshot_reusesCreatedAtAndUpdatesTitle() {
    val state = ChatUiState(
      messages = listOf(
        ChatMessage.User(id = "msg-1", time = "10:00", text = "极限题怎么做")
      ),
      activeSessionId = "session-1"
    )

    val synced = buildSyncedSessionSnapshot(
      state = state,
      fallbackTime = "11:22",
      now = 200L,
      existingCreatedAt = 100L
    )

    assertEquals("session-1", synced.id)
    assertEquals("极限题怎么做", synced.title)
    assertEquals(100L, synced.createdAt)
    assertEquals(200L, synced.updatedAt)
  }

  @Test
  fun buildPersistedSessionsPayload_handlesBlankAndActiveSessionId() {
    val blankState = ChatUiState(activeSessionId = "  ")
    assertNull(buildPersistedSessionsPayload(blankState, sessions = emptyList()))

    val settings = RuntimeSettings.defaults().copy(arkApiKey = "test-key")
    val activeState = ChatUiState(activeSessionId = "session-2", settings = settings)
    val stored = toStoredSessionSnapshot(
      state = activeState,
      title = "会话",
      createdAt = 1L,
      updatedAt = 2L
    )

    val payload = buildPersistedSessionsPayload(activeState, sessions = listOf(stored))

    assertEquals("session-2", payload?.activeSessionId)
    assertEquals("test-key", payload?.settings?.arkApiKey)
    assertEquals(1, payload?.sessions?.size)
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

  @Test
  fun isTokenStale_detectsTokenMismatch() {
    assertTrue(isTokenStale(requestToken = 2L, activeToken = 3L))
    assertEquals(false, isTokenStale(requestToken = 5L, activeToken = 5L))
  }

  @Test
  fun deliverTokenAwareResult_staleTokenOnlyTriggersStaleCallback() {
    var staleCalled = false
    var successCalled = false
    var failureCalled = false

    deliverTokenAwareResult(
      result = Result.success("ok"),
      requestToken = 10L,
      activeToken = 11L,
      onStale = { staleCalled = true },
      onSuccess = { successCalled = true },
      onFailure = { failureCalled = true }
    )

    assertTrue(staleCalled)
    assertFalse(successCalled)
    assertFalse(failureCalled)
  }

  @Test
  fun deliverTokenAwareResult_matchingTokenRoutesResultCallbacks() {
    var successValue: String? = null
    var failureValue: Throwable? = null

    deliverTokenAwareResult(
      result = Result.success("done"),
      requestToken = 12L,
      activeToken = 12L,
      onStale = { fail("stale callback should not be called") },
      onSuccess = { value -> successValue = value },
      onFailure = { throwable -> failureValue = throwable }
    )

    assertEquals("done", successValue)
    assertEquals(null, failureValue)

    val failure = RuntimeException("network")
    successValue = null
    failureValue = null

    deliverTokenAwareResult(
      result = Result.failure<String>(failure),
      requestToken = 13L,
      activeToken = 13L,
      onStale = { fail("stale callback should not be called") },
      onSuccess = { value -> successValue = value },
      onFailure = { throwable -> failureValue = throwable }
    )

    assertEquals(null, successValue)
    assertSame(failure, failureValue)
  }

  @Test
  fun routeRequestFailure_handlesCancelAndErrorSeparately() {
    var cancelCalled = false
    assertThrows(CancellationException::class.java) {
      routeRequestFailure(
        throwable = CancellationException("cancelled"),
        onCancel = { cancelCalled = true },
        onError = { fail("error callback should not be called") }
      )
    }
    assertTrue(cancelCalled)

    var errorHint: String? = null
    routeRequestFailure(
      throwable = RuntimeException(""),
      fallback = "网络不可用",
      onCancel = { fail("cancel callback should not be called") },
      onError = { hint -> errorHint = hint }
    )

    assertEquals("网络不可用", errorHint)
  }

  @Test
  fun extractFencedJsonCandidate_parsesMarkdownFencedJson() {
    val raw = """
      ```json
      {
        "skip": false,
        "front": "牛顿第二定律",
        "back": "F=ma",
        "tags": ["物理", "力学"]
      }
      ```
    """.trimIndent()

    val json = extractFencedJsonCandidate(raw)

    assertEquals(
      """
      {
        "skip": false,
        "front": "牛顿第二定律",
        "back": "F=ma",
        "tags": ["物理", "力学"]
      }
      """.trimIndent(),
      json
    )
  }

  @Test
  fun resolveErrorHint_usesFallbackAndTruncatesMessage() {
    val fallback = resolveErrorHint(throwable = null, fallback = "网络不可用")
    val longMessage = "x".repeat(120)
    val resolved = resolveErrorHint(RuntimeException(longMessage), fallback = "unused")

    assertEquals("网络不可用", fallback)
    assertEquals(80, resolved.length)
  }
}
