package com.htorrent.mobile.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.htorrent.mobile.MainActivity
import com.htorrent.mobile.core.tr
import com.htorrent.mobile.R
import com.htorrent.mobile.core.Format
import com.htorrent.mobile.data.DownloadItem
import com.htorrent.mobile.data.Downloads
import java.util.Calendar

/** Keeps downloads running while the app is in the background, with an ongoing notification as Android requires. */
class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (Downloads.activeCount == 0 && Downloads.items.value.none { it.status == com.htorrent.mobile.data.Status.QUEUED }) {
                stopForegroundCompat(); stopSelf(); return
            }
            NotificationManagerCompat.from(this@DownloadService).let {
                if (canNotify(this@DownloadService)) it.notify(Notifier.ONGOING_ID, Notifier.ongoing(this@DownloadService))
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Downloads.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) {
            Downloads.pauseAll()
        }
        val n = Notifier.ongoing(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifier.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.ONGOING_ID, n)
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    /** Android 15 ends data-sync services after a few hours: keep progress and let the user continue. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloads.pauseForSystem()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.htorrent.mobile.PAUSE_ALL"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }

        fun stop(context: Context) {
            // The service stops itself on its next tick when nothing is running.
        }
    }
}

fun canNotify(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

object Notifier {
    const val ONGOING_ID = 1
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, tr("Downloads in progress"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, tr("Finished downloads"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(context: Context, id: Long = 0): PendingIntent =
        PendingIntent.getActivity(context, id.toInt(), Intent(context, MainActivity::class.java).putExtra("open", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun ongoing(context: Context): Notification {
        val active = Downloads.items.value.filter { it.status == com.htorrent.mobile.data.Status.DOWNLOADING }
        val size = active.sumOf { it.size ?: 0 }
        val written = active.sumOf { it.written }
        val title = when (active.size) {
            0 -> tr("Preparing downloads")
            1 -> active[0].fileName
            else -> "Downloading ${active.size} files"
        }
        val speed = Format.speed(Downloads.totalSpeed)
        val text = listOf(if (size > 0) "${Format.size(written)} of ${Format.size(size)}" else Format.size(written), speed)
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val pause = PendingIntent.getService(context, 1, Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, if (size > 0) (written * 100 / size).toInt() else 0, size <= 0)
            .setContentIntent(openApp(context))
            .addAction(0, tr("Pause all"), pause)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun finished(context: Context, item: DownloadItem) {
        if (!canNotify(context) || !Downloads.settings.notifyFinished) return
        val open = item.contentUri?.let { uri ->
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), context.contentResolver.getType(Uri.parse(uri)) ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(context, item.id.toInt(), Intent.createChooser(view, tr("Open with")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } ?: openApp(context, item.id)
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(item.fileName)
            .setContentText("Download complete · ${Format.size(item.size ?: item.written)}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(item.id.toInt(), n) }
    }

    fun failed(context: Context, item: DownloadItem) {
        if (!canNotify(context) || !Downloads.settings.notifyFailed) return
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(item.fileName)
            .setContentText(item.error ?: tr("Download failed"))
            .setContentIntent(openApp(context, item.id))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(item.id.toInt(), n) }
    }

    fun scheduledReady(context: Context) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(tr("Scheduled downloads are ready"))
            .setContentText("Tap to start them now.")
            .setContentIntent(PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java).putExtra("startLater", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(7, n) }
    }
}

/** Starts the downloads kept for later at the scheduled time every day. */
object Scheduler {
    fun apply(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 3, Intent(context, ScheduleReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        val s = Downloads.settings
        if (!s.scheduleEnabled) return
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.scheduleMinutes / 60)
            set(Calendar.MINUTE, s.scheduleMinutes % 60)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi)
    }

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Downloads.init(context)
        val later = Downloads.items.value.any { it.status == com.htorrent.mobile.data.Status.LATER }
        if (later) {
            try {
                // Exact alarms may start a foreground service from the background; otherwise Android refuses and we ask the user.
                Downloads.startLater()
            } catch (e: Exception) {
                Notifier.scheduledReady(context)
            }
            if (!Scheduler.canExact(context)) Notifier.scheduledReady(context)
        }
        Scheduler.apply(context)
    }
}
