package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.ArkRequestMessage
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.Locale

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
  return throwable?.message?.take(80).orEmpty().ifBlank { fallback }
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
      message is ChatMessage.Assistant && message.spans.firstOrNull()?.sourceQuestion == "初始化引导"
    }
    .takeLast(12)
    .mapNotNull { message ->
      when (message) {
        is ChatMessage.User -> {
          val text = message.text.trim()
          if (text.isBlank()) null else ArkRequestMessage(role = "user", text = text)
        }

        is ChatMessage.Assistant -> {
          val text = message.spans.joinToString(separator = "\n\n") { span -> span.content }.trim()
          if (text.isBlank()) null else ArkRequestMessage(role = "assistant", text = text)
        }
      }
    }
    .toList()
}

internal fun toSpanFollowupMessages(
  span: SpanData,
  followupQuestion: String,
  details: List<SpanDetail>
): List<ArkRequestMessage> {
  val recentDetails = details.take(4).asReversed()
  val contextMessage = buildString {
    append("我们只讨论这一段内容，请基于段落回答追问。\n")
    append("回答要求：简洁直接，先结论后要点，默认不超过6行。\n")
    append("段落：")
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
  val normalized = text.lowercase(Locale.getDefault())
  val matched = knowledgeRules
    .filter { rule -> rule.keywords.any { keyword -> normalized.contains(keyword) } }
    .map { rule -> rule.point }
    .distinct()

  return if (matched.isEmpty()) detectTopicsForProfile(text) else matched
}

internal fun buildAnkiGenerationPrompt(
  mode: String,
  spanContent: String,
  question: String?,
  answer: String,
  profile: ProfileState,
  knowledgePoints: Map<String, Int>,
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

internal fun buildSessionSummaries(
  sessionOrder: List<String>,
  sessionsById: Map<String, StoredSession>
): List<SessionSummary> {
  return sessionOrder.mapNotNull { id ->
    val item = sessionsById[id] ?: return@mapNotNull null
    SessionSummary(
      id = item.id,
      title = item.title,
      updatedAt = item.updatedAt,
      messageCount = item.messages.size
    )
  }.sortedByDescending { summary -> summary.updatedAt }
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
    .flatMap { assistant -> assistant.spans }
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
