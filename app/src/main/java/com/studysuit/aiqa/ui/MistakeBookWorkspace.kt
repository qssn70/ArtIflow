package com.studysuit.aiqa.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studysuit.aiqa.data.MistakeBookItem
import com.studysuit.aiqa.data.MistakeRecognitionDraft
import com.studysuit.aiqa.data.MistakeRecognitionStatus
import com.studysuit.aiqa.data.MistakeSrsEngine
import com.studysuit.aiqa.data.MistakeStatus
import com.studysuit.aiqa.data.MistakeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun MistakeBookWorkspace(
  items: List<MistakeBookItem>,
  drafts: List<MistakeRecognitionDraft>,
  activeDraftId: String?,
  activeReviewId: String?,
  activeReviewSuggestion: MistakeReviewSuggestion?,
  mistakeAiAnalysis: MistakeBookAiAnalysis?,
  isDueReviewMode: Boolean,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onCameraCapture: () -> Unit,
  onGalleryPick: () -> Unit,
  onOpenDueQueue: () -> Unit,
  onRefreshReminder: () -> Unit,
  onExport: () -> Unit,
  onImport: () -> Unit,
  onAnalyzeWithAi: () -> Unit,
  onConfirmDraft: (MistakeRecognitionDraft) -> Unit,
  onRecordReview: (itemId: String, isCorrect: Boolean, userAnswer: String) -> Unit,
  onRequestJudgement: (itemId: String, userAnswer: String) -> Unit,
  onCameraAnswerCapture: (itemId: String, userAnswer: String) -> Unit,
  onGalleryAnswerPick: (itemId: String, userAnswer: String) -> Unit,
  onConfirmJudgement: (itemId: String, isCorrect: Boolean) -> Unit,
  onAddToAnki: (String) -> Unit,
  onDeleteItem: (String) -> Unit,
  onReopenItem: (String) -> Unit
) {
  var selectedTab by remember { mutableStateOf(MistakeBookTab.DUE) }
  var selectedFilters by remember { mutableStateOf(MistakeBookFilters()) }
  val now = System.currentTimeMillis()
  val stats = remember(items, now) { buildMistakeBookStats(items, now = now) }
  val dueItems = remember(items, now) { MistakeSrsEngine.dueMistakes(items, now = now) }
  val filterOptions = remember(items) { buildMistakeBookFilterOptions(items) }
  val activeDraft = remember(drafts, activeDraftId) {
    drafts.firstOrNull { draft -> draft.id == activeDraftId } ?: drafts.firstOrNull()
  }
  val activeReviewItem = remember(items, dueItems, activeReviewId) {
    items.firstOrNull { item -> item.id == activeReviewId }
      ?: dueItems.firstOrNull()
      ?: items.firstOrNull { item -> item.status == MistakeStatus.DUE }
  }
  val filteredItems = remember(items, selectedTab, searchQuery, selectedFilters, now) {
    filterMistakeBookItems(items, selectedTab, searchQuery, now = now, filters = selectedFilters)
  }

  LaunchedEffect(activeDraftId) {
    if (!activeDraftId.isNullOrBlank()) {
      selectedTab = MistakeBookTab.DRAFTS
    }
  }
  LaunchedEffect(isDueReviewMode, activeReviewId) {
    if (isDueReviewMode || !activeReviewId.isNullOrBlank()) {
      selectedTab = MistakeBookTab.DUE
    }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("mistake-book-list"),
    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item(key = "actions", contentType = "actions") {
      MistakeBookActionStrip(
        stats = stats,
        onCameraCapture = onCameraCapture,
        onGalleryPick = onGalleryPick,
        onOpenDueQueue = onOpenDueQueue,
        onRefreshReminder = onRefreshReminder,
        onExport = onExport,
        onImport = onImport,
        onAnalyzeWithAi = onAnalyzeWithAi
      )
    }

    item(key = "tabs", contentType = "tabs") {
      MistakeBookTabs(
        selectedTab = selectedTab,
        onSelectTab = { tab -> selectedTab = tab },
        stats = stats
      )
    }

    item(key = "search", contentType = "search") {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        label = { Text(text = "搜索题干 / 知识点 / 错因") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
      )
    }

    item(key = "filters", contentType = "filters") {
      MistakeBookFilterBar(
        options = filterOptions,
        filters = selectedFilters,
        onFiltersChange = { filters -> selectedFilters = filters }
      )
    }

    when (selectedTab) {
      MistakeBookTab.STATS -> item(key = "stats-panel", contentType = "stats") {
        MistakeStatsPanel(
          stats = stats,
          items = items,
          analysis = mistakeAiAnalysis
        )
      }
      MistakeBookTab.DRAFTS -> {
        if (activeDraft != null) {
          item(key = "active-draft-${activeDraft.id}", contentType = "draft-editor") {
            MistakeDraftEditor(draft = activeDraft, onConfirmDraft = onConfirmDraft)
          }
        }
        val remainingDrafts = drafts.filterNot { draft -> draft.id == activeDraft?.id }
        items(
          items = remainingDrafts,
          key = { draft -> "draft-${draft.id}" },
          contentType = { "draft-summary" }
        ) { draft ->
          MistakeDraftSummary(draft = draft, onEdit = { onConfirmDraft(draft) })
        }
        val draftItems = filteredItems.filter { item -> item.status == MistakeStatus.DRAFT }
        if (activeDraft == null && draftItems.isEmpty()) {
          item(key = "drafts-empty", contentType = "empty") {
            MistakeEmptyState(text = "暂无待完善草稿。")
          }
        }
        items(
          items = draftItems,
          key = { item -> item.id },
          contentType = { "mistake-item" }
        ) { item ->
          MistakeItemCard(
            item = item,
            onAddToAnki = onAddToAnki,
            onDeleteItem = onDeleteItem,
            onReopenItem = onReopenItem
          )
        }
      }

      else -> {
        if (selectedTab == MistakeBookTab.DUE && activeReviewItem != null && activeReviewItem.isReadyForReview) {
          item(key = "review-${activeReviewItem.id}", contentType = "review") {
            MistakeReviewCard(
              item = activeReviewItem,
              suggestion = activeReviewSuggestion?.takeIf { suggestion -> suggestion.itemId == activeReviewItem.id },
              onRecordReview = onRecordReview,
              onRequestJudgement = onRequestJudgement,
              onCameraAnswerCapture = onCameraAnswerCapture,
              onGalleryAnswerPick = onGalleryAnswerPick,
              onConfirmJudgement = onConfirmJudgement,
              onAddToAnki = onAddToAnki
            )
          }
        }
        if (filteredItems.isEmpty()) {
          item(key = "items-empty-${selectedTab.name}", contentType = "empty") {
            MistakeEmptyState(
              text = when (selectedTab) {
                MistakeBookTab.DUE -> "当前暂无到期错题。"
                MistakeBookTab.COMPLETED -> "还没有已完成错题。"
                else -> "暂无匹配错题。"
              }
            )
          }
        } else {
          items(
            items = filteredItems,
            key = { item -> item.id },
            contentType = { "mistake-item" }
          ) { item ->
            MistakeItemCard(
              item = item,
              onAddToAnki = onAddToAnki,
              onDeleteItem = onDeleteItem,
              onReopenItem = onReopenItem
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MistakeBookFilterBar(
  options: MistakeBookFilterOptions,
  filters: MistakeBookFilters,
  onFiltersChange: (MistakeBookFilters) -> Unit
) {
  if (
    options.subjects.isEmpty() &&
    options.knowledgeTags.isEmpty() &&
    options.mistakeTypes.isEmpty() &&
    !filters.hasAny
  ) {
    return
  }

  Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
    if (options.subjects.isNotEmpty()) {
      MistakeStringFilterChipRow(
        title = "科目",
        values = options.subjects,
        selected = filters.subject,
        onSelect = { subject ->
          onFiltersChange(filters.copy(subject = subject))
        }
      )
    }
    if (options.knowledgeTags.isNotEmpty()) {
      MistakeStringFilterChipRow(
        title = "知识点",
        values = options.knowledgeTags,
        selected = filters.knowledgeTag,
        onSelect = { tag ->
          onFiltersChange(filters.copy(knowledgeTag = tag))
        }
      )
    }
    if (options.mistakeTypes.isNotEmpty()) {
      MistakeTypeFilterChipRow(
        title = "错误类型",
        values = options.mistakeTypes,
        selected = filters.mistakeType,
        onSelect = { type ->
          onFiltersChange(filters.copy(mistakeType = type))
        }
      )
    }
  }
}

@Composable
private fun MistakeStringFilterChipRow(
  title: String,
  values: List<String>,
  selected: String,
  onSelect: (String) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelSmall,
      color = Color(0xFF627770)
    )
    MistakeTypeChip(
      label = "全部",
      selected = selected.isBlank(),
      onClick = { onSelect("") }
    )
    values.forEach { value ->
      MistakeTypeChip(
        label = value,
        selected = selected == value,
        onClick = { onSelect(value) }
      )
    }
  }
}

@Composable
private fun MistakeTypeFilterChipRow(
  title: String,
  values: List<MistakeType>,
  selected: MistakeType?,
  onSelect: (MistakeType?) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelSmall,
      color = Color(0xFF627770)
    )
    MistakeTypeChip(
      label = "全部",
      selected = selected == null,
      onClick = { onSelect(null) }
    )
    values.forEach { value ->
      MistakeTypeChip(
        label = mistakeTypeLabel(value),
        selected = selected == value,
        onClick = { onSelect(value) }
      )
    }
  }
}

@Composable
private fun MistakeBookActionStrip(
  stats: MistakeBookStats,
  onCameraCapture: () -> Unit,
  onGalleryPick: () -> Unit,
  onOpenDueQueue: () -> Unit,
  onRefreshReminder: () -> Unit,
  onExport: () -> Unit,
  onImport: () -> Unit,
  onAnalyzeWithAi: () -> Unit
) {
  Surface(
    color = Color(0xFFFBFEFC),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x173B5D52), RoundedCornerShape(14.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = "今日待复习 ${stats.dueCount} 题",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFF285C4E)
          )
          Text(
            text = "总 ${stats.totalCount} · 待完善 ${stats.draftCount} · 已完成 ${stats.completedCount}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF667B74)
          )
        }
        Text(
          text = stats.recentAccuracyPercent?.let { accuracy -> "近况 $accuracy%" } ?: "暂无复习",
          style = MaterialTheme.typography.labelMedium,
          color = Color(0xFF2E6E5A)
        )
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Button(onClick = onCameraCapture) {
          Text(text = "拍照录入")
        }
        OutlinedButton(onClick = onGalleryPick) {
          Text(text = "相册录入")
        }
        OutlinedButton(onClick = onOpenDueQueue) {
          Text(text = "今日复习")
        }
        OutlinedButton(onClick = onImport) {
          Text(text = "导入")
        }
        OutlinedButton(onClick = onExport) {
          Text(text = "导出")
        }
        OutlinedButton(onClick = onAnalyzeWithAi) {
          Text(text = "AI分析")
        }
        TextButton(onClick = onRefreshReminder) {
          Text(text = "更新提醒", color = Color(0xFF2D6F5D))
        }
      }
    }
  }
}

@Composable
private fun MistakeBookTabs(
  selectedTab: MistakeBookTab,
  onSelectTab: (MistakeBookTab) -> Unit,
  stats: MistakeBookStats
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    MistakeBookTab.values().forEach { tab ->
      val selected = tab == selectedTab
      val count = when (tab) {
        MistakeBookTab.DUE -> stats.dueCount
        MistakeBookTab.ALL -> stats.totalCount
        MistakeBookTab.DRAFTS -> stats.draftCount
        MistakeBookTab.COMPLETED -> stats.completedCount
        MistakeBookTab.STATS -> stats.reviewAttemptCount
      }
      Surface(
        color = if (selected) Color(0xFFE4F2EC) else Color(0xFFFBFEFC),
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
          .border(1.dp, if (selected) Color(0xFF72A18F) else Color(0x183B5D52), RoundedCornerShape(999.dp))
          .clickable { onSelectTab(tab) }
      ) {
        Text(
          text = "${tab.label} $count",
          style = MaterialTheme.typography.labelMedium,
          color = if (selected) Color(0xFF2C6756) else Color(0xFF5F746D),
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
      }
    }
  }
}

@Composable
private fun MistakeDraftEditor(
  draft: MistakeRecognitionDraft,
  onConfirmDraft: (MistakeRecognitionDraft) -> Unit
) {
  var question by remember(draft.id, draft.updatedAt) { mutableStateOf(draft.question.ifBlank { draft.ocrText }) }
  var subject by remember(draft.id, draft.updatedAt) { mutableStateOf(draft.subject) }
  var questionType by remember(draft.id, draft.updatedAt) { mutableStateOf(draft.questionType) }
  var tagsText by remember(draft.id, draft.updatedAt) { mutableStateOf(draft.knowledgeTags.joinToString(separator = "，")) }
  var studentAnswer by remember(draft.id, draft.updatedAt) { mutableStateOf(draft.studentAnswer) }
  var correctAnswer by remember(draft.id, draft.updatedAt) { mutableStateOf(draft.correctAnswer) }
  var explanation by remember(draft.id, draft.updatedAt) { mutableStateOf(draft.explanation) }
  var mistakeReason by remember(draft.id, draft.updatedAt) { mutableStateOf(draft.mistakeReason) }
  var selectedMistakeType by remember(draft.id, draft.updatedAt) { mutableStateOf(draft.mistakeType) }

  Surface(
    color = Color(0xFFFFFCF4),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x26A98039), RoundedCornerShape(14.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(
        text = "识别草稿 · ${mistakeRecognitionStatusLabel(draft.status)}",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF6B5C2E)
      )
      MistakeImagePreview(imageRefs = draft.imageRefs)
      OutlinedTextField(
        value = question,
        onValueChange = { question = it },
        label = { Text(text = "题干") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth()
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = subject,
          onValueChange = { subject = it },
          label = { Text(text = "科目") },
          singleLine = true,
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = questionType,
          onValueChange = { questionType = it },
          label = { Text(text = "题型") },
          singleLine = true,
          modifier = Modifier.weight(1f)
        )
      }
      OutlinedTextField(
        value = tagsText,
        onValueChange = { tagsText = it },
        label = { Text(text = "知识点标签") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = studentAnswer,
        onValueChange = { studentAnswer = it },
        label = { Text(text = "当时作答") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = correctAnswer,
        onValueChange = { correctAnswer = it },
        label = { Text(text = "正确答案") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = explanation,
        onValueChange = { explanation = it },
        label = { Text(text = "解析") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = mistakeReason,
        onValueChange = { mistakeReason = it },
        label = { Text(text = "错因") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
      )
      MistakeTypeSelector(
        selected = selectedMistakeType,
        onSelect = { selectedMistakeType = it }
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = if (question.isBlank() || correctAnswer.isBlank()) "题干和正确答案补齐后进入复习队列" else "可进入复习队列",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF6A7D75),
          modifier = Modifier.weight(1f)
        )
        Button(
          modifier = Modifier.testTag("confirm-draft-button"),
          onClick = {
            onConfirmDraft(
              draft.copy(
                question = question,
                subject = subject,
                questionType = questionType,
                knowledgeTags = parseMistakeTags(tagsText),
                studentAnswer = studentAnswer,
                correctAnswer = correctAnswer,
                explanation = explanation,
                mistakeReason = mistakeReason,
                mistakeType = selectedMistakeType,
                status = MistakeRecognitionStatus.MANUAL
              )
            )
          }
        ) {
          Text(text = "保存错题")
        }
      }
    }
  }
}

@Composable
private fun MistakeDraftSummary(
  draft: MistakeRecognitionDraft,
  onEdit: () -> Unit
) {
  Surface(
    color = Color(0xFFFBFEFC),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x173B5D52), RoundedCornerShape(12.dp))
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = draft.question.ifBlank { draft.ocrText.ifBlank { "未识别出题干" } },
          style = MaterialTheme.typography.labelMedium,
          color = Color(0xFF385A50),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = "草稿 · ${mistakeRecognitionStatusLabel(draft.status)} · ${formatSessionTime(draft.updatedAt)}",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF6A7D75)
        )
      }
      TextButton(onClick = onEdit) {
        Text(text = "保存为错题", color = Color(0xFF2D6F5D))
      }
    }
  }
}

@Composable
private fun MistakeReviewCard(
  item: MistakeBookItem,
  suggestion: MistakeReviewSuggestion?,
  onRecordReview: (itemId: String, isCorrect: Boolean, userAnswer: String) -> Unit,
  onRequestJudgement: (itemId: String, userAnswer: String) -> Unit,
  onCameraAnswerCapture: (itemId: String, userAnswer: String) -> Unit,
  onGalleryAnswerPick: (itemId: String, userAnswer: String) -> Unit,
  onConfirmJudgement: (itemId: String, isCorrect: Boolean) -> Unit,
  onAddToAnki: (String) -> Unit
) {
  var showAnswer by remember(item.id) { mutableStateOf(false) }
  var userAnswer by remember(item.id) { mutableStateOf("") }

  Surface(
    color = Color(0xFFF2FAF6),
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.5.dp, Color(0xFF78A892), RoundedCornerShape(16.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
      Text(
        text = "复习卡 · ${item.reviewState.reviewCount + 1} 次",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF2C6756)
      )
      MistakeImagePreview(imageRefs = item.imageRefs)
      Text(
        text = item.question,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF254F43)
      )
      MistakeMetaLine(item = item)
      OutlinedTextField(
        value = userAnswer,
        onValueChange = { userAnswer = it },
        label = { Text(text = "本次作答") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
      )
      if (showAnswer) {
        MistakeAnswerBlock(item = item)
      }
      if (suggestion != null) {
        MistakeReviewSuggestionPanel(
          suggestion = suggestion,
          onConfirmCorrect = { onConfirmJudgement(item.id, true) },
          onConfirmWrong = { onConfirmJudgement(item.id, false) }
        )
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .testTag("mistake-review-actions"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        OutlinedButton(onClick = { showAnswer = !showAnswer }) {
          Text(text = if (showAnswer) "隐藏答案" else "显示答案")
        }
        OutlinedButton(onClick = { onAddToAnki(item.id) }) {
          Text(text = "生成Anki")
        }
        OutlinedButton(onClick = { onRequestJudgement(item.id, userAnswer) }) {
          Text(text = "模型判题")
        }
        OutlinedButton(onClick = { onCameraAnswerCapture(item.id, userAnswer) }) {
          Text(text = "拍照判题")
        }
        OutlinedButton(onClick = { onGalleryAnswerPick(item.id, userAnswer) }) {
          Text(text = "相册判题")
        }
        OutlinedButton(onClick = { onRecordReview(item.id, false, userAnswer) }) {
          Text(text = "做错")
        }
        Button(
          modifier = Modifier.testTag("mistake-review-correct-button"),
          onClick = { onRecordReview(item.id, true, userAnswer) }
        ) {
          Text(text = "做对")
        }
      }
    }
  }
}

@Composable
private fun MistakeReviewSuggestionPanel(
  suggestion: MistakeReviewSuggestion,
  onConfirmCorrect: () -> Unit,
  onConfirmWrong: () -> Unit
) {
  Surface(
    color = Color(0xFFFFFCF4),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x26A98039), RoundedCornerShape(12.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Text(
        text = "模型建议：${if (suggestion.isCorrect) "做对" else "做错"} · ${(suggestion.confidence.coerceIn(0.0, 1.0) * 100).toInt()}%",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF6B5C2E)
      )
      if (suggestion.reason.isNotBlank()) {
        Text(
          text = suggestion.reason,
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF5D6D66)
        )
      }
      if (suggestion.answerOcrText.isNotBlank()) {
        Text(
          text = "图片识别：${suggestion.answerOcrText.take(80)}",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF6A7D75),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
      ) {
        TextButton(onClick = onConfirmWrong) {
          Text(text = "确认做错", color = Color(0xFF8E4D4D))
        }
        Button(onClick = onConfirmCorrect) {
          Text(text = "确认做对")
        }
      }
    }
  }
}

@Composable
private fun MistakeItemCard(
  item: MistakeBookItem,
  onAddToAnki: (String) -> Unit,
  onDeleteItem: (String) -> Unit,
  onReopenItem: (String) -> Unit
) {
  var expanded by remember(item.id) { mutableStateOf(false) }
  Surface(
    color = if (item.status == MistakeStatus.COMPLETED) Color(0xFFF7FBF8) else Color(0xFFFBFEFC),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x173B5D52), RoundedCornerShape(14.dp))
      .clickable { expanded = !expanded }
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
      MistakeImagePreview(imageRefs = item.imageRefs)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = mistakeStatusLabel(item.status),
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF2D6F5D)
        )
        Text(
          text = item.reviewState.nextReviewAt?.let { next -> "下次 ${formatSessionTime(next)}" } ?: "无待复习提醒",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF6B8079)
        )
      }
      Text(
        text = item.question.ifBlank { "未填写题干" },
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF295C4D),
        maxLines = if (expanded) 6 else 2,
        overflow = TextOverflow.Ellipsis
      )
      MistakeMetaLine(item = item)
      if (item.mistakeReason.isNotBlank()) {
        Text(
          text = "错因：${item.mistakeReason}",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF536760),
          maxLines = if (expanded) 4 else 2,
          overflow = TextOverflow.Ellipsis
        )
      }
      if (expanded) {
        MistakeAnswerBlock(item = item)
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
      ) {
        if (item.status == MistakeStatus.COMPLETED) {
          TextButton(onClick = { onReopenItem(item.id) }) {
            Text(text = "重新复习", color = Color(0xFF2D6F5D))
          }
        }
        if (item.isReadyForReview) {
          TextButton(onClick = { onAddToAnki(item.id) }) {
            Text(text = "生成Anki", color = Color(0xFF2D6F5D))
          }
        }
        TextButton(onClick = { onDeleteItem(item.id) }) {
          Text(text = "删除", color = Color(0xFF8E4D4D))
        }
      }
    }
  }
}

@Composable
private fun MistakeMetaLine(item: MistakeBookItem) {
  val labels = buildList {
    if (item.subject.isNotBlank()) add(item.subject)
    if (item.questionType.isNotBlank()) add(item.questionType)
    item.mistakeType?.let { type -> add(mistakeTypeLabel(type)) }
    addAll(item.knowledgeTags.take(3))
  }
  Text(
    text = labels.joinToString(separator = " · ").ifBlank { "未设置标签" } +
      " · 复习 ${item.reviewState.reviewCount} 次 · 连对 ${item.reviewState.correctStreak}",
    style = MaterialTheme.typography.labelSmall,
    color = Color(0xFF60756E),
    maxLines = 2,
    overflow = TextOverflow.Ellipsis
  )
}

@Composable
private fun MistakeAnswerBlock(item: MistakeBookItem) {
  Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
    if (item.studentAnswer.isNotBlank()) {
      Text(
        text = "原作答：${item.studentAnswer}",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF5D7069)
      )
    }
    Text(
      text = "正确答案：${item.correctAnswer.ifBlank { "未填写" }}",
      style = MaterialTheme.typography.bodySmall,
      color = Color(0xFF385A50)
    )
    if (item.explanation.isNotBlank()) {
      MarkdownBodyText(markdown = item.explanation, modifier = Modifier.fillMaxWidth())
    }
  }
}

@Composable
private fun MistakeStatsPanel(
  stats: MistakeBookStats,
  items: List<MistakeBookItem>,
  analysis: MistakeBookAiAnalysis?
) {
  Surface(
    color = Color(0xFFFBFEFC),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x173B5D52), RoundedCornerShape(14.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
      Text(
        text = "复习统计",
        style = MaterialTheme.typography.titleSmall,
        color = Color(0xFF285C4E)
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        MistakeStatCell(label = "总错题", value = stats.totalCount.toString(), modifier = Modifier.weight(1f))
        MistakeStatCell(label = "待复习", value = stats.dueCount.toString(), modifier = Modifier.weight(1f))
        MistakeStatCell(label = "已完成", value = stats.completedCount.toString(), modifier = Modifier.weight(1f))
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        MistakeStatCell(label = "复习记录", value = stats.reviewAttemptCount.toString(), modifier = Modifier.weight(1f))
        MistakeStatCell(
          label = "近期正确率",
          value = stats.recentAccuracyPercent?.let { "$it%" } ?: "--",
          modifier = Modifier.weight(1f)
        )
        MistakeStatCell(label = "待完善", value = stats.draftCount.toString(), modifier = Modifier.weight(1f))
      }
      Text(
        text = "薄弱聚焦：${stats.weakLabels.take(5).joinToString(separator = " · ").ifBlank { "暂无集中标签" }}",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF536760)
      )
      MistakeAiAnalysisPanel(analysis = analysis)
      val latestAttempts = items.flatMap { item -> item.reviewAttempts.map { attempt -> item to attempt } }
        .sortedByDescending { pair -> pair.second.reviewedAt }
        .take(5)
      if (latestAttempts.isNotEmpty()) {
        latestAttempts.forEach { (item, attempt) ->
          Text(
            text = "${formatSessionTime(attempt.reviewedAt)} · ${if (attempt.isCorrect) "做对" else "做错"} · ${item.question.take(24)}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF617771),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}

@Composable
private fun MistakeAiAnalysisPanel(analysis: MistakeBookAiAnalysis?) {
  Surface(
    color = Color(0xFFF4F8FF),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x1A567AC8), RoundedCornerShape(12.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Text(
        text = "AI 学情分析",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF37599A)
      )
      if (analysis == null) {
        Text(
          text = "还没有生成分析。导入错题本后可直接让 AI 总结薄弱点、安排复习节奏和今日行动。",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF5C6B86)
        )
        return@Column
      }

      Text(
        text = analysis.summary,
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF3C4E71)
      )
      MistakeAiAnalysisSection(title = "薄弱点", items = analysis.weaknesses)
      MistakeAiAnalysisSection(title = "学习计划", items = analysis.plan)
      MistakeAiAnalysisSection(title = "下一步", items = analysis.nextActions)
    }
  }
}

@Composable
private fun MistakeAiAnalysisSection(
  title: String,
  items: List<String>
) {
  if (items.isEmpty()) {
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelSmall,
      color = Color(0xFF5B6F9B)
    )
    items.forEach { item ->
      Text(
        text = "• $item",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF425578)
      )
    }
  }
}

@Composable
private fun MistakeStatCell(
  label: String,
  value: String,
  modifier: Modifier = Modifier
) {
  Surface(
    color = Color(0xFFF2F7F3),
    shape = RoundedCornerShape(12.dp),
    modifier = modifier
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      Text(text = value, style = MaterialTheme.typography.titleMedium, color = Color(0xFF2C6756))
      Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF60756E))
    }
  }
}

@Composable
private fun MistakeTypeSelector(
  selected: MistakeType?,
  onSelect: (MistakeType?) -> Unit
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = "错误类型",
      style = MaterialTheme.typography.labelMedium,
      color = Color(0xFF5A6E67)
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      MistakeTypeChip(
        label = "未设置",
        selected = selected == null,
        onClick = { onSelect(null) }
      )
      MistakeType.values().forEach { type ->
        MistakeTypeChip(
          label = mistakeTypeLabel(type),
          selected = selected == type,
          onClick = { onSelect(type) }
        )
      }
    }
  }
}

@Composable
private fun MistakeTypeChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit
) {
  Surface(
    color = if (selected) Color(0xFFE4F2EC) else Color(0xFFFBFEFC),
    shape = RoundedCornerShape(999.dp),
    modifier = Modifier
      .border(1.dp, if (selected) Color(0xFF72A18F) else Color(0x183B5D52), RoundedCornerShape(999.dp))
      .clickable(onClick = onClick)
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = if (selected) Color(0xFF2C6756) else Color(0xFF5F746D),
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
    )
  }
}

@Composable
private fun MistakeImagePreview(imageRefs: List<String>) {
  val context = LocalContext.current
  val bitmaps by produceState(initialValue = emptyList(), context, imageRefs) {
    value = withContext(Dispatchers.Default) {
      loadMistakeBitmaps(context, imageRefs)
    }
  }
  if (bitmaps.isEmpty()) {
    return
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    bitmaps.forEachIndexed { index, bitmap ->
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "错题图片 ${index + 1}",
        modifier = Modifier
          .size(if (bitmaps.size == 1) 116.dp else 82.dp)
          .clip(RoundedCornerShape(10.dp)),
        contentScale = ContentScale.Crop
      )
    }
  }
}

@Composable
private fun MistakeEmptyState(text: String) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 120.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodySmall,
      color = Color(0xFF5D7069)
    )
  }
}

private fun loadMistakeBitmaps(context: Context, imageRefs: List<String>) =
  imageRefs.mapNotNull { ref ->
    val file = File(context.filesDir, ref)
    BitmapFactory.decodeFile(file.absolutePath)
  }

private fun parseMistakeTags(raw: String): List<String> {
  return raw
    .split(',', '，', ';', '；', '\n')
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .take(12)
}

private fun mistakeRecognitionStatusLabel(status: MistakeRecognitionStatus): String {
  return when (status) {
    MistakeRecognitionStatus.PENDING -> "识别中"
    MistakeRecognitionStatus.OCR_READY -> "OCR草稿"
    MistakeRecognitionStatus.AI_READY -> "模型已校对"
    MistakeRecognitionStatus.FAILED -> "识别失败"
    MistakeRecognitionStatus.MANUAL -> "手动修正"
  }
}
