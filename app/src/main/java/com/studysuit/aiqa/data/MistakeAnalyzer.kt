package com.studysuit.aiqa.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 错误分析结果
 */
data class MistakeAnalysisResult(
  val errorType: String,
  val knowledgePoints: List<String>,
  val explanation: String,
  val suggestions: List<String>,
  val relatedTopics: List<String>
)

/**
 * 错误分析器配置
 */
data class MistakeAnalyzerConfig(
  val apiKey: String = "",
  val model: String = "gpt-4",
  val baseUrl: String = "http://49.235.88.239:3000/v1",
  val timeoutSeconds: Long = 60
)

/**
 * AI 错误分析器
 * 分析学生答题错误，提供错误原因、知识点漏洞和改进建议
 */
class MistakeAnalyzer(
  private val httpClient: OkHttpClient = defaultHttpClient()
) {

  /**
   * 分析答题错误
   *
   * @param question 题目内容
   * @param studentAnswer 学生答案
   * @param correctAnswer 标准答案
   * @param subject 学科（数学、物理、化学等）
   * @param config API 配置
   * @return 分析结果
   */
  suspend fun analyzeMistake(
    question: String,
    studentAnswer: String,
    correctAnswer: String,
    subject: String,
    config: MistakeAnalyzerConfig = MistakeAnalyzerConfig()
  ): Result<MistakeAnalysisResult> {
    // 参数校验
    if (question.isBlank()) {
      return Result.failure(IllegalArgumentException("题目不能为空"))
    }
    if (studentAnswer.isBlank()) {
      return Result.failure(IllegalArgumentException("学生答案不能为空"))
    }
    if (correctAnswer.isBlank()) {
      return Result.failure(IllegalArgumentException("标准答案不能为空"))
    }
    if (config.apiKey.isBlank()) {
      return Result.failure(IllegalStateException("请先配置 API Key"))
    }

    return withContext(Dispatchers.IO) {
      runCatching {
        val systemPrompt = buildSystemPrompt(subject)
        val userPrompt = buildUserPrompt(question, studentAnswer, correctAnswer)
        val requestBody = buildRequestBody(systemPrompt, userPrompt, config)

        val request = Request.Builder()
          .url("${config.baseUrl.trimEnd('/')}/chat/completions")
          .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
          .header("Authorization", "Bearer ${config.apiKey}")
          .header("Content-Type", "application/json")
          .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
          val message = extractErrorMessage(body)
          throw IllegalStateException("AI 分析请求失败 (${response.code}): $message")
        }

        val text = parseAssistantText(body)
        if (text.isBlank()) {
          throw IllegalStateException("AI 分析返回为空，请稍后重试")
        }

        parseAnalysisResult(text)
      }
    }
  }

  /**
   * 构建系统提示词
   */
  private fun buildSystemPrompt(subject: String): String {
    return """
      你是一名专业的${subject}教师，擅长分析学生的答题错误。
      请分析学生答案与标准答案的差异，找出：
      1. 错误类型（计算错误、概念错误、审题错误、方法错误等）
      2. 涉及的知识点
      3. 错误原因的详细解释
      4. 针对性的改进建议
      5. 需要巩固的相关知识点

      请以 JSON 格式返回结果，格式如下：
      ```json
      {
        "errorType": "错误类型",
        "knowledgePoints": ["知识点1", "知识点2"],
        "explanation": "详细错误解析",
        "suggestions": ["建议1", "建议2"],
        "relatedTopics": ["相关知识点1", "相关知识点2"]
      }
      ```
    """.trimIndent()
  }

  /**
   * 构建用户提示词
   */
  private fun buildUserPrompt(
    question: String,
    studentAnswer: String,
    correctAnswer: String
  ): String {
    return """
      请分析以下答题错误：

      【题目】
      $question

      【学生答案】
      $studentAnswer

      【标准答案】
      $correctAnswer

      请分析学生的错误原因并提供改进建议。
    """.trimIndent()
  }

  /**
   * 构建请求体
   */
  private fun buildRequestBody(
    systemPrompt: String,
    userPrompt: String,
    config: MistakeAnalyzerConfig
  ): String {
    val messages = JSONArray()
      .put(
        JSONObject()
          .put("role", "system")
          .put("content", systemPrompt)
      )
      .put(
        JSONObject()
          .put("role", "user")
          .put("content", userPrompt)
      )

    return JSONObject()
      .put("model", config.model)
      .put("messages", messages)
      .put("temperature", 0.7)
      .toString()
  }

  /**
   * 解析 AI 返回的文本
   */
  private fun parseAssistantText(body: String): String {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return ""

    val choices = root.optJSONArray("choices")
    if (choices != null && choices.length() > 0) {
      val message = choices.optJSONObject(0)?.optJSONObject("message")
      if (message != null) {
        val content = message.optString("content")
        if (content.isNotBlank()) {
          return content
        }
      }
    }

    return ""
  }

  /**
   * 从 AI 返回的文本中解析 JSON 分析结果
   */
  private fun parseAnalysisResult(text: String): MistakeAnalysisResult {
    // 尝试提取 JSON 代码块中的内容
    val jsonText = extractJsonFromMarkdown(text) ?: text

    val root = runCatching { JSONObject(jsonText) }.getOrNull()
      ?: return createDefaultResult(text)

    return MistakeAnalysisResult(
      errorType = root.optString("errorType").ifBlank { "未知错误类型" },
      knowledgePoints = parseStringArray(root.optJSONArray("knowledgePoints")),
      explanation = root.optString("explanation").ifBlank { text },
      suggestions = parseStringArray(root.optJSONArray("suggestions")),
      relatedTopics = parseStringArray(root.optJSONArray("relatedTopics"))
    )
  }

  /**
   * 从 Markdown 代码块中提取 JSON
   */
  private fun extractJsonFromMarkdown(text: String): String? {
    val jsonBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```")
    val match = jsonBlockRegex.find(text)
    return match?.groupValues?.get(1)?.trim()
  }

  /**
   * 解析字符串数组
   */
  private fun parseStringArray(array: JSONArray?): List<String> {
    if (array == null) return emptyList()

    return (0 until array.length())
      .mapNotNull { index ->
        array.optString(index).takeIf { it.isNotBlank() }
      }
  }

  /**
   * 创建默认结果（当解析失败时）
   */
  private fun createDefaultResult(text: String): MistakeAnalysisResult {
    return MistakeAnalysisResult(
      errorType = "分析结果解析失败",
      knowledgePoints = emptyList(),
      explanation = text,
      suggestions = emptyList(),
      relatedTopics = emptyList()
    )
  }

  /**
   * 提取错误信息
   */
  private fun extractErrorMessage(body: String): String {
    if (body.isBlank()) {
      return "空响应"
    }

    return runCatching {
      val root = JSONObject(body)
      val error = root.optJSONObject("error")
      when {
        error != null && error.optString("message").isNotBlank() -> error.optString("message")
        root.optString("message").isNotBlank() -> root.optString("message")
        else -> body.take(280)
      }
    }.getOrElse {
      body.take(280)
    }
  }

  companion object {
    private fun defaultHttpClient(): OkHttpClient {
      return OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    }
  }
}
