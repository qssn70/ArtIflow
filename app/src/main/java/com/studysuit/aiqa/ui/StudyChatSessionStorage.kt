package com.studysuit.aiqa.ui

import android.content.Context
import android.util.Base64
import android.util.Log
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
  val coachInput: String = "",
  val activePage: WorkspacePage,
  val quickFollowupSpanId: String? = null,
  val quickFollowupDetailId: String? = null,
  val quickFollowupSourceMessageId: String? = null,
  val coachMessages: List<CoachChatMessage> = emptyList(),
  val coachDigest: CoachDailyDigest? = null,
  val dailyTraining: DailyTrainingState = DailyTrainingState(),
  val savedQuestions: List<SavedQuestion> = emptyList(),
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

  fun exportPayloadJson(payload: PersistedSessions): String {
    return buildRootJson(payload).toString()
  }

  fun save(payload: PersistedSessions): Result<Unit> {
    return runCatching {
      val root = buildRootJson(payload)

      storageFile.parentFile?.mkdirs()
      storageFile.writeText(root.toString(), Charsets.UTF_8)
    }.onFailure { error ->
      Log.w(TAG, "Failed to persist sessions", error)
    }
  }

  private fun buildRootJson(payload: PersistedSessions): JSONObject {
    return JSONObject()
      .put("version", 1)
      .put("activeSessionId", payload.activeSessionId)
      .put("settings", payload.settings.toJson())
      .put("sessions", JSONArray().apply {
        payload.sessions.forEach { session ->
          put(session.toJson())
        }
      })
  }

  fun load(): PersistedSessions? {
    if (!storageFile.exists()) {
      return null
    }

    val raw = runCatching {
      storageFile.readText(Charsets.UTF_8)
    }.onFailure { error ->
      Log.w(TAG, "Failed to read persisted sessions", error)
    }.getOrNull() ?: return null

    return parsePersistedSessionsJson(raw).onFailure { error ->
      Log.w(TAG, "Failed to restore sessions", error)
    }.getOrNull()
  }

  private companion object {
    private const val TAG = "SessionStorage"
  }
}

internal fun parsePersistedSessionsJson(raw: String): Result<PersistedSessions> {
  return runCatching {
    val root = JSONObject(raw)
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

    PersistedSessions(
      activeSessionId = activeSessionId,
      settings = settings,
      sessions = sessions
    )
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
    .put("flowStudyServerUrl", flowStudyServerUrl)
    .put("flowStudyDeviceId", flowStudyDeviceId)
    .put("flowStudyDeviceToken", flowStudyDeviceToken)
    .put("customModelBaseUrl", customModelBaseUrl)
    .put("customModelApiKey", customModelApiKey)
    .put("customModelName", customModelName)
    .put("mistakeRecognitionModelCount", mistakeRecognitionModelCount.coerceIn(1, 3))
    .put("mistakeSecondModelBaseUrl", mistakeSecondModelBaseUrl)
    .put("mistakeSecondModelApiKey", mistakeSecondModelApiKey)
    .put("mistakeSecondModelName", mistakeSecondModelName)
    .put("mistakeFusionModelBaseUrl", mistakeFusionModelBaseUrl)
    .put("mistakeFusionModelApiKey", mistakeFusionModelApiKey)
    .put("mistakeFusionModelName", mistakeFusionModelName)
    .put(
      "customModelPresets",
      JSONArray().apply {
        customModelPresets.forEach { preset ->
          put(preset.toJson())
        }
      }
    )
}

private fun JSONObject.toRuntimeSettings(): RuntimeSettings {
  val defaults = RuntimeSettings.defaults()
  val rawArkModel = optString("arkModel", defaults.arkModel).trim()
  val normalizedArkModel = when {
    rawArkModel.isBlank() -> defaults.arkModel
    rawArkModel == LEGACY_ARK_MODEL -> defaults.arkModel
    else -> rawArkModel
  }
  return RuntimeSettings(
    arkApiKey = optString("arkApiKey", defaults.arkApiKey),
    arkModel = normalizedArkModel,
    arkBaseUrl = optString("arkBaseUrl", defaults.arkBaseUrl),
    arkEndpoint = optString("arkEndpoint", defaults.arkEndpoint),
    arkSystemPrompt = optString("arkSystemPrompt", defaults.arkSystemPrompt),
    imagePrompt = optString("imagePrompt", defaults.imagePrompt),
    openSpeechApiKey = optString("openSpeechApiKey", defaults.openSpeechApiKey),
    openSpeechResourceId = optString("openSpeechResourceId", defaults.openSpeechResourceId),
    openSpeechSubmitUrl = optString("openSpeechSubmitUrl", defaults.openSpeechSubmitUrl),
    openSpeechQueryUrl = optString("openSpeechQueryUrl", defaults.openSpeechQueryUrl),
    openSpeechUid = optString("openSpeechUid", defaults.openSpeechUid),
    flowStudyServerUrl = optString("flowStudyServerUrl", defaults.flowStudyServerUrl),
    flowStudyDeviceId = optString("flowStudyDeviceId", defaults.flowStudyDeviceId),
    flowStudyDeviceToken = optString("flowStudyDeviceToken", defaults.flowStudyDeviceToken),
    customModelBaseUrl = optString("customModelBaseUrl", defaults.customModelBaseUrl),
    customModelApiKey = optString("customModelApiKey", defaults.customModelApiKey),
    customModelName = optString("customModelName", defaults.customModelName),
    customModelPresets = optJSONArray("customModelPresets")?.toModelPresets().orEmpty(),
    mistakeRecognitionModelCount = optInt(
      "mistakeRecognitionModelCount",
      defaults.mistakeRecognitionModelCount
    ).coerceIn(1, 3),
    mistakeSecondModelBaseUrl = optString("mistakeSecondModelBaseUrl", defaults.mistakeSecondModelBaseUrl),
    mistakeSecondModelApiKey = optString("mistakeSecondModelApiKey", defaults.mistakeSecondModelApiKey),
    mistakeSecondModelName = optString("mistakeSecondModelName", defaults.mistakeSecondModelName),
    mistakeFusionModelBaseUrl = optString("mistakeFusionModelBaseUrl", defaults.mistakeFusionModelBaseUrl),
    mistakeFusionModelApiKey = optString("mistakeFusionModelApiKey", defaults.mistakeFusionModelApiKey),
    mistakeFusionModelName = optString("mistakeFusionModelName", defaults.mistakeFusionModelName)
  )
}

private fun ModelPreset.toJson(): JSONObject {
  return JSONObject()
    .put("id", id)
    .put("name", name)
    .put("baseUrl", baseUrl)
    .put("apiKey", apiKey)
    .put("modelName", modelName)
}

private fun JSONArray.toModelPresets(): List<ModelPreset> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      val id = item.optString("id").trim().ifBlank { "preset-$index" }
      val name = item.optString("name").trim()
      if (name.isNotBlank()) {
        add(
          ModelPreset(
            id = id,
            name = name,
            baseUrl = item.optString("baseUrl").trim(),
            apiKey = item.optString("apiKey").trim(),
            modelName = item.optString("modelName").trim()
          )
        )
      }
    }
  }
}

private const val LEGACY_ARK_MODEL = "doubao-seed-1-8-251228"

private fun StoredSession.toJson(): JSONObject {
  return JSONObject()
    .put("id", id)
    .put("title", title)
    .put("createdAt", createdAt)
    .put("updatedAt", updatedAt)
    .put("input", input)
    .put("coachInput", coachInput)
    .put("activePage", activePage.name)
    .put("quickFollowupSpanId", quickFollowupSpanId ?: JSONObject.NULL)
    .put("quickFollowupDetailId", quickFollowupDetailId ?: JSONObject.NULL)
    .put("quickFollowupSourceMessageId", quickFollowupSourceMessageId ?: JSONObject.NULL)
    .put("coachMessages", JSONArray().apply { coachMessages.forEach { message -> put(message.toJson()) } })
    .put("coachDigest", coachDigest?.toJson() ?: JSONObject.NULL)
    .put("dailyTraining", dailyTraining.toJson())
    .put("savedQuestions", JSONArray().apply { savedQuestions.forEach { question -> put(question.toJson()) } })
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

  val title = optString("title").ifBlank { "主界面" }
  val createdAt = optLong("createdAt", System.currentTimeMillis())
  val updatedAt = optLong("updatedAt", createdAt)
  val input = optString("input")
  val coachInput = optString("coachInput")

  val profile = optJSONObject("profile")?.toProfileState() ?: ProfileState(level = "高二 · 进阶冲刺")
  val messages = optJSONArray("messages")?.toChatMessages().orEmpty()
  val histories = optJSONObject("histories")?.toHistories().orEmpty()
  val activePage = optString("activePage").toWorkspacePageOrDefault()
  val quickFollowupSpanId = optString("quickFollowupSpanId").trim().ifBlank { null }
  val quickFollowupDetailId = optString("quickFollowupDetailId").trim().ifBlank { null }
  val quickFollowupSourceMessageId = optString("quickFollowupSourceMessageId").trim().ifBlank { null }
  val coachMessages = optJSONArray("coachMessages")?.toCoachMessages().orEmpty()
  val coachDigest = optJSONObject("coachDigest")?.toCoachDailyDigest()
  val dailyTraining = optJSONObject("dailyTraining")?.toDailyTrainingState() ?: DailyTrainingState()
  val savedQuestions = optJSONArray("savedQuestions")?.toSavedQuestions().orEmpty()
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
    coachInput = coachInput,
    activePage = activePage,
    quickFollowupSpanId = quickFollowupSpanId,
    quickFollowupDetailId = quickFollowupDetailId,
    quickFollowupSourceMessageId = quickFollowupSourceMessageId,
    coachMessages = coachMessages,
    coachDigest = coachDigest,
    dailyTraining = dailyTraining,
    savedQuestions = savedQuestions,
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
    is ChatMessage.User -> {
      val previewList = if (imagePreviewList.isNotEmpty()) {
        imagePreviewList
      } else {
        imagePreviewBytes?.let { bytes -> listOf(bytes) }.orEmpty()
      }

      JSONObject()
        .put("type", "user")
        .put("id", id)
        .put("time", time)
        .put("text", text)
        .put("image", previewList.firstOrNull()?.let { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) } ?: JSONObject.NULL)
        .put(
          "images",
          JSONArray().apply {
            previewList.forEach { bytes ->
              put(Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
          }
        )
    }

    is ChatMessage.Assistant -> JSONObject()
      .put("type", "assistant")
      .put("id", id)
      .put("time", time)
      .put(
        "mainSpan",
        mainSpan?.let { span ->
          JSONObject()
            .put("id", span.id)
            .put("content", span.content)
            .put("sourceQuestion", span.sourceQuestion)
        } ?: JSONObject.NULL
      )
      .put("reasoningSummary", reasoningSummary ?: JSONObject.NULL)
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
          val imageList = item.optJSONArray("images")?.let { array ->
            buildList {
              for (imageIndex in 0 until array.length()) {
                val encoded = array.optString(imageIndex)
                if (encoded.isBlank() || encoded == "null") {
                  continue
                }
                runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()?.let { decoded ->
                  if (decoded.isNotEmpty()) {
                    add(decoded)
                  }
                }
              }
            }
          }.orEmpty()

          val encodedImage = item.optString("image")
          val legacyImage = if (encodedImage.isBlank() || encodedImage == "null") {
            null
          } else {
            runCatching { Base64.decode(encodedImage, Base64.DEFAULT) }.getOrNull()
          }

          val previewList = if (imageList.isNotEmpty()) {
            imageList
          } else {
            legacyImage?.let { bytes -> listOf(bytes) }.orEmpty()
          }

          add(
            ChatMessage.User(
              id = item.optString("id"),
              time = item.optString("time"),
              text = item.optString("text"),
              imagePreviewBytes = previewList.firstOrNull(),
              imagePreviewList = previewList
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
          val mainSpan = item.optJSONObject("mainSpan")?.let { spanObj ->
            SpanData(
              id = spanObj.optString("id"),
              content = spanObj.optString("content"),
              sourceQuestion = spanObj.optString("sourceQuestion")
            )
          } ?: buildLegacyMainSpan(
            messageId = item.optString("id"),
            spans = spans
          )

          add(
            ChatMessage.Assistant(
              id = item.optString("id"),
              time = item.optString("time"),
              spans = spans,
              mainSpan = mainSpan,
              reasoningSummary = item.optString("reasoningSummary")
                .takeIf { summary -> summary.isNotBlank() && summary != "null" }
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
                .put("parentDetailId", detail.parentDetailId ?: JSONObject.NULL)
                .put("summary", detail.summary ?: JSONObject.NULL)
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
            answer = detailObj.optString("answer"),
            parentDetailId = detailObj.optString("parentDetailId").takeIf { it.isNotBlank() && it != "null" },
            summary = detailObj.optString("summary").takeIf { it.isNotBlank() && it != "null" }
          )
        )
      }
    }
    histories[spanId] = details
  }
  return histories
}

private fun SavedQuestion.toJson(): JSONObject {
  val previewList = if (imagePreviewList.isNotEmpty()) {
    imagePreviewList
  } else {
    imagePreviewBytes?.let { bytes -> listOf(bytes) }.orEmpty()
  }

  return JSONObject()
    .put("id", id)
    .put("sourceMessageId", sourceMessageId)
    .put("question", question)
    .put("answer", answer)
    .put("sourceTime", sourceTime)
    .put("savedAt", savedAt)
    .put("followupCount", followupCount)
    .put("knowledgeTags", JSONArray(knowledgeTags))
    .put("subject", subject)
    .put("questionType", questionType)
    .put("analysisSummary", analysisSummary)
    .put("image", previewList.firstOrNull()?.let { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) } ?: JSONObject.NULL)
    .put(
      "images",
      JSONArray().apply {
        previewList.forEach { bytes ->
          put(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
      }
    )
}

private fun JSONArray.toSavedQuestions(): List<SavedQuestion> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      val id = item.optString("id").trim()
      val question = item.optString("question").trim()
      if (id.isBlank() || question.isBlank()) {
        continue
      }
      val imageList = item.optJSONArray("images")?.let { array ->
        buildList {
          for (imageIndex in 0 until array.length()) {
            val encoded = array.optString(imageIndex)
            if (encoded.isBlank() || encoded == "null") {
              continue
            }
            runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()?.let { decoded ->
              if (decoded.isNotEmpty()) {
                add(decoded)
              }
            }
          }
        }
      }.orEmpty()
      val encodedImage = item.optString("image")
      val legacyImage = if (encodedImage.isBlank() || encodedImage == "null") {
        null
      } else {
        runCatching { Base64.decode(encodedImage, Base64.DEFAULT) }.getOrNull()
      }
      val previewList = if (imageList.isNotEmpty()) {
        imageList
      } else {
        legacyImage?.let { bytes -> listOf(bytes) }.orEmpty()
      }
      add(
        SavedQuestion(
          id = id,
          sourceMessageId = item.optString("sourceMessageId").trim(),
          question = question,
          answer = item.optString("answer"),
          sourceTime = item.optString("sourceTime"),
          savedAt = item.optLong("savedAt", System.currentTimeMillis()),
          followupCount = item.optInt("followupCount", 0).coerceAtLeast(0),
          knowledgeTags = item.optJSONArray("knowledgeTags")?.let { tagsArray ->
            buildList {
              for (tagIndex in 0 until tagsArray.length()) {
                val tag = tagsArray.optString(tagIndex).trim()
                if (tag.isNotBlank()) {
                  add(tag)
                }
              }
            }
          }.orEmpty(),
          subject = item.optString("subject").trim(),
          questionType = item.optString("questionType").trim(),
          analysisSummary = item.optString("analysisSummary").trim(),
          imagePreviewBytes = previewList.firstOrNull(),
          imagePreviewList = previewList
        )
      )
    }
  }
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
    .put("nextReviewAt", nextReviewAt)
    .put("reviewCount", reviewCount)
    .put("lastReviewedAt", lastReviewedAt ?: JSONObject.NULL)
    .put("mastery", mastery.name)
    .put("deck", deckName)
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
          createdAt = item.optLong("createdAt", System.currentTimeMillis()),
          nextReviewAt = item.optLong("nextReviewAt", item.optLong("createdAt", System.currentTimeMillis())),
          reviewCount = item.optInt("reviewCount", 0).coerceAtLeast(0),
          lastReviewedAt = item.optLong("lastReviewedAt", -1L).takeIf { value -> value > 0L },
          mastery = item.optString("mastery").toCardMasteryLevelOrDefault(),
          deckName = item.optString("deck").trim().takeIf { value -> value.isNotBlank() } ?: DEFAULT_ANKI_DECK_NAME
        )
      )
    }
  }
}

private fun CoachChatMessage.toJson(): JSONObject {
  return JSONObject()
    .put("id", id)
    .put("role", role.name)
    .put("time", time)
    .put("text", text)
}

private fun JSONArray.toCoachMessages(): List<CoachChatMessage> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      val id = item.optString("id").trim()
      if (id.isBlank()) {
        continue
      }
      val text = item.optString("text").trim()
      add(
        CoachChatMessage(
          id = id,
          role = item.optString("role").toCoachMessageRoleOrDefault(),
          time = item.optString("time"),
          text = text
        )
      )
    }
  }
}

private fun CoachDailyDigest.toJson(): JSONObject {
  return JSONObject()
    .put("dateKey", dateKey)
    .put("generatedAt", generatedAt)
    .put("headline", headline)
    .put("summary", summary)
    .put(
      "focusAreas",
      JSONArray().apply {
        focusAreas.forEach { area -> put(area.toJson()) }
      }
    )
    .put(
      "recommendedQuestions",
      JSONArray().apply {
        recommendedQuestions.forEach { question -> put(question.toJson()) }
      }
    )
}

private fun DailyTrainingState.toJson(): JSONObject {
  return JSONObject()
    .put("dateKey", dateKey)
    .put("currentIndex", currentIndex)
    .put("phase", phase.name)
    .put("currentQuestionText", currentQuestionText)
    .put(
      "rounds",
      JSONArray().apply {
        rounds.forEach { round -> put(round.toJson()) }
      }
    )
}

private fun JSONObject.toDailyTrainingState(): DailyTrainingState {
  return DailyTrainingState(
    dateKey = optString("dateKey"),
    rounds = optJSONArray("rounds")?.toCoachRecommendedQuestions().orEmpty(),
    currentIndex = optInt("currentIndex", 0).coerceAtLeast(0),
    phase = optString("phase").toDailyTrainingPhaseOrDefault(),
    currentQuestionText = optString("currentQuestionText")
  )
}

private fun JSONObject.toCoachDailyDigest(): CoachDailyDigest {
  return CoachDailyDigest(
    dateKey = optString("dateKey"),
    generatedAt = optLong("generatedAt", System.currentTimeMillis()),
    headline = optString("headline"),
    summary = optString("summary"),
    focusAreas = optJSONArray("focusAreas")?.toCoachFocusAreas().orEmpty(),
    recommendedQuestions = optJSONArray("recommendedQuestions")?.toCoachRecommendedQuestions().orEmpty()
  )
}

private fun CoachFocusArea.toJson(): JSONObject {
  return JSONObject()
    .put("point", point)
    .put("level", level.name)
    .put("diagnosis", diagnosis)
    .put("action", action)
    .put("evidence", evidence)
}

private fun JSONArray.toCoachFocusAreas(): List<CoachFocusArea> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      val point = item.optString("point").trim()
      if (point.isBlank()) {
        continue
      }
      add(
        CoachFocusArea(
          point = point,
          level = item.optString("level").toKnowledgeGapLevelOrDefault(),
          diagnosis = item.optString("diagnosis"),
          action = item.optString("action"),
          evidence = item.optString("evidence")
        )
      )
    }
  }
}

private fun CoachRecommendedQuestion.toJson(): JSONObject {
  return JSONObject()
    .put("id", id)
    .put("title", title)
    .put("reason", reason)
    .put("prompt", prompt)
    .put("basis", basis)
    .put("anchorSavedQuestionId", anchorSavedQuestionId ?: JSONObject.NULL)
}

private fun JSONArray.toCoachRecommendedQuestions(): List<CoachRecommendedQuestion> {
  return buildList {
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      val prompt = item.optString("prompt").trim()
      if (prompt.isBlank()) {
        continue
      }
      add(
        CoachRecommendedQuestion(
          id = item.optString("id").trim().ifBlank { "coach-rec-$index" },
          title = item.optString("title"),
          reason = item.optString("reason"),
          prompt = prompt,
          basis = item.optString("basis"),
          anchorSavedQuestionId = item.optString("anchorSavedQuestionId").trim().takeIf { value -> value.isNotBlank() }
        )
      )
    }
  }
}

private fun String.toCoachMessageRoleOrDefault(): CoachMessageRole {
  return runCatching { CoachMessageRole.valueOf(this) }.getOrDefault(CoachMessageRole.ASSISTANT)
}

private fun String.toDailyTrainingPhaseOrDefault(): DailyTrainingPhase {
  return runCatching { DailyTrainingPhase.valueOf(this) }.getOrDefault(DailyTrainingPhase.IDLE)
}

private fun String.toKnowledgeGapLevelOrDefault(): KnowledgeGapLevel {
  return runCatching { KnowledgeGapLevel.valueOf(this) }.getOrDefault(KnowledgeGapLevel.MEDIUM)
}

private fun String.toCardMasteryLevelOrDefault(): CardMasteryLevel {
  return runCatching { CardMasteryLevel.valueOf(this) }.getOrDefault(CardMasteryLevel.UNRATED)
}

private fun String.toWorkspacePageOrDefault(): WorkspacePage {
  return runCatching { WorkspacePage.valueOf(this) }.getOrDefault(WorkspacePage.CHAT)
}

private fun buildLegacyMainSpan(messageId: String, spans: List<SpanData>): SpanData? {
  if (spans.isEmpty()) {
    return null
  }

  val fullAnswer = spans.joinToString(separator = "\n\n") { span -> span.content.trim() }.trim()
  if (fullAnswer.isBlank()) {
    return null
  }

  val sourceQuestion = spans
    .firstOrNull { span -> span.sourceQuestion.isNotBlank() }
    ?.sourceQuestion
    .orEmpty()

  return SpanData(
    id = "assistant-main-${messageId.ifBlank { "legacy" }}",
    content = fullAnswer,
    sourceQuestion = sourceQuestion
  )
}
