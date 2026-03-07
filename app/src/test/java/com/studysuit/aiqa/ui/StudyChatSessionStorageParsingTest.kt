package com.studysuit.aiqa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyChatSessionStorageParsingTest {

  @Test
  fun parsePersistedSessionsJsonWithEmptySessionsKeepsSettingsPayload() {
    val raw = """
      {
        "version": 1,
        "activeSessionId": "",
        "settings": {
          "arkApiKey": "ark-key",
          "flowStudyServerUrl": "https://flow.example.com",
          "flowStudyDeviceId": "device-1",
          "flowStudyDeviceToken": "token-1",
          "customModelBaseUrl": "https://api.openai.com/v1",
          "customModelApiKey": "custom-key",
          "customModelName": "gpt-4o-mini"
        },
        "sessions": []
      }
    """.trimIndent()

    val parsed = parsePersistedSessionsJson(raw).getOrThrow()

    assertEquals("", parsed.activeSessionId)
    assertTrue(parsed.sessions.isEmpty())
    assertEquals("ark-key", parsed.settings.arkApiKey)
    assertEquals("https://flow.example.com", parsed.settings.flowStudyServerUrl)
    assertEquals("device-1", parsed.settings.flowStudyDeviceId)
    assertEquals("token-1", parsed.settings.flowStudyDeviceToken)
    assertEquals("https://api.openai.com/v1", parsed.settings.customModelBaseUrl)
    assertEquals("custom-key", parsed.settings.customModelApiKey)
    assertEquals("gpt-4o-mini", parsed.settings.customModelName)
  }

  @Test
  fun parsePersistedSessionsJsonRestoresCustomModelPresets() {
    val raw = """
      {
        "version": 1,
        "activeSessionId": "",
        "settings": {
          "customModelPresets": [
            {
              "id": "preset-1",
              "name": "OpenAI 日常",
              "baseUrl": "https://api.openai.com/v1",
              "apiKey": "secret-key",
              "modelName": "gpt-4o-mini"
            }
          ]
        },
        "sessions": []
      }
    """.trimIndent()

    val parsed = parsePersistedSessionsJson(raw).getOrThrow()

    assertEquals(1, parsed.settings.customModelPresets.size)
    assertEquals("OpenAI 日常", parsed.settings.customModelPresets.first().name)
    assertEquals("gpt-4o-mini", parsed.settings.customModelPresets.first().modelName)
  }

  @Test
  fun parsePersistedSessionsJsonWithLegacyModelNormalizesToDefault() {
    val raw = """
      {
        "version": 1,
        "activeSessionId": "session-1",
        "settings": {
          "arkModel": "doubao-seed-1-8-251228"
        },
        "sessions": []
      }
    """.trimIndent()

    val parsed = parsePersistedSessionsJson(raw).getOrThrow()

    assertNotEquals("doubao-seed-1-8-251228", parsed.settings.arkModel)
    assertEquals(RuntimeSettings.defaults().arkModel, parsed.settings.arkModel)
  }

  @Test
  fun parsePersistedSessionsJsonWithInvalidPayloadReturnsFailure() {
    val result = parsePersistedSessionsJson("{not-valid-json")

    assertTrue(result.isFailure)
  }

  @Test
  fun parsePersistedSessionsJsonRestoresSavedQuestionsArchive() {
    val raw = """
      {
        "version": 1,
        "activeSessionId": "session-1",
        "settings": {},
        "sessions": [
          {
            "id": "session-1",
            "title": "主界面",
            "createdAt": 1,
            "updatedAt": 2,
            "input": "",
            "activePage": "ARCHIVE",
            "profile": { "level": "高二" },
            "messages": [],
            "savedQuestions": [
              {
                "id": "saved-1",
                "sourceMessageId": "msg-2",
                "question": "这题怎么做",
                "answer": "先设未知数，再列方程。",
                "sourceTime": "10:01",
                "savedAt": 99,
                "followupCount": 2,
                "knowledgeTags": ["方程", "设元"]
              },
              {
                "id": "saved-2",
                "sourceMessageId": "msg-3",
                "question": "   ",
                "answer": "这条应该被过滤",
                "sourceTime": "10:02",
                "savedAt": 100,
                "followupCount": 0,
                "knowledgeTags": []
              }
            ],
            "knowledgePoints": {},
            "ankiCards": [],
            "histories": {}
          }
        ]
      }
    """.trimIndent()

    val parsed = parsePersistedSessionsJson(raw).getOrThrow()
    val session = parsed.sessions.first()
    val saved = session.savedQuestions.single()

    assertEquals(WorkspacePage.ARCHIVE, session.activePage)
    assertEquals("saved-1", saved.id)
    assertEquals("msg-2", saved.sourceMessageId)
    assertEquals("这题怎么做", saved.question)
    assertEquals("先设未知数，再列方程。", saved.answer)
    assertEquals("10:01", saved.sourceTime)
    assertEquals(99L, saved.savedAt)
    assertEquals(2, saved.followupCount)
    assertEquals(listOf("方程", "设元"), saved.knowledgeTags)
  }

  @Test
  fun parsePersistedSessionsJsonWithoutMainSpanBuildsLegacyQuestionScopeCard() {
    val raw = """
      {
        "version": 1,
        "activeSessionId": "session-1",
        "settings": {},
        "sessions": [
          {
            "id": "session-1",
            "title": "主界面",
            "createdAt": 1,
            "updatedAt": 2,
            "input": "",
            "activePage": "CHAT",
            "profile": { "level": "高二" },
            "messages": [
              {
                "type": "assistant",
                "id": "msg-2",
                "time": "10:01",
                "spans": [
                  {
                    "id": "span-1",
                    "content": "第一段",
                    "sourceQuestion": "这题怎么做"
                  },
                  {
                    "id": "span-2",
                    "content": "第二段",
                    "sourceQuestion": "这题怎么做"
                  }
                ]
              }
            ],
            "knowledgePoints": {},
            "ankiCards": [],
            "histories": {}
          }
        ]
      }
    """.trimIndent()

    val parsed = parsePersistedSessionsJson(raw).getOrThrow()
    val assistant = parsed.sessions.first().messages.first() as ChatMessage.Assistant

    assertNotNull(assistant.mainSpan)
    assertEquals("assistant-main-msg-2", assistant.mainSpan?.id)
    assertTrue(assistant.mainSpan?.content.orEmpty().contains("第一段"))
    assertTrue(assistant.mainSpan?.content.orEmpty().contains("第二段"))
  }
}
