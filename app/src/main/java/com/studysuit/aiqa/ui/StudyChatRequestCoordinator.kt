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
  val knowledgeGapInsights: List<KnowledgeGapInsight>,
  val existingDecks: List<String>,
  val settings: RuntimeSettings
)

internal class StudyChatRequestCoordinator(
  private val arkApiClient: ArkApiClient,
  private val openSpeechAsrClient: OpenSpeechAsrClient
) {
  suspend fun replyForImageQuestion(
    imageBytes: ByteArray,
    settings: RuntimeSettings,
    note: String?,
    imageCount: Int
  ): Result<String> {
    val prompt = buildImageQuestionPrompt(
      imagePrompt = settings.imagePrompt,
      note = note,
      imageCount = imageCount
    )

    return arkApiClient.generateReplyWithImage(
      prompt = prompt,
      imageBytes = imageBytes,
      mimeType = "image/jpeg",
      config = settings.toArkRuntimeConfig()
    )
  }

  suspend fun replyForImageQuestionStream(
    imageBytes: ByteArray,
    settings: RuntimeSettings,
    note: String?,
    imageCount: Int,
    onDelta: (String) -> Unit,
    onReasoningDelta: ((String) -> Unit)? = null
  ): Result<String> {
    val prompt = buildImageQuestionPrompt(
      imagePrompt = settings.imagePrompt,
      note = note,
      imageCount = imageCount
    )

    return arkApiClient.generateReplyWithImageStream(
      prompt = prompt,
      imageBytes = imageBytes,
      mimeType = "image/jpeg",
      config = settings.toArkRuntimeConfig(),
      onDelta = onDelta,
      onReasoningDelta = onReasoningDelta
    )
  }

  suspend fun replyForAutoExplain(spanContent: String, settings: RuntimeSettings): Result<String> {
    val requestMessages = listOf(
      ArkRequestMessage(role = "user", text = buildAutoExplainPrompt(spanContent))
    )
    return arkApiClient.generateReply(requestMessages, config = settings.toArkRuntimeConfig())
  }

  suspend fun replyForAutoExplainStream(
    spanContent: String,
    settings: RuntimeSettings,
    onDelta: (String) -> Unit
  ): Result<String> {
    val requestMessages = listOf(
      ArkRequestMessage(role = "user", text = buildAutoExplainPrompt(spanContent))
    )
    return arkApiClient.generateReplyStream(
      messages = requestMessages,
      config = settings.toArkRuntimeConfig(),
      onDelta = onDelta
    )
  }

  suspend fun replyForConversation(
    messages: List<ChatMessage>,
    settings: RuntimeSettings
  ): Result<String> {
    return arkApiClient.generateReply(toArkMessages(messages), config = settings.toArkRuntimeConfig())
  }

  suspend fun replyForConversationStream(
    messages: List<ChatMessage>,
    settings: RuntimeSettings,
    onDelta: (String) -> Unit,
    onReasoningDelta: ((String) -> Unit)? = null
  ): Result<String> {
    return arkApiClient.generateReplyStream(
      messages = toArkMessages(messages),
      config = settings.toArkRuntimeConfig(),
      onDelta = onDelta,
      onReasoningDelta = onReasoningDelta
    )
  }

  suspend fun replyForSpanFollowup(
    span: SpanData,
    followupQuestion: String,
    details: List<SpanDetail>,
    messages: List<ChatMessage>,
    settings: RuntimeSettings
  ): Result<String> {
    val historyForRequest = toSpanFollowupMessages(
      span = span,
      followupQuestion = followupQuestion,
      details = details,
      messages = messages
    )
    return arkApiClient.generateReply(historyForRequest, config = settings.toArkRuntimeConfig())
  }

  suspend fun replyForSpanFollowupStream(
    span: SpanData,
    followupQuestion: String,
    details: List<SpanDetail>,
    messages: List<ChatMessage>,
    settings: RuntimeSettings,
    onDelta: (String) -> Unit
  ): Result<String> {
    val historyForRequest = toSpanFollowupMessages(
      span = span,
      followupQuestion = followupQuestion,
      details = details,
      messages = messages
    )
    return arkApiClient.generateReplyStream(
      messages = historyForRequest,
      config = settings.toArkRuntimeConfig(),
      onDelta = onDelta
    )
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
      knowledgePoints = input.knowledgePoints,
      knowledgeGapInsights = input.knowledgeGapInsights,
      existingDecks = input.existingDecks
    )

    return arkApiClient.generateReply(
      messages = listOf(ArkRequestMessage(role = "user", text = prompt)),
      config = cardConfig
    ).map { raw ->
      parseAiAnkiCardPayload(raw)
    }
  }

  private fun buildImageQuestionPrompt(
    imagePrompt: String,
    note: String?,
    imageCount: Int
  ): String {
    return buildString {
      append(normalizeImagePrompt(imagePrompt))

      if (imageCount > 1) {
        append("\n补充要求：本次上传了")
        append(imageCount)
        append("张图片，请综合识别后统一作答。")
      }

      val normalizedNote = note?.trim().orEmpty()
      if (normalizedNote.isNotBlank()) {
        append("\n用户补充：")
        append(normalizedNote)
      }

      append("\n如果题目较复杂，先给关键结论与最必要步骤，再补充细节。")
    }
  }
}
