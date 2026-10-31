package com.changakira.screenshotbye.remind

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.media.DeleteGateway
import com.changakira.screenshotbye.notify.Notify
import com.changakira.screenshotbye.service.WatchService

/** 到点弹出「还需要这张截图吗」的通知。 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val mediaId = inputData.getLong(KEY_MEDIA_ID, -1L)
        if (mediaId <= 0) return Result.failure()

        val app = applicationContext
        val store = PendingShotStore.of(app)
        val shot = store.find(mediaId) ?: return Result.success()

        // 用户已经自己在相册里删掉了，不必再问
        if (!DeleteGateway(app).exists(shot.contentUri)) {
            store.remove(mediaId)
            WatchService.refreshNotification(app)
            return Result.success()
        }

        Notify.notify(app, Notify.reviewNotifId(mediaId), Notify.review(app, shot))
        store.upsert(shot.copy(state = ShotState.ASKED))
        Settings.of(app).appendLog("已询问：${shot.name}")
        WatchService.refreshNotification(app)
        return Result.success()
    }

    companion object {
        const val KEY_MEDIA_ID = "media_id"
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
