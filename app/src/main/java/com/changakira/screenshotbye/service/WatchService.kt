package com.changakira.screenshotbye.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.Reminders
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.media.MediaScanner
import com.changakira.screenshotbye.notify.Channels
import com.changakira.screenshotbye.notify.Notify

/**
 * 常驻前台服务：持有 MediaStore 监听，是「持续后台活动」的载体。
 *
 * 用 specialUse 类型的 foreground service：Android 15 起 dataSync 等类型有累计 6 小时上限，
 * 而截图监控本身没有合适的官方归类，specialUse 没有时间限制，但需要在 Play 控制台说明用途。
 */
class WatchService : Service() {

    private var scanner: MediaScanner? = null
    private val settings by lazy { Settings.of(this) }

    override fun onCreate() {
        super.onCreate()
        Channels.ensure(this)
        ScreenshotRepository(this).ensureCleanupSchedule()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(watching = settings.enabled)

        when (intent?.action) {
            ACTION_STOP -> {
                settings.enabled = false
                stopWatching()
                goForeground(watching = false)
                return START_NOT_STICKY
            }

            ACTION_RESCAN -> scanner?.rescanNow()

            else -> if (settings.enabled) startWatching() else stopWatching()
        }
        return START_STICKY
    }

    private fun startWatching() {
        if (scanner != null) return
        running = true
        val repo = ScreenshotRepository(this)
        scanner = MediaScanner(
            ctx = this,
            onHit = { hit -> repo.onDetected(hit) },
            onNote = { msg -> settings.appendLog(msg) },
        ).also { it.start() }
        // 进程重启后，把上次没问完的续上：到期的立即问，未到期的重新排队
        Reminders.catchUpOverdue(this, PendingShotStore.of(this))
        goForeground(watching = true)
    }

    private fun stopWatching() {
        running = false
        scanner?.stop()
        scanner = null
    }

    private fun goForeground(watching: Boolean) {
        val pending = PendingShotStore.of(this).all().size
        val notification = Notify.serviceNotification(this, watching, pending)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notify.ID_SERVICE, notification, type)
        running = watching
    }

    override fun onDestroy() {
        stopWatching()
        settings.appendLog("监控服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_STOP = "com.changakira.screenshotbye.action.STOP"
        private const val ACTION_RESCAN = "com.changakira.screenshotbye.action.RESCAN"

        /** 进程内标记，用于判断服务是否存活；进程被杀后自然复位。 */
        @Volatile var running = false
            private set

        fun start(ctx: android.content.Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
        }

        fun stop(ctx: android.content.Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java).setAction(ACTION_STOP))
        }

        /** 规则改动后立即补扫一次。 */
        fun rescan(ctx: android.content.Context) {
            if (!running) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java).setAction(ACTION_RESCAN))
        }

        /** 待确认数量变化时刷新常驻通知。服务没跑就不动，避免误拉起。 */
        fun refreshNotification(ctx: android.content.Context) {
            if (!running) return
            val watching = Settings.of(ctx).enabled
            Notify.notify(ctx, Notify.ID_SERVICE, Notify.serviceNotification(ctx, watching, PendingShotStore.of(ctx).all().size))
        }
    }
}
