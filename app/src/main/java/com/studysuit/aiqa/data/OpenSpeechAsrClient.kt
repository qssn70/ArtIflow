package com.studysuit.aiqa.data

import android.util.Base64
import com.studysuit.aiqa.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OpenSpeechRuntimeConfig(
  val apiKey: String = BuildConfig.OPENSPEECH_API_KEY,
  val resourceId: String = BuildConfig.OPENSPEECH_RESOURCE_ID,
  val submitUrl: String = BuildConfig.OPENSPEECH_SUBMIT_URL,
  val queryUrl: String = BuildConfig.OPENSPEECH_QUERY_URL,
  val uid: String = BuildConfig.OPENSPEECH_UID
)

class OpenSpeechAsrClient(
  private val httpClient: OkHttpClient = defaultHttpClient()
) {
  fun isConfigured(config: OpenSpeechRuntimeConfig = OpenSpeechRuntimeConfig()): Boolean {
    return config.apiKey.isNotBlank() && config.resourceId.isNotBlank()
  }

  suspend fun transcribeByAudioData(
    audioBytes: ByteArray,
    config: OpenSpeechRuntimeConfig = OpenSpeechRuntimeConfig()
  ): Result<String> {
    if (!isConfigured(config)) {
      return Result.failure(IllegalStateException("请先在 local.properties 配置 OpenSpeech 参数"))
    }

    if (audioBytes.isEmpty()) {
      return Result.failure(IllegalArgumentException("音频数据为空"))
    }

    return withContext(Dispatchers.IO) {
      runCatching {
        val requestId = UUID.randomUUID().toString()
        val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        submitTaskByAudioData(requestId = requestId, audioBase64 = audioBase64, uid = defaultUid(config), config = config)
        awaitTranscript(requestId, config)
      }
    }
  }

  private suspend fun awaitTranscript(requestId: String, config: OpenSpeechRuntimeConfig): String {
    val pollAttempts = 24
    repeat(pollAttempts) { attempt ->
      val queryResult = queryTask(requestId, config)
      when (queryResult.statusCode) {
        STATUS_SUCCESS -> {
          val transcript = queryResult.transcript
          if (transcript.isBlank()) {
            throw IllegalStateException("语音识别结果为空")
          }
          return transcript
        }

        STATUS_PROCESSING,
        STATUS_QUEUEING -> {
          if (attempt == pollAttempts - 1) {
            throw IllegalStateException("语音识别超时，请稍后重试")
          }
          delay(1500)
        }

        else -> {
          val err = queryResult.message.ifBlank { "状态码 ${queryResult.statusCode}" }
          throw IllegalStateException("语音识别失败: $err")
        }
      }
    }

    throw IllegalStateException("语音识别失败：未知状态")
  }

  private fun submitTaskByAudioData(
    requestId: String,
    audioBase64: String,
    uid: String,
    config: OpenSpeechRuntimeConfig
  ) {
    val bodyJson = JSONObject()
      .put("user", JSONObject().put("uid", uid))
      .put(
        "audio",
        JSONObject()
          .put("data", audioBase64)
          .put("format", "wav")
          .put("codec", "raw")
          .put("rate", 16000)
          .put("bits", 16)
          .put("channel", 1)
      )
      .put(
        "request",
        JSONObject()
          .put("model_name", "bigmodel")
          .put("enable_itn", true)
          .put("enable_punc", false)
          .put("enable_ddc", false)
          .put("enable_speaker_info", false)
          .put("enable_channel_split", false)
          .put("show_utterances", false)
          .put("vad_segment", false)
          .put("sensitive_words_filter", "")
      )

    val request = Request.Builder()
      .url(config.submitUrl)
      .header("Content-Type", "application/json")
      .header("x-api-key", config.apiKey)
      .header("X-Api-Resource-Id", config.resourceId)
      .header("X-Api-Request-Id", requestId)
      .header("X-Api-Sequence", "-1")
      .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()

    httpClient.newCall(request).execute().use { response ->
      val statusCode = response.header("X-Api-Status-Code").orEmpty()
      val message = response.header("X-Api-Message").orEmpty()

      if (!response.isSuccessful) {
        throw IllegalStateException("提交语音识别任务失败(${response.code}): ${message.ifBlank { "HTTP 错误" }}")
      }

      if (statusCode.isNotBlank() && statusCode != STATUS_SUCCESS && statusCode != STATUS_PROCESSING && statusCode != STATUS_QUEUEING) {
        throw IllegalStateException("提交语音识别任务失败: ${message.ifBlank { "状态码 $statusCode" }}")
      }
    }
  }

  private fun defaultUid(config: OpenSpeechRuntimeConfig): String {
    return config.uid.trim().ifEmpty { "study-suit-user" }
  }

  private fun queryTask(requestId: String, config: OpenSpeechRuntimeConfig): QueryResult {
    val request = Request.Builder()
      .url(config.queryUrl)
      .header("Content-Type", "application/json")
      .header("x-api-key", config.apiKey)
      .header("X-Api-Resource-Id", config.resourceId)
      .header("X-Api-Request-Id", requestId)
      .post("{}".toRequestBody(JSON_MEDIA_TYPE))
      .build()

    httpClient.newCall(request).execute().use { response ->
      val statusCode = response.header("X-Api-Status-Code").orEmpty()
      val message = response.header("X-Api-Message").orEmpty()
      val body = response.body?.string().orEmpty()

      if (!response.isSuccessful) {
        return QueryResult(
          statusCode = if (statusCode.isBlank()) response.code.toString() else statusCode,
          message = message.ifBlank { "HTTP 错误" },
          transcript = ""
        )
      }

      val transcript = parseTranscript(body)
      return QueryResult(
        statusCode = if (statusCode.isBlank()) STATUS_SUCCESS else statusCode,
        message = message,
        transcript = transcript
      )
    }
  }

  private fun parseTranscript(body: String): String {
    if (body.isBlank()) {
      return ""
    }

    return runCatching {
      val root = JSONObject(body)
      val result = root.optJSONObject("result")
      if (result != null) {
        val text = result.optString("text")
        if (text.isNotBlank()) {
          return@runCatching text.trim()
        }

        val utterances = result.optJSONArray("utterances")
        if (utterances != null && utterances.length() > 0) {
          buildString {
            for (index in 0 until utterances.length()) {
              val item = utterances.optJSONObject(index) ?: continue
              val segment = item.optString("text").trim()
              if (segment.isNotBlank()) {
                if (isNotEmpty()) {
                  append('\n')
                }
                append(segment)
              }
            }
          }
        } else {
          ""
        }
      } else {
        ""
      }
    }.getOrElse {
      ""
    }
  }

  companion object {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val STATUS_SUCCESS = "20000000"
    private const val STATUS_PROCESSING = "20000001"
    private const val STATUS_QUEUEING = "20000002"

    private fun defaultHttpClient(): OkHttpClient {
      return OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    }
  }
}

private data class QueryResult(
  val statusCode: String,
  val message: String,
  val transcript: String
)
