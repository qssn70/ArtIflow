package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.ArkRequestMessage
import com.studysuit.aiqa.data.QuestionTagger
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

private val fencedJsonRegex = Regex(
  pattern = "```(?:json)?\\s*(\\{.*?\\})\\s*```",
  options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

internal fun extractFencedJsonCandidate(raw: String): String? {
  return fencedJsonRegex
    .find(raw)
    ?.groupValues
    ?.getOrNull(1)
    ?.trim()
}

internal fun isTokenStale(requestToken: Long, activeToken: Long): Boolean {
  return requestToken != activeToken
}

internal fun resolveErrorHint(throwable: Throwable?, fallback: String): String {
  if (throwable == null) {
    return fallback
  }

  resolveNetworkErrorHint(throwable)?.let { hint ->
    return hint
  }

  return throwable.message?.trim().orEmpty().ifBlank { fallback }.take(80)
}

private fun resolveNetworkErrorHint(throwable: Throwable): String? {
  val causeChain = throwable.toCauseChain()
  if (causeChain.any { cause -> cause is SocketTimeoutException }) {
    return "网络超时，请稍后重试"
  }

  if (causeChain.any { cause -> cause is SSLHandshakeException || cause is SSLPeerUnverifiedException }) {
    return "证书校验失败；如果你连的是自建 IP 接口，请把 BASEURL 改成 http://IP:端口/v1"
  }

  val normalizedMessages = causeChain.map { cause ->
    cause.message.orEmpty().lowercase(Locale.ROOT)
  }
  if (normalizedMessages.any { message ->
      message.contains("certificate") ||
        message.contains("certpath") ||
        message.contains("trust anchor") ||
        message.contains("hostname") ||
        message.contains("peer not verified")
    }) {
    return "证书校验失败；如果你连的是自建 IP 接口，请把 BASEURL 改成 http://IP:端口/v1"
  }
  if (normalizedMessages.any { message ->
      message.contains("software caused connection abort") ||
        message.contains("connection reset") ||
        message.contains("connection aborted") ||
        message.contains("broken pipe") ||
        message.contains("unexpected end of stream")
    }) {
    return "网络连接中断，请重试"
  }

  return null
}

private fun Throwable.toCauseChain(maxDepth: Int = 8): List<Throwable> {
  val chain = mutableListOf<Throwable>()
  var cursor: Throwable? = this
  while (cursor != null && chain.size < maxDepth) {
    chain += cursor
    cursor = cursor.cause
  }
  return chain
}

internal fun <T> deliverTokenAwareResult(
  result: Result<T>,
  requestToken: Long,
  activeToken: Long,
  onStale: () -> Unit,
  onSuccess: (T) -> Unit,
  onFailure: (Throwable) -> Unit
) {
  if (isTokenStale(requestToken, activeToken)) {
    onStale()
    return
  }

  result.onSuccess(onSuccess).onFailure(onFailure)
}

internal fun routeRequestFailure(
  throwable: Throwable,
  fallback: String = "网络不可用",
  onCancel: () -> Unit = {},
  onError: (String) -> Unit
) {
  if (throwable is CancellationException) {
    onCancel()
    throw throwable
  }

  onError(resolveErrorHint(throwable, fallback))
}

internal fun toArkMessages(messages: List<ChatMessage>): List<ArkRequestMessage> {
  return messages
    .filterNot { message ->
      message is ChatMessage.Assistant &&
        (message.mainSpan?.sourceQuestion ?: message.spans.firstOrNull()?.sourceQuestion) == "初始化引导"
    }
    .takeLast(12)
    .mapNotNull { message ->
      when (message) {
        is ChatMessage.User -> {
          val text = message.text.trim()
          if (text.isBlank()) null else ArkRequestMessage(role = "user", text = text)
        }

        is ChatMessage.Assistant -> {
          val text = message.fullAnswerText()
          if (text.isBlank()) null else ArkRequestMessage(role = "assistant", text = text)
        }
      }
    }
    .toList()
}

internal fun toSpanFollowupMessages(
  span: SpanData,
  followupQuestion: String,
  details: List<SpanDetail>,
  messages: List<ChatMessage> = emptyList()
): List<ArkRequestMessage> {
  val recentDetails = details.take(4).asReversed()
  val sourceQuestion = resolveQuestionScopeQuestion(span = span, messages = messages)
  val questionScopeAnswer = resolveQuestionScopeAnswer(spanId = span.id, messages = messages)
  val contextMessage = buildString {
    append("我们以题目为单位回答追问，请先阅读完整上下文再作答。\n")
    append("回答要求：简洁直接，先结论后要点，默认不超过6行。\n")
    append("题目：")
    append(sourceQuestion.ifBlank { "（原题缺失，请结合上下文回答）" })
    if (questionScopeAnswer.isNotBlank()) {
      append("\n该题完整回答：")
      append(questionScopeAnswer)
    }
    append("\n当前追问聚焦段落：")
    append(span.content)
  }

  return buildList {
    add(ArkRequestMessage(role = "user", text = contextMessage))

    recentDetails.forEach { detail ->
      detail.question?.takeIf { question -> question.isNotBlank() }?.let { question ->
        add(ArkRequestMessage(role = "user", text = question))
      }

      val answer = detail.answer.trim()
      if (answer.isNotBlank()) {
        add(ArkRequestMessage(role = "assistant", text = answer))
      }
    }

    add(ArkRequestMessage(role = "user", text = followupQuestion))
  }
}

private fun resolveQuestionScopeAnswer(spanId: String, messages: List<ChatMessage>): String {
  val sourceAssistantMessage = messages.firstOrNull { message ->
    message is ChatMessage.Assistant && message.findSpan(spanId) != null
  } as? ChatMessage.Assistant ?: return ""

  return sourceAssistantMessage.fullAnswerText()
}

private fun resolveQuestionScopeQuestion(span: SpanData, messages: List<ChatMessage>): String {
  val sourceAssistantIndex = messages.indexOfFirst { message ->
    message is ChatMessage.Assistant && message.findSpan(span.id) != null
  }
  val sourceAssistantMessage = messages.getOrNull(sourceAssistantIndex) as? ChatMessage.Assistant

  val sourceSpanQuestion = sourceAssistantMessage
    ?.findSpan(span.id)
    ?.sourceQuestion
    ?.trim()
    .orEmpty()
  if (sourceSpanQuestion.isNotBlank()) {
    return sourceSpanQuestion
  }

  val fallbackSpanQuestion = span.sourceQuestion.trim()
  if (fallbackSpanQuestion.isNotBlank()) {
    return fallbackSpanQuestion
  }

  if (sourceAssistantIndex > 0) {
    for (index in sourceAssistantIndex - 1 downTo 0) {
      val message = messages[index]
      if (message is ChatMessage.User) {
        val question = message.text.trim()
        if (question.isNotBlank()) {
          return question
        }
      }
    }
  }

  return ""
}

internal fun splitParagraphs(content: String): List<String> {
  val byBlankLines = content
    .split(Regex("\\n{2,}"))
    .map(String::trim)
    .filter(String::isNotEmpty)

  if (byBlankLines.size > 1) {
    return byBlankLines
  }

  val sentences = Regex("[^。！？!?]+[。！？!?]?").findAll(content).map { it.value }.toList()
  if (sentences.isEmpty()) {
    return listOf(content.trim()).filter(String::isNotEmpty)
  }

  val chunks = mutableListOf<String>()
  var current = ""

  sentences.forEach { sentence ->
    if ((current + sentence).length > 54 && current.isNotBlank()) {
      chunks += current.trim()
      current = sentence
    } else {
      current += sentence
    }
  }

  if (current.isNotBlank()) {
    chunks += current.trim()
  }

  return chunks
}

internal fun buildAutoExplainPrompt(spanContent: String): String {
  return buildString {
    append("请只针对下面这一段内容做简洁讲解。")
    append("输出要求：")
    append("1) 用中文；2) 先1句结论；3) 再给2~3条关键点；4) 总字数尽量控制在120字内；5) 不要套话。")
    append("\n\n段落内容：")
    append(spanContent)
  }
}

internal fun buildDetailCardSummary(question: String?, answer: String): String {
  val questionSnippet = normalizeInlineText(question.orEmpty()).take(28)
  val answerSnippet = extractAnswerSnippetForSummary(answer)

  return when {
    questionSnippet.isBlank() && answerSnippet.isBlank() -> "讲解摘要"
    questionSnippet.isBlank() -> answerSnippet
    answerSnippet.isBlank() -> questionSnippet
    else -> "$questionSnippet · $answerSnippet"
  }
}

private fun extractAnswerSnippetForSummary(answer: String): String {
  val plainAnswer = answer
    .replace(Regex("```[\\s\\S]*?```"), " ")
    .replace(Regex("[#>*`_\\[\\]()|]"), " ")
    .let(::normalizeInlineText)

  if (plainAnswer.isBlank()) {
    return ""
  }

  val firstSentence = Regex("[^。！？!?；;]{1,54}[。！？!?；;]?")
    .find(plainAnswer)
    ?.value
    .orEmpty()
    .trim()

  return if (firstSentence.isNotBlank()) {
    firstSentence.take(54)
  } else {
    plainAnswer.take(54)
  }
}

private fun normalizeInlineText(text: String): String {
  return text.replace(Regex("\\s+"), " ").trim()
}

internal fun buildFollowupTreeExportMarkdown(
  scopes: List<FollowupTreeScope>,
  exportedAtMillis: Long = System.currentTimeMillis()
): String {
  if (scopes.isEmpty()) {
    return "暂无追问图谱可导出。"
  }

  val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
  val exportedAt = formatter.format(Date(exportedAtMillis))
  val nodeCount = scopes.sumOf { scope -> scope.details.size }

  return buildString {
    append("# 追问图谱导出\n\n")
    append("- 导出时间：").append(exportedAt).append('\n')
    append("- 段落数：").append(scopes.size).append('\n')
    append("- 追问节点数：").append(nodeCount).append('\n')

    scopes.forEachIndexed { index, scope ->
      val normalizedSpanContent = normalizeInlineText(scope.spanContent)
      val normalizedSourceQuestion = normalizeInlineText(scope.sourceQuestion)
      append("\n## 段落 ").append(index + 1).append('\n')
      append("- 段落ID：").append(scope.spanId).append('\n')
      append("- 段落内容：").append(normalizedSpanContent.ifBlank { "（空）" }).append('\n')
      append("- 来源问题：").append(normalizedSourceQuestion.ifBlank { "（空）" }).append('\n')

      if (scope.details.isEmpty()) {
        append("- 追问节点：暂无\n")
        return@forEachIndexed
      }

      append("- 追问节点：\n")
      buildFollowupTreeExportEntries(scope.details).forEach { (depth, detail) ->
        val indent = "  ".repeat(depth)
        val summary = detail.summary
          ?.trim()
          ?.takeIf { summaryText -> summaryText.isNotEmpty() }
          ?: buildDetailCardSummary(question = detail.question, answer = detail.answer)
        append("  ").append(indent).append("- ")
          .append(detail.mode)
          .append(" · ")
          .append(detail.time)
          .append('\n')
        append("  ").append(indent).append("  - 问题：")
          .append(normalizeInlineText(detail.question.orEmpty()).ifBlank { "（无问题文本）" })
          .append('\n')
        append("  ").append(indent).append("  - 摘要：")
          .append(normalizeInlineText(summary).ifBlank { "讲解摘要" })
          .append('\n')
        append("  ").append(indent).append("  - 回答预览：")
          .append(buildFollowupTreeAnswerPreview(detail.answer))
          .append('\n')
        detail.parentDetailId?.takeIf { parentId -> parentId.isNotBlank() }?.let { parentId ->
          append("  ").append(indent).append("  - 父节点：").append(parentId).append('\n')
        }
      }
    }
  }.trimEnd()
}

internal fun buildFollowupTreeExportFileName(exportedAtMillis: Long = System.currentTimeMillis()): String {
  val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.SIMPLIFIED_CHINESE)
  val timestamp = formatter.format(Date(exportedAtMillis))
  return "追问图谱-$timestamp.md"
}

internal fun inferKnowledgePoints(text: String): List<String> {
  return detectKnowledgePoints(text)
}

internal fun buildSavedQuestionPreview(answer: String): String {
  return extractAnswerSnippetForSummary(answer).ifBlank {
    normalizeInlineText(answer).take(56)
  }
}

private fun buildFollowupTreeExportEntries(details: List<SpanDetail>): List<Pair<Int, SpanDetail>> {
  if (details.isEmpty()) {
    return emptyList()
  }

  val chronological = details.asReversed()
  val allIds = chronological.map { detail -> detail.id }.toSet()
  val childrenByParent = LinkedHashMap<String?, MutableList<SpanDetail>>()

  chronological.forEach { detail ->
    val normalizedParent = detail.parentDetailId?.takeIf { parentId -> parentId in allIds }
    childrenByParent.getOrPut(normalizedParent) { mutableListOf() }.add(detail)
  }

  val flattened = mutableListOf<Pair<Int, SpanDetail>>()
  val visited = mutableSetOf<String>()

  fun appendNode(detail: SpanDetail, depth: Int, lineage: Set<String>) {
    if (detail.id in lineage || !visited.add(detail.id)) {
      return
    }

    flattened += depth to detail
    val nextLineage = lineage + detail.id
    childrenByParent[detail.id].orEmpty().forEach { child ->
      appendNode(child, depth = depth + 1, lineage = nextLineage)
    }
  }

  val roots = childrenByParent[null].orEmpty().ifEmpty { chronological }
  roots.forEach { root ->
    appendNode(root, depth = 0, lineage = emptySet())
  }

  chronological.forEach { detail ->
    if (detail.id !in visited) {
      appendNode(detail, depth = 0, lineage = emptySet())
    }
  }

  return flattened
}

private fun buildFollowupTreeAnswerPreview(answer: String): String {
  val normalized = normalizeInlineText(answer)
  if (normalized.isBlank()) {
    return "（无回答内容）"
  }

  return if (normalized.length <= 160) {
    normalized
  } else {
    normalized.take(160) + "..."
  }
}

internal fun mergeKnowledgePoints(
  current: Map<String, Int>,
  texts: List<String>
): Map<String, Int> {
  val merged = current.toMutableMap()
  val points = texts
    .asSequence()
    .flatMap { text -> detectKnowledgePoints(text).asSequence() }
    .filter { point -> point.isNotBlank() }
    .distinct()
    .toList()

  points.forEach { point ->
    merged[point] = (merged[point] ?: 0) + 1
  }

  return merged
}

private fun detectKnowledgePoints(text: String): List<String> {
  val tagged = QuestionTagger.autoTag(text).knowledgePoints
    .map { point -> point.trim() }
    .filter { point -> point.isNotBlank() }
    .distinct()
  if (tagged.isNotEmpty()) {
    return tagged
  }

  val normalized = text.lowercase(Locale.getDefault())
  val matched = knowledgeRules
    .filter { rule -> rule.keywords.any { keyword -> normalized.contains(keyword) } }
    .map { rule -> rule.point }
    .distinct()

  return if (matched.isEmpty()) detectTopicsForProfile(text) else matched
}

private data class KnowledgeGapStats(
  val point: String,
  var exposureCount: Int = 0,
  var directWeakCount: Int = 0,
  var reasoningCount: Int = 0,
  var methodCount: Int = 0,
  var conceptCount: Int = 0,
  var threadedDepth: Int = 0,
  val evidenceSamples: MutableList<String> = mutableListOf()
)

private val directWeakKeywords = listOf(
  "不会", "不太会", "没学会", "没懂", "不懂", "不明白", "看不懂", "不会做", "卡住", "忘了"
)
private val reasoningGapKeywords = listOf(
  "为什么", "怎么想到", "怎么判断", "哪里来", "依据", "凭什么", "为什么这样", "怎么得出"
)
private val methodGapKeywords = listOf(
  "怎么做", "怎么下手", "思路", "方法", "步骤", "套路", "切入点", "先做什么"
)
private val conceptGapKeywords = listOf(
  "概念", "定义", "性质", "公式", "定理", "判定", "条件", "含义", "是什么"
)

internal fun buildKnowledgeGapInsights(
  messages: List<ChatMessage>,
  histories: Map<String, List<SpanDetail>>,
  knowledgePoints: Map<String, Int>
): List<KnowledgeGapInsight> {
  val statsByPoint = linkedMapOf<String, KnowledgeGapStats>()

  fun record(
    text: String,
    fallbackPoints: List<String> = emptyList(),
    depthBoost: Int = 0
  ) {
    val normalized = text.trim()
    if (normalized.isBlank()) {
      return
    }

    val points = (detectKnowledgePoints(normalized) + fallbackPoints)
      .map { point -> point.trim() }
      .filter { point -> point.isNotBlank() }
      .distinct()
    if (points.isEmpty()) {
      return
    }

    val lowered = normalized.lowercase(Locale.getDefault())
    val directWeakHits = directWeakKeywords.count { keyword -> lowered.contains(keyword) }
    val reasoningHits = reasoningGapKeywords.count { keyword -> lowered.contains(keyword) }
    val methodHits = methodGapKeywords.count { keyword -> lowered.contains(keyword) }
    val conceptHits = conceptGapKeywords.count { keyword -> lowered.contains(keyword) }
    val snippet = normalizeInlineText(normalized).take(28)

    points.forEach { point ->
      val stats = statsByPoint.getOrPut(point) { KnowledgeGapStats(point = point) }
      stats.exposureCount += 1
      stats.directWeakCount += directWeakHits
      stats.reasoningCount += reasoningHits
      stats.methodCount += methodHits
      stats.conceptCount += conceptHits
      stats.threadedDepth += depthBoost
      if (snippet.isNotBlank() && stats.evidenceSamples.none { sample -> sample == snippet }) {
        stats.evidenceSamples += snippet
      }
    }
  }

  messages.forEach { message ->
    if (message is ChatMessage.User) {
      record(message.text)
    }
  }

  histories.forEach { (spanId, details) ->
    val span = findSpanById(messages, spanId)
    val threadContextPoints = detectKnowledgePoints(
      buildString {
        span?.sourceQuestion?.takeIf { value -> value.isNotBlank() }?.let {
          append(it)
          append('\n')
        }
        span?.content?.takeIf { value -> value.isNotBlank() }?.let { append(it) }
      }
    )
    val extraDepth = (details.size - 1).coerceAtLeast(0)
    if (extraDepth > 0) {
      details.firstOrNull()?.question?.takeIf { question -> question.isNotBlank() }?.let { rootQuestion ->
        record(rootQuestion, fallbackPoints = threadContextPoints, depthBoost = extraDepth)
      }
    }

    details.forEachIndexed { index, detail ->
      val branchDepth = if (detail.parentDetailId != null) index + 1 else 0
      detail.question?.let { question ->
        record(question, fallbackPoints = threadContextPoints, depthBoost = branchDepth)
      }
    }
  }

  return statsByPoint.values
    .map { stats ->
      val heatBoost = (knowledgePoints[stats.point] ?: 0).coerceAtMost(3)
      val score =
        stats.directWeakCount * 4 +
          stats.reasoningCount * 3 +
          stats.methodCount * 2 +
          stats.conceptCount * 2 +
          stats.threadedDepth * 2 +
          stats.exposureCount +
          heatBoost
      val level = when {
        score >= 10 -> KnowledgeGapLevel.HIGH
        score >= 6 -> KnowledgeGapLevel.MEDIUM
        else -> KnowledgeGapLevel.LOW
      }
      val diagnosis = when {
        stats.directWeakCount > 0 && stats.directWeakCount >= maxOf(stats.reasoningCount, stats.methodCount, stats.conceptCount) -> {
          "你多次直接表示这块不会或没懂，基础还不稳"
        }
        stats.conceptCount >= maxOf(stats.reasoningCount, stats.methodCount) && stats.conceptCount > 0 -> {
          "更像定义、性质或判定条件没有真正吃透"
        }
        stats.reasoningCount >= stats.methodCount && stats.reasoningCount > 0 -> {
          "更像为什么这么做的依据链断了"
        }
        stats.methodCount > 0 -> {
          "更像切入点和解题步骤不够稳定"
        }
        stats.threadedDepth >= 2 -> {
          "同类问题连续追问，说明这里仍然卡住"
        }
        else -> {
          "这一块反复出现，建议回补基础再做题"
        }
      }
      val evidenceParts = buildList {
        if (stats.exposureCount > 0) add("相关提问${stats.exposureCount}次")
        if (stats.directWeakCount > 0) add("直接说不会/没懂${stats.directWeakCount}次")
        if (stats.threadedDepth > 0) add("连续深追${stats.threadedDepth}层")
      }
      val evidence = buildString {
        append(evidenceParts.joinToString(separator = " · ").ifBlank { "已多次围绕这一点追问" })
        stats.evidenceSamples.firstOrNull()?.let { sample ->
          append(" · 例：")
          append(sample)
        }
      }
      val action = when {
        stats.conceptCount > 0 -> "先补 ${stats.point} 的定义、判定条件和常见误区，再做 1 道基础题验证。"
        stats.reasoningCount > 0 -> "把 ${stats.point} 每一步为什么能这样做写成依据链，再回到原题复现。"
        stats.methodCount > 0 -> "把 ${stats.point} 的标准切入步骤压成 3 步模板，重新走一遍原题。"
        else -> "先做 1-2 道同类型基础题，把 ${stats.point} 练熟后再继续追问。"
      }
      KnowledgeGapInsight(
        point = stats.point,
        level = level,
        score = score,
        evidence = evidence,
        diagnosis = diagnosis,
        action = action
      )
    }
    .filter { insight -> insight.score >= 4 }
    .sortedWith(compareByDescending<KnowledgeGapInsight> { it.score }.thenBy { it.point })
    .take(5)
}

internal fun summarizeKnowledgeGapInsights(insights: List<KnowledgeGapInsight>): String {
  return insights
    .take(4)
    .joinToString(separator = "；") { insight ->
      "${insight.point}(${insight.level.label})：${insight.diagnosis}"
    }
    .ifBlank { "暂无明显薄弱点" }
}

internal fun buildAnkiGenerationPrompt(
  mode: String,
  spanContent: String,
  question: String?,
  answer: String,
  profile: ProfileState,
  knowledgePoints: Map<String, Int>,
  knowledgeGapInsights: List<KnowledgeGapInsight>,
  existingDecks: List<String>
): String {
  val topTopics = profile.topicHits.entries
    .sortedByDescending { entry -> entry.value }
    .take(4)
    .joinToString(separator = "，") { entry -> "${entry.key}(${entry.value})" }
    .ifBlank { "暂无" }
  val topKnowledge = knowledgePoints.entries
    .sortedByDescending { entry -> entry.value }
    .take(6)
    .joinToString(separator = "，") { entry -> "${entry.key}(${entry.value})" }
    .ifBlank { "暂无" }
  val deckCatalog = existingDecks
    .asSequence()
    .map { deck -> deck.trim() }
    .filter { deck -> deck.isNotBlank() }
    .distinct()
    .joinToString(separator = "，")
    .ifBlank { "暂无" }
  val gapSummary = summarizeKnowledgeGapInsights(knowledgeGapInsights)

  return buildString {
    append("请根据下面学习交互，生成1张最合适的Anki卡片。")
    append("不要套固定模板，请自行判断卡型（概念/对比/因果/步骤/易错点/例题等）。")
    append("要求：front可直接测验、back简洁准确、不要套话。")
    append("如本次内容不适合制卡，返回 skip=true。")
    append("需要输出 deck 字段：优先归入已有卡组；只有都不匹配时再创建新卡组名。")
    append("\n仅输出JSON，不要代码块，不要解释。")
    append("\nJSON格式：{\"skip\":false,\"front\":\"...\",\"back\":\"...\",\"tags\":[\"...\"],\"deck\":\"...\",\"card_type\":\"...\"}")
    append("\n约束：front<=60字，back<=180字，tags<=6。")
    append("\ndeck命名：2~12字，中文优先，不要使用\"卡组\"、\"默认\"这类空泛名称。")
    append("\n\n交互模式：")
    append(mode)
    append("\n用户画像热点：")
    append(topTopics)
    append("\n知识点热度：")
    append(topKnowledge)
    append("\n薄弱点洞察：")
    append(gapSummary)
    append("\n已有卡组：")
    append(deckCatalog)
    append("\n段落内容：")
    append(spanContent)
    if (!question.isNullOrBlank()) {
      append("\n用户追问：")
      append(question)
    }
    append("\nAI回答：")
    append(answer)
  }
}

internal data class AiAnkiCardPayload(
  val front: String,
  val back: String,
  val tags: List<String>,
  val deck: String?
)

internal data class AnkiDeckSummary(
  val name: String,
  val cardCount: Int,
  val topTags: List<String>,
  val needsWorkCount: Int,
  val proficientCount: Int
)

internal fun parseAiAnkiCardPayload(raw: String): AiAnkiCardPayload? {
  val payload = parseJsonObjectSafely(raw) ?: return null
  if (payload.optBoolean("skip", false)) {
    return null
  }

  val front = payload.optString("front").trim()
  val back = payload.optString("back").trim()
  if (front.isBlank() || back.isBlank()) {
    return null
  }

  val tags = payload.optJSONArray("tags")?.let { array ->
    buildList {
      for (index in 0 until array.length()) {
        val tag = array.optString(index).trim()
        if (tag.isNotBlank()) {
          add(tag)
        }
      }
    }
  }.orEmpty()

  val deck = payload.optString("deck").trim().takeIf { value -> value.isNotBlank() }

  return AiAnkiCardPayload(front = front, back = back, tags = tags, deck = deck)
}

internal fun detectExistingDeckCategories(cards: List<AnkiCard>): List<String> {
  return cards
    .asSequence()
    .map { card -> card.deckName.trim() }
    .filter { deck -> deck.isNotBlank() }
    .distinct()
    .toList()
}

internal fun buildAnkiDeckSummaries(cards: List<AnkiCard>): List<AnkiDeckSummary> {
  return cards
    .groupBy { card -> normalizeDeckName(card.deckName) ?: DEFAULT_ANKI_DECK_NAME }
    .map { (deck, cardsInDeck) ->
      val tagHits = linkedMapOf<String, Int>()
      cardsInDeck.forEach { card ->
        card.tags.forEach { tag ->
          val normalized = tag.trim()
          if (normalized.isNotBlank()) {
            tagHits[normalized] = (tagHits[normalized] ?: 0) + 1
          }
        }
      }

      AnkiDeckSummary(
        name = deck,
        cardCount = cardsInDeck.size,
        topTags = tagHits.entries
          .sortedByDescending { entry -> entry.value }
          .map { entry -> entry.key }
          .take(3),
        needsWorkCount = cardsInDeck.count { card -> card.mastery == CardMasteryLevel.NEEDS_WORK },
        proficientCount = cardsInDeck.count { card -> card.mastery == CardMasteryLevel.PROFICIENT }
      )
    }
    .sortedWith(compareByDescending<AnkiDeckSummary> { it.cardCount }.thenBy { it.name })
}

internal fun buildDeckPracticeSummary(
  deckName: String,
  cards: List<AnkiCard>,
  selections: Map<String, CardMasteryLevel>
): DeckPracticeSummary {
  val reviewed = selections.values.toList()
  return DeckPracticeSummary(
    deckName = deckName,
    totalCards = cards.size,
    reviewedCards = reviewed.size,
    needsWorkCount = reviewed.count { mastery -> mastery == CardMasteryLevel.NEEDS_WORK },
    familiarCount = reviewed.count { mastery -> mastery == CardMasteryLevel.FAMILIAR },
    proficientCount = reviewed.count { mastery -> mastery == CardMasteryLevel.PROFICIENT }
  )
}

internal fun resolveDeckNameForAutoCard(
  suggestedDeck: String?,
  tags: List<String>,
  existingCards: List<AnkiCard>
): String {
  val existingDecks = detectExistingDeckCategories(existingCards)
  val normalizedSuggestion = normalizeDeckName(suggestedDeck)

  if (normalizedSuggestion != null) {
    return existingDecks.firstOrNull { deck ->
      deck.equals(normalizedSuggestion, ignoreCase = true)
    } ?: normalizedSuggestion
  }

  val normalizedTags = tags
    .asSequence()
    .map { tag -> tag.trim().lowercase(Locale.getDefault()) }
    .filter { tag -> tag.isNotBlank() }
    .toSet()

  if (normalizedTags.isNotEmpty()) {
    val grouped = existingCards.groupBy { card -> card.deckName }
    val matchedDeck = grouped
      .map { (deck, cardsInDeck) ->
        val deckTags = cardsInDeck
          .flatMap { card -> card.tags }
          .asSequence()
          .map { tag -> tag.trim().lowercase(Locale.getDefault()) }
          .filter { tag -> tag.isNotBlank() }
          .toSet()
        val score = normalizedTags.count { tag -> tag in deckTags }
        deck to score
      }
      .filter { (_, score) -> score > 0 }
      .maxByOrNull { (_, score) -> score }
      ?.first

    if (!matchedDeck.isNullOrBlank()) {
      return matchedDeck
    }
  }

  val firstTag = tags.firstOrNull { tag -> tag.isNotBlank() }
  if (!firstTag.isNullOrBlank()) {
    return normalizeDeckName("${firstTag.trim()}卡组") ?: DEFAULT_ANKI_DECK_NAME
  }

  return existingDecks.firstOrNull { deck ->
    deck.equals(DEFAULT_ANKI_DECK_NAME, ignoreCase = true)
  } ?: DEFAULT_ANKI_DECK_NAME
}

internal fun normalizeDeckName(raw: String?): String? {
  val trimmed = raw?.trim().orEmpty()
  if (trimmed.isBlank()) {
    return null
  }
  return trimmed.replace(Regex("\\s+"), " ").take(12)
}

private fun parseJsonObjectSafely(raw: String): JSONObject? {
  val fenced = extractFencedJsonCandidate(raw)

  val trimmed = raw.trim()
  val bracketStart = trimmed.indexOf('{')
  val bracketEnd = trimmed.lastIndexOf('}')
  val sliced = if (bracketStart >= 0 && bracketEnd > bracketStart) {
    trimmed.substring(bracketStart, bracketEnd + 1)
  } else {
    null
  }

  val candidates = buildList {
    if (!fenced.isNullOrBlank()) add(fenced)
    if (!sliced.isNullOrBlank()) add(sliced)
    if (trimmed.isNotBlank()) add(trimmed)
  }

  candidates.forEach { candidate ->
    runCatching { JSONObject(candidate) }.getOrNull()?.let { parsed ->
      return parsed
    }
  }
  return null
}

internal fun normalizeCardText(text: String, maxLen: Int): String {
  val normalized = text
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .trim()
  if (normalized.isEmpty()) {
    return ""
  }

  val trimmedLines = normalized
    .split('\n')
    .map { line -> line.trimEnd() }

  val collapsed = buildString {
    var pendingBlank = 0
    trimmedLines.forEach { line ->
      if (line.isBlank()) {
        pendingBlank += 1
        return@forEach
      }

      if (length > 0) {
        val blanksToInsert = pendingBlank.coerceIn(1, 2)
        repeat(blanksToInsert) { append('\n') }
      }
      pendingBlank = 0
      append(line)
    }
  }.trim()

  return collapsed.take(maxLen)
}

internal fun prependAnkiCard(current: List<AnkiCard>, card: AnkiCard): List<AnkiCard> {
  val deduplicated = current.filterNot { existing ->
    existing.front == card.front && existing.back == card.back
  }
  return listOf(card) + deduplicated.take(199)
}

internal fun sortAnkiCardsForReview(cards: List<AnkiCard>): List<AnkiCard> {
  return cards.sortedWith(
    compareBy<AnkiCard> { card -> card.mastery.reviewPriority }
      .thenByDescending { card -> card.createdAt }
  )
}

internal fun mergeGlobalAnkiCards(sessions: List<StoredSession>): List<AnkiCard> {
  val mergedByContent = linkedMapOf<String, AnkiCard>()

  sessions.forEach { session ->
    session.ankiCards.forEach { card ->
      val key = "${card.front.trim()}\u0001${card.back.trim()}"
      val existing = mergedByContent[key]
      mergedByContent[key] = if (existing == null) {
        card
      } else {
        pickPreferredGlobalCard(existing, card)
      }
    }
  }

  return sortAnkiCardsForReview(mergedByContent.values.toList())
}

private fun pickPreferredGlobalCard(existing: AnkiCard, candidate: AnkiCard): AnkiCard {
  val existingReviewMoment = existing.lastReviewedAt ?: Long.MIN_VALUE
  val candidateReviewMoment = candidate.lastReviewedAt ?: Long.MIN_VALUE

  return when {
    candidateReviewMoment > existingReviewMoment -> candidate
    candidateReviewMoment < existingReviewMoment -> existing
    candidate.reviewCount > existing.reviewCount -> candidate
    candidate.reviewCount < existing.reviewCount -> existing
    candidate.createdAt > existing.createdAt -> candidate
    else -> existing
  }
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

internal fun isCardDueForReview(card: AnkiCard, now: Long = System.currentTimeMillis()): Boolean {
  return card.nextReviewAt <= now
}

internal fun dueReviewCards(cards: List<AnkiCard>, now: Long = System.currentTimeMillis()): List<AnkiCard> {
  return cards
    .asSequence()
    .filter { card -> isCardDueForReview(card, now) }
    .sortedWith(
      compareBy<AnkiCard> { card -> card.nextReviewAt }
        .thenBy { card -> card.mastery.reviewPriority }
        .thenByDescending { card -> card.createdAt }
    )
    .toList()
}

internal fun countDueReviewCards(cards: List<AnkiCard>, now: Long = System.currentTimeMillis()): Int {
  return dueReviewCards(cards, now).size
}

internal fun applySrsReview(
  card: AnkiCard,
  mastery: CardMasteryLevel,
  reviewedAt: Long = System.currentTimeMillis()
): AnkiCard {
  val intervalDays = when (mastery) {
    CardMasteryLevel.UNRATED -> 0L
    CardMasteryLevel.NEEDS_WORK -> 1L
    CardMasteryLevel.FAMILIAR -> 3L
    CardMasteryLevel.PROFICIENT -> 7L
  }

  val nextReviewAt = if (intervalDays <= 0L) {
    reviewedAt
  } else {
    reviewedAt + intervalDays * MILLIS_PER_DAY
  }

  return card.copy(
    mastery = mastery,
    reviewCount = card.reviewCount + 1,
    lastReviewedAt = reviewedAt,
    nextReviewAt = nextReviewAt
  )
}

internal fun markSpanProcessing(current: ChatUiState, spanId: String): ChatUiState {
  return current.copy(processingSpanIds = current.processingSpanIds + spanId)
}

internal fun clearSpanProcessing(
  current: ChatUiState,
  spanId: String,
  toastMessage: String? = current.toastMessage
): ChatUiState {
  return current.copy(
    processingSpanIds = current.processingSpanIds - spanId,
    toastMessage = toastMessage
  )
}

internal fun appendSpanDetailHistory(
  current: ChatUiState,
  spanId: String,
  detail: SpanDetail,
  toastMessage: String
): ChatUiState {
  val updatedHistory = current.histories.toMutableMap()
  updatedHistory[spanId] = listOf(detail) + current.histories[spanId].orEmpty()
  return current.copy(
    histories = updatedHistory,
    processingSpanIds = current.processingSpanIds - spanId,
    toastMessage = toastMessage
  )
}

internal fun upsertSpanDetailHistory(
  current: ChatUiState,
  spanId: String,
  detail: SpanDetail,
  clearProcessing: Boolean = false,
  toastMessage: String? = current.toastMessage
): ChatUiState {
  val updatedHistory = current.histories.toMutableMap()
  val existing = current.histories[spanId].orEmpty()
  val index = existing.indexOfFirst { item -> item.id == detail.id }
  updatedHistory[spanId] = if (index >= 0) {
    existing.toMutableList().also { mutable ->
      mutable[index] = detail
    }
  } else {
    listOf(detail) + existing
  }

  val nextProcessing = if (clearProcessing) {
    current.processingSpanIds - spanId
  } else {
    current.processingSpanIds
  }

  return current.copy(
    histories = updatedHistory,
    processingSpanIds = nextProcessing,
    toastMessage = toastMessage
  )
}

internal fun removeSpanDetailHistory(
  current: ChatUiState,
  spanId: String,
  detailId: String
): ChatUiState {
  val existing = current.histories[spanId].orEmpty()
  if (existing.none { detail -> detail.id == detailId }) {
    return current
  }

  val updatedHistory = current.histories.toMutableMap()
  val remaining = existing.filterNot { detail -> detail.id == detailId }
  if (remaining.isEmpty()) {
    updatedHistory.remove(spanId)
  } else {
    updatedHistory[spanId] = remaining
  }
  return current.copy(histories = updatedHistory)
}

internal data class SessionSeeds(
  val messageSeed: Int,
  val spanSeed: Int,
  val detailSeed: Int,
  val cardSeed: Int
)

internal fun deriveSessionSeeds(active: StoredSession): SessionSeeds {
  val messageSeed = active.messages.mapNotNull { message ->
    message.id.removePrefix("msg-").toIntOrNull()
  }.maxOrNull() ?: 0

  val spanSeed = active.messages
    .filterIsInstance<ChatMessage.Assistant>()
    .flatMap { assistant -> assistant.interactiveSpans() }
    .mapNotNull { span -> span.id.removePrefix("span-").toIntOrNull() }
    .maxOrNull() ?: 0

  val detailSeed = active.histories.values
    .flatten()
    .mapNotNull { detail -> detail.id.removePrefix("detail-").toIntOrNull() }
    .maxOrNull() ?: 0

  val cardSeed = active.ankiCards.mapNotNull { card ->
    card.id.removePrefix("card-").toIntOrNull()
  }.maxOrNull() ?: 0

  return SessionSeeds(
    messageSeed = messageSeed,
    spanSeed = spanSeed,
    detailSeed = detailSeed,
    cardSeed = cardSeed
  )
}
