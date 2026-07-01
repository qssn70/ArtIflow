package com.studysuit.aiqa.data

enum class MistakeBookStorageLocation {
  OBSIDIAN,
  LOCAL
}

interface MistakeBookRepository {
  fun load(): List<MistakeBookItem>
  fun save(items: List<MistakeBookItem>): Result<Unit>
  fun upsert(item: MistakeBookItem): Result<List<MistakeBookItem>>
  fun delete(itemId: String): Result<List<MistakeBookItem>>
  fun saveImageBytes(bytes: ByteArray, fileNameHint: String): Result<String>
  fun loadImageBytes(ref: String): Result<ByteArray>
}

data class MistakeBookRepositorySelection(
  val repository: MistakeBookRepository,
  val key: String,
  val isPendingObsidianAuthorization: Boolean
)

const val DEFAULT_OBSIDIAN_MISTAKE_FOLDER = "ArtIflow/错题本"

fun selectMistakeBookRepository(
  localRepository: MistakeBookRepository,
  storageLocation: MistakeBookStorageLocation,
  obsidianVaultTreeUri: String,
  obsidianMistakeFolder: String = DEFAULT_OBSIDIAN_MISTAKE_FOLDER,
  createObsidianRepository: (vaultTreeUri: String, folder: String) -> MistakeBookRepository
): Result<MistakeBookRepositorySelection> {
  return runCatching {
    val normalizedFolder = obsidianMistakeFolder.toSafeObsidianFolderSegments().joinToString("/")
    when (storageLocation) {
      MistakeBookStorageLocation.LOCAL -> MistakeBookRepositorySelection(
        repository = localRepository,
        key = "local",
        isPendingObsidianAuthorization = false
      )
      MistakeBookStorageLocation.OBSIDIAN -> {
        val vaultUri = obsidianVaultTreeUri.trim()
        if (vaultUri.isBlank()) {
          MistakeBookRepositorySelection(
            repository = localRepository,
            key = "obsidian-pending-local",
            isPendingObsidianAuthorization = true
          )
        } else {
          MistakeBookRepositorySelection(
            repository = createObsidianRepository(vaultUri, normalizedFolder),
            key = "obsidian:$vaultUri:$normalizedFolder",
            isPendingObsidianAuthorization = false
          )
        }
      }
    }
  }
}
