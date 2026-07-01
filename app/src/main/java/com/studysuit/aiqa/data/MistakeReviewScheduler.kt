package com.studysuit.aiqa.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.studysuit.aiqa.MainActivity
import com.studysuit.aiqa.ui.ObsidianVaultPermissionSnapshot
import com.studysuit.aiqa.ui.RuntimeSettings
import com.studysuit.aiqa.ui.SessionStorage
import com.studysuit.aiqa.ui.hasPersistedObsidianVaultPermission
import java.util.concurrent.TimeUnit

sealed interface MistakeReviewScheduleDecision {
  data object Cancel : MistakeReviewScheduleDecision
  data class Schedule(val delayMillis: Long, val dueAt: Long) : MistakeReviewScheduleDecision
}

object MistakeReviewScheduler {
  const val UNIQUE_WORK_NAME = "mistake_review_due_reminder"

  fun nextDueReviewAt(items: List<MistakeBookItem>, now: Long = System.currentTimeMillis()): Long? {
    return items
      .asSequence()
      .filter { item -> item.isReadyForReview }
      .filterNot { item -> item.status == MistakeStatus.DRAFT || item.status == MistakeStatus.COMPLETED || item.status == MistakeStatus.ARCHIVED }
      .mapNotNull { item -> item.reviewState.nextReviewAt }
      .minOrNull()
      ?.let { next -> if (next <= now) now else next }
  }

  fun scheduleDecision(
    items: List<MistakeBookItem>,
    now: Long = System.currentTimeMillis()
  ): MistakeReviewScheduleDecision {
    val dueAt = nextDueReviewAt(items, now = now)
      ?: return MistakeReviewScheduleDecision.Cancel
    return MistakeReviewScheduleDecision.Schedule(
      delayMillis = (dueAt - now).coerceAtLeast(0L),
      dueAt = dueAt
    )
  }

  fun reschedule(
    context: Context,
    items: List<MistakeBookItem>,
    now: Long = System.currentTimeMillis()
  ) {
    val workManager = WorkManager.getInstance(context)
    val decision = scheduleDecision(items = items, now = now)
    if (decision is MistakeReviewScheduleDecision.Cancel) {
      workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
      return
    }
    val schedule = decision as MistakeReviewScheduleDecision.Schedule
    val request = OneTimeWorkRequestBuilder<MistakeReviewWorker>()
      .setInitialDelay(schedule.delayMillis, TimeUnit.MILLISECONDS)
      .build()
    workManager.enqueueUniqueWork(
      UNIQUE_WORK_NAME,
      ExistingWorkPolicy.REPLACE,
      request
    )
  }
}

class MistakeReviewWorker(
  appContext: Context,
  params: WorkerParameters
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val localStorage = MistakeBookStorage(applicationContext)
    val loadedSettings = SessionStorage(applicationContext).load()?.settings ?: RuntimeSettings.defaults()
    val settings = if (
      loadedSettings.mistakeBookStorageLocation == MistakeBookStorageLocation.OBSIDIAN &&
      loadedSettings.obsidianVaultTreeUri.isNotBlank() &&
      !hasPersistedObsidianVaultPermission(
        vaultTreeUri = loadedSettings.obsidianVaultTreeUri,
        permissions = applicationContext.contentResolver.persistedUriPermissions.map { permission ->
          ObsidianVaultPermissionSnapshot(
            uri = permission.uri.toString(),
            canRead = permission.isReadPermission,
            canWrite = permission.isWritePermission
          )
        }
      )
    ) {
      loadedSettings.copy(obsidianVaultTreeUri = "")
    } else {
      loadedSettings
    }
    val storage = selectMistakeBookRepository(
      localRepository = localStorage,
      storageLocation = settings.mistakeBookStorageLocation,
      obsidianVaultTreeUri = settings.obsidianVaultTreeUri,
      obsidianMistakeFolder = settings.obsidianMistakeFolder,
      createObsidianRepository = { vaultUri, folder ->
        AndroidObsidianMistakeBookStorage(
          context = applicationContext,
          vaultTreeUri = vaultUri,
          folder = folder
        )
      }
    ).getOrNull()?.repository ?: localStorage
    val items = storage.load()
    val dueItems = MistakeSrsEngine.dueMistakes(items, now = System.currentTimeMillis())
    if (dueItems.isNotEmpty()) {
      MistakeReviewNotification.showDueReminder(
        context = applicationContext,
        dueCount = dueItems.size,
        firstItem = dueItems.first()
      )
    } else {
      MistakeReviewScheduler.reschedule(applicationContext, items)
    }
    return Result.success()
  }
}

object MistakeReviewNotification {
  const val ACTION_OPEN_MISTAKE_REVIEW = "com.studysuit.aiqa.action.OPEN_MISTAKE_REVIEW"
  private const val CHANNEL_ID = "mistake_review_reminders"
  private const val NOTIFICATION_ID = 4207

  fun isOpenMistakeReviewIntent(intent: Intent?): Boolean {
    return intent?.action == ACTION_OPEN_MISTAKE_REVIEW
  }

  fun showDueReminder(
    context: Context,
    dueCount: Int,
    firstItem: MistakeBookItem
  ) {
    if (dueCount <= 0) {
      return
    }
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) {
      return
    }

    ensureChannel(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("错题复习到时间了")
      .setContentText("有 $dueCount 道错题待复习：${firstItem.question.take(24)}")
      .setStyle(
        NotificationCompat.BigTextStyle().bigText(
          "有 $dueCount 道错题待复习。\n${firstItem.question.take(80)}"
        )
      )
      .setContentIntent(openMistakeReviewPendingIntent(context))
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .build()

    manager.notify(NOTIFICATION_ID, notification)
  }

  private fun ensureChannel(context: Context) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
      CHANNEL_ID,
      "错题复习提醒",
      NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
      description = "按记忆曲线提醒到期错题复习"
      setShowBadge(true)
    }
    notificationManager.createNotificationChannel(channel)
  }

  private fun openMistakeReviewPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      action = ACTION_OPEN_MISTAKE_REVIEW
      flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }
}
