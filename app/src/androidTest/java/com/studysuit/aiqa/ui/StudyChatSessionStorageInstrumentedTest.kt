package com.studysuit.aiqa.ui

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class StudyChatSessionStorageInstrumentedTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val storageFile = File(context.filesDir, "study_suit_sessions_v1.json")
  private val backupFile = File(context.filesDir, "study_suit_sessions_v1.json.instrumented-backup")

  @Before
  fun backUpExistingStorage() {
    backupFile.delete()
    if (storageFile.exists()) {
      storageFile.copyTo(backupFile, overwrite = true)
    }
    storageFile.delete()
  }

  @After
  fun restoreExistingStorage() {
    storageFile.delete()
    if (backupFile.exists()) {
      backupFile.copyTo(storageFile, overwrite = true)
      backupFile.delete()
    }
  }

  @Test
  fun loadMigratesLegacyBase64ImagesToV2ImageRefs() {
    val imageBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
    val encodedImage = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
    storageFile.writeText(
      """
        {
          "version": 1,
          "activeSessionId": "session-1",
          "settings": {},
          "sessions": [
            {
              "id": "session-1",
              "title": "拍照题",
              "createdAt": 1,
              "updatedAt": 2,
              "input": "",
              "activePage": "CHAT",
              "profile": { "level": "高二" },
              "messages": [
                {
                  "type": "user",
                  "id": "msg-1",
                  "time": "10:00",
                  "text": "请看图解题",
                  "image": "$encodedImage",
                  "images": ["$encodedImage"]
                }
              ],
              "savedQuestions": [],
              "knowledgePoints": {},
              "ankiCards": [],
              "histories": {}
            }
          ]
        }
      """.trimIndent(),
      Charsets.UTF_8
    )

    val loaded = SessionStorage(context).load()
    val userMessage = loaded?.sessions?.single()?.messages?.single() as ChatMessage.User
    val rewritten = storageFile.readText(Charsets.UTF_8)
    val rewrittenRoot = JSONObject(rewritten)
    val rewrittenUser = rewrittenRoot
      .getJSONArray("sessions")
      .getJSONObject(0)
      .getJSONArray("messages")
      .getJSONObject(0)

    assertArrayEquals(imageBytes, userMessage.imagePreviewBytes)
    assertEquals(2, rewrittenRoot.getInt("version"))
    assertTrue(rewrittenUser.getJSONArray("imageRefs").length() == 1)
    assertFalse(rewrittenUser.has("image"))
    assertFalse(rewrittenUser.has("images"))
    assertFalse(rewritten.contains(encodedImage))
  }
}
