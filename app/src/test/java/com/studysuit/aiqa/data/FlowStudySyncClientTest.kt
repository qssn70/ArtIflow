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

class FlowStudySyncClientTest {

  @Test
  fun pairDeviceNonJsonErrorBodyKeepsServerTextInFailureMessage(): Unit = runBlocking {
    val client = FlowStudySyncClient(
      httpClient = staticHttpClient(
        code = 502,
        body = "<html>bad gateway</html>",
        contentType = "text/html; charset=utf-8"
      )
    )

    val result: Result<FlowStudyPairResponse> = client.pairDevice(
      serverUrl = "https://flow.example.com",
      pairCode = "PAIR-001",
      deviceId = "device-a"
    )

    assertTrue(result.isFailure)
    val message = result.exceptionOrNull()?.message.orEmpty()
    assertTrue(message.contains("配对失败 (502)"))
    assertTrue(message.contains("bad gateway"))
  }

  @Test
  fun pushSessionsSuccessResponseParsesCounters(): Unit = runBlocking {
    val client = FlowStudySyncClient(
      httpClient = staticHttpClient(
        code = 200,
        body = """{"inserted_sessions":2,"updated_sessions":1,"skipped_sessions":3,"import_batch_id":"batch-42"}"""
      )
    )

    val result: Result<FlowStudyPushResponse> = client.pushSessions(
      serverUrl = "https://flow.example.com",
      deviceToken = "token-a",
      deviceId = "device-a",
      payloadJson = """{"activeSessionId":"s1","settings":{},"sessions":[]}"""
    )

    assertTrue(result.isSuccess)
    val payload = result.getOrThrow()
    assertEquals(2, payload.insertedSessions)
    assertEquals(1, payload.updatedSessions)
    assertEquals(3, payload.skippedSessions)
    assertEquals("batch-42", payload.importBatchId)
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
