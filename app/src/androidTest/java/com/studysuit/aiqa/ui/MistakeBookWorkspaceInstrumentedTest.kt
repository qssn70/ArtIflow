package com.studysuit.aiqa.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.studysuit.aiqa.data.MistakeBookItem
import com.studysuit.aiqa.data.MistakeRecognitionDraft
import com.studysuit.aiqa.data.MistakeRecognitionStatus
import com.studysuit.aiqa.data.MistakeType
import com.studysuit.aiqa.ui.theme.StudySuitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MistakeBookWorkspaceInstrumentedTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun captureActionsOpenCameraAndGalleryEntrypoints() {
    var cameraTapped = false
    var galleryTapped = false

    setMistakeWorkspace(
      items = emptyList(),
      onCameraCapture = { cameraTapped = true },
      onGalleryPick = { galleryTapped = true }
    )

    composeRule.onNodeWithText("拍照录入").performClick()
    composeRule.onNodeWithText("相册录入").performClick()

    composeRule.runOnIdle {
      assertTrue(cameraTapped)
      assertTrue(galleryTapped)
    }
  }

  @Test
  fun draftEditorSavesEditableRecognizedMistake() {
    var savedDraft: MistakeRecognitionDraft? = null
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      ocrText = "OCR 题干",
      question = "已知 x+1=3，求 x。",
      subject = "数学",
      knowledgeTags = listOf("一元一次方程"),
      correctAnswer = "2",
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )

    setMistakeWorkspace(
      drafts = listOf(draft),
      activeDraftId = "draft-1",
      onConfirmDraft = { savedDraft = it }
    )

    composeRule.onNodeWithText("保存错题").performScrollTo().performClick()

    composeRule.runOnIdle {
      assertEquals("draft-1", savedDraft?.id)
      assertEquals("已知 x+1=3，求 x。", savedDraft?.question)
      assertEquals("2", savedDraft?.correctAnswer)
    }
  }

  @Test
  fun searchFiltersMistakeCardsInTheList() {
    val functionItem = mistakeItem(
      id = "mistake-1",
      question = "二次函数顶点式怎么用？",
      subject = "数学",
      knowledgeTags = listOf("函数")
    )
    val physicsItem = mistakeItem(
      id = "mistake-2",
      question = "牛顿第二定律受力分析",
      subject = "物理",
      knowledgeTags = listOf("力学")
    )

    composeRule.setContent {
      StudySuitTheme {
        var searchQuery by remember { mutableStateOf("") }
        MistakeBookWorkspace(
          items = listOf(functionItem, physicsItem),
          drafts = emptyList(),
          activeDraftId = null,
          activeReviewId = null,
          activeReviewSuggestion = null,
          mistakeAiAnalysis = null,
          isDueReviewMode = false,
          searchQuery = searchQuery,
          onSearchQueryChange = { searchQuery = it },
          onCameraCapture = {},
          onGalleryPick = {},
          onOpenDueQueue = {},
          onRefreshReminder = {},
          onExport = {},
          onImport = {},
          onAnalyzeWithAi = {},
          onConfirmDraft = {},
          onRecordReview = { _, _, _ -> },
          onRequestJudgement = { _, _ -> },
          onCameraAnswerCapture = { _, _ -> },
          onGalleryAnswerPick = { _, _ -> },
          onConfirmJudgement = { _, _ -> },
          onAddToAnki = {},
          onDeleteItem = {},
          onReopenItem = {}
        )
      }
    }

    composeRule.onNodeWithText("搜索题干 / 知识点 / 错因").performTextInput("函数")

    composeRule.onNodeWithText("二次函数顶点式怎么用？").assertIsDisplayed()
    composeRule.onAllNodesWithText("牛顿第二定律受力分析").assertCountEquals(0)
  }

  @Test
  fun reviewCardHidesAnswerUntilUserExpandsIt() {
    val item = mistakeItem(
      id = "mistake-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "2"
    )

    setMistakeWorkspace(items = listOf(item), activeReviewId = item.id, isDueReviewMode = true)

    composeRule.onAllNodesWithText("正确答案：2").assertCountEquals(0)
    composeRule.onNodeWithText("显示答案").performScrollTo().performClick()
    composeRule.onNodeWithText("正确答案：2").assertIsDisplayed()
  }

  @Test
  fun reviewCardRecordsUserCorrectnessWithTypedAnswer() {
    var recorded: Triple<String, Boolean, String>? = null
    val item = mistakeItem(
      id = "mistake-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "2"
    )

    setMistakeWorkspace(
      items = listOf(item),
      activeReviewId = item.id,
      isDueReviewMode = true,
      onRecordReview = { itemId, isCorrect, userAnswer ->
        recorded = Triple(itemId, isCorrect, userAnswer)
      }
    )

    composeRule.onNodeWithText("本次作答").performTextInput("2")
    composeRule.onNodeWithText("做对").performScrollTo().performClick()

    composeRule.runOnIdle {
      assertEquals(Triple("mistake-1", true, "2"), recorded)
    }
  }

  private fun setMistakeWorkspace(
    items: List<MistakeBookItem> = emptyList(),
    drafts: List<MistakeRecognitionDraft> = emptyList(),
    activeDraftId: String? = null,
    activeReviewId: String? = null,
    activeReviewSuggestion: MistakeReviewSuggestion? = null,
    isDueReviewMode: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onCameraCapture: () -> Unit = {},
    onGalleryPick: () -> Unit = {},
    onOpenDueQueue: () -> Unit = {},
    onRefreshReminder: () -> Unit = {},
    onConfirmDraft: (MistakeRecognitionDraft) -> Unit = {},
    onRecordReview: (itemId: String, isCorrect: Boolean, userAnswer: String) -> Unit = { _, _, _ -> },
    onRequestJudgement: (itemId: String, userAnswer: String) -> Unit = { _, _ -> },
    onCameraAnswerCapture: (itemId: String, userAnswer: String) -> Unit = { _, _ -> },
    onGalleryAnswerPick: (itemId: String, userAnswer: String) -> Unit = { _, _ -> },
    onConfirmJudgement: (itemId: String, isCorrect: Boolean) -> Unit = { _, _ -> },
    onAddToAnki: (String) -> Unit = {},
    onDeleteItem: (String) -> Unit = {},
    onReopenItem: (String) -> Unit = {}
  ) {
    composeRule.setContent {
      StudySuitTheme {
        MistakeBookWorkspace(
          items = items,
          drafts = drafts,
          activeDraftId = activeDraftId,
          activeReviewId = activeReviewId,
          activeReviewSuggestion = activeReviewSuggestion,
          mistakeAiAnalysis = null,
          isDueReviewMode = isDueReviewMode,
          searchQuery = searchQuery,
          onSearchQueryChange = onSearchQueryChange,
          onCameraCapture = onCameraCapture,
          onGalleryPick = onGalleryPick,
          onOpenDueQueue = onOpenDueQueue,
          onRefreshReminder = onRefreshReminder,
          onExport = {},
          onImport = {},
          onAnalyzeWithAi = {},
          onConfirmDraft = onConfirmDraft,
          onRecordReview = onRecordReview,
          onRequestJudgement = onRequestJudgement,
          onCameraAnswerCapture = onCameraAnswerCapture,
          onGalleryAnswerPick = onGalleryAnswerPick,
          onConfirmJudgement = onConfirmJudgement,
          onAddToAnki = onAddToAnki,
          onDeleteItem = onDeleteItem,
          onReopenItem = onReopenItem
        )
      }
    }
  }

  private fun mistakeItem(
    id: String,
    question: String,
    correctAnswer: String = "标准答案",
    subject: String = "数学",
    knowledgeTags: List<String> = listOf("函数"),
    mistakeType: MistakeType = MistakeType.CONCEPT_ERROR
  ): MistakeBookItem {
    return MistakeBookItem.create(
      id = id,
      question = question,
      correctAnswer = correctAnswer,
      subject = subject,
      knowledgeTags = knowledgeTags,
      mistakeType = mistakeType,
      createdAt = 1L
    )
  }
}
