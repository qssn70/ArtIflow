package com.studysuit.aiqa.ui

import com.studysuit.aiqa.BuildConfig
import com.studysuit.aiqa.data.ArkRuntimeConfig
import com.studysuit.aiqa.data.OpenSpeechRuntimeConfig
import java.util.Locale

internal fun findSpanById(messages: List<ChatMessage>, spanId: String?): SpanData? {
  if (spanId == null) {
    return null
  }

  messages.forEach { message ->
    if (message is ChatMessage.Assistant) {
      message.spans.firstOrNull { span -> span.id == spanId }?.let { found ->
        return found
      }
    }
  }

  return null
}

internal fun ProfileState.updateWith(text: String, isFollowup: Boolean, isVoice: Boolean): ProfileState {
  val hits = topicHits.toMutableMap()
  val topics = detectTopicsForProfile(text)
  topics.forEach { topic ->
    hits[topic] = (hits[topic] ?: 0) + 1
  }

  return copy(
    topicHits = hits,
    followups = followups + if (isFollowup) 1 else 0,
    voiceFollowups = voiceFollowups + if (isVoice) 1 else 0
  )
}

internal fun detectTopicsForProfile(text: String): List<String> {
  val normalized = text.lowercase(Locale.getDefault())
  val matched = topicRules.filter { rule ->
    rule.keywords.any { keyword -> normalized.contains(keyword) }
  }.map { it.topic }

  return if (matched.isEmpty()) listOf("通用方法") else matched
}

private data class TopicRule(
  val topic: String,
  val keywords: List<String>
)

internal data class KnowledgeRule(
  val point: String,
  val keywords: List<String>
)

private val topicRules = listOf(
  TopicRule(topic = "函数", keywords = listOf("函数", "顶点", "最值", "导数", "单调")),
  TopicRule(topic = "几何", keywords = listOf("几何", "三角形", "圆", "向量", "角度")),
  TopicRule(topic = "概率", keywords = listOf("概率", "随机", "独立", "期望", "方差")),
  TopicRule(topic = "物理", keywords = listOf("力", "加速度", "电场", "磁场", "电流")),
  TopicRule(topic = "化学", keywords = listOf("氧化", "还原", "反应", "离子", "平衡"))
)

internal val knowledgeRules = listOf(
  KnowledgeRule(point = "函数与图像", keywords = listOf("函数", "图像", "抛物线", "导数", "单调", "最值")),
  KnowledgeRule(point = "方程与不等式", keywords = listOf("方程", "不等式", "根", "判别式", "配方", "二次")),
  KnowledgeRule(point = "几何证明", keywords = listOf("几何", "三角形", "圆", "向量", "相似", "全等")),
  KnowledgeRule(point = "概率统计", keywords = listOf("概率", "随机", "期望", "方差", "排列", "组合")),
  KnowledgeRule(point = "力学", keywords = listOf("受力", "牛顿", "加速度", "速度", "位移", "动量")),
  KnowledgeRule(point = "电磁学", keywords = listOf("电场", "电势", "电流", "电阻", "磁场", "感应")),
  KnowledgeRule(point = "化学反应", keywords = listOf("氧化", "还原", "离子", "平衡", "反应", "浓度"))
)

private const val LEGACY_ARK_SYSTEM_PROMPT =
  "你是一个有用的AI学习辅导助手，擅长把复杂知识点讲清楚，优先给步骤化解释。"

private const val DEFAULT_ARK_SYSTEM_PROMPT =
  "你是中学学习辅导助手。回答简洁明了：先给结论，再给关键点；默认3-6行；不要套话，不要长篇大论。"

internal const val ANKI_CARD_SYSTEM_PROMPT =
  "你是Anki制卡助手。根据学习交互自选最合适卡型，输出可直接测验的卡片；内容简洁准确，不套模板。"

internal const val DEFAULT_ANKI_DECK_NAME = "未分类"

private const val LEGACY_IMAGE_PROMPT =
  "你是一名中学学科辅导老师。请先识别图片中的题干，再按步骤讲解并给出最终答案。" +
    "如果图片里有多个小题，请按小题编号分别作答。输出格式：\n" +
    "1) 题目识别\n2) 解题思路\n3) 详细步骤\n4) 最终答案"

private const val DEFAULT_IMAGE_PROMPT =
  "你是一名中学学科辅导老师。请识别题目并简洁作答：先给结论，再给2-4条必要步骤；" +
    "多小题按编号回答；不要套话和长篇大论。"

data class RuntimeSettings(
  val arkApiKey: String,
  val arkModel: String,
  val arkBaseUrl: String,
  val arkEndpoint: String,
  val arkSystemPrompt: String,
  val imagePrompt: String,
  val openSpeechApiKey: String,
  val openSpeechResourceId: String,
  val openSpeechSubmitUrl: String,
  val openSpeechQueryUrl: String,
  val openSpeechUid: String
) {
  companion object {
    fun defaults(): RuntimeSettings {
      return RuntimeSettings(
        arkApiKey = BuildConfig.ARK_API_KEY,
        arkModel = BuildConfig.ARK_MODEL,
        arkBaseUrl = BuildConfig.ARK_BASE_URL,
        arkEndpoint = BuildConfig.ARK_ENDPOINT,
        arkSystemPrompt = BuildConfig.ARK_SYSTEM_PROMPT,
        imagePrompt = DEFAULT_IMAGE_PROMPT,
        openSpeechApiKey = BuildConfig.OPENSPEECH_API_KEY,
        openSpeechResourceId = BuildConfig.OPENSPEECH_RESOURCE_ID,
        openSpeechSubmitUrl = BuildConfig.OPENSPEECH_SUBMIT_URL,
        openSpeechQueryUrl = BuildConfig.OPENSPEECH_QUERY_URL,
        openSpeechUid = BuildConfig.OPENSPEECH_UID
      )
    }
  }
}

internal fun RuntimeSettings.toArkRuntimeConfig(): ArkRuntimeConfig {
  return ArkRuntimeConfig(
    apiKey = arkApiKey,
    model = arkModel,
    baseUrl = arkBaseUrl,
    endpoint = arkEndpoint,
    systemPrompt = normalizeSystemPrompt(arkSystemPrompt)
  )
}

internal fun RuntimeSettings.toOpenSpeechRuntimeConfig(): OpenSpeechRuntimeConfig {
  return OpenSpeechRuntimeConfig(
    apiKey = openSpeechApiKey,
    resourceId = openSpeechResourceId,
    submitUrl = openSpeechSubmitUrl,
    queryUrl = openSpeechQueryUrl,
    uid = openSpeechUid
  )
}

data class ChatUiState(
  val messages: List<ChatMessage> = emptyList(),
  val histories: Map<String, List<SpanDetail>> = emptyMap(),
  val profile: ProfileState = ProfileState(level = "高二 · 进阶冲刺"),
  val input: String = "",
  val selectedSpanId: String? = null,
  val activePage: WorkspacePage = WorkspacePage.CHAT,
  val knowledgePoints: Map<String, Int> = emptyMap(),
  val ankiCards: List<AnkiCard> = emptyList(),
  val isDueReviewMode: Boolean = false,
  val activeSessionId: String = "",
  val sessionSummaries: List<SessionSummary> = emptyList(),
  val isSessionsOpen: Boolean = false,
  val toastMessage: String? = null,
  val processingSpanIds: Set<String> = emptySet(),
  val isLoading: Boolean = false,
  val isSettingsOpen: Boolean = false,
  val settings: RuntimeSettings = RuntimeSettings.defaults(),
  val settingsDraft: RuntimeSettings = RuntimeSettings.defaults()
)

data class SessionSummary(
  val id: String,
  val title: String,
  val updatedAt: Long,
  val messageCount: Int
)

data class ProfileState(
  val level: String,
  val topicHits: Map<String, Int> = emptyMap(),
  val followups: Int = 0,
  val voiceFollowups: Int = 0
)

sealed interface ChatMessage {
  val id: String
  val time: String

  data class User(
    override val id: String,
    override val time: String,
    val text: String,
    val imagePreviewBytes: ByteArray? = null,
    val imagePreviewList: List<ByteArray> = emptyList()
  ) : ChatMessage

  data class Assistant(
    override val id: String,
    override val time: String,
    val spans: List<SpanData>
  ) : ChatMessage
}

data class SpanData(
  val id: String,
  val content: String,
  val sourceQuestion: String
)

data class SpanDetail(
  val id: String,
  val mode: String,
  val time: String,
  val question: String? = null,
  val answer: String
)

enum class WorkspacePage {
  CHAT,
  ANKI
}

data class AnkiCard(
  val id: String,
  val front: String,
  val back: String,
  val tags: List<String>,
  val source: String,
  val createdAt: Long,
  val nextReviewAt: Long = createdAt,
  val reviewCount: Int = 0,
  val lastReviewedAt: Long? = null,
  val mastery: CardMasteryLevel = CardMasteryLevel.UNRATED,
  val deckName: String = DEFAULT_ANKI_DECK_NAME
)

enum class CardMasteryLevel(
  val label: String,
  val reviewPriority: Int
) {
  UNRATED("未标记", 0),
  NEEDS_WORK("生疏", 1),
  FAMILIAR("一般", 2),
  PROFICIENT("熟练", 3)
}

private fun normalizeSystemPrompt(prompt: String): String {
  val trimmed = prompt.trim()
  return when {
    trimmed.isBlank() -> DEFAULT_ARK_SYSTEM_PROMPT
    trimmed == LEGACY_ARK_SYSTEM_PROMPT -> DEFAULT_ARK_SYSTEM_PROMPT
    else -> trimmed
  }
}

internal fun normalizeImagePrompt(prompt: String): String {
  val trimmed = prompt.trim()
  return when {
    trimmed.isBlank() -> DEFAULT_IMAGE_PROMPT
    trimmed == LEGACY_IMAGE_PROMPT -> DEFAULT_IMAGE_PROMPT
    else -> trimmed
  }
}
