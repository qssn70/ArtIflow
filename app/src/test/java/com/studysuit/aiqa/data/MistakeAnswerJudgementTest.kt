package com.studysuit.aiqa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeAnswerJudgementTest {

  @Test
  fun parseMistakeAnswerJudgementAcceptsFencedJson() {
    val judgement = parseMistakeAnswerJudgement(
      """
      ```json
      {
        "is_correct": true,
        "confidence": 0.91,
        "reason": "关键结论和步骤都一致",
        "suggested_score": 5
      }
      ```
      """.trimIndent()
    )

    requireNotNull(judgement)
    assertTrue(judgement.isCorrect)
    assertEquals(0.91, judgement.confidence, 0.001)
    assertEquals("关键结论和步骤都一致", judgement.reason)
    assertEquals(5, judgement.suggestedScore)
  }

  @Test
  fun parseMistakeAnswerJudgementUnderstandsWrongAnswer() {
    val judgement = parseMistakeAnswerJudgement(
      """{"is_correct":false,"confidence":0.8,"reason":"符号写反了"}"""
    )

    requireNotNull(judgement)
    assertFalse(judgement.isCorrect)
    assertEquals("符号写反了", judgement.reason)
  }

  @Test
  fun buildMistakeAnswerJudgementPromptContainsQuestionAnswerAndUserResponse() {
    val item = MistakeBookItem.create(
      id = "mistake-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "2",
      explanation = "移项可得 x=2。",
      createdAt = 1L
    )

    val prompt = buildMistakeAnswerJudgementPrompt(
      item = item,
      userAnswer = "x=1",
      answerOcrText = "作答图片 OCR"
    )

    assertTrue(prompt.contains("已知 x+1=3"))
    assertTrue(prompt.contains("正确答案"))
    assertTrue(prompt.contains("x=1"))
    assertTrue(prompt.contains("作答图片 OCR"))
    assertTrue(prompt.contains("仅输出JSON"))
  }
}
