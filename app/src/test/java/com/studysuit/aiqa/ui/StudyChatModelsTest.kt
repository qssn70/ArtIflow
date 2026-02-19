package com.studysuit.aiqa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyChatModelsTest {

  @Test
  fun findSpanById_returnsMatchingAssistantSpan() {
    val messages = listOf(
      ChatMessage.User(id = "msg-1", time = "10:00", text = "question"),
      ChatMessage.Assistant(
        id = "msg-2",
        time = "10:01",
        spans = listOf(
          SpanData(id = "span-1", content = "第一段", sourceQuestion = "q1"),
          SpanData(id = "span-2", content = "第二段", sourceQuestion = "q1")
        )
      )
    )

    val found = findSpanById(messages, "span-2")

    assertEquals("span-2", found?.id)
    assertEquals("第二段", found?.content)
  }

  @Test
  fun findSpanById_returnsNullForMissingSpan() {
    val messages = listOf(
      ChatMessage.Assistant(
        id = "msg-2",
        time = "10:01",
        spans = listOf(SpanData(id = "span-1", content = "第一段", sourceQuestion = "q1"))
      )
    )

    val found = findSpanById(messages, "missing")

    assertNull(found)
  }

  @Test
  fun findLatestAssistantSpan_returnsLastSpanFromLatestAssistantMessage() {
    val messages = listOf(
      ChatMessage.Assistant(
        id = "msg-1",
        time = "10:00",
        spans = listOf(
          SpanData(id = "span-1", content = "第一段", sourceQuestion = "q1")
        )
      ),
      ChatMessage.User(id = "msg-2", time = "10:01", text = "继续"),
      ChatMessage.Assistant(
        id = "msg-3",
        time = "10:02",
        spans = listOf(
          SpanData(id = "span-2", content = "第二段", sourceQuestion = "q2"),
          SpanData(id = "span-3", content = "第三段", sourceQuestion = "q2")
        )
      )
    )

    val latest = findLatestAssistantSpan(messages)

    assertEquals("span-3", latest?.id)
  }

  @Test
  fun findDetailById_returnsMatchingDetail() {
    val details = listOf(
      SpanDetail(id = "detail-1", mode = "自动讲解", time = "10:00", answer = "a1"),
      SpanDetail(id = "detail-2", mode = "快捷追问", time = "10:01", question = "q2", answer = "a2")
    )

    val found = findDetailById(details, "detail-2")

    assertEquals("detail-2", found?.id)
    assertEquals("q2", found?.question)
  }

  @Test
  fun buildDetailPath_returnsRootToTargetPath() {
    val details = listOf(
      SpanDetail(id = "detail-3", mode = "快捷追问", time = "10:02", question = "q3", answer = "a3", parentDetailId = "detail-2"),
      SpanDetail(id = "detail-2", mode = "快捷追问", time = "10:01", question = "q2", answer = "a2", parentDetailId = "detail-1"),
      SpanDetail(id = "detail-1", mode = "自动讲解", time = "10:00", answer = "a1")
    )

    val path = buildDetailPath(details, "detail-3")

    assertEquals(listOf("detail-1", "detail-2", "detail-3"), path.map { detail -> detail.id })
  }

  @Test
  fun updateWith_incrementsTopicAndBehaviorCounters() {
    val profile = ProfileState(level = "高二")

    val updated = profile.updateWith(
      text = "函数最值题怎么做",
      isFollowup = true,
      isVoice = true
    )

    assertEquals(1, updated.followups)
    assertEquals(1, updated.voiceFollowups)
    assertEquals(1, updated.topicHits["函数"])
  }

  @Test
  fun detectTopicsForProfile_returnsFallbackForUnknownText() {
    val topics = detectTopicsForProfile("今天状态不错")

    assertEquals(listOf("通用方法"), topics)
  }

  @Test
  fun normalizeImagePrompt_replacesLegacyOrBlankPrompt() {
    val legacyPrompt =
      "你是一名中学学科辅导老师。请先识别图片中的题干，再按步骤讲解并给出最终答案。" +
        "如果图片里有多个小题，请按小题编号分别作答。输出格式：\n" +
        "1) 题目识别\n2) 解题思路\n3) 详细步骤\n4) 最终答案"

    val fromBlank = normalizeImagePrompt("  ")
    val fromLegacy = normalizeImagePrompt(legacyPrompt)
    val fromCustom = normalizeImagePrompt("按步骤给出关键点")

    assertTrue(fromBlank.contains("请识别题目并简洁作答"))
    assertTrue(fromLegacy.contains("请识别题目并简洁作答"))
    assertEquals("按步骤给出关键点", fromCustom)
  }

  @Test
  fun toArkRuntimeConfig_normalizesLegacySystemPrompt() {
    val settings = RuntimeSettings(
      arkApiKey = "key",
      arkModel = "model",
      arkBaseUrl = "https://example.com",
      arkEndpoint = "responses",
      arkSystemPrompt = "你是一个有用的AI学习辅导助手，擅长把复杂知识点讲清楚，优先给步骤化解释。",
      imagePrompt = "img",
      openSpeechApiKey = "speech-key",
      openSpeechResourceId = "resource",
      openSpeechSubmitUrl = "https://submit",
      openSpeechQueryUrl = "https://query",
      openSpeechUid = "uid"
    )

    val config = settings.toArkRuntimeConfig()

    assertEquals("key", config.apiKey)
    assertTrue(config.systemPrompt.contains("先给结论"))
  }
}
