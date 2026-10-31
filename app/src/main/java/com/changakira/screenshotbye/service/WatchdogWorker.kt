package com.changakira.screenshotbye.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.notify.Notify

/**
 * 服务存活看门狗。
 *
 * 国产 ROM 的后台管控会把前台服务一并杀掉，杀完不一定重启。这里每 15 分钟检查一次：
 * 服务没在跑就尝试拉起；Android 12+ 从后台拉起前台服务可能被系统直接拒绝
 * （ForegroundServiceStartNotAllowedException），此时不再硬试，改为发一条通知让用户点一下恢复。
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!Settings.of(app).enabled) return Result.success()
        if (WatchService.running) return Result.success()

        return try {
            WatchService.start(app)
            Settings.of(app).appendLog("看门狗：已重新拉起监控")
            Result.success()
        } catch (e: Exception) {
            Settings.of(app).appendLog("看门狗：拉起失败，需用户手动恢复（${e.javaClass.simpleName}）")
            Notify.notify(app, Notify.ID_RECOVERY, Notify.recoveryNeeded(app))
            Result.success()
        }
    }
}
