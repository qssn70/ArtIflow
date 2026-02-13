package com.studysuit.aiqa.ui

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class StoredSession(
  val id: String,
  val title: String,
  val createdAt: Long,
  val updatedAt: Long,
  val messages: List<ChatMessage>,
  val histories: Map<String, List<SpanDetail>>,
  val profile: ProfileState,
  val input: String,
  val activePage: WorkspacePage,
  val knowledgePoints: Map<String, Int>,
  val ankiCards: List<AnkiCard>
)

internal data class PersistedSessions(
  val activeSessionId: String,
  val settings: RuntimeSettings,
  val sessions: List<StoredSession>
)

internal class SessionStorage(private val context: Context) {
  private val storageFile = File(context.filesDir, "study_suit_sessions_v1.json")

  fun save(payload: PersistedSessions) {
    runCatching {
      val root = JSONObject()
        .put("version", 1)
        .put("activeSessionId", payload.activeSessionId)
        .put("settings", payload.settings.toJson())
        .put("sessions", JSONArray().apply {
          payload.sessions.forEach { session ->
            put(session.toJson())
          }
        })

      storageFile.parentFile?.mkdirs()
      storageFile.writeText(root.toString(), Charsets.UTF_8)
    }
  }

  fun load(): PersistedSessions? {
    if (!storageFile.exists()) {
      return null
    }

    return runCatching {
      val root = JSONObject(storageFile.readText(Charsets.UTF_8))
      val activeSessionId = root.optString("activeSessionId").trim()
      val settings = root.optJSONObject("settings")?.toRuntimeSettings() ?: RuntimeSettings.defaults()
      val sessions = root.optJSONArray("sessions")
        ?.let { array ->
          buildList {
            for (index in 0 until array.length()) {
              val item = array.optJSONObject(index) ?: continue
              item.toStoredSession()?.let { session -> add(session) }
            }
          }
        }
        .orEmpty()

      if (sessions.isEmpty()) {
        null
      } else {
        PersistedSessions(
          activeSessionId = activeSessionId,
          settings = settings,
          sessions = sessions
        )
      }
    }.getOrNull()
  }
}

private fun RuntimeSettings.toJson(): JSONObject {
  return JSONObject()
    .put("arkApiKey", arkApiKey)
    .put("arkModel", arkModel)
    .put("arkBaseUrl", arkBaseUrl)
    .put("arkEndpoint", arkEndpoint)
    .put("arkSystemPrompt", arkSystemPrompt)
    .put("imagePrompt", imagePrompt)
    .put("openSpeechApiKey", openSpeechApiKey)
    .put("openSpeechResourceId", openSpeechResourceId)
    .put("openSpeechSubmitUrl", openSpeechSubmitUrl)
    .put("openSpeechQueryUrl", openSpeechQueryUrl)
    .put("openSpeechUid", openSpeechUid)
}

private fun JSONObject.toRuntimeSettings(): RuntimeSettings {
  val defaults = RuntimeSettings.defaults()
  return RuntimeSettings(
    arkApiKey = optString("arkApiKey", defaults.arkApiKey),
    arkModel = optString("arkModel", defaults.arkModel),
    arkBaseUrl = optString("arkBaseUrl", defaults.arkBaseUrl),
    arkEndpoint = optString("arkEndpoint", defaults.arkEndpoint),
    arkSystemPrompt = optString("arkSystemPrompt", defaults.arkSystemPrompt),
    imagePrompt = optString("imagePrompt", defaults.imagePrompt),
    openSpeechApiKey = optString("openSpeechApiKey", defaults.openSpeechApiKey),
    openSpeechResourceId = optString("openSpeechResourceId", defaults.openSpeechResourceId),
    openSpeechSubmitUrl = optString("openSpeechSubmitUrl", defaults.openSpeechSubmitUrl),
    openSpeechQueryUrl = optString("openSpeechQueryUrl", defaults.openSpeechQueryUrl),
    openSpeechUid = optString("openSpeechUid", defaults.openSpeechUid)
  )
}

private fun StoredSession.toJson(): JSONObject {
  return JSONObject()
    .put("id", id)
    .put("title", title)
    .put("createdAt", createdAt)
    .put("updatedAt", updatedAt)
    .put("input", input)
    .put("activePage", activePage.name)
    .put("profile", profile.toJson())
    .put("messages", JSONArray().apply {
      messages.forEach { message -> put(message.toJson()) }
    })
    .put("knowledgePoints", knowledgePoints.toKnowledgePointsJson())
    .put("ankiCards", ankiCards.toJson())
    .put("histories", histories.toJson())
}

private fun JSONObject.toStoredSession(): StoredSession? {
  val id = optString("id").trim()
  if (id.isBlank()) {
    return null
  }

  val title = optString("title").ifBlank { "历史会话" }
  val createdAt = optLong("createdAt", System.currentTimeMillis())
  val updatedAt = optLong("updatedAt", createdAt)
  val input = optString("input")

  val profile = optJSONObject("profile")?.toProfileState() ?: ProfileState(level = "高二 · 进阶冲刺")
  val messages = optJSONArray("messages")?.toChatMessages().orEmpty()
  val histories = optJSONObject("histories")?.toHistories().orEmpty()
  val activePage = optString("activePage").toWorkspacePageOrDefault()
  val knowledgePoints = optJSONObject("knowledgePoints")?.toKnowledgePoints().orEmpty()
  val ankiCards = optJSONArray("ankiCards")?.toAnkiCards().orEmpty()

  return StoredSession(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messages = messages,
    histories = histories,
    profile = profile,
    input = input,
    activePage = activePage,
    knowledgePoints = knowledgePoints,
    ankiCards = ankiCards
  )
}

private fun ProfileState.toJson(): JSONObject {
  return JSONObject()
    .put("level", level)
    .put("followups", followups)
    .put("voiceFollowups", voiceFollowups)
    .put(
      "topicHits",
      JSONObject().apply {
        topicHits.forEach { (topic, count) ->
          put(topic, count)
        }
      }
    )
}

private fun JSONObject.toProfileState(): ProfileState {
  val topicHitsObj = optJSONObject("topicHits")
  val topicHits = mutableMapOf<String, Int>()
  topicHitsObj?.keys()?.let { iterator ->
    while (iterator.hasNext()) {
      val key = iterator.next()
      topicHits[key] = topicHitsObj.optInt(key)
    }
  }

  return ProfileState(
    level = optString("level", "高二 · 进阶冲刺"),
    topicHits = topicHits,
    followups = optInt("followups", 0),
    voiceFollowups = optInt("voiceFollowups", 0)
  )
}

private fun ChatMessage.toJson(): JSONObject {
  return when (this) {
    is ChatMessage.User -> JSONObject()
      .put("type", "user")
      .put("id", id)
      .put("time", time)
      .put("text", text)
      .put("image", imagePreviewBytes?.let { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) } ?: JSONObject.NULL)

    is ChatMessage.Assistant -> JSONObject()
      .put("type", "assistant")
      .put("id", id)
      .put("time", time)
      .put("spans", JSONArray().apply {
        spans.forEach { span ->
          put(
            JSONObject()
              .put("id", span.id)
              .put("content", span.content)
              .put("sourceQuestion", span.sourceQuestion)
          )
        }
      })
  }
}

private fun JSONArray.toChatMessages(): List<ChatMessage> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      when (item.optString("type")) {
        "user" -> {
          val encodedImage = item.optString("image")
          val imageBytes = if (encodedImage.isBlank() || encodedImage == "null") {
            null
          } else {
            runCatching { Base64.decode(encodedImage, Base64.DEFAULT) }.getOrNull()
          }
          add(
            ChatMessage.User(
              id = item.optString("id"),
              time = item.optString("time"),
              text = item.optString("text"),
              imagePreviewBytes = imageBytes
            )
          )
        }

        "assistant" -> {
          val spans = item.optJSONArray("spans")?.let { array ->
            buildList {
              for (spanIndex in 0 until array.length()) {
                val spanObj = array.optJSONObject(spanIndex) ?: continue
                add(
                  SpanData(
                    id = spanObj.optString("id"),
                    content = spanObj.optString("content"),
                    sourceQuestion = spanObj.optString("sourceQuestion")
                  )
                )
              }
            }
          }.orEmpty()

          add(
            ChatMessage.Assistant(
              id = item.optString("id"),
              time = item.optString("time"),
              spans = spans
            )
          )
        }
      }
    }
  }
}

private fun Map<String, List<SpanDetail>>.toJson(): JSONObject {
  return JSONObject().apply {
    forEach { (spanId, details) ->
      put(
        spanId,
        JSONArray().apply {
          details.forEach { detail ->
            put(
              JSONObject()
                .put("id", detail.id)
                .put("mode", detail.mode)
                .put("time", detail.time)
                .put("question", detail.question ?: JSONObject.NULL)
                .put("answer", detail.answer)
            )
          }
        }
      )
    }
  }
}

private fun JSONObject.toHistories(): Map<String, List<SpanDetail>> {
  val histories = mutableMapOf<String, List<SpanDetail>>()
  val iterator = keys()
  while (iterator.hasNext()) {
    val spanId = iterator.next()
    val detailsArray = optJSONArray(spanId) ?: continue
    val details = buildList {
      for (index in 0 until detailsArray.length()) {
        val detailObj = detailsArray.optJSONObject(index) ?: continue
        add(
          SpanDetail(
            id = detailObj.optString("id"),
            mode = detailObj.optString("mode"),
            time = detailObj.optString("time"),
            question = detailObj.optString("question").takeIf { it.isNotBlank() && it != "null" },
            answer = detailObj.optString("answer")
          )
        )
      }
    }
    histories[spanId] = details
  }
  return histories
}

private fun Map<String, Int>.toKnowledgePointsJson(): JSONObject {
  return JSONObject().apply {
    forEach { (point, count) ->
      put(point, count)
    }
  }
}

private fun JSONObject.toKnowledgePoints(): Map<String, Int> {
  val points = linkedMapOf<String, Int>()
  val iterator = keys()
  while (iterator.hasNext()) {
    val key = iterator.next()
    points[key] = optInt(key, 0)
  }
  return points
}

private fun List<AnkiCard>.toJson(): JSONArray {
  return JSONArray().apply {
    forEach { card -> put(card.toJson()) }
  }
}

private fun AnkiCard.toJson(): JSONObject {
  return JSONObject()
    .put("id", id)
    .put("front", front)
    .put("back", back)
    .put("tags", JSONArray(tags))
    .put("source", source)
    .put("createdAt", createdAt)
}

private fun JSONArray.toAnkiCards(): List<AnkiCard> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      val id = item.optString("id").ifBlank { "card-${System.currentTimeMillis()}-$index" }
      val tags = item.optJSONArray("tags")?.let { array ->
        buildList {
          for (tagIndex in 0 until array.length()) {
            val tag = array.optString(tagIndex).trim()
            if (tag.isNotBlank()) {
              add(tag)
            }
          }
        }
      }.orEmpty()

      add(
        AnkiCard(
          id = id,
          front = item.optString("front"),
          back = item.optString("back"),
          tags = tags,
          source = item.optString("source"),
          createdAt = item.optLong("createdAt", System.currentTimeMillis())
        )
      )
    }
  }
}

private fun String.toWorkspacePageOrDefault(): WorkspacePage {
  return runCatching { WorkspacePage.valueOf(this) }.getOrDefault(WorkspacePage.CHAT)
}
