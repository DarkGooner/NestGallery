package com.nestgallery.viewer.data.nsfw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nestgallery.viewer.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Keeps the NSFW scan alive when the app is minimised or the screen is off, like [com.nestgallery.viewer.data.face.FaceScanService]:
 * foreground (data-sync) + partial wake lock + a progress notification with Pause / Resume / Stop; stops itself when the
 * scan ends. Results are committed in batches, so if Android kills the process anyway the next scan resumes.
 */
class NsfwScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = NsfwScannerManager.getInstance(this)
        when (intent?.action) {
            ACTION_PAUSE -> manager.pauseScan()
            ACTION_RESUME -> manager.resumeScan()
            ACTION_STOP -> manager.stopScan()
        }
        ensureChannel()
        // Must be called promptly after startForegroundService().
        enterForeground(buildNotification(manager.status.value))
        acquireWakeLock()

        if (watcher == null) {
            watcher = scope.launch {
                manager.status.collect { status ->
                    when (status) {
                        is NsfwScanStatus.Idle -> finish(null)
                        is NsfwScanStatus.Completed -> finish(status)
                        is NsfwScanStatus.Failed -> {
                            postFinalNotification("NSFW scan failed", status.message)
                            shutDown()
                        }
                        else -> {
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 700 || status is NsfwScanStatus.Paused) {
                                lastNotify = now
                                notificationManager().notify(NOTIFICATION_ID, buildNotification(status))
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: data-sync foreground services get a time budget. Stop cleanly; the next scan resumes. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        NsfwScannerManager.getInstance(this).stopScan()
        postFinalNotification("NSFW scan paused", "Android's background time limit was reached. Open the folder and tap NSFW scan to continue.")
        shutDown()
    }

    private fun finish(done: NsfwScanStatus.Completed?) {
        if (done != null) {
            val n = NumberFormat.getInstance()
            postFinalNotification("NSFW scan complete", "${n.format(done.totalScanned)} photos scanned · tap Filters in the folder view")
        }
        shutDown()
    }

    private fun shutDown() {
        watcher?.cancel(); watcher = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---- notification ---------------------------------------------------------------------------

    private fun buildNotification(status: NsfwScanStatus): Notification {
        val n = NumberFormat.getInstance()
        var title = "NSFW scan"
        var text = "Starting…"
        var progress = 0; var max = 0; var indeterminate = true
        var paused = false
        when (status) {
            is NsfwScanStatus.Scanning -> {
                max = status.totalCount; progress = status.scannedCount; indeterminate = max <= 0
                text = if (max > 0) {
                    val rate = if (status.photosPerSecond > 0.05f) " · %.1f photos/s".format(status.photosPerSecond) else ""
                    val eta = if (status.etaSeconds >= 0) " · ${formatEta(status.etaSeconds)} left" else ""
                    "${n.format(progress)} / ${n.format(max)} photos$rate$eta"
                } else status.currentFileName
            }
            is NsfwScanStatus.Paused -> {
                paused = true; title = "NSFW scan paused"
                max = status.totalCount; progress = status.scannedCount; indeterminate = false
                text = "${n.format(progress)} / ${n.format(max)} photos"
            }
            else -> {}
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(max, progress, indeterminate)
            .addAction(0, if (paused) "Resume" else "Pause", actionIntent(if (paused) ACTION_RESUME else ACTION_PAUSE))
            .addAction(0, "Stop", actionIntent(ACTION_STOP))
            .build()
    }

    private fun postFinalNotification(title: String, text: String) {
        ensureChannel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setSilent(true)
            .build()
        try { notificationManager().notify(NOTIFICATION_ID + 1, n) } catch (_: SecurityException) { /* notifications not permitted */ }
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(), Intent(this, NsfwScanService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun enterForeground(notification: Notification) {
        if (isForeground) return
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        isForeground = true
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = notificationManager()
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "NSFW scanning", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of on-device NSFW scanning"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ---- wake lock ------------------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NestGallery:NsfwScan").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_MS)                       // safety net; released as soon as the scan ends
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "nsfw_scan"
        private const val NOTIFICATION_ID = 4721
        private const val MAX_WAKE_MS = 6 * 60 * 60 * 1000L
        const val ACTION_PAUSE = "com.nestgallery.viewer.nsfw.PAUSE"
        const val ACTION_RESUME = "com.nestgallery.viewer.nsfw.RESUME"
        const val ACTION_STOP = "com.nestgallery.viewer.nsfw.STOP"

        /** "45 s", "12 min", "2 h 05 min" */
        fun formatEta(seconds: Long): String = when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${(seconds + 30) / 60} min"
            else -> "%d h %02d min".format(seconds / 3600, seconds / 60 % 60)
        }
    }
}
