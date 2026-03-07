package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.ArkRequestMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyCoachSupportTest {

  @Test
  fun buildCoachDailyDigest_returnsFallbackWhenNoSamples() {
    val digest = buildCoachDailyDigest(
      messages = emptyList(),
      histories = emptyMap(),
      savedQuestions = emptyList(),
      knowledgePoints = emptyMap(),
      nowMillis = 1L
    )

    assertTrue(digest.headline.contains("先给我几道题"))
    assertTrue(digest.summary.contains("足够样本"))
    assertFalse(digest.recommendedQuestions.isEmpty())
  }

  @Test
  fun buildCoachDailyDigest_surfacesFocusAreaAndRecommendation() {
    val question = "这道函数图像题我不会，为什么这里要这样判断？"
    val messages = listOf(
      ChatMessage.User(id = "msg-1", time = "10:00", text = question),
      ChatMessage.Assistant(
        id = "msg-2",
        time = "10:01",
        spans = listOf(
          SpanData(id = "span-1", content = "先看函数单调性，再判断图像变化。", sourceQuestion = question)
        ),
        mainSpan = SpanData(
          id = "span-main-1",
          content = "先看函数单调性，再判断图像变化。",
          sourceQuestion = question
        )
      )
    )
    val savedQuestions = listOf(
      SavedQuestion(
        id = "saved-1",
        sourceMessageId = "msg-2",
        question = "已知二次函数图像，判断最值和单调区间",
        answer = "先找对称轴，再判断开口方向。",
        sourceTime = "10:01",
        savedAt = 100L,
        followupCount = 2,
        knowledgeTags = listOf("函数与图像")
      )
    )

    val digest = buildCoachDailyDigest(
      messages = messages,
      histories = emptyMap(),
      savedQuestions = savedQuestions,
      knowledgePoints = mapOf("函数与图像" to 2),
      nowMillis = 1000L
    )

    assertTrue(digest.focusAreas.any { area -> area.point == "函数与图像" })
    assertTrue(digest.recommendedQuestions.any { questionItem ->
      questionItem.prompt.contains("函数与图像")
    })
  }

  @Test
  fun buildCoachTrainingRounds_fillsToThreeRounds() {
    val digest = CoachDailyDigest(
      dateKey = "2026-03-07",
      generatedAt = 1000L,
      headline = "今天先盯住函数与图像",
      summary = "你更容易卡在切入点判断。",
      focusAreas = listOf(
        CoachFocusArea(
          point = "函数与图像",
          level = KnowledgeGapLevel.HIGH,
          diagnosis = "基础判断不稳",
          action = "先判断图像特征再列步骤",
          evidence = "经常问为什么这样判断"
        )
      ),
      recommendedQuestions = listOf(
        CoachRecommendedQuestion(
          id = "rec-1",
          title = "函数题",
          reason = "先补基础判断。",
          prompt = "请给我一道函数与图像的典型题。"
        )
      )
    )

    val rounds = buildCoachTrainingRounds(digest)

    assertEquals(3, rounds.size)
    assertTrue(rounds.first().prompt.contains("函数与图像"))
  }

  @Test
  fun buildCoachTrainingPrompt_prefersFirstRecommendedQuestion() {
    val digest = CoachDailyDigest(
      dateKey = "2026-03-07",
      generatedAt = 1000L,
      headline = "今天先盯住函数与图像",
      summary = "你更容易卡在切入点判断。",
      recommendedQuestions = listOf(
        CoachRecommendedQuestion(
          id = "rec-1",
          title = "函数题",
          reason = "先补基础判断。",
          prompt = "请给我一道函数与图像的典型题。"
        )
      )
    )

    assertEquals("请给我一道函数与图像的典型题。", buildCoachTrainingPrompt(digest))
  }

  @Test
  fun buildTrainingEvaluationPrompt_containsQuestionAndAnswer() {
    val round = CoachRecommendedQuestion(
      id = "rec-1",
      title = "函数与图像 · 典型题",
      reason = "先补基础判断。",
      prompt = "请给我一道函数与图像的典型题。"
    )

    val prompt = buildTrainingEvaluationPrompt(
      round = round,
      trainingQuestion = "已知函数图像，判断单调区间。",
      studentAnswer = "我觉得先看开口方向。"
    )

    assertTrue(prompt.contains("已知函数图像"))
    assertTrue(prompt.contains("我觉得先看开口方向"))
    assertTrue(prompt.contains("可以进入下一题"))
  }

  @Test
  fun buildCoachConversationMessages_prependsContextAndMapsRoles() {
    val digest = CoachDailyDigest(
      dateKey = "2026-03-07",
      generatedAt = 1000L,
      headline = "今天先盯住函数与图像",
      summary = "你更容易卡在切入点判断。"
    )
    val coachMessages = listOf(
      CoachChatMessage(id = "msg-1", role = CoachMessageRole.USER, time = "10:00", text = "我到底漏了什么？"),
      CoachChatMessage(id = "msg-2", role = CoachMessageRole.ASSISTANT, time = "10:01", text = "你主要漏了判断依据。")
    )

    val requestMessages = buildCoachConversationMessages(
      digest = digest,
      coachMessages = coachMessages,
      profile = ProfileState(level = "高二"),
      knowledgePoints = mapOf("函数与图像" to 3),
      knowledgeGapInsights = emptyList(),
      savedQuestions = emptyList()
    )

    assertFalse(requestMessages.isEmpty())
    assertEquals("user", requestMessages.first().role)
    assertTrue(requestMessages.first().text.contains("今日教练总结"))
    assertEquals(
      listOf(
        ArkRequestMessage(role = "user", text = "我到底漏了什么？"),
        ArkRequestMessage(role = "assistant", text = "你主要漏了判断依据。")
      ),
      requestMessages.drop(1)
    )
  }
}
