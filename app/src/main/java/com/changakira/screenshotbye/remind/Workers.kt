package com.changakira.screenshotbye.remind

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.media.DeleteGateway

/** 到点把这张截图推进待确认清单，并按节流规则刷新那条合并询问通知。 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val mediaId = inputData.getLong(KEY_MEDIA_ID, -1L)
        if (mediaId <= 0) return Result.failure()

        val app = applicationContext
        val store = PendingShotStore.of(app)
        val repo = ScreenshotRepository(app)
        val shot = store.find(mediaId) ?: return Result.success()

        // 用户已经自己在相册里删掉了，不必再问
        if (!DeleteGateway(app).exists(shot.contentUri)) {
            store.remove(mediaId)
            repo.updateReviewNotification(app)
            return Result.success()
        }

        repo.onReminderFired(mediaId)
        return Result.success()
    }

    companion object {
        const val KEY_MEDIA_ID = "media_id"
    }
}

/**
 * 询问通知被用户划掉后的补提醒。
 *
 * 划掉不等于回答：条目还在队列里，如果不补一次，它就永远沉在通知栏历史记录里，
 * 用户只能自己想起来去应用里找。这里只负责在延时到期后走一次正常的提醒判定，
 * 该不该出声仍由仓库的节流规则决定。
 */
class AlertRetryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        ScreenshotRepository(app).refreshReviewNotification(app, newDue = true)
        return Result.success()
    }
}

/** 每天清理超过保留期的回收站备份。 */
class TrashCleanupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val settings = Settings.of(app)
        val purged = DeleteGateway(app).trash.purgeExpired(settings.trashDays)
        if (purged > 0) settings.appendLog("回收站彻底清除 $purged 项")
        return Result.success()
    }
}
