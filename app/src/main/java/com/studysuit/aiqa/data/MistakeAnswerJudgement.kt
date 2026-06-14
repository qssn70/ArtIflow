package com.studysuit.aiqa.data

import org.json.JSONObject

data class MistakeAnswerJudgement(
  val isCorrect: Boolean,
  val confidence: Double,
  val reason: String,
  val suggestedScore: Int? = null
)

fun parseMistakeAnswerJudgement(raw: String): MistakeAnswerJudgement? {
  val json = extractJsonObjectCandidate(raw) ?: return null
  val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
  return MistakeAnswerJudgement(
    isCorrect = root.optFlexibleBoolean("is_correct", "isCorrect", "correct"),
    confidence = root.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
    reason = root.optFlexibleString("reason", "理由"),
    suggestedScore = root.optFlexibleInt("suggested_score", "suggestedScore", "score", "分数")
  )
}

fun buildMistakeAnswerJudgementPrompt(
  item: MistakeBookItem,
  userAnswer: String,
  answerOcrText: String = ""
): String {
  return """
    你是错题本复习判分助手。请比较学生本次作答与标准答案，给出是否正确的建议。

    题目：
    ${item.question}

    正确答案：
    ${item.correctAnswer}

    解析：
    ${item.explanation.ifBlank { "（无）" }}

    学生本次文字作答：
    ${userAnswer.trim().ifBlank { "（无）" }}

    作答图片 OCR：
    ${answerOcrText.trim().ifBlank { "（无）" }}

    仅输出JSON，不要 Markdown。格式：
    {"is_correct":true,"confidence":0.0,"reason":"一句话说明","suggested_score":null}
  """.trimIndent()
}

private fun JSONObject.optFlexibleBoolean(vararg names: String): Boolean {
  for (name in names) {
    if (!has(name) || isNull(name)) {
      continue
    }
    return when (val value = opt(name)) {
      is Boolean -> value
      is Number -> value.toInt() != 0
      is String -> value.trim().lowercase() in setOf("true", "yes", "1", "正确", "对")
      else -> false
    }
  }
  return false
}

private fun JSONObject.optFlexibleString(vararg names: String): String {
  for (name in names) {
    if (!has(name) || isNull(name)) {
      continue
    }
    val text = opt(name)?.toString()?.trim().orEmpty()
    if (text.isNotBlank() && text != "null") {
      return text
    }
  }
  return ""
}

private fun JSONObject.optFlexibleInt(vararg names: String): Int? {
  for (name in names) {
    if (!has(name) || isNull(name)) {
      continue
    }
    val value = opt(name)
    val parsed = when (value) {
      is Number -> value.toInt()
      is String -> value.trim().toIntOrNull()
      else -> null
    }
    if (parsed != null) {
      return parsed
    }
  }
  return null
}
