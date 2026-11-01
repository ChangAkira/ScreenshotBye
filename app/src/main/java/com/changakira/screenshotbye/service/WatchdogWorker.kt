package com.changakira.screenshotbye.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.notify.Notify

/**
 * 服务存活看门狗，兼作提醒心跳。
 *
 * 国产 ROM 的后台管控会把前台服务一并杀掉，杀完不一定重启。这里每 15 分钟检查一次：
 * 服务没在跑就尝试拉起；Android 12+ 从后台拉起前台服务可能被系统直接拒绝
 * （ForegroundServiceStartNotAllowedException），此时不再硬试，改为发一条通知让用户点一下恢复。
 *
 * 顺带两件事：清掉「一直没人回答」和「系统确认卡住」的过期条目，
 * 以及按提醒间隔重新出声——服务被杀、定时任务丢失时，这条心跳是唯一的兜底。
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val settings = Settings.of(app)
        val repo = ScreenshotRepository(app)
        repo.sweepStale()
        // 监控关着的时候不该再追着提醒用户，只把通知内容同步一下
        if (settings.enabled) repo.refreshReviewNotification(app)
        else repo.updateReviewNotification(app)

        if (!settings.enabled) return Result.success()
        if (WatchService.running) return Result.success()

        return try {
            WatchService.start(app)
            settings.appendLog("看门狗：已重新拉起监控")
            Result.success()
        } catch (e: Exception) {
            settings.appendLog("看门狗：拉起失败，需用户手动恢复（${e.javaClass.simpleName}）")
            Notify.notify(app, Notify.ID_RECOVERY, Notify.recoveryNeeded(app))
            Result.success()
        }
    }
}
