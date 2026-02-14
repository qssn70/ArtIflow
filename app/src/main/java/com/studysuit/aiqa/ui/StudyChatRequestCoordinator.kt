package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.ArkApiClient
import com.studysuit.aiqa.data.ArkRequestMessage
import com.studysuit.aiqa.data.OpenSpeechAsrClient

internal data class AnkiGenerationInput(
  val mode: String,
  val spanContent: String,
  val question: String?,
  val answer: String,
  val profile: ProfileState,
  val knowledgePoints: Map<String, Int>,
  val settings: RuntimeSettings
)

internal class StudyChatRequestCoordinator(
  private val arkApiClient: ArkApiClient,
  private val openSpeechAsrClient: OpenSpeechAsrClient
) {
  suspend fun replyForImageQuestion(imageBytes: ByteArray, settings: RuntimeSettings): Result<String> {
    val prompt = normalizeImagePrompt(settings.imagePrompt)
    return arkApiClient.generateReplyWithImage(
      prompt = prompt,
      imageBytes = imageBytes,
      mimeType = "image/jpeg",
      config = settings.toArkRuntimeConfig()
    )
  }

  suspend fun replyForAutoExplain(spanContent: String, settings: RuntimeSettings): Result<String> {
    val requestMessages = listOf(
      ArkRequestMessage(role = "user", text = buildAutoExplainPrompt(spanContent))
    )
    return arkApiClient.generateReply(requestMessages, config = settings.toArkRuntimeConfig())
  }

  suspend fun replyForConversation(
    messages: List<ChatMessage>,
    settings: RuntimeSettings
  ): Result<String> {
    return arkApiClient.generateReply(toArkMessages(messages), config = settings.toArkRuntimeConfig())
  }

  suspend fun replyForSpanFollowup(
    span: SpanData,
    followupQuestion: String,
    details: List<SpanDetail>,
    settings: RuntimeSettings
  ): Result<String> {
    val historyForRequest = toSpanFollowupMessages(
      span = span,
      followupQuestion = followupQuestion,
      details = details
    )
    return arkApiClient.generateReply(historyForRequest, config = settings.toArkRuntimeConfig())
  }

  fun canTranscribe(settings: RuntimeSettings): Boolean {
    return openSpeechAsrClient.isConfigured(settings.toOpenSpeechRuntimeConfig())
  }

  suspend fun transcribe(audioBytes: ByteArray, settings: RuntimeSettings): Result<String> {
    return openSpeechAsrClient.transcribeByAudioData(
      audioBytes = audioBytes,
      config = settings.toOpenSpeechRuntimeConfig()
    )
  }

  suspend fun generateAnkiPayload(input: AnkiGenerationInput): Result<AiAnkiCardPayload?> {
    val cardConfig = input.settings.toArkRuntimeConfig().copy(systemPrompt = ANKI_CARD_SYSTEM_PROMPT)
    val prompt = buildAnkiGenerationPrompt(
      mode = input.mode,
      spanContent = input.spanContent,
      question = input.question,
      answer = input.answer,
      profile = input.profile,
      knowledgePoints = input.knowledgePoints
    )

    return arkApiClient.generateReply(
      messages = listOf(ArkRequestMessage(role = "user", text = prompt)),
      config = cardConfig
    ).map { raw ->
      parseAiAnkiCardPayload(raw)
    }
  }
}
