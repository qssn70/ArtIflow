package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.ArkRequestMessage
import org.json.JSONObject
import java.util.Locale

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
  knowledgePoints: Map<String, Int>
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

  return buildString {
    append("请根据下面学习交互，生成1张最合适的Anki卡片。")
    append("不要套固定模板，请自行判断卡型（概念/对比/因果/步骤/易错点/例题等）。")
    append("要求：front可直接测验、back简洁准确、不要套话。")
    append("如本次内容不适合制卡，返回 skip=true。")
    append("\n仅输出JSON，不要代码块，不要解释。")
    append("\nJSON格式：{\"skip\":false,\"front\":\"...\",\"back\":\"...\",\"tags\":[\"...\"],\"card_type\":\"...\"}")
    append("\n约束：front<=60字，back<=180字，tags<=6。")
    append("\n\n交互模式：")
    append(mode)
    append("\n用户画像热点：")
    append(topTopics)
    append("\n知识点热度：")
    append(topKnowledge)
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
  val tags: List<String>
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

  return AiAnkiCardPayload(front = front, back = back, tags = tags)
}

private fun parseJsonObjectSafely(raw: String): JSONObject? {
  val fenced = Regex("```(?:json)?\\s*(\\{[\\s\\S]*})\\s*```", setOf(RegexOption.IGNORE_CASE))
    .find(raw)
    ?.groupValues
    ?.getOrNull(1)
    ?.trim()

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
