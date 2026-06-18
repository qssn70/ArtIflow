package com.studysuit.aiqa.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArkApiClientTest {

  @Test
  fun generateReplyWithoutApiKeyPointsUserToInAppSettings(): Unit = runBlocking {
    val client = ArkApiClient(
      httpClient = staticHttpClient(
        code = 200,
        body = """{"output_text":"unused"}"""
      )
    )

    val result = client.generateReply(
      messages = listOf(ArkRequestMessage(role = "user", text = "讲解这题")),
      config = runtimeConfig(endpoint = "responses").copy(apiKey = "")
    )

    assertTrue(result.isFailure)
    assertEquals("请先在设置中配置 ARK_API_KEY", result.exceptionOrNull()?.message)
  }

  @Test
  fun generateReplyResponsesEndpointParsesOutputText(): Unit = runBlocking {
    val client = ArkApiClient(
      httpClient = staticHttpClient(
        code = 200,
        body = """{"output_text":"先给结论，再给要点。"}"""
      )
    )

    val result: Result<String> = client.generateReply(
      messages = listOf(ArkRequestMessage(role = "user", text = "帮我讲一下函数最值")),
      config = runtimeConfig(endpoint = "responses")
    )

    assertTrue(result.isSuccess)
    assertEquals("先给结论，再给要点。", result.getOrThrow())
  }

  @Test
  fun generateReplyStreamResponsesEndpointAggregatesDeltaChunks(): Unit = runBlocking {
    val streamPayload = """
      data: {"type":"response.output_text.delta","delta":"先给结论"}
      data: {"type":"response.output_text.delta","delta":"，再给步骤"}
      data: [DONE]
    """.trimIndent()
    val observed = StringBuilder()

    val client = ArkApiClient(
      httpClient = staticHttpClient(
        code = 200,
        body = streamPayload,
        contentType = "text/event-stream"
      )
    )

    val result: Result<String> = client.generateReplyStream(
      messages = listOf(ArkRequestMessage(role = "user", text = "讲解这题")),
      config = runtimeConfig(endpoint = "responses"),
      onDelta = { delta -> observed.append(delta) }
    )

    assertTrue(result.isSuccess)
    assertEquals("先给结论，再给步骤", observed.toString())
    assertEquals("先给结论，再给步骤", result.getOrThrow())
  }

  private fun runtimeConfig(endpoint: String): ArkRuntimeConfig {
    return ArkRuntimeConfig(
      apiKey = "test-key",
      model = "test-model",
      baseUrl = "https://ark.example.com/api/v3",
      endpoint = endpoint,
      systemPrompt = ""
    )
  }

  private fun staticHttpClient(
    code: Int,
    body: String,
    contentType: String = "application/json; charset=utf-8"
  ): OkHttpClient {
    val mediaType = contentType.toMediaType()
    return OkHttpClient.Builder()
      .addInterceptor { chain ->
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(code)
          .message(if (code in 200..299) "OK" else "ERR")
          .body(body.toResponseBody(mediaType))
          .build()
      }
      .build()
  }
}
