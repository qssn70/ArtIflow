package com.studysuit.aiqa

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class AiGenerationForegroundService : Service() {
  private var latestStatus: String = ""
  private var latestPreview: String? = null
  private var hasStartedForeground = false
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> stopForegroundWork()
      else -> {
        val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 0) ?: 0
        latestStatus = intent?.getStringExtra(EXTRA_STATUS).orEmpty().trim()
          .ifBlank { getString(R.string.background_ai_default_status) }
        latestPreview = intent?.getStringExtra(EXTRA_PREVIEW).orEmpty().trim().ifBlank { null }

        if (activeCount <= 0) {
          stopForegroundWork()
        } else {
          startOrUpdateForeground()
        }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    releaseWakeLock()
    super.onDestroy()
  }

  private fun startOrUpdateForeground() {
    ensureWakeLock()
    val notification = buildNotification()
    if (!hasStartedForeground) {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      )
      hasStartedForeground = true
    } else {
      NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }
  }

  private fun stopForegroundWork() {
    releaseWakeLock()
    if (hasStartedForeground) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      hasStartedForeground = false
    }
    stopSelf()
  }

  private fun ensureWakeLock() {
    val heldLock = wakeLock
    if (heldLock?.isHeld == true) {
      return
    }

    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    val createdLock = powerManager.newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK,
      "$packageName:AiGenerationForeground"
    ).apply {
      setReferenceCounted(false)
      acquire()
    }
    wakeLock = createdLock
  }

  private fun releaseWakeLock() {
    wakeLock?.let { lock ->
      if (lock.isHeld) {
        lock.release()
      }
    }
    wakeLock = null
  }

  private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
    .setSmallIcon(android.R.drawable.stat_notify_sync)
    .setContentTitle(getString(R.string.background_ai_notification_title))
    .setContentText(latestPreview ?: latestStatus)
    .setSubText(getString(R.string.background_ai_notification_subtitle))
    .setStyle(
      NotificationCompat.BigTextStyle().bigText(
        buildString {
          append(latestStatus)
          latestPreview?.takeIf { preview -> preview != latestStatus }?.let { preview ->
            append('\n')
            append(preview)
          }
        }
      )
    )
    .setContentIntent(buildOpenAppPendingIntent())
    .setOnlyAlertOnce(true)
    .setOngoing(true)
    .setSilent(true)
    .setPriority(NotificationCompat.PRIORITY_LOW)
    .setCategory(NotificationCompat.CATEGORY_SERVICE)
    .build()

  private fun buildOpenAppPendingIntent(): PendingIntent {
    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return PendingIntent.getActivity(
      this,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun ensureNotificationChannel() {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.background_ai_channel_name),
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = getString(R.string.background_ai_channel_description)
      setShowBadge(false)
    }
    manager.createNotificationChannel(channel)
  }

  companion object {
    private const val ACTION_START_OR_UPDATE = "com.studysuit.aiqa.action.AI_BACKGROUND_START_OR_UPDATE"
    private const val ACTION_STOP = "com.studysuit.aiqa.action.AI_BACKGROUND_STOP"
    private const val EXTRA_ACTIVE_COUNT = "active_count"
    private const val EXTRA_STATUS = "status"
    private const val EXTRA_PREVIEW = "preview"
    private const val CHANNEL_ID = "ai_generation_background"
    private const val NOTIFICATION_ID = 3107

    fun startOrUpdate(
      context: Context,
      activeCount: Int,
      status: String?,
      preview: String?
    ) {
      val intent = Intent(context, AiGenerationForegroundService::class.java).apply {
        action = ACTION_START_OR_UPDATE
        putExtra(EXTRA_ACTIVE_COUNT, activeCount)
        putExtra(EXTRA_STATUS, status.orEmpty())
        putExtra(EXTRA_PREVIEW, preview.orEmpty())
      }
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, AiGenerationForegroundService::class.java).apply {
        action = ACTION_STOP
      }
      context.startService(intent)
    }
  }
}
