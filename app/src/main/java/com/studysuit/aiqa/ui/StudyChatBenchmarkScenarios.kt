package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.MistakeBookItem
import com.studysuit.aiqa.data.MistakeType
import java.util.Base64

internal const val BENCHMARK_SCENARIO_EXTRA = "com.studysuit.aiqa.BENCHMARK_SCENARIO"

internal enum class BenchmarkScenario(val wireName: String) {
  CHAT_100_SCROLL("chat_100_scroll"),
  MISTAKE_200_SEARCH("mistake_200_search"),
  THREE_IMAGE_PREVIEW("three_image_preview"),
  STREAM_2_MINUTES("stream_2_minutes"),
  FORMULA_IMAGE_HEAVY("formula_image_heavy");

  companion object {
    fun fromWireName(value: String?): BenchmarkScenario? {
      return entries.firstOrNull { scenario -> scenario.wireName == value?.trim() }
    }
  }
}

internal fun buildBenchmarkScenarioState(scenario: BenchmarkScenario): ChatUiState {
  return when (scenario) {
    BenchmarkScenario.CHAT_100_SCROLL -> benchmarkChatScrollState()
    BenchmarkScenario.MISTAKE_200_SEARCH -> benchmarkMistakeSearchState()
    BenchmarkScenario.THREE_IMAGE_PREVIEW -> benchmarkThreeImagePreviewState()
    BenchmarkScenario.STREAM_2_MINUTES -> benchmarkStreamingState()
    BenchmarkScenario.FORMULA_IMAGE_HEAVY -> benchmarkFormulaImageHeavyState()
  }
}

private fun benchmarkChatScrollState(): ChatUiState {
  val messages = (1..50).flatMap { turn ->
    listOf(
      ChatMessage.User(
        id = "benchmark-user-$turn",
        time = "10:${turn.toString().padStart(2, '0')}",
        text = "第 ${turn * 2 - 1} 条聊天消息：函数与几何综合题。"
      ),
      ChatMessage.Assistant(
        id = "benchmark-assistant-$turn",
        time = "10:${turn.toString().padStart(2, '0')}",
        spans = listOf(
          SpanData(
            id = "benchmark-span-$turn",
            content = "第 ${turn * 2} 条聊天消息：先拆条件，再列式，最后检查边界情况。",
            sourceQuestion = "第 ${turn * 2 - 1} 条聊天消息"
          )
        )
      )
    )
  }
  return ChatUiState(
    activePage = WorkspacePage.CHAT,
    activeSessionId = "benchmark-chat-100",
    messages = messages
  )
}

private fun benchmarkMistakeSearchState(): ChatUiState {
  val mistakes = (1..200).map { index ->
    MistakeBookItem.create(
      id = "benchmark-mistake-$index",
      question = "第 $index 道错题：函数图像与参数范围搜索样本",
      correctAnswer = "第 $index 题标准答案",
      subject = if (index % 3 == 0) "物理" else "数学",
      questionType = "综合题",
      knowledgeTags = listOf("函数", "性能基准", "错题筛选-$index"),
      studentAnswer = "漏看定义域",
      explanation = "先定位关键词，再检查筛选结果是否稳定。",
      mistakeReason = "审题与概念混合错误",
      mistakeType = MistakeType.CONCEPT_ERROR,
      createdAt = 1_700_000_000_000L + index
    )
  }
  return ChatUiState(
    activePage = WorkspacePage.MISTAKES,
    activeSessionId = "benchmark-mistake-200",
    mistakeItems = mistakes
  )
}

private fun benchmarkThreeImagePreviewState(): ChatUiState {
  val previews = listOf(
    benchmarkPngBytes(),
    benchmarkPngBytes(),
    benchmarkPngBytes()
  )
  return ChatUiState(
    activePage = WorkspacePage.CHAT,
    activeSessionId = "benchmark-three-image",
    messages = listOf(
      ChatMessage.User(
        id = "benchmark-three-image-user",
        time = "10:00",
        text = "三图搜题样本：请综合三张图片解题。",
        imagePreviewList = previews,
        imagePreviewBytes = previews.first()
      ),
      ChatMessage.Assistant(
        id = "benchmark-three-image-assistant",
        time = "10:01",
        spans = listOf(
          SpanData(
            id = "benchmark-three-image-span",
            content = "已收到 3 张图片，先做缩略图预览，再保留原图用于模型请求。",
            sourceQuestion = "三图搜题样本"
          )
        )
      )
    )
  )
}

private fun benchmarkStreamingState(): ChatUiState {
  return ChatUiState(
    activePage = WorkspacePage.CHAT,
    activeSessionId = "benchmark-stream-2-minutes",
    isLoading = true,
    messages = listOf(
      ChatMessage.User(
        id = "benchmark-stream-user",
        time = "10:00",
        text = "请用两分钟流式回答这道综合题。"
      ),
      ChatMessage.Assistant(
        id = BENCHMARK_STREAM_ASSISTANT_ID,
        time = "10:01",
        spans = listOf(
          SpanData(
            id = BENCHMARK_STREAM_SPAN_ID,
            content = "流式回答性能样本：",
            sourceQuestion = "两分钟流式回答"
          )
        )
      )
    )
  )
}

private fun benchmarkFormulaImageHeavyState(): ChatUiState {
  val preview = benchmarkPngBytes()
  val formula = """
    含图片与公式消息打开样本：

    \[
    x_1 = \frac{-b + \sqrt{b^2 - 4ac}}{2a}
    \]

    再结合图片条件，计算面积 \(S=\frac{1}{2}ab\sin C\)。
  """.trimIndent()
  return ChatUiState(
    activePage = WorkspacePage.CHAT,
    activeSessionId = "benchmark-formula-image-heavy",
    messages = listOf(
      ChatMessage.User(
        id = "benchmark-formula-image-user",
        time = "10:00",
        text = "含图片与公式消息打开样本",
        imagePreviewList = listOf(preview),
        imagePreviewBytes = preview
      ),
      ChatMessage.Assistant(
        id = "benchmark-formula-image-assistant",
        time = "10:01",
        spans = listOf(
          SpanData(
            id = "benchmark-formula-image-span",
            content = formula,
            sourceQuestion = "含图片与公式消息打开样本"
          )
        )
      )
    )
  )
}

internal const val BENCHMARK_STREAM_ASSISTANT_ID = "benchmark-stream-assistant"
internal const val BENCHMARK_STREAM_SPAN_ID = "benchmark-stream-span"
internal const val BENCHMARK_STREAM_DURATION_MS = 120_000L
internal const val BENCHMARK_STREAM_FRAME_MS = 80L

internal fun buildBenchmarkStreamChunk(index: Int): String {
  return " 第 $index 步，按条件展开并检查公式。"
}

private fun benchmarkPngBytes(): ByteArray {
  return Base64.getDecoder()
    .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lG0H+QAAAABJRU5ErkJggg==")
}
