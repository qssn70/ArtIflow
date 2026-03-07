package com.studysuit.aiqa.data

import android.util.Base64
import com.studysuit.aiqa.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ArkRequestMessage(
  val role: String,
  val text: String
)

data class ArkRuntimeConfig(
  val apiKey: String = BuildConfig.ARK_API_KEY,
  val model: String = BuildConfig.ARK_MODEL,
  val baseUrl: String = BuildConfig.ARK_BASE_URL,
  val endpoint: String = BuildConfig.ARK_ENDPOINT,
  val systemPrompt: String = BuildConfig.ARK_SYSTEM_PROMPT
)

class ArkApiClient(
  private val httpClient: OkHttpClient = defaultHttpClient()
) {
  fun isConfigured(config: ArkRuntimeConfig = ArkRuntimeConfig()): Boolean {
    return config.apiKey.isNotBlank()
  }

  suspend fun generateReply(
    messages: List<ArkRequestMessage>,
    config: ArkRuntimeConfig = ArkRuntimeConfig()
  ): Result<String> {
    if (messages.isEmpty()) {
      return Result.failure(IllegalArgumentException("消息列表为空"))
    }

    if (!isConfigured(config)) {
      return Result.failure(IllegalStateException("请先在 local.properties 配置 ARK_API_KEY"))
    }

    return withContext(Dispatchers.IO) {
      runCatching {
        val endpoint = normalizedEndpoint(config)
        val requestBody = buildRequestBody(endpoint = endpoint, messages = messages, config = config)
        val request = Request.Builder()
          .url(endpointUrl(endpoint, config))
          .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
          .header("Authorization", "Bearer ${config.apiKey}")
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

  suspend fun generateReplyStream(
    messages: List<ArkRequestMessage>,
    config: ArkRuntimeConfig = ArkRuntimeConfig(),
    onDelta: (String) -> Unit,
    onReasoningDelta: ((String) -> Unit)? = null
  ): Result<String> {
    if (messages.isEmpty()) {
      return Result.failure(IllegalArgumentException("消息列表为空"))
    }

    if (!isConfigured(config)) {
      return Result.failure(IllegalStateException("请先在 local.properties 配置 ARK_API_KEY"))
    }

    return withContext(Dispatchers.IO) {
      runCatching {
        val endpoint = normalizedEndpoint(config)
        val requestBody = buildRequestBody(
          endpoint = endpoint,
          messages = messages,
          config = config,
          stream = true
        )
        val request = Request.Builder()
          .url(endpointUrl(endpoint, config))
          .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
          .header("Authorization", "Bearer ${config.apiKey}")
          .header("Content-Type", "application/json")
          .build()

        httpClient.newCall(request).execute().use { response ->
          val body = response.body
          if (!response.isSuccessful) {
            val message = extractErrorMessage(body?.string().orEmpty())
            throw IllegalStateException("ARK 请求失败 (${response.code}): $message")
          }

          if (body == null) {
            throw IllegalStateException("ARK 流式返回为空，请稍后重试")
          }

          val text = parseAssistantTextStream(
            endpoint = endpoint,
            body = body,
            onDelta = onDelta,
            onReasoningDelta = onReasoningDelta
          )
          if (text.isBlank()) {
            throw IllegalStateException("ARK 返回为空，请稍后重试")
          }

          text
        }
      }
    }
  }

  suspend fun generateReplyWithImage(
    prompt: String,
    imageBytes: ByteArray,
    mimeType: String = "image/jpeg",
    config: ArkRuntimeConfig = ArkRuntimeConfig()
  ): Result<String> {
    val normalizedPrompt = prompt.trim()
    if (normalizedPrompt.isBlank()) {
      return Result.failure(IllegalArgumentException("图片提问提示词为空"))
    }

    if (imageBytes.isEmpty()) {
      return Result.failure(IllegalArgumentException("图片数据为空"))
    }

    if (!isConfigured(config)) {
      return Result.failure(IllegalStateException("请先在 local.properties 配置 ARK_API_KEY"))
    }

    return withContext(Dispatchers.IO) {
      runCatching {
        val endpoint = normalizedEndpoint(config)
        val requestBody = buildImageRequestBody(
          endpoint = endpoint,
          prompt = normalizedPrompt,
          imageBytes = imageBytes,
          mimeType = mimeType,
          config = config,
          stream = false
        )

        val request = Request.Builder()
          .url(endpointUrl(endpoint, config))
          .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
          .header("Authorization", "Bearer ${config.apiKey}")
          .header("Content-Type", "application/json")
          .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
          val message = extractErrorMessage(body)
          throw IllegalStateException("ARK 图片请求失败 (${response.code}): $message")
        }

        val text = parseAssistantText(endpoint = endpoint, body = body)
        if (text.isBlank()) {
          throw IllegalStateException("ARK 图片识别返回为空，请稍后重试")
        }

        text
      }
    }
  }

  suspend fun generateReplyWithImageStream(
    prompt: String,
    imageBytes: ByteArray,
    mimeType: String = "image/jpeg",
    config: ArkRuntimeConfig = ArkRuntimeConfig(),
    onDelta: (String) -> Unit,
    onReasoningDelta: ((String) -> Unit)? = null
  ): Result<String> {
    val normalizedPrompt = prompt.trim()
    if (normalizedPrompt.isBlank()) {
      return Result.failure(IllegalArgumentException("图片提问提示词为空"))
    }

    if (imageBytes.isEmpty()) {
      return Result.failure(IllegalArgumentException("图片数据为空"))
    }

    if (!isConfigured(config)) {
      return Result.failure(IllegalStateException("请先在 local.properties 配置 ARK_API_KEY"))
    }

    return withContext(Dispatchers.IO) {
      runCatching {
        val endpoint = normalizedEndpoint(config)
        val requestBody = buildImageRequestBody(
          endpoint = endpoint,
          prompt = normalizedPrompt,
          imageBytes = imageBytes,
          mimeType = mimeType,
          config = config,
          stream = true
        )

        val request = Request.Builder()
          .url(endpointUrl(endpoint, config))
          .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
          .header("Authorization", "Bearer ${config.apiKey}")
          .header("Content-Type", "application/json")
          .build()

        httpClient.newCall(request).execute().use { response ->
          val body = response.body
          if (!response.isSuccessful) {
            val message = extractErrorMessage(body?.string().orEmpty())
            throw IllegalStateException("ARK 图片请求失败 (${response.code}): $message")
          }

          if (body == null) {
            throw IllegalStateException("ARK 图片识别返回为空，请稍后重试")
          }

          val text = parseAssistantTextStream(
            endpoint = endpoint,
            body = body,
            onDelta = onDelta,
            onReasoningDelta = onReasoningDelta
          )
          if (text.isBlank()) {
            throw IllegalStateException("ARK 图片识别返回为空，请稍后重试")
          }

          text
        }
      }
    }
  }

  private fun buildRequestBody(
    endpoint: String,
    messages: List<ArkRequestMessage>,
    config: ArkRuntimeConfig,
    stream: Boolean = false
  ): String {
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

    val prompt = config.systemPrompt.trim()
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
        .put("model", config.model)
        .put("input", inputArray)
        .put("temperature", 0.7)
        .put("stream", stream)
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
        .put("model", config.model)
        .put("messages", chatMessages)
        .put("temperature", 0.7)
        .put("stream", stream)
        .toString()
    }
  }

  private fun buildImageRequestBody(
    endpoint: String,
    prompt: String,
    imageBytes: ByteArray,
    mimeType: String,
    config: ArkRuntimeConfig,
    stream: Boolean = false
  ): String {
    val dataUrl = "data:$mimeType;base64,${Base64.encodeToString(imageBytes, Base64.NO_WRAP)}"
    val systemPrompt = config.systemPrompt.trim()

    return if (endpoint == RESPONSES_ENDPOINT) {
      val input = JSONArray()

      if (systemPrompt.isNotBlank()) {
        input.put(
          JSONObject()
            .put("role", "system")
            .put(
              "content",
              JSONArray().put(
                JSONObject()
                  .put("type", "input_text")
                  .put("text", systemPrompt)
              )
            )
        )
      }

      val userContent = JSONArray()
        .put(
          JSONObject()
            .put("type", "input_image")
            .put("image_url", dataUrl)
        )
        .put(
          JSONObject()
            .put("type", "input_text")
            .put("text", prompt)
        )

      input.put(
        JSONObject()
          .put("role", "user")
          .put("content", userContent)
      )

      JSONObject()
        .put("model", config.model)
        .put("input", input)
        .put("temperature", 0.7)
        .put("stream", stream)
        .toString()
    } else {
      val messages = JSONArray()

      if (systemPrompt.isNotBlank()) {
        messages.put(
          JSONObject()
            .put("role", "system")
            .put("content", systemPrompt)
        )
      }

      val content = JSONArray()
        .put(
          JSONObject()
            .put("type", "text")
            .put("text", prompt)
        )
        .put(
          JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", dataUrl))
        )

      messages.put(
        JSONObject()
          .put("role", "user")
          .put("content", content)
      )

      JSONObject()
        .put("model", config.model)
        .put("messages", messages)
        .put("temperature", 0.7)
        .put("stream", stream)
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

  private fun parseAssistantTextStream(
    endpoint: String,
    body: ResponseBody,
    onDelta: (String) -> Unit,
    onReasoningDelta: ((String) -> Unit)?
  ): String {
    val aggregate = StringBuilder()
    var fallbackText = ""
    val reasoningAggregate = StringBuilder()
    var reasoningFallback = ""

    body.charStream().buffered().useLines { lines ->
      lines.forEach { lineRaw ->
        val line = lineRaw.trimEnd()
        val payload = when {
          line.startsWith("data:") -> line.removePrefix("data:").trimStart()
          line.startsWith("event:") || line.startsWith(":") || line.isBlank() -> ""
          else -> line
        }

        if (payload.isBlank() || payload == "[DONE]") {
          return@forEach
        }

        val parsed = parseStreamPayload(endpoint = endpoint, payload = payload)
        if (parsed.delta.isNotEmpty()) {
          aggregate.append(parsed.delta)
          onDelta(parsed.delta)
        }

        if (parsed.reasoningDelta.isNotEmpty()) {
          reasoningAggregate.append(parsed.reasoningDelta)
          onReasoningDelta?.invoke(parsed.reasoningDelta)
        }

        if (parsed.fallbackText.isNotBlank()) {
          fallbackText = parsed.fallbackText
        }

        if (parsed.reasoningFallback.isNotBlank()) {
          reasoningFallback = parsed.reasoningFallback
        }
      }
    }

    if (reasoningAggregate.isEmpty() && reasoningFallback.isNotBlank()) {
      onReasoningDelta?.invoke(reasoningFallback)
    }

    return aggregate.toString().ifBlank { fallbackText }
  }

  private data class StreamPayloadParseResult(
    val delta: String,
    val fallbackText: String,
    val reasoningDelta: String,
    val reasoningFallback: String
  )

  private fun parseStreamPayload(endpoint: String, payload: String): StreamPayloadParseResult {
    val root = runCatching { JSONObject(payload) }.getOrNull()
      ?: return StreamPayloadParseResult(
        delta = "",
        fallbackText = "",
        reasoningDelta = "",
        reasoningFallback = ""
      )

    return if (endpoint == RESPONSES_ENDPOINT) {
      parseResponsesStreamPayload(root)
    } else {
      parseChatCompletionsStreamPayload(root)
    }
  }

  private fun parseResponsesStreamPayload(root: JSONObject): StreamPayloadParseResult {
    val type = root.optString("type")

    val delta = when (type) {
      "response.output_text.delta" -> root.optString("delta")
      else -> ""
    }

    val reasoningDelta = when (type) {
      "response.reasoning_summary_text.delta" -> root.optString("delta")
      else -> ""
    }

    val responseObject = root.optJSONObject("response")
    val fallback = when {
      type == "response.output_text.done" -> root.optString("text")
      type == "response.completed" -> root.optString("output_text")
      responseObject?.optString("output_text").isNullOrBlank().not() -> responseObject?.optString("output_text").orEmpty()
      else -> ""
    }

    val reasoningFallback = when (type) {
      "response.reasoning_summary_text.done" -> root.optString("text")
      "response.reasoning_summary_part.done" -> root.optJSONObject("part")?.optString("text").orEmpty()
      "response.completed" -> extractReasoningSummary(root.optJSONObject("response"))
      else -> extractReasoningSummary(responseObject)
    }

    return StreamPayloadParseResult(
      delta = delta,
      fallbackText = fallback,
      reasoningDelta = reasoningDelta,
      reasoningFallback = reasoningFallback
    )
  }

  private fun parseChatCompletionsStreamPayload(root: JSONObject): StreamPayloadParseResult {
    val choices = root.optJSONArray("choices")
    if (choices == null || choices.length() == 0) {
      return StreamPayloadParseResult(
        delta = "",
        fallbackText = "",
        reasoningDelta = "",
        reasoningFallback = ""
      )
    }

    val firstChoice = choices.optJSONObject(0)
      ?: return StreamPayloadParseResult(
        delta = "",
        fallbackText = "",
        reasoningDelta = "",
        reasoningFallback = ""
      )
    val deltaObj = firstChoice.optJSONObject("delta")
    val deltaValue = deltaObj?.opt("content")

    val delta = when (deltaValue) {
      is String -> deltaValue
      is JSONArray -> deltaValue.extractTextFromContentArray()
      else -> ""
    }

    val fallback = firstChoice.optString("text")
    return StreamPayloadParseResult(
      delta = delta,
      fallbackText = fallback,
      reasoningDelta = "",
      reasoningFallback = ""
    )
  }

  private fun extractReasoningSummary(response: JSONObject?): String {
    val output = response?.optJSONArray("output") ?: return ""
    for (index in 0 until output.length()) {
      val item = output.optJSONObject(index) ?: continue
      if (item.optString("type") != "reasoning") {
        continue
      }

      val summary = item.optJSONArray("summary") ?: continue
      val text = summary.extractTextFromContentArray()
      if (text.isNotBlank()) {
        return text
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

  private fun normalizedEndpoint(config: ArkRuntimeConfig): String {
    val value = config.endpoint.trim().lowercase()
    return if (value.contains("chat/completions")) CHAT_COMPLETIONS_ENDPOINT else RESPONSES_ENDPOINT
  }

  private fun endpointUrl(endpoint: String, config: ArkRuntimeConfig): String {
    return config.baseUrl.trimEnd('/') + "/" + endpoint
  }

  companion object {
    private const val RESPONSES_ENDPOINT = "responses"
    private const val CHAT_COMPLETIONS_ENDPOINT = "chat/completions"

    private fun defaultHttpClient(): OkHttpClient {
      return OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    }
  }
}
