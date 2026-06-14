package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.ArkRequestMessage
import com.studysuit.aiqa.data.MistakeBookItem
import com.studysuit.aiqa.data.MistakeSrsEngine
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
  fun buildCoachQuickActions_includesFocusAndRecommendationShortcuts() {
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
          action = "先看图像特征",
          evidence = "经常卡在切入点"
        )
      ),
      recommendedQuestions = listOf(
        CoachRecommendedQuestion(
          id = "rec-1",
          title = "函数与图像 · 典型题",
          reason = "先补切入点判断。",
          prompt = "请给我一道函数与图像的典型题。"
        )
      )
    )

    val actions = buildCoachQuickActions(digest)

    assertEquals(3, actions.size)
    assertTrue(actions.any { action -> action.label.contains("核心问题") })
    assertTrue(actions.any { action -> action.prompt.contains("函数与图像") })
    assertTrue(actions.any { action -> action.label.contains("练前") })
  }

  @Test
  fun buildCoachReplyQuickActions_returnsTrainingHintsWhenAwaitingAnswer() {
    val actions = buildCoachReplyQuickActions(
      message = CoachChatMessage(id = "msg-1", role = CoachMessageRole.ASSISTANT, time = "10:00", text = "这是一道训练题"),
      digest = CoachDailyDigest(
        dateKey = "2026-03-07",
        generatedAt = 1L,
        headline = "今天先盯住函数与图像",
        summary = "先补函数与图像"
      ),
      training = DailyTrainingState(
        dateKey = "2026-03-07",
        rounds = listOf(
          CoachRecommendedQuestion(
            id = "rec-1",
            title = "函数与图像 · 典型题",
            reason = "先补基础判断",
            prompt = "请给我一道函数题"
          )
        ),
        currentIndex = 0,
        phase = DailyTrainingPhase.AWAITING_ANSWER,
        currentQuestionText = "题目"
      )
    )

    assertEquals(3, actions.size)
    assertTrue(actions.any { it.label.contains("提示") })
    assertTrue(actions.any { it.prompt.contains("第一步") })
  }

  @Test
  fun buildCoachReplyQuickActions_returnsFollowupPromptsForGeneralCoachReply() {
    val actions = buildCoachReplyQuickActions(
      message = CoachChatMessage(id = "msg-1", role = CoachMessageRole.ASSISTANT, time = "10:00", text = "你主要漏了函数与图像的判断依据。"),
      digest = CoachDailyDigest(
        dateKey = "2026-03-07",
        generatedAt = 1L,
        headline = "今天先盯住函数与图像",
        summary = "先补函数与图像",
        focusAreas = listOf(
          CoachFocusArea(
            point = "函数与图像",
            level = KnowledgeGapLevel.HIGH,
            diagnosis = "判断依据不稳",
            action = "先看图像特征",
            evidence = "经常问为什么"
          )
        )
      ),
      training = DailyTrainingState()
    )

    assertEquals(3, actions.size)
    assertTrue(actions.any { it.label.contains("出一道题") })
    assertTrue(actions.any { it.prompt.contains("函数与图像") })
    assertTrue(actions.any { it.label.contains("具体") || it.label.contains("其他") })
  }

  @Test
  fun buildCoachRecommendationFollowupPrompt_mentionsTitleAndReason() {
    val prompt = buildCoachRecommendationFollowupPrompt(
      CoachRecommendedQuestion(
        id = "rec-1",
        title = "函数与图像 · 典型题",
        reason = "先补切入点判断。",
        prompt = "请给我一道函数与图像的典型题。"
      )
    )

    assertTrue(prompt.contains("函数与图像 · 典型题"))
    assertTrue(prompt.contains("先补切入点判断"))
    assertTrue(prompt.contains("第一眼先看什么"))
  }

  @Test
  fun buildCoachConversationTurns_groupsByTurnButKeepsQuestionBeforeReply() {
    val turns = buildCoachConversationTurns(
      listOf(
        CoachChatMessage(id = "u1", role = CoachMessageRole.USER, time = "10:00", text = "第1问"),
        CoachChatMessage(id = "a1", role = CoachMessageRole.ASSISTANT, time = "10:01", text = "第1答"),
        CoachChatMessage(id = "u2", role = CoachMessageRole.USER, time = "10:02", text = "第2问"),
        CoachChatMessage(id = "a2", role = CoachMessageRole.ASSISTANT, time = "10:03", text = "第2答")
      )
    )

    assertEquals(2, turns.size)
    assertEquals(listOf("u1", "a1"), turns[0].messages.map { it.id })
    assertEquals(listOf("u2", "a2"), turns[1].messages.map { it.id })
  }

  @Test
  fun buildCoachDailyDigest_recommendationCarriesBasisAndAnchorQuestion() {
    val savedQuestions = listOf(
      SavedQuestion(
        id = "saved-anchor",
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
      messages = listOf(
        ChatMessage.User(id = "msg-1", time = "10:00", text = "函数图像这题我总不会"),
        ChatMessage.Assistant(
          id = "msg-2",
          time = "10:01",
          spans = listOf(SpanData(id = "span-1", content = "先看图像特征。", sourceQuestion = "函数图像这题我总不会")),
          mainSpan = SpanData(id = "span-main", content = "先看图像特征。", sourceQuestion = "函数图像这题我总不会")
        )
      ),
      histories = emptyMap(),
      savedQuestions = savedQuestions,
      knowledgePoints = mapOf("函数与图像" to 3),
      nowMillis = 1000L
    )

    val question = digest.recommendedQuestions.first()
    assertEquals("saved-anchor", question.anchorSavedQuestionId)
    assertTrue(question.basis.contains("函数与图像"))
    assertTrue(question.basis.contains("依据题") || question.basis.contains("卡住"))
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

  @Test
  fun buildCoachDailyDigest_usesMistakeBookWeakSignals() {
    val mistake = MistakeSrsEngine.recordReview(
      item = MistakeBookItem.create(
        id = "mistake-1",
        question = "函数图像题",
        correctAnswer = "先判断定义域和单调性",
        knowledgeTags = listOf("函数与图像"),
        mistakeReason = "定义域漏看",
        createdAt = 1L
      ),
      isCorrect = false,
      reviewedAt = 2L
    )

    val digest = buildCoachDailyDigest(
      messages = emptyList(),
      histories = emptyMap(),
      savedQuestions = emptyList(),
      mistakeItems = listOf(mistake),
      knowledgePoints = emptyMap(),
      nowMillis = 1000L
    )

    assertTrue(digest.focusAreas.any { area -> area.point == "函数与图像" })
    assertTrue(digest.summary.contains("错题"))
  }

  @Test
  fun buildCoachConversationMessages_mentionsMistakeBookSignals() {
    val mistake = MistakeSrsEngine.recordReview(
      item = MistakeBookItem.create(
        id = "mistake-1",
        question = "函数图像题",
        correctAnswer = "先判断定义域和单调性",
        knowledgeTags = listOf("函数与图像"),
        mistakeReason = "定义域漏看",
        createdAt = 1L
      ),
      isCorrect = false,
      reviewedAt = 2L
    )

    val requestMessages = buildCoachConversationMessages(
      digest = null,
      coachMessages = emptyList(),
      profile = ProfileState(level = "高二"),
      knowledgePoints = emptyMap(),
      knowledgeGapInsights = emptyList(),
      savedQuestions = emptyList(),
      mistakeItems = listOf(mistake)
    )

    assertTrue(requestMessages.first().text.contains("错题本"))
    assertTrue(requestMessages.first().text.contains("函数与图像"))
  }
}
