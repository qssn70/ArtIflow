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
import java.net.SocketTimeoutException

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
  fun toArkMessages_prefersMainSpanAsAssistantQuestionScopeText() {
    val messages = listOf(
      ChatMessage.User(id = "msg-1", time = "10:00", text = "题目"),
      ChatMessage.Assistant(
        id = "msg-2",
        time = "10:01",
        spans = listOf(
          SpanData(id = "span-1", content = "第一段", sourceQuestion = "题目"),
          SpanData(id = "span-2", content = "第二段", sourceQuestion = "题目")
        ),
        mainSpan = SpanData(id = "span-main", content = "整题统一回答", sourceQuestion = "题目")
      )
    )

    val requestMessages = toArkMessages(messages)

    assertEquals(2, requestMessages.size)
    assertEquals("整题统一回答", requestMessages[1].text)
  }

  @Test
  fun toSpanFollowupMessages_containsContextHistoryAndFollowup() {
    val span = SpanData(id = "span-1", content = "这是段落", sourceQuestion = "完整题目")
    val sourceAnswer = ChatMessage.Assistant(
      id = "msg-2",
      time = "10:01",
      spans = listOf(
        span,
        SpanData(id = "span-2", content = "补充说明", sourceQuestion = "完整题目")
      )
    )
    val conversation = listOf(
      ChatMessage.User(id = "msg-1", time = "10:00", text = "完整题目"),
      sourceAnswer
    )
    val details = listOf(
      SpanDetail(id = "detail-1", mode = "自动讲解", time = "10:00", question = "q1", answer = "a1"),
      SpanDetail(id = "detail-2", mode = "自动讲解", time = "10:01", question = "q2", answer = "a2")
    )

    val messages = toSpanFollowupMessages(span, "新的追问", details, conversation)

    assertTrue(messages.first().text.contains("题目：完整题目"))
    assertTrue(messages.first().text.contains("该题完整回答：这是段落"))
    assertTrue(messages.first().text.contains("补充说明"))
    assertTrue(messages.first().text.contains("这是段落"))
    assertFalse(messages.first().text.contains("只讨论这一段"))
    assertEquals("新的追问", messages.last().text)
    assertTrue(messages.any { message -> message.text == "q1" })
    assertTrue(messages.any { message -> message.text == "a2" })
  }

  @Test
  fun toSpanFollowupMessages_usesSourceUserQuestionWhenSpanQuestionBlank() {
    val span = SpanData(id = "span-1", content = "这是段落", sourceQuestion = "   ")
    val conversation = listOf(
      ChatMessage.User(id = "msg-1", time = "10:00", text = "原题：求二次函数的最值"),
      ChatMessage.Assistant(
        id = "msg-2",
        time = "10:01",
        spans = listOf(span)
      )
    )

    val messages = toSpanFollowupMessages(span, "继续追问", details = emptyList(), messages = conversation)

    assertTrue(messages.first().text.contains("题目：原题：求二次函数的最值"))
    assertFalse(messages.first().text.contains("原题缺失"))
  }

  @Test
  fun buildDetailCardSummary_mergesQuestionAndAnswerSnippet() {
    val summary = buildDetailCardSummary(
      question = "为什么这里要先配方再求顶点？",
      answer = "先配方可以直接得到顶点式。```kotlin\nval x = 1\n```再根据顶点坐标判断最值。"
    )

    assertTrue(summary.startsWith("为什么这里要先配方再求顶点"))
    assertTrue(summary.contains("先配方可以直接得到顶点式"))
    assertFalse(summary.contains("```"))
  }

  @Test
  fun buildFollowupTreeExportMarkdown_containsHierarchyAndFallbackSummary() {
    val scopes = listOf(
      FollowupTreeScope(
        spanId = "span-1",
        spanContent = "二次函数最值判断",
        sourceQuestion = "这题怎么做",
        details = listOf(
          SpanDetail(
            id = "detail-2",
            mode = "精细追问",
            time = "10:02",
            question = "那a<0时怎么办？",
            answer = "当 a<0 时开口向下，所以最大值在顶点。",
            parentDetailId = "detail-1"
          ),
          SpanDetail(
            id = "detail-1",
            mode = "自动讲解",
            time = "10:01",
            answer = "先配方得到顶点式，再读出最值。",
            summary = "配方后看顶点"
          )
        )
      )
    )

    val markdown = buildFollowupTreeExportMarkdown(scopes, exportedAtMillis = 0L)

    assertTrue(markdown.contains("# 追问图谱导出"))
    assertTrue(markdown.contains("段落ID：span-1"))
    assertTrue(markdown.contains("追问节点数：2"))
    assertTrue(markdown.contains("- 自动讲解 · 10:01"))
    assertTrue(markdown.contains("- 精细追问 · 10:02"))
    assertTrue(markdown.contains("摘要：配方后看顶点"))
    assertTrue(markdown.contains("父节点：detail-1"))
  }

  @Test
  fun buildFollowupTreeExportMarkdown_returnsHintWhenNoScope() {
    val markdown = buildFollowupTreeExportMarkdown(emptyList(), exportedAtMillis = 0L)

    assertEquals("暂无追问图谱可导出。", markdown)
  }

  @Test
  fun buildQuestionWorkspaceMessages_preservesOriginalSpanCards() {
    val assistant = ChatMessage.Assistant(
      id = "msg-assistant",
      time = "10:01",
      spans = listOf(
        SpanData(id = "span-1", content = "第一张卡", sourceQuestion = "原题"),
        SpanData(id = "span-2", content = "第二张卡", sourceQuestion = "原题")
      ),
      mainSpan = SpanData(id = "span-main", content = "整题回答", sourceQuestion = "原题")
    )
    val sourceUser = ChatMessage.User(id = "msg-user", time = "10:00", text = "原题")

    val messages = buildQuestionWorkspaceMessages(assistantMessage = assistant, sourceUserMessage = sourceUser)

    assertEquals(2, messages.size)
    assertEquals("msg-user", (messages[0] as ChatMessage.User).id)
    val rebuiltAssistant = messages[1] as ChatMessage.Assistant
    assertEquals("msg-assistant", rebuiltAssistant.id)
    assertEquals(listOf("span-1", "span-2"), rebuiltAssistant.spans.map { span -> span.id })
    assertEquals("span-main", rebuiltAssistant.mainSpan?.id)
  }

  @Test
  fun buildQuestionWorkspaceHistories_preservesFollowupsForEachCard() {
    val assistant = ChatMessage.Assistant(
      id = "msg-assistant",
      time = "10:01",
      spans = listOf(
        SpanData(id = "span-1", content = "第一张卡", sourceQuestion = "原题"),
        SpanData(id = "span-2", content = "第二张卡", sourceQuestion = "原题")
      ),
      mainSpan = SpanData(id = "span-main", content = "整题回答", sourceQuestion = "原题")
    )
    val histories = mapOf(
      "span-main" to listOf(SpanDetail(id = "detail-main", mode = "精细追问", time = "10:02", question = "整题追问", answer = "整题答复")),
      "span-1" to listOf(SpanDetail(id = "detail-1", mode = "自动讲解", time = "10:03", question = "卡1追问", answer = "卡1答复")),
      "span-2" to listOf(SpanDetail(id = "detail-2", mode = "自动讲解", time = "10:04", question = "卡2追问", answer = "卡2答复"))
    )

    val rebuilt = buildQuestionWorkspaceHistories(assistantMessage = assistant, histories = histories)

    assertEquals(3, rebuilt.size)
    assertEquals(listOf("detail-main"), rebuilt["span-main"]?.map { detail -> detail.id })
    assertEquals(listOf("detail-1"), rebuilt["span-1"]?.map { detail -> detail.id })
    assertEquals(listOf("detail-2"), rebuilt["span-2"]?.map { detail -> detail.id })
  }

  @Test
  fun filterFollowupTreeScopesForWorkspace_keepsOnlyCurrentQuestionInQuestionWorkspace() {
    val scopes = listOf(
      FollowupTreeScope(spanId = "span-main", spanContent = "整题", sourceQuestion = "题1", details = emptyList()),
      FollowupTreeScope(spanId = "span-1", spanContent = "分卡1", sourceQuestion = "题1", details = emptyList()),
      FollowupTreeScope(spanId = "span-other", spanContent = "别的题", sourceQuestion = "题2", details = emptyList())
    )

    val filtered = filterFollowupTreeScopesForWorkspace(
      scopes = scopes,
      activePage = WorkspacePage.QUICK_FOLLOWUP,
      activeSpanId = "span-main",
      questionSpanIds = setOf("span-main", "span-1")
    )

    assertEquals(listOf("span-main", "span-1"), filtered.map { scope -> scope.spanId })
  }

  @Test
  fun filterFollowupTreeScopesForWorkspace_keepsOnlyActiveSpanInSingleSpanFollowup() {
    val scopes = listOf(
      FollowupTreeScope(spanId = "span-1", spanContent = "当前卡", sourceQuestion = "题1", details = emptyList()),
      FollowupTreeScope(spanId = "span-2", spanContent = "别的卡", sourceQuestion = "题2", details = emptyList())
    )

    val filtered = filterFollowupTreeScopesForWorkspace(
      scopes = scopes,
      activePage = WorkspacePage.QUICK_FOLLOWUP,
      activeSpanId = "span-1",
      questionSpanIds = emptySet()
    )

    assertEquals(listOf("span-1"), filtered.map { scope -> scope.spanId })
  }

  @Test
  fun buildFollowupTreeExportFileName_usesTimestampPatternAndMarkdownSuffix() {
    val fileName = buildFollowupTreeExportFileName(exportedAtMillis = 0L)

    assertTrue(fileName.matches(Regex("追问图谱-\\d{8}-\\d{6}\\.md")))
  }

  @Test
  fun buildMistakeBookExportFileName_usesTimestampPatternAndJsonSuffix() {
    val fileName = buildMistakeBookExportFileName(exportedAtMillis = 0L)

    assertTrue(fileName.matches(Regex("错题本-\\d{8}-\\d{6}\\.json")))
  }

  @Test
  fun splitParagraphs_prefersBlankLineBlocks() {
    val content = "第一段\n\n第二段\n\n第三段"

    val blocks = splitParagraphs(content)

    assertEquals(listOf("第一段", "第二段", "第三段"), blocks)
  }

  @Test
  fun buildKnowledgeGapInsights_detectsRepeatedWeakSignals() {
    val span = SpanData(
      id = "span-1",
      content = "先配方，再根据顶点判断最值。",
      sourceQuestion = "二次函数最值怎么做"
    )
    val messages = listOf(
      ChatMessage.User(id = "msg-1", time = "10:00", text = "二次函数最值怎么做"),
      ChatMessage.Assistant(id = "msg-2", time = "10:01", spans = listOf(span), mainSpan = span)
    )
    val histories = mapOf(
      span.id to listOf(
        SpanDetail(id = "detail-1", mode = "精细追问", time = "10:02", question = "为什么这里要先配方？", answer = "为了更快看出顶点。"),
        SpanDetail(id = "detail-2", mode = "精细追问", time = "10:03", question = "我还是不会判断顶点为什么是最值点", answer = "看开口方向。", parentDetailId = "detail-1"),
        SpanDetail(id = "detail-3", mode = "精细追问", time = "10:04", question = "这个判定条件我不明白", answer = "a>0 最小，a<0 最大。", parentDetailId = "detail-2")
      )
    )

    val insights = buildKnowledgeGapInsights(
      messages = messages,
      histories = histories,
      knowledgePoints = mapOf("函数与图像" to 2)
    )

    assertFalse(insights.isEmpty())
    assertTrue(insights.any { insight -> insight.level == KnowledgeGapLevel.HIGH })
    assertTrue(insights.any { insight -> insight.point.contains("函数") || insight.point.contains("二次") })
    assertTrue(insights.any { insight -> insight.evidence.contains("连续深追") })
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
  fun applySrsReview_updatesReviewCountersAndNextSchedule() {
    val dayMillis = 24L * 60L * 60L * 1000L
    val base = AnkiCard(
      id = "card-1",
      front = "Q",
      back = "A",
      tags = emptyList(),
      source = "s",
      createdAt = 1L
    )

    val reviewed = applySrsReview(
      card = base,
      mastery = CardMasteryLevel.NEEDS_WORK,
      reviewedAt = 2000L
    )

    assertEquals(CardMasteryLevel.NEEDS_WORK, reviewed.mastery)
    assertEquals(1, reviewed.reviewCount)
    assertEquals(2000L, reviewed.lastReviewedAt)
    assertEquals(2000L + dayMillis, reviewed.nextReviewAt)

    val reviewedAgain = applySrsReview(
      card = reviewed,
      mastery = CardMasteryLevel.PROFICIENT,
      reviewedAt = 5000L
    )

    assertEquals(CardMasteryLevel.PROFICIENT, reviewedAgain.mastery)
    assertEquals(2, reviewedAgain.reviewCount)
    assertEquals(5000L, reviewedAgain.lastReviewedAt)
    assertEquals(5000L + 7L * dayMillis, reviewedAgain.nextReviewAt)
  }

  @Test
  fun dueReviewCards_filtersFutureCardsAndSortsByDueTime() {
    val now = 1_000_000L
    val cards = listOf(
      AnkiCard(
        id = "card-1",
        front = "Q1",
        back = "A1",
        tags = emptyList(),
        source = "s",
        createdAt = 10L,
        nextReviewAt = 900_000L,
        mastery = CardMasteryLevel.FAMILIAR
      ),
      AnkiCard(
        id = "card-2",
        front = "Q2",
        back = "A2",
        tags = emptyList(),
        source = "s",
        createdAt = 20L,
        nextReviewAt = 800_000L,
        mastery = CardMasteryLevel.NEEDS_WORK
      ),
      AnkiCard(
        id = "card-3",
        front = "Q3",
        back = "A3",
        tags = emptyList(),
        source = "s",
        createdAt = 30L,
        nextReviewAt = 1_200_000L,
        mastery = CardMasteryLevel.UNRATED
      )
    )

    val due = dueReviewCards(cards, now = now)

    assertEquals(listOf("card-2", "card-1"), due.map { card -> card.id })
    assertEquals(2, countDueReviewCards(cards, now = now))
  }

  @Test
  fun mergeGlobalAnkiCards_mergesAcrossSessionsByCardContent() {
    val now = 10_000L
    val sessionA = StoredSession(
      id = "session-a",
      title = "A",
      createdAt = 1L,
      updatedAt = 2L,
      messages = emptyList(),
      histories = emptyMap(),
      profile = ProfileState(level = "高二 · 进阶冲刺"),
      input = "",
      activePage = WorkspacePage.CHAT,
      knowledgePoints = emptyMap(),
      ankiCards = listOf(
        AnkiCard(
          id = "card-1",
          front = "同一题",
          back = "同一答",
          tags = listOf("函数"),
          source = "s",
          createdAt = now,
          reviewCount = 1,
          lastReviewedAt = now,
          deckName = "函数"
        )
      )
    )
    val sessionB = StoredSession(
      id = "session-b",
      title = "B",
      createdAt = 3L,
      updatedAt = 4L,
      messages = emptyList(),
      histories = emptyMap(),
      profile = ProfileState(level = "高二 · 进阶冲刺"),
      input = "",
      activePage = WorkspacePage.CHAT,
      knowledgePoints = emptyMap(),
      ankiCards = listOf(
        AnkiCard(
          id = "card-2",
          front = "同一题",
          back = "同一答",
          tags = listOf("导数"),
          source = "s",
          createdAt = now + 100,
          reviewCount = 3,
          lastReviewedAt = now + 100,
          deckName = "导数"
        ),
        AnkiCard(
          id = "card-3",
          front = "另一题",
          back = "另一答",
          tags = emptyList(),
          source = "s",
          createdAt = now + 200
        )
      )
    )

    val merged = mergeGlobalAnkiCards(listOf(sessionA, sessionB))

    assertEquals(2, merged.size)
    assertTrue(merged.any { card -> card.front == "同一题" && card.reviewCount == 3 && card.deckName == "导数" })
    assertTrue(merged.any { card -> card.front == "另一题" })
  }

  @Test
  fun inferKnowledgePoints_filtersOutGenericMethodText() {
    val points = inferKnowledgePoints("这题的审题思路还是有点乱")

    assertTrue(points.isEmpty())
  }

  @Test
  fun parseAiAnkiCardPayload_filtersNonStudyTags() {
    val payload = parseAiAnkiCardPayload(
      """
      {"skip":false,"front":"f","back":"b","tags":["审题","函数与图像","方法总结","电场强度"],"deck":"方法总结"}
      """.trimIndent()
    )

    assertEquals(listOf("函数与图像", "电磁学"), payload?.tags)
  }

  @Test
  fun resolveDeckNameForAutoCard_ignoresGenericSuggestion() {
    val deck = resolveDeckNameForAutoCard(
      suggestedDeck = "方法总结",
      tags = emptyList(),
      existingCards = emptyList()
    )

    assertEquals(DEFAULT_ANKI_DECK_NAME, deck)
  }


  @Test
  fun filterToHighSchoolKnowledgeTags_keepsCanonicalPointLabelsStable() {
    val tags = filterToHighSchoolKnowledgeTags(listOf("电磁学", "导数与应用"), maxSize = 6)

    assertEquals(listOf("电磁学", "导数与应用"), tags)
  }

  @Test
  fun sanitizeStoredSession_removesDirtyKnowledgeTagsAndDecks() {
    val sanitized = sanitizeStoredSession(
      StoredSession(
        id = "session-1",
        title = "主界面",
        createdAt = 1L,
        updatedAt = 2L,
        messages = emptyList(),
        histories = emptyMap(),
        profile = ProfileState(level = "高二"),
        input = "",
        activePage = WorkspacePage.CHAT,
        savedQuestions = listOf(
          SavedQuestion(
            id = "saved-1",
            sourceMessageId = "msg-1",
            question = " 这题怎么做 ",
            answer = " 先看函数图像 ",
            sourceTime = "10:00",
            savedAt = 3L,
            knowledgeTags = listOf("审题", "导数")
          ),
          SavedQuestion(
            id = "saved-2",
            sourceMessageId = "msg-2",
            question = "   ",
            answer = "会被清掉",
            sourceTime = "10:01",
            savedAt = 4L
          )
        ),
        knowledgePoints = linkedMapOf("审题" to 3, "导数" to 2, "函数" to 1),
        ankiCards = listOf(
          AnkiCard(
            id = "card-1",
            front = "q",
            back = "a",
            tags = listOf("方法总结", "导数"),
            source = "src",
            createdAt = 5L,
            deckName = "方法总结"
          )
        )
      )
    )

    assertEquals(1, sanitized.savedQuestions.size)
    assertEquals(listOf("函数与图像", "导数与应用"), sanitized.savedQuestions.first().knowledgeTags)
    assertEquals(linkedMapOf("函数与图像" to 3, "导数与应用" to 2), sanitized.knowledgePoints)
    assertEquals(listOf("函数与图像", "导数与应用"), sanitized.ankiCards.first().tags)
    assertEquals("函数与图像卡组", sanitized.ankiCards.first().deckName)
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

    assertTrue(newDeck.endsWith("卡组"))
    assertTrue(newDeck.contains("化学"))
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
  fun storedSessionSnapshot_andBuildUiState_keepQuickFollowupSpanId() {
    val state = ChatUiState(
      activeSessionId = "session-quick",
      activePage = WorkspacePage.QUICK_FOLLOWUP,
      quickFollowupSpanId = "span-9",
      quickFollowupDetailId = "detail-4"
    )

    val snapshot = toStoredSessionSnapshot(
      state = state,
      title = "精细追问",
      createdAt = 11L,
      updatedAt = 12L
    )
    val rebuilt = buildUiStateFromSession(
      session = snapshot,
      ankiCards = emptyList(),
      settings = RuntimeSettings.defaults(),
      toastMessage = null
    )

    assertEquals("span-9", snapshot.quickFollowupSpanId)
    assertEquals("detail-4", snapshot.quickFollowupDetailId)
    assertEquals(WorkspacePage.QUICK_FOLLOWUP, rebuilt.activePage)
    assertEquals("span-9", rebuilt.quickFollowupSpanId)
    assertEquals("detail-4", rebuilt.quickFollowupDetailId)
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
  fun upsertAndRemoveSpanDetailHistory_replaceAndDeleteById() {
    val base = ChatUiState(
      histories = mapOf(
        "span-1" to listOf(
          SpanDetail(id = "detail-1", mode = "自动讲解", time = "10:00", answer = "old")
        )
      )
    )

    val replaced = upsertSpanDetailHistory(
      current = base,
      spanId = "span-1",
      detail = SpanDetail(id = "detail-1", mode = "自动讲解", time = "10:01", answer = "new")
    )
    val removed = removeSpanDetailHistory(
      current = replaced,
      spanId = "span-1",
      detailId = "detail-1"
    )

    assertEquals("new", replaced.histories["span-1"]?.firstOrNull()?.answer)
    assertTrue(removed.histories["span-1"].isNullOrEmpty())
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
  fun parseImageQuestionArchivePayload_parsesQuestionTypeAndTags() {
    val payload = parseImageQuestionArchivePayload(
      """
      {
        "question": "已知二次函数 y=ax^2+bx+c，求最值并判断单调区间。",
        "subject": "数学",
        "question_type": "解答题",
        "knowledge_tags": ["二次函数", "函数与图像", "套路"],
        "analysis_summary": "重点考查二次函数图像与最值判断，容易漏掉对称轴。"
      }
      """.trimIndent()
    )

    requireNotNull(payload)
    assertEquals("数学", payload.subject)
    assertEquals("解答题", payload.questionType)
    assertEquals(listOf("二次函数", "函数与图像"), payload.knowledgeTags)
  }

  @Test
  fun parseMistakeBookAiAnalysis_parsesStructuredJsonPayload() {
    val payload = parseMistakeBookAiAnalysis(
      """
      {
        "summary": "函数与图像是当前最明显短板。",
        "weaknesses": ["函数与图像", "审题稳定性"],
        "plan": ["先复盘近7天错题", "每天2题同类训练"],
        "next_actions": ["今晚先复习到期错题", "整理二次函数判断清单"]
      }
      """.trimIndent()
    )

    requireNotNull(payload)
    assertEquals("函数与图像是当前最明显短板。", payload.summary)
    assertEquals(listOf("函数与图像", "审题稳定性"), payload.weaknesses)
    assertEquals(listOf("先复盘近7天错题", "每天2题同类训练"), payload.plan)
    assertEquals(listOf("今晚先复习到期错题", "整理二次函数判断清单"), payload.nextActions)
  }

  @Test
  fun parseMistakeBookAiAnalysis_fallsBackToRawTextWhenJsonMissing() {
    val payload = parseMistakeBookAiAnalysis(
      "你目前主要卡在函数与图像。建议先集中复习到期错题，再做两道同类题。"
    )

    requireNotNull(payload)
    assertTrue(payload.summary.contains("函数与图像"))
    assertTrue(payload.rawText.contains("集中复习到期错题"))
  }

  @Test
  fun buildFallbackImageArchivePayload_prefersNonGenericQuestionTitle() {
    val payload = buildFallbackImageArchivePayload(
      sourceQuestion = "拍照搜题：请识别并讲解这道题",
      answer = "题目：已知二次函数 y=ax^2+bx+c，求最值并判断单调区间。先找对称轴。"
    )

    assertTrue(payload.question.contains("二次函数") || payload.question.contains("最值"))
    assertTrue(payload.analysisSummary.isNotBlank())
  }

  @Test
  fun resolveErrorHint_usesFallbackAndTruncatesMessage() {
    val fallback = resolveErrorHint(throwable = null, fallback = "网络不可用")
    val longMessage = "x".repeat(120)
    val resolved = resolveErrorHint(RuntimeException(longMessage), fallback = "unused")

    assertEquals("网络不可用", fallback)
    assertEquals(80, resolved.length)
  }

  @Test
  fun resolveErrorHint_mapsSoftwareAbortToFriendlyText() {
    val resolved = resolveErrorHint(
      throwable = RuntimeException("Software caused connection abort"),
      fallback = "网络不可用"
    )

    assertEquals("网络连接中断，请重试", resolved)
  }

  @Test
  fun resolveErrorHint_mapsSocketTimeoutToFriendlyText() {
    val resolved = resolveErrorHint(
      throwable = SocketTimeoutException("timeout"),
      fallback = "网络不可用"
    )

    assertEquals("网络超时，请稍后重试", resolved)
  }
}
