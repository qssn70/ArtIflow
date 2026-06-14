package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.ArkRequestMessage
import com.studysuit.aiqa.data.MistakeBookItem
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
  mistakeItems: List<MistakeBookItem> = emptyList(),
  nowMillis: Long = System.currentTimeMillis()
): CoachDailyDigest {
  val insights = buildKnowledgeGapInsights(
    messages = messages,
    histories = histories,
    knowledgePoints = knowledgePoints
  )
  val mistakeSignals = buildMistakeCoachSignals(mistakeItems)
  val knowledgeFocusAreas = insights
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
  val mistakeFocusAreas = mistakeSignals.topWeakLabels.take(3).map { label ->
    CoachFocusArea(
      point = label,
      level = KnowledgeGapLevel.HIGH,
      diagnosis = "错题本里这个点反复出错或到期未稳",
      action = "先复盘错因，再做一道同类变式",
      evidence = normalizeCoachText(mistakeSignals.summary)
    )
  }
  val focusAreas = (mistakeFocusAreas + knowledgeFocusAreas)
    .distinctBy { area -> area.point }
    .take(3)

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
  ).let { base ->
    if (mistakeItems.isEmpty()) base else "$base；${mistakeSignals.summary}"
  }
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
  savedQuestions: List<SavedQuestion>,
  mistakeItems: List<MistakeBookItem> = emptyList()
): List<ArkRequestMessage> {
  val contextMessage = ArkRequestMessage(
    role = "user",
    text = buildCoachContextText(
      digest = digest,
      profile = profile,
      knowledgePoints = knowledgePoints,
      knowledgeGapInsights = knowledgeGapInsights,
      savedQuestions = savedQuestions,
      mistakeItems = mistakeItems
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

internal fun buildCoachConversationTurns(messages: List<CoachChatMessage>): List<CoachConversationTurn> {
  if (messages.isEmpty()) {
    return emptyList()
  }

  val turns = mutableListOf<CoachConversationTurn>()
  val pending = mutableListOf<CoachChatMessage>()

  fun flushPending() {
    if (pending.isEmpty()) {
      return
    }
    turns += CoachConversationTurn(
      id = pending.first().id,
      messages = pending.toList()
    )
    pending.clear()
  }

  messages.forEach { message ->
    when (message.role) {
      CoachMessageRole.USER -> {
        flushPending()
        pending += message
      }

      CoachMessageRole.ASSISTANT -> {
        pending += message
        flushPending()
      }
    }
  }

  flushPending()
  return turns
}

internal data class CoachQuickAction(
  val label: String,
  val prompt: String
)

internal data class CoachReplyQuickAction(
  val label: String,
  val prompt: String
)

internal fun buildCoachQuickActions(digest: CoachDailyDigest?): List<CoachQuickAction> {
  val actions = mutableListOf(
    CoachQuickAction(
      label = "先说核心问题",
      prompt = "先直接告诉我：今天我最核心的问题是什么？只说最该先补的一点，再给我一个立刻能做的动作。"
    )
  )

  digest?.focusAreas?.firstOrNull()?.let { area ->
    actions += CoachQuickAction(
      label = "为什么卡${area.point}",
      prompt = "为什么我在${area.point}上总会卡住？请结合今天的表现，指出我最容易漏掉的判断点，再提醒我做这类题第一眼先看什么。"
    )
  }

  digest?.recommendedQuestions?.firstOrNull()?.let { question ->
    val title = question.title.trim().ifBlank { "这道推荐题" }
    actions += CoachQuickAction(
      label = "练前先提醒我",
      prompt = "如果我现在开始练「$title」这类题，第一眼最该先判断什么？请给我一个很短的检查顺序。"
    )
  }

  return actions
    .mapNotNull { action ->
      val label = action.label.trim()
      val prompt = action.prompt.trim()
      if (label.isBlank() || prompt.isBlank()) {
        null
      } else {
        action.copy(label = label, prompt = prompt)
      }
    }
    .distinctBy { action -> action.prompt }
    .take(3)
}

internal fun buildCoachReplyQuickActions(
  message: CoachChatMessage,
  digest: CoachDailyDigest?,
  training: DailyTrainingState
): List<CoachReplyQuickAction> {
  if (message.role != CoachMessageRole.ASSISTANT) {
    return emptyList()
  }

  val normalizedText = message.text.trim()
  val focusPoint = digest?.focusAreas?.firstOrNull()?.point.orEmpty()
  val focusLabel = focusPoint.ifBlank { "这块" }
  val activeRound = training.currentRound

  val actions = when {
    training.isActive && training.phase == DailyTrainingPhase.AWAITING_ANSWER -> listOf(
      CoachReplyQuickAction(
        label = "先给我一点提示",
        prompt = "先别直接给答案。请只提醒我这题第一步该看什么，再给我一个很小的提示。"
      ),
      CoachReplyQuickAction(
        label = "这题在考什么",
        prompt = "这道题本质上在考我什么？请只说核心考点和判断入口，不要直接展开完整解答。"
      ),
      CoachReplyQuickAction(
        label = "换一道同类题",
        prompt = activeRound?.let { round ->
          "把当前这道先放下，再给我一道和「${round.title}」同方向、但更容易一点的题，先只给题目不要答案。"
        } ?: "再给我一道同方向但更容易一点的题，先只给题目不要答案。"
      )
    )

    normalizedText.contains("错因") || normalizedText.contains("漏掉") || normalizedText.contains("知识点") -> listOf(
      CoachReplyQuickAction(
        label = "再分析其他漏洞",
        prompt = "除了你刚才说的这个点，再帮我排查一下我还有没有第二个容易漏掉的漏洞。请按轻重缓急说。"
      ),
      CoachReplyQuickAction(
        label = "帮我出一道题",
        prompt = if (focusPoint.isNotBlank()) {
          "围绕「${focusPoint}」马上给我出一道典型题，先不要答案，等我作答后你再批改。"
        } else {
          "根据你刚才的分析，马上给我出一道最能暴露问题的典型题，先不要答案。"
        }
      ),
      CoachReplyQuickAction(
        label = "给我一个检查顺序",
        prompt = "把你刚才说的内容压缩成一个很短的检查顺序，我下次做题时可以直接照着过一遍。"
      )
    )

    else -> listOf(
      CoachReplyQuickAction(
        label = "帮我出一道题",
        prompt = if (focusPoint.isNotBlank()) {
          "围绕「${focusLabel}」给我出一道典型题，先不要答案，等我作答后你再批改。"
        } else {
          "根据你刚才的分析给我出一道题，先不要答案，等我作答后你再批改。"
        }
      ),
      CoachReplyQuickAction(
        label = "再分析分析其他",
        prompt = "在刚才那段分析之外，再帮我看看我还有没有别的薄弱点，尤其是容易被我忽略的那种。"
      ),
      CoachReplyQuickAction(
        label = "说具体一点",
        prompt = "把你刚才的判断再说具体一点：到底是哪一步最容易出问题？我第一眼应该先检查什么？"
      )
    )
  }

  return actions
    .mapNotNull { action ->
      val label = action.label.trim()
      val prompt = action.prompt.trim()
      if (label.isBlank() || prompt.isBlank()) null else action.copy(label = label, prompt = prompt)
    }
    .distinctBy { action -> action.prompt }
    .take(3)
}

internal fun buildCoachRecommendationFollowupPrompt(question: CoachRecommendedQuestion): String {
  val title = question.title.trim().ifBlank { "这道推荐题" }
  val reason = normalizeCoachSentence(question.reason)
  val basis = normalizeCoachSentence(question.basis)
  return buildString {
    append("你为什么今天推荐我练「")
    append(title)
    append("」？请直接说清它对应我的哪个漏洞，并提醒我做这类题第一眼先看什么。")
    if (reason.isNotBlank()) {
      append("你之前给的理由是：")
      append(reason)
      append("。")
    }
    if (basis.isNotBlank()) {
      append("出题依据是：")
      append(basis)
      append("。")
    }
  }
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
        prompt = prompt,
        basis = buildCoachRecommendationBasis(
          area = area,
          anchor = null,
          actionHint = normalizeCoachSentence(area.action)
        )
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
        prompt = prompt,
        basis = "用于补足今天的训练闭环，继续验证你当前最容易漏掉的判断点。"
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
        prompt = "请给我一道适合我当前年级的中等难度典型题，先不要答案，等我作答后再批改，并指出我漏掉的知识点。",
        basis = "当前样本还不够，所以先用一题通用回测题快速补齐样本。"
      ),
      CoachRecommendedQuestion(
        id = "coach-rec-$nowMillis-1",
        title = "思路判断题",
        reason = "先测我是不是会选切入点，而不只是会套步骤。",
        prompt = "请给我一道需要先判断切入点的典型题，先只给题目，不要答案；等我作答后重点评价我的思路是否正确。",
        basis = "当前先优先排查你是不是卡在切入点判断，而不是只会套步骤。"
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
      prompt = prompt,
      basis = buildCoachRecommendationBasis(
        area = area,
        anchor = anchor,
        actionHint = actionHint
      ),
      anchorSavedQuestionId = anchor?.id
    )
  }
}

private fun buildCoachRecommendationBasis(
  area: CoachFocusArea,
  anchor: SavedQuestion?,
  actionHint: String
): String {
  val anchorSnippet = anchor?.question?.let { question -> normalizeCoachSnippet(question, 18) }.orEmpty()
  return buildString {
    append("这道题主要围绕“")
    append(area.point)
    append("”，因为你今天最集中暴露的问题是：")
    append(normalizeCoachSentence(area.diagnosis))
    append("。")
    if (actionHint.isNotBlank()) {
      append("训练重点放在：")
      append(actionHint)
      append("。")
    }
    if (anchorSnippet.isNotBlank()) {
      append("并参考你之前卡住的依据题《")
      append(anchorSnippet)
      append("》。")
    }
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
  savedQuestions: List<SavedQuestion>,
  mistakeItems: List<MistakeBookItem>
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

  val mistakeSummary = buildMistakeCoachSignals(mistakeItems).summary

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
    - 错题本：
    $mistakeSummary

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
