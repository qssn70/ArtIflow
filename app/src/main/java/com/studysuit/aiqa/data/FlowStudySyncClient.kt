package com.studysuit.aiqa.data

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class FlowStudyPairResponse(
  val deviceToken: String,
  val userId: String
)

data class FlowStudyPushResponse(
  val insertedSessions: Int,
  val updatedSessions: Int,
  val skippedSessions: Int,
  val importBatchId: String
)

class FlowStudySyncClient(
  private val httpClient: OkHttpClient = defaultHttpClient()
) {
  suspend fun pairDevice(
    serverUrl: String,
    pairCode: String,
    deviceId: String
  ): Result<FlowStudyPairResponse> {
    val baseUrl = normalizeServerUrl(serverUrl)
    if (baseUrl.isBlank()) {
      return Result.failure(IllegalArgumentException("FlowStudy 地址为空"))
    }
    if (pairCode.isBlank()) {
      return Result.failure(IllegalArgumentException("配对码为空"))
    }
    if (deviceId.isBlank()) {
      return Result.failure(IllegalArgumentException("device_id 为空"))
    }

    return withContext(Dispatchers.IO) {
      runCatching {
        val url = "$baseUrl/api/devices/pair"
        val body = JSONObject()
          .put("pair_code", pairCode.trim())
          .put("device_id", deviceId)
          .put("device_name", defaultDeviceName())
          .toString()

        val request = Request.Builder()
          .url(url)
          .post(body.toRequestBody(JSON_MEDIA_TYPE))
          .header("Content-Type", "application/json")
          .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          val detail = parseErrorDetail(responseBody)
          throw IllegalStateException("配对失败 (${response.code}): $detail")
        }

        val json = JSONObject(responseBody)
        val token = json.optString("device_token").trim()
        val userId = json.optString("user_id").trim()
        if (token.isBlank() || userId.isBlank()) {
          throw IllegalStateException("配对返回无效")
        }
        FlowStudyPairResponse(deviceToken = token, userId = userId)
      }
    }
  }

  suspend fun pushSessions(
    serverUrl: String,
    deviceToken: String,
    deviceId: String,
    payloadJson: String
  ): Result<FlowStudyPushResponse> {
    val baseUrl = normalizeServerUrl(serverUrl)
    if (baseUrl.isBlank()) {
      return Result.failure(IllegalArgumentException("FlowStudy 地址为空"))
    }
    if (deviceToken.isBlank()) {
      return Result.failure(IllegalArgumentException("设备 token 为空，请先配对"))
    }
    if (payloadJson.isBlank()) {
      return Result.failure(IllegalArgumentException("payload 为空"))
    }

    return withContext(Dispatchers.IO) {
      runCatching {
        val url = "$baseUrl/api/artiflow/push-sessions"

        val bodyObj = JSONObject()
          .put("device_id", deviceId)
          .put("payload", JSONObject(payloadJson))
        val body = bodyObj.toString()

        val request = Request.Builder()
          .url(url)
          .post(body.toRequestBody(JSON_MEDIA_TYPE))
          .header("Content-Type", "application/json")
          .header("X-Device-Token", deviceToken)
          .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          val detail = parseErrorDetail(responseBody)
          throw IllegalStateException("上传失败 (${response.code}): $detail")
        }

        val json = JSONObject(responseBody)
        FlowStudyPushResponse(
          insertedSessions = json.optInt("inserted_sessions"),
          updatedSessions = json.optInt("updated_sessions"),
          skippedSessions = json.optInt("skipped_sessions"),
          importBatchId = json.optString("import_batch_id").trim()
        )
      }
    }
  }

  private fun normalizeServerUrl(value: String): String {
    return value.trim().trimEnd('/')
  }

  private fun defaultDeviceName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    return listOf(manufacturer, model)
      .filter { it.isNotBlank() }
      .joinToString(separator = " ")
      .ifBlank { "Android" }
      .take(64)
  }

  private fun parseErrorDetail(responseBody: String): String {
    val fallback = responseBody.trim().ifBlank { "空响应" }
    val parsed = runCatching {
      val root = JSONObject(responseBody)
      root.optString("detail")
        .ifBlank { root.optString("message") }
        .trim()
    }.getOrNull()

    return parsed?.ifBlank { fallback }?.take(280) ?: fallback.take(280)
  }

  companion object {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun defaultHttpClient(): OkHttpClient {
      return OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    }
  }
}
