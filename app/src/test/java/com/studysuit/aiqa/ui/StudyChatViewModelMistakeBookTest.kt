package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.MistakeRecognitionDraft
import com.studysuit.aiqa.data.MistakeRecognitionStatus
import com.studysuit.aiqa.data.MistakeAnswerJudgement
import com.studysuit.aiqa.data.MistakeBookItem
import com.studysuit.aiqa.data.MistakeBookRepository
import com.studysuit.aiqa.data.MistakeBookRepositorySelection
import com.studysuit.aiqa.data.MistakeBookStorageLocation
import com.studysuit.aiqa.data.MistakeReviewJudgementSource
import com.studysuit.aiqa.data.MistakeStatus
import com.studysuit.aiqa.data.MistakeType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class StudyChatViewModelMistakeBookTest {

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun defaultObsidianWithoutVaultPersistsToPendingRepositoryAndPrompts() {
    val pendingRepository = RecordingMistakeBookRepository()
    val viewModel = StudyChatViewModel(
      mistakeBookRepositorySelector = { settings ->
        MistakeBookRepositorySelection(
          repository = pendingRepository,
          key = "pending",
          isPendingObsidianAuthorization = settings.mistakeBookStorageLocation == MistakeBookStorageLocation.OBSIDIAN &&
            settings.obsidianVaultTreeUri.isBlank()
        )
      }
    )

    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-1",
        question = "默认 Obsidian 未授权",
        correctAnswer = "先暂存",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )

    assertTrue(pendingRepository.awaitSave())
    assertEquals(1, pendingRepository.savedItems.last().size)
    assertEquals("请在设置中选择 Obsidian 仓库以同步", viewModel.uiState.value.toastMessage)
  }

  @Test
  fun recognizeMistakeFromImagesStopsWhenImagePersistenceFails() {
    val failingRepository = RecordingMistakeBookRepository().apply {
      imageSaveFailure = IllegalStateException("vault full")
    }
    val viewModel = StudyChatViewModel(
      mistakeBookRepositorySelector = {
        MistakeBookRepositorySelection(
          repository = failingRepository,
          key = "obsidian",
          isPendingObsidianAuthorization = false
        )
      }
    )

    viewModel.recognizeMistakeFromImages(listOf(byteArrayOf(1, 2, 3)))

    assertEquals("错题图片保存失败：vault full", viewModel.uiState.value.toastMessage)
    assertTrue(viewModel.uiState.value.mistakeRecognitionDrafts.isEmpty())
  }

  @Test
  fun saveSettingsSyncsPendingMistakesAfterObsidianVaultAuthorized() {
    val pendingRepository = RecordingMistakeBookRepository()
    val obsidianRepository = RecordingMistakeBookRepository()
    val viewModel = StudyChatViewModel(
      mistakeBookRepositorySelector = { settings ->
        if (settings.obsidianVaultTreeUri.isBlank()) {
          MistakeBookRepositorySelection(
            repository = pendingRepository,
            key = "pending",
            isPendingObsidianAuthorization = true
          )
        } else {
          MistakeBookRepositorySelection(
            repository = obsidianRepository,
            key = "obsidian:${settings.obsidianVaultTreeUri}",
            isPendingObsidianAuthorization = false
          )
        }
      }
    )
    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-1",
        question = "需要同步的错题",
        correctAnswer = "授权后写入 Obsidian",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )
    assertTrue(pendingRepository.awaitSave())

    viewModel.openSettings()
    viewModel.setObsidianVaultTreeUri("content://vault")
    viewModel.saveSettings(migrateMistakeBook = true)

    assertTrue(obsidianRepository.awaitSave())
    assertEquals(listOf("需要同步的错题"), obsidianRepository.savedItems.last().map { it.question })
    assertEquals("Obsidian 仓库已授权并同步错题", viewModel.uiState.value.toastMessage)
  }

  @Test
  fun saveSettingsDoesNotSyncPendingMistakesWhenObsidianVaultAuthorizedWithoutMigration() {
    val pendingRepository = RecordingMistakeBookRepository()
    val obsidianRepository = RecordingMistakeBookRepository()
    val viewModel = StudyChatViewModel(
      mistakeBookRepositorySelector = { settings ->
        if (settings.obsidianVaultTreeUri.isBlank()) {
          MistakeBookRepositorySelection(
            repository = pendingRepository,
            key = "pending",
            isPendingObsidianAuthorization = true
          )
        } else {
          MistakeBookRepositorySelection(
            repository = obsidianRepository,
            key = "obsidian:${settings.obsidianVaultTreeUri}",
            isPendingObsidianAuthorization = false
          )
        }
      }
    )
    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-1",
        question = "仅保留在暂存区的错题",
        correctAnswer = "不自动迁移",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )
    assertTrue(pendingRepository.awaitSave())

    viewModel.openSettings()
    viewModel.setObsidianVaultTreeUri("content://vault")
    viewModel.saveSettings(migrateMistakeBook = false)

    assertTrue(obsidianRepository.savedItems.isEmpty())
    assertEquals(MistakeBookStorageLocation.OBSIDIAN, viewModel.uiState.value.settings.mistakeBookStorageLocation)
    assertEquals("content://vault", viewModel.uiState.value.settings.obsidianVaultTreeUri)
    assertEquals("Obsidian 仓库已授权", viewModel.uiState.value.toastMessage)
  }

  @Test
  fun switchingToLocalWithMigrationCopiesVisibleMistakesToLocalRepository() {
    val pendingRepository = RecordingMistakeBookRepository()
    val localRepository = RecordingMistakeBookRepository()
    val viewModel = StudyChatViewModel(
      mistakeBookRepositorySelector = { settings ->
        when (settings.mistakeBookStorageLocation) {
          MistakeBookStorageLocation.LOCAL -> MistakeBookRepositorySelection(
            repository = localRepository,
            key = "local",
            isPendingObsidianAuthorization = false
          )
          MistakeBookStorageLocation.OBSIDIAN -> MistakeBookRepositorySelection(
            repository = pendingRepository,
            key = "pending",
            isPendingObsidianAuthorization = true
          )
        }
      }
    )
    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-1",
        question = "迁移到本软件",
        correctAnswer = "复制当前错题",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )
    assertTrue(pendingRepository.awaitSave())

    viewModel.openSettings()
    viewModel.setSettingsDraft(
      viewModel.uiState.value.settingsDraft.copy(mistakeBookStorageLocation = MistakeBookStorageLocation.LOCAL)
    )
    viewModel.saveSettings(migrateMistakeBook = true)

    assertTrue(localRepository.awaitSave())
    assertEquals(MistakeBookStorageLocation.LOCAL, viewModel.uiState.value.settings.mistakeBookStorageLocation)
    assertEquals(listOf("迁移到本软件"), localRepository.savedItems.last().map { it.question })
  }

  @Test
  fun switchingToObsidianWithMigrationCopiesVisibleMistakesToVaultRepository() {
    val localRepository = RecordingMistakeBookRepository()
    val obsidianRepository = RecordingMistakeBookRepository()
    val viewModel = StudyChatViewModel(
      mistakeBookRepositorySelector = { settings ->
        when (settings.mistakeBookStorageLocation) {
          MistakeBookStorageLocation.LOCAL -> MistakeBookRepositorySelection(
            repository = localRepository,
            key = "local",
            isPendingObsidianAuthorization = false
          )
          MistakeBookStorageLocation.OBSIDIAN -> MistakeBookRepositorySelection(
            repository = obsidianRepository,
            key = "obsidian:${settings.obsidianVaultTreeUri}",
            isPendingObsidianAuthorization = settings.obsidianVaultTreeUri.isBlank()
          )
        }
      }
    )
    viewModel.openSettings()
    viewModel.setSettingsDraft(
      viewModel.uiState.value.settingsDraft.copy(mistakeBookStorageLocation = MistakeBookStorageLocation.LOCAL)
    )
    viewModel.saveSettings()
    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-1",
        question = "迁移到 Obsidian",
        correctAnswer = "写成 Markdown",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )
    assertTrue(localRepository.awaitSave())

    viewModel.openSettings()
    viewModel.setSettingsDraft(
      viewModel.uiState.value.settingsDraft.copy(
        mistakeBookStorageLocation = MistakeBookStorageLocation.OBSIDIAN,
        obsidianVaultTreeUri = "content://vault"
      )
    )
    viewModel.saveSettings(migrateMistakeBook = true)

    assertTrue(obsidianRepository.awaitSave())
    assertEquals(MistakeBookStorageLocation.OBSIDIAN, viewModel.uiState.value.settings.mistakeBookStorageLocation)
    assertEquals(listOf("迁移到 Obsidian"), obsidianRepository.savedItems.last().map { it.question })
  }

  @Test
  fun switchingToObsidianWithMigrationCopiesLocalImagesIntoVaultAssets() {
    val localRepository = RecordingMistakeBookRepository(imageRefPrefix = "mistake_images")
    val obsidianRepository = RecordingMistakeBookRepository(imageRefPrefix = "assets")
    localRepository.savedImageBytes["mistake_images/local-capture.jpg"] = byteArrayOf(1, 9, 8)
    val viewModel = StudyChatViewModel(
      mistakeBookRepositorySelector = { settings ->
        when (settings.mistakeBookStorageLocation) {
          MistakeBookStorageLocation.LOCAL -> MistakeBookRepositorySelection(
            repository = localRepository,
            key = "local",
            isPendingObsidianAuthorization = false
          )
          MistakeBookStorageLocation.OBSIDIAN -> MistakeBookRepositorySelection(
            repository = obsidianRepository,
            key = "obsidian:${settings.obsidianVaultTreeUri}",
            isPendingObsidianAuthorization = settings.obsidianVaultTreeUri.isBlank()
          )
        }
      }
    )
    viewModel.openSettings()
    viewModel.setSettingsDraft(
      viewModel.uiState.value.settingsDraft.copy(mistakeBookStorageLocation = MistakeBookStorageLocation.LOCAL)
    )
    viewModel.saveSettings()
    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-with-image",
        imageRefs = listOf("mistake_images/local-capture.jpg"),
        question = "带图片迁移到 Obsidian",
        correctAnswer = "图片也要复制",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )
    assertTrue(localRepository.awaitSave())

    viewModel.openSettings()
    viewModel.setSettingsDraft(
      viewModel.uiState.value.settingsDraft.copy(
        mistakeBookStorageLocation = MistakeBookStorageLocation.OBSIDIAN,
        obsidianVaultTreeUri = "content://vault"
      )
    )
    viewModel.saveSettings(migrateMistakeBook = true)

    assertTrue(obsidianRepository.awaitSave())
    assertEquals(byteArrayOf(1, 9, 8).toList(), obsidianRepository.savedImageBytes["assets/local-capture.jpg"]?.toList())
    assertEquals(listOf("assets/local-capture.jpg"), obsidianRepository.savedItems.last().first().imageRefs)
    assertEquals(MistakeBookStorageLocation.OBSIDIAN, viewModel.uiState.value.settings.mistakeBookStorageLocation)
  }

  @Test
  fun switchingToObsidianWithMigrationKeepsExistingVaultMistakes() {
    val localRepository = RecordingMistakeBookRepository()
    val obsidianRepository = RecordingMistakeBookRepository().apply {
      loadedItems = listOf(
        MistakeBookItem.create(
          id = "mistake-existing",
          question = "Vault 已有错题",
          correctAnswer = "保留",
          createdAt = 5L
        )
      )
    }
    val viewModel = StudyChatViewModel(
      mistakeBookRepositorySelector = { settings ->
        when (settings.mistakeBookStorageLocation) {
          MistakeBookStorageLocation.LOCAL -> MistakeBookRepositorySelection(
            repository = localRepository,
            key = "local",
            isPendingObsidianAuthorization = false
          )
          MistakeBookStorageLocation.OBSIDIAN -> MistakeBookRepositorySelection(
            repository = obsidianRepository,
            key = "obsidian:${settings.obsidianVaultTreeUri}",
            isPendingObsidianAuthorization = settings.obsidianVaultTreeUri.isBlank()
          )
        }
      }
    )
    viewModel.openSettings()
    viewModel.setSettingsDraft(
      viewModel.uiState.value.settingsDraft.copy(mistakeBookStorageLocation = MistakeBookStorageLocation.LOCAL)
    )
    viewModel.saveSettings()
    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-new",
        question = "本地待迁移错题",
        correctAnswer = "复制到 vault",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )
    assertTrue(localRepository.awaitSave())

    viewModel.openSettings()
    viewModel.setSettingsDraft(
      viewModel.uiState.value.settingsDraft.copy(
        mistakeBookStorageLocation = MistakeBookStorageLocation.OBSIDIAN,
        obsidianVaultTreeUri = "content://vault"
      )
    )
    viewModel.saveSettings(migrateMistakeBook = true)

    assertTrue(obsidianRepository.awaitSave())
    assertEquals(
      listOf("本地待迁移错题", "Vault 已有错题"),
      obsidianRepository.savedItems.last().map { item -> item.question }
    )
  }

  @Test
  fun migrationFailureKeepsPreviousStorageSettingsAndReportsReason() {
    val localRepository = RecordingMistakeBookRepository()
    val obsidianRepository = RecordingMistakeBookRepository().apply {
      saveFailure = IllegalStateException("vault read only")
    }
    val viewModel = StudyChatViewModel(
      mistakeBookRepositorySelector = { settings ->
        when (settings.mistakeBookStorageLocation) {
          MistakeBookStorageLocation.LOCAL -> MistakeBookRepositorySelection(
            repository = localRepository,
            key = "local",
            isPendingObsidianAuthorization = false
          )
          MistakeBookStorageLocation.OBSIDIAN -> MistakeBookRepositorySelection(
            repository = obsidianRepository,
            key = "obsidian:${settings.obsidianVaultTreeUri}",
            isPendingObsidianAuthorization = settings.obsidianVaultTreeUri.isBlank()
          )
        }
      }
    )
    viewModel.openSettings()
    viewModel.setSettingsDraft(
      viewModel.uiState.value.settingsDraft.copy(mistakeBookStorageLocation = MistakeBookStorageLocation.LOCAL)
    )
    viewModel.saveSettings()
    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-new",
        question = "迁移失败时仍留在本软件",
        correctAnswer = "不能切换配置",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )
    assertTrue(localRepository.awaitSave())

    viewModel.openSettings()
    viewModel.setSettingsDraft(
      viewModel.uiState.value.settingsDraft.copy(
        mistakeBookStorageLocation = MistakeBookStorageLocation.OBSIDIAN,
        obsidianVaultTreeUri = "content://vault"
      )
    )
    viewModel.saveSettings(migrateMistakeBook = true)

    assertEquals(MistakeBookStorageLocation.LOCAL, viewModel.uiState.value.settings.mistakeBookStorageLocation)
    assertEquals("", viewModel.uiState.value.settings.obsidianVaultTreeUri)
    assertEquals("错题本迁移失败：vault read only", viewModel.uiState.value.toastMessage)
  }

  @Test
  fun confirmMistakeDraftCreatesDueMistakeItem() {
    val viewModel = StudyChatViewModel()
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "2",
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )

    viewModel.saveMistakeRecognitionDraft(draft)
    viewModel.confirmMistakeDraft("draft-1")

    val state = viewModel.uiState.value
    assertEquals(WorkspacePage.MISTAKES, state.activePage)
    assertEquals(1, state.mistakeItems.size)
    assertEquals(MistakeStatus.DUE, state.mistakeItems.first().status)
    assertEquals("draft-1", state.mistakeItems.first().recognitionDraftId)
  }

  @Test
  fun confirmMistakeDraftUpdatesExistingItemForSameDraft() {
    val viewModel = StudyChatViewModel()
    val incompleteDraft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "",
      status = MistakeRecognitionStatus.OCR_READY,
      createdAt = 1L,
      updatedAt = 1L
    )
    val completedDraft = incompleteDraft.copy(
      correctAnswer = "2",
      status = MistakeRecognitionStatus.AI_READY,
      updatedAt = 2L
    )

    viewModel.saveMistakeRecognitionDraft(incompleteDraft)
    viewModel.confirmMistakeDraft("draft-1")
    val firstItemId = viewModel.uiState.value.mistakeItems.first().id

    viewModel.confirmMistakeDraft(completedDraft)

    val state = viewModel.uiState.value
    assertEquals(1, state.mistakeItems.size)
    assertEquals(firstItemId, state.mistakeItems.first().id)
    assertEquals(MistakeStatus.DUE, state.mistakeItems.first().status)
    assertEquals("2", state.mistakeItems.first().correctAnswer)
  }

  @Test
  fun updateMistakeSearchQueryStoresQueryInState() {
    val viewModel = StudyChatViewModel()

    viewModel.updateMistakeSearchQuery("函数")

    assertEquals("函数", viewModel.uiState.value.mistakeSearchQuery)
  }

  @Test
  fun recordMistakeReviewUpdatesItemInState() {
    val viewModel = StudyChatViewModel()
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "2",
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )
    viewModel.saveMistakeRecognitionDraft(draft)
    viewModel.confirmMistakeDraft("draft-1")
    val itemId = viewModel.uiState.value.mistakeItems.first().id

    viewModel.recordMistakeReview(itemId = itemId, isCorrect = false, userAnswer = "1")

    val reviewed = viewModel.uiState.value.mistakeItems.first()
    assertEquals(1, reviewed.reviewAttempts.size)
    assertEquals(false, reviewed.reviewAttempts.first().isCorrect)
    assertTrue(reviewed.reviewState.nextReviewAt!! > reviewed.createdAt)
  }

  @Test
  fun confirmMistakeAnswerJudgementRecordsUserConfirmedModelAttempt() {
    val viewModel = StudyChatViewModel()
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 x+1=3，求 x。",
      correctAnswer = "2",
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )
    viewModel.saveMistakeRecognitionDraft(draft)
    viewModel.confirmMistakeDraft("draft-1")
    val itemId = viewModel.uiState.value.mistakeItems.first().id

    viewModel.applyMistakeAnswerJudgement(
      itemId = itemId,
      userAnswer = "2",
      judgement = MistakeAnswerJudgement(
        isCorrect = true,
        confidence = 0.95,
        reason = "与标准答案一致",
        suggestedScore = 100
      )
    )
    viewModel.confirmMistakeAnswerJudgement(itemId = itemId, finalIsCorrect = true)

    val attempt = viewModel.uiState.value.mistakeItems.first().reviewAttempts.single()
    assertEquals(true, attempt.isCorrect)
    assertEquals(MistakeReviewJudgementSource.USER_CONFIRMED_MODEL, attempt.judgementSource)
    assertTrue(attempt.modelSuggestion.contains("与标准答案一致"))
    assertEquals(null, viewModel.uiState.value.activeMistakeReviewSuggestion)
  }

  @Test
  fun addMistakeToAnkiCreatesReviewCardFromMistake() {
    val viewModel = StudyChatViewModel()
    val draft = MistakeRecognitionDraft(
      id = "draft-1",
      question = "已知 f(x)=x^2，求 f'(x)。",
      subject = "数学",
      questionType = "解答题",
      knowledgeTags = listOf("导数与应用"),
      correctAnswer = "f'(x)=2x",
      explanation = "幂函数求导公式：x^n 的导数是 nx^(n-1)。",
      mistakeReason = "把平方函数导数记成常数。",
      mistakeType = MistakeType.CONCEPT_ERROR,
      status = MistakeRecognitionStatus.AI_READY,
      createdAt = 1L,
      updatedAt = 1L
    )
    viewModel.saveMistakeRecognitionDraft(draft)
    viewModel.confirmMistakeDraft("draft-1")
    val itemId = viewModel.uiState.value.mistakeItems.first().id

    viewModel.addMistakeToAnki(itemId)

    val card = viewModel.uiState.value.ankiCards.single()
    assertTrue(card.front.contains("f(x)=x^2"))
    assertTrue(card.back.contains("正确答案"))
    assertTrue(card.back.contains("f'(x)=2x"))
    assertTrue(card.back.contains("错因"))
    assertTrue(card.tags.contains("导数与应用"))
    assertTrue(card.source.contains("错题本"))
  }

  @Test
  fun importMistakeBookJson_mergesImportedItemsIntoState() {
    val viewModel = StudyChatViewModel()
    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-old",
        question = "旧题",
        correctAnswer = "旧答",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )

    viewModel.importMistakeBookJson(
      """
      {
        "version": 1,
        "items": [
          {
            "id": "mistake-1",
            "question": "导入后覆盖的旧题",
            "correctAnswer": "新答案",
            "status": "DUE",
            "createdAt": 2,
            "updatedAt": 3,
            "reviewState": {
              "nextReviewAt": 2,
              "reviewCount": 0,
              "correctStreak": 0,
              "easeFactor": 2.5,
              "currentIntervalMillis": 0
            },
            "reviewAttempts": []
          },
          {
            "id": "mistake-new",
            "question": "新增导入题",
            "correctAnswer": "新增答案",
            "status": "DUE",
            "createdAt": 4,
            "updatedAt": 4,
            "reviewState": {
              "nextReviewAt": 4,
              "reviewCount": 0,
              "correctStreak": 0,
              "easeFactor": 2.5,
              "currentIntervalMillis": 0
            },
            "reviewAttempts": []
          }
        ]
      }
      """.trimIndent()
    )

    val state = viewModel.uiState.value
    assertEquals(2, state.mistakeItems.size)
    assertEquals(listOf("mistake-1", "mistake-new"), state.mistakeItems.map { it.id })
    assertEquals("导入后覆盖的旧题", state.mistakeItems.first().question)
  }

  @Test
  fun buildMistakeBookExportJson_returnsSerializedState() {
    val viewModel = StudyChatViewModel()
    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-1",
        question = "导数题",
        correctAnswer = "2x",
        subject = "数学",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )

    val exported = viewModel.buildMistakeBookExportJson()

    assertTrue(exported.contains("\"version\":1"))
    assertTrue(exported.contains("导数题"))
    assertTrue(exported.contains("\"subject\":\"数学\""))
  }

  @Test
  fun analyzeMistakeBookWithAi_updatesAnalysisState() = runTest {
    val viewModel = StudyChatViewModel(
      mistakeBookAnalysisRequester = { items, _ ->
        assertEquals(1, items.size)
        Result.success(
          MistakeBookAiAnalysis(
            summary = "函数与图像是当前核心短板",
            weaknesses = listOf("函数与图像"),
            plan = listOf("先复习到期错题"),
            nextActions = listOf("今晚完成2题同类训练"),
            rawText = ""
          )
        )
      }
    )
    viewModel.confirmMistakeDraft(
      MistakeRecognitionDraft(
        id = "draft-1",
        question = "函数图像题",
        correctAnswer = "先看定义域",
        status = MistakeRecognitionStatus.AI_READY,
        createdAt = 1L,
        updatedAt = 1L
      )
    )

    viewModel.analyzeMistakeBookWithAi()
    advanceUntilIdle()

    val analysis = viewModel.uiState.value.mistakeAiAnalysis
    assertNotNull(analysis)
    assertEquals("函数与图像是当前核心短板", analysis?.summary)
    assertEquals(listOf("函数与图像"), analysis?.weaknesses)
    assertTrue(viewModel.uiState.value.toastMessage?.contains("AI") == true)
  }

  private class RecordingMistakeBookRepository(
    private val imageRefPrefix: String = "assets"
  ) : MistakeBookRepository {
    private val saveLatch = CountDownLatch(1)
    val savedItems = mutableListOf<List<MistakeBookItem>>()
    val savedImageBytes = mutableMapOf<String, ByteArray>()
    var loadedItems: List<MistakeBookItem> = emptyList()
    var saveFailure: Throwable? = null
    var imageSaveFailure: Throwable? = null

    override fun load(): List<MistakeBookItem> = loadedItems

    override fun save(items: List<MistakeBookItem>): Result<Unit> {
      saveFailure?.let { error -> return Result.failure(error) }
      savedItems += items
      loadedItems = items
      saveLatch.countDown()
      return Result.success(Unit)
    }

    override fun upsert(item: MistakeBookItem): Result<List<MistakeBookItem>> {
      val next = listOf(item) + loadedItems.filterNot { existing -> existing.id == item.id }
      save(next)
      return Result.success(next)
    }

    override fun delete(itemId: String): Result<List<MistakeBookItem>> {
      val next = loadedItems.filterNot { item -> item.id == itemId }
      save(next)
      return Result.success(next)
    }

    override fun saveImageBytes(bytes: ByteArray, fileNameHint: String): Result<String> {
      imageSaveFailure?.let { error -> return Result.failure(error) }
      val ref = "$imageRefPrefix/${fileNameHint.substringAfterLast('/')}"
      savedImageBytes[ref] = bytes
      return Result.success(ref)
    }

    override fun loadImageBytes(ref: String): Result<ByteArray> {
      val bytes = savedImageBytes[ref] ?: return Result.failure(IllegalArgumentException("missing image: $ref"))
      return Result.success(bytes)
    }

    fun awaitSave(): Boolean = saveLatch.await(2, TimeUnit.SECONDS)
  }
}
