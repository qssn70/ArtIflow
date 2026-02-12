package com.studysuit.aiqa.data

import com.studysuit.aiqa.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ArkRequestMessage(
  val role: String,
  val text: String
)

class ArkApiClient(
  private val httpClient: OkHttpClient = defaultHttpClient()
) {
  fun isConfigured(): Boolean {
    return BuildConfig.ARK_API_KEY.isNotBlank()
  }

  suspend fun generateReply(messages: List<ArkRequestMessage>): Result<String> {
    if (messages.isEmpty()) {
      return Result.failure(IllegalArgumentException("消息列表为空"))
    }

    if (!isConfigured()) {
      return Result.failure(IllegalStateException("请先在 local.properties 配置 ARK_API_KEY"))
    }

    return withContext(Dispatchers.IO) {
      runCatching {
        val endpoint = normalizedEndpoint()
        val requestBody = buildRequestBody(endpoint = endpoint, messages = messages)
        val request = Request.Builder()
          .url(endpointUrl(endpoint))
          .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
          .header("Authorization", "Bearer ${BuildConfig.ARK_API_KEY}")
          .header("Content-Type", "application/json")
          .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
          val message = extractErrorMessage(body)
          throw IllegalStateException("ARK 请求失败 (${response.code}): $message")
        }

        val text = parseAssistantText(endpoint = endpoint, body = body)
        if (text.isBlank()) {
          throw IllegalStateException("ARK 返回为空，请稍后重试")
        }

        text
      }
    }
  }

  private fun buildRequestBody(endpoint: String, messages: List<ArkRequestMessage>): String {
    val normalized = messages
      .mapNotNull { message ->
        val role = message.role.trim().lowercase()
        val content = message.text.trim()
        if (content.isBlank()) {
          null
        } else {
          ArkRequestMessage(role = role, text = content)
        }
      }

    val prompt = BuildConfig.ARK_SYSTEM_PROMPT.trim()
    val finalMessages = buildList {
      if (prompt.isNotBlank()) {
        add(ArkRequestMessage(role = "system", text = prompt))
      }
      addAll(normalized)
    }

    return if (endpoint == RESPONSES_ENDPOINT) {
      val inputArray = JSONArray()
      finalMessages.forEach { message ->
        val content = JSONArray()
          .put(
            JSONObject()
              .put("type", "input_text")
              .put("text", message.text)
          )

        inputArray.put(
          JSONObject()
            .put("role", message.role)
            .put("content", content)
        )
      }

      JSONObject()
        .put("model", BuildConfig.ARK_MODEL)
        .put("input", inputArray)
        .put("temperature", 0.7)
        .toString()
    } else {
      val chatMessages = JSONArray()
      finalMessages.forEach { message ->
        chatMessages.put(
          JSONObject()
            .put("role", message.role)
            .put("content", message.text)
        )
      }

      JSONObject()
        .put("model", BuildConfig.ARK_MODEL)
        .put("messages", chatMessages)
        .put("temperature", 0.7)
        .toString()
    }
  }

  private fun parseAssistantText(endpoint: String, body: String): String {
    val root = JSONObject(body)

    if (endpoint == RESPONSES_ENDPOINT) {
      val outputText = root.optString("output_text")
      if (outputText.isNotBlank()) {
        return outputText
      }

      val output = root.optJSONArray("output")
      output?.let {
        for (index in 0 until it.length()) {
          val item = it.optJSONObject(index) ?: continue
          val contentArray = item.optJSONArray("content") ?: continue
          val parsed = contentArray.extractTextFromContentArray()
          if (parsed.isNotBlank()) {
            return parsed
          }
        }
      }
    }

    val choices = root.optJSONArray("choices")
    if (choices != null && choices.length() > 0) {
      val message = choices.optJSONObject(0)?.optJSONObject("message")
      if (message != null) {
        val content = message.opt("content")
        when (content) {
          is String -> {
            if (content.isNotBlank()) {
              return content
            }
          }

          is JSONArray -> {
            val parsed = content.extractTextFromContentArray()
            if (parsed.isNotBlank()) {
              return parsed
            }
          }
        }
      }
    }

    return ""
  }

  private fun JSONArray.extractTextFromContentArray(): String {
    val chunks = mutableListOf<String>()
    for (index in 0 until length()) {
      val part = opt(index)
      when (part) {
        is String -> {
          if (part.isNotBlank()) {
            chunks += part
          }
        }

        is JSONObject -> {
          val text = part.optString("text")
          if (text.isNotBlank()) {
            chunks += text
          }
        }
      }
    }
    return chunks.joinToString(separator = "\n").trim()
  }

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

  private fun normalizedEndpoint(): String {
    val value = BuildConfig.ARK_ENDPOINT.trim().lowercase()
    return if (value.contains("chat/completions")) CHAT_COMPLETIONS_ENDPOINT else RESPONSES_ENDPOINT
  }

  private fun endpointUrl(endpoint: String): String {
    return BuildConfig.ARK_BASE_URL.trimEnd('/') + "/" + endpoint
  }

  companion object {
    private const val RESPONSES_ENDPOINT = "responses"
    private const val CHAT_COMPLETIONS_ENDPOINT = "chat/completions"

    private fun defaultHttpClient(): OkHttpClient {
      return OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    }
  }
}
