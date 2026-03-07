package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.ArkRequestMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val COACH_SYSTEM_PROMPT =
  "你是学生的AI学习教练。回答时先指出最核心的问题，再给1-3条可执行建议；语言直接、具体、简短，不空话。必要时可以安排一题小练习或判断标准。"

private val coachPunctuationRegex = Regex("[。！？!?,，；;：:]+$")
private val coachWhitespaceRegex = Regex("\\s+")

internal fun currentCoachDateKey(nowMillis: Long = System.currentTimeMillis()): String {
  val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.SIMPLIFIED_CHINESE)
  return formatter.format(Date(nowMillis))
}

internal fun buildCoachDailyDigest(
  messages: List<ChatMessage>,
  histories: Map<String, List<SpanDetail>>,
  savedQuestions: List<SavedQuestion>,
  knowledgePoints: Map<String, Int>,
  nowMillis: Long = System.currentTimeMillis()
): CoachDailyDigest {
  val insights = buildKnowledgeGapInsights(
    messages = messages,
    histories = histories,
    knowledgePoints = knowledgePoints
  )
  val focusAreas = insights
    .take(3)
    .map { insight ->
      CoachFocusArea(
        point = insight.point,
        level = insight.level,
        diagnosis = normalizeCoachText(insight.diagnosis),
        action = normalizeCoachText(insight.action),
        evidence = normalizeCoachText(insight.evidence)
      )
    }

  val practiceCount = messages.count { message -> message is ChatMessage.User }
  val headline = when {
    focusAreas.isEmpty() && practiceCount == 0 && savedQuestions.isEmpty() -> "今天先给我几道题，我再当你的教练"
    focusAreas.isEmpty() -> "今天整体还算稳，继续保持做题手感"
    focusAreas.size == 1 -> "今天先盯住 ${focusAreas.first().point}"
    else -> "今天重点补 ${focusAreas.joinToString(separator = "、") { area -> area.point }}"
  }
  val summary = buildCoachDigestSummary(
    focusAreas = focusAreas,
    practiceCount = practiceCount,
    savedCount = savedQuestions.size
  )
  val recommendedQuestions = buildCoachRecommendedQuestions(
    focusAreas = focusAreas,
    savedQuestions = savedQuestions,
    nowMillis = nowMillis
  )

  return CoachDailyDigest(
    dateKey = currentCoachDateKey(nowMillis),
    generatedAt = nowMillis,
    headline = headline,
    summary = summary,
    focusAreas = focusAreas,
    recommendedQuestions = recommendedQuestions
  )
}

internal fun buildCoachConversationMessages(
  digest: CoachDailyDigest?,
  coachMessages: List<CoachChatMessage>,
  profile: ProfileState,
  knowledgePoints: Map<String, Int>,
  knowledgeGapInsights: List<KnowledgeGapInsight>,
  savedQuestions: List<SavedQuestion>
): List<ArkRequestMessage> {
  val contextMessage = ArkRequestMessage(
    role = "user",
    text = buildCoachContextText(
      digest = digest,
      profile = profile,
      knowledgePoints = knowledgePoints,
      knowledgeGapInsights = knowledgeGapInsights,
      savedQuestions = savedQuestions
    )
  )

  val historyMessages = coachMessages
    .takeLast(10)
    .mapNotNull { message ->
      val normalized = message.text.trim()
      if (normalized.isBlank()) {
        null
      } else {
        ArkRequestMessage(
          role = if (message.role == CoachMessageRole.USER) "user" else "assistant",
          text = normalized
        )
      }
    }

  return listOf(contextMessage) + historyMessages
}

internal fun upsertCoachMessage(
  messages: List<CoachChatMessage>,
  target: CoachChatMessage
): List<CoachChatMessage> {
  if (messages.any { message -> message.id == target.id }) {
    return messages.map { message -> if (message.id == target.id) target else message }
  }
  return messages + target
}

internal fun buildCoachTrainingPrompt(digest: CoachDailyDigest?): String {
  val firstRecommended = digest?.recommendedQuestions?.firstOrNull()?.prompt?.trim().orEmpty()
  if (firstRecommended.isNotBlank()) {
    return firstRecommended
  }

  val focusSummary = digest?.focusAreas
    .orEmpty()
    .take(3)
    .joinToString(separator = "、") { area -> area.point }
    .ifBlank { "当前最需要排查的薄弱点" }

  return "请根据我今天的学习情况，先给我一道围绕“$focusSummary”的典型题，先不要答案；等我作答后再批改，并指出我漏掉的知识点和错因。"
}

internal fun buildCoachTrainingRounds(digest: CoachDailyDigest?): List<CoachRecommendedQuestion> {
  val recommended = digest?.recommendedQuestions.orEmpty()
  if (recommended.size >= 3) {
    return recommended.take(3)
  }

  val generated = mutableListOf<CoachRecommendedQuestion>()
  val existingPrompts = recommended.map { question -> question.prompt.trim() }.toMutableSet()
  val dateKey = digest?.dateKey.orEmpty().ifBlank { currentCoachDateKey() }

  digest?.focusAreas.orEmpty().forEachIndexed { index, area ->
    if (generated.size + recommended.size >= 3) {
      return@forEachIndexed
    }
    val prompt = "请给我一道围绕“${area.point}”的典型训练题，先只给题目，不要答案；等我作答后再批改，并指出我在${area.point}上最容易漏掉的知识点。"
    if (existingPrompts.add(prompt)) {
      generated += CoachRecommendedQuestion(
        id = "coach-round-$dateKey-$index-${area.point.hashCode()}",
        title = "${area.point} · 加练",
        reason = area.diagnosis,
        prompt = prompt
      )
    }
  }

  while (generated.size + recommended.size < 3) {
    val index = generated.size + recommended.size
    val prompt = if (index == 0) {
      buildCoachTrainingPrompt(digest)
    } else {
      "请给我一道中等难度典型题，先只给题目不要答案；等我作答后再批改，并明确指出我漏掉的知识点和错因。"
    }
    if (existingPrompts.add(prompt.trim())) {
      generated += CoachRecommendedQuestion(
        id = "coach-round-$dateKey-generic-$index",
        title = "综合训练 · 第${index + 1}题",
        reason = "补足今天的完整训练轮次。",
        prompt = prompt
      )
    } else {
      break
    }
  }

  return (recommended + generated).take(3)
}

internal fun buildTrainingRoundDisplayText(
  round: CoachRecommendedQuestion,
  roundIndex: Int,
  totalRounds: Int
): String {
  val title = round.title.trim().ifBlank { "典型题" }
  return "今日训练 · 第${roundIndex + 1}/${totalRounds}题 · $title"
}

internal fun buildTrainingEvaluationPrompt(
  round: CoachRecommendedQuestion,
  trainingQuestion: String,
  studentAnswer: String
): String {
  val normalizedQuestion = trainingQuestion.trim().ifBlank { "（题目文本缺失，请先基于下方作答意图尽量批改）" }
  val normalizedAnswer = studentAnswer.trim()
  return """
    你刚才给我的训练题如下，请直接开始批改。

    【训练主题】
    ${round.title.ifBlank { "典型题训练" }}

    【原题】
    $normalizedQuestion

    【我的作答】
    $normalizedAnswer

    请按下面结构输出，简洁直接：
    1. 结论：正确 / 部分正确 / 错误
    2. 我真正漏掉的知识点
    3. 关键错因（为什么会错）
    4. 下一步怎么改
    5. 一句过关判断

    要求：
    - 先指出最核心的问题，不要空话；
    - 除非我整体思路都错了，否则不要展开很长的完整题解；
    - 过关判断里如果可以进入下一题，请明确写“可以进入下一题”。
  """.trimIndent()
}

private fun buildCoachDigestSummary(
  focusAreas: List<CoachFocusArea>,
  practiceCount: Int,
  savedCount: Int
): String {
  if (focusAreas.isEmpty()) {
    return if (practiceCount == 0 && savedCount == 0) {
      "今天还没有足够样本。先做2到3道题，最好带上你的作答过程，我会更容易抓到你真正漏掉的知识点。"
    } else {
      "你目前没有出现特别集中的漏洞。继续做题时，尽量把“为什么这样做”和“哪里不懂”说清楚，我会更快定位你的卡点。"
    }
  }

  val lead = focusAreas.first()
  val extra = focusAreas.drop(1).joinToString(separator = "、") { area -> area.point }
  return buildString {
    append("结合今天")
    append(practiceCount.coerceAtLeast(0))
    append("次提问")
    if (savedCount > 0) {
      append("和")
      append(savedCount)
      append("道收藏题")
    }
    append("来看，你最该先补的是“")
    append(lead.point)
    append("”。")
    append(lead.diagnosis)
    if (extra.isNotBlank()) {
      append("其次再看")
      append(extra)
      append("。")
    }
    append("今天的练法就按“先判断、再步骤、最后复盘知识点”的顺序来。")
  }
}

private fun buildCoachRecommendedQuestions(
  focusAreas: List<CoachFocusArea>,
  savedQuestions: List<SavedQuestion>,
  nowMillis: Long
): List<CoachRecommendedQuestion> {
  if (focusAreas.isEmpty()) {
    return listOf(
      CoachRecommendedQuestion(
        id = "coach-rec-$nowMillis-0",
        title = "通用回测题",
        reason = "先给我一道题做样本，教练才能更快定位薄弱点。",
        prompt = "请给我一道适合我当前年级的中等难度典型题，先不要答案，等我作答后再批改，并指出我漏掉的知识点。"
      ),
      CoachRecommendedQuestion(
        id = "coach-rec-$nowMillis-1",
        title = "思路判断题",
        reason = "先测我是不是会选切入点，而不只是会套步骤。",
        prompt = "请给我一道需要先判断切入点的典型题，先只给题目，不要答案；等我作答后重点评价我的思路是否正确。"
      )
    )
  }

  return focusAreas.take(3).mapIndexed { index, area ->
    val anchor = selectCoachAnchorQuestion(area.point, savedQuestions)
    val difficultyHint = when (area.level) {
      KnowledgeGapLevel.HIGH -> "基础到中档"
      KnowledgeGapLevel.MEDIUM -> "中档"
      KnowledgeGapLevel.LOW -> "入门到中档"
    }
    val actionHint = normalizeCoachSentence(area.action)
    val title = "${area.point} · 典型题"
    val reason = anchor?.let { saved ->
      "参考你之前卡住的题《${normalizeCoachSnippet(saved.question, 16)}》：${area.diagnosis}"
    } ?: area.diagnosis
    val prompt = buildString {
      append("请给我一道围绕“${area.point}”的")
      append(difficultyHint)
      append("典型题，重点训练：")
      append(actionHint)
      append("。先只给题目，不要答案；等我作答后再批改，并指出我漏掉的知识点。")
      anchor?.let { saved ->
        append("可以参考我之前容易错的方向：")
        append(normalizeCoachSnippet(saved.question, 28))
        append("。")
      }
    }
    CoachRecommendedQuestion(
      id = "coach-rec-$nowMillis-$index-${area.point.hashCode()}",
      title = title,
      reason = reason,
      prompt = prompt
    )
  }
}

private fun selectCoachAnchorQuestion(
  point: String,
  savedQuestions: List<SavedQuestion>
): SavedQuestion? {
  return savedQuestions
    .asSequence()
    .filter { saved ->
      saved.knowledgeTags.any { tag -> tag == point } ||
        inferKnowledgePoints(listOf(saved.question, saved.answer).joinToString(separator = "\n")).any { tag -> tag == point }
    }
    .sortedWith(
      compareByDescending<SavedQuestion> { saved -> saved.followupCount }
        .thenByDescending { saved -> saved.savedAt }
    )
    .firstOrNull()
}

private fun buildCoachContextText(
  digest: CoachDailyDigest?,
  profile: ProfileState,
  knowledgePoints: Map<String, Int>,
  knowledgeGapInsights: List<KnowledgeGapInsight>,
  savedQuestions: List<SavedQuestion>
): String {
  val topKnowledge = knowledgePoints.entries
    .sortedByDescending { entry -> entry.value }
    .take(5)
    .joinToString(separator = "、") { entry -> "${entry.key}${entry.value}次" }
    .ifBlank { "暂无" }

  val gapSummary = knowledgeGapInsights
    .take(3)
    .joinToString(separator = "\n") { insight ->
      "- ${insight.point}（${insight.level.label}）：${normalizeCoachText(insight.diagnosis)}；建议 ${normalizeCoachSentence(insight.action)}"
    }
    .ifBlank { "- 暂无明显集中薄弱点" }

  val savedSummary = savedQuestions
    .take(3)
    .joinToString(separator = "\n") { saved ->
      "- ${normalizeCoachSnippet(saved.question, 26)}"
    }
    .ifBlank { "- 暂无收藏题" }

  val digestSummary = digest?.let { current ->
    buildString {
      append(current.headline)
      append("；")
      append(normalizeCoachText(current.summary))
    }
  } ?: "今日教练总结暂未生成"

  return """
    以下是学生的学习画像，只作背景参考，不要逐条复述：
    - 当前阶段：${profile.level}
    - 高频知识点：$topKnowledge
    - 今日教练总结：$digestSummary
    - 当前薄弱点：
    $gapSummary
    - 最近收藏题：
    $savedSummary

    请你像学习教练一样回答：
    1. 先指出最核心的问题；
    2. 再给1到3条可执行建议；
    3. 如果适合，就顺手安排一个很小的判断练习或复盘动作；
    4. 保持简洁，不要空话。
  """.trimIndent()
}

private fun normalizeCoachSentence(text: String): String {
  return normalizeCoachText(text).trimEnd { it == '。' || it == '！' || it == '？' }
}

private fun normalizeCoachSnippet(text: String, maxLen: Int): String {
  val normalized = normalizeCoachText(text)
  return if (normalized.length <= maxLen) normalized else normalized.take(maxLen) + "…"
}

private fun normalizeCoachText(text: String): String {
  return text
    .replace(coachWhitespaceRegex, " ")
    .replace(coachPunctuationRegex, "")
    .trim()
}
