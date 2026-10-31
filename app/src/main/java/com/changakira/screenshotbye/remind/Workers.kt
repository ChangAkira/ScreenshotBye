package com.changakira.screenshotbye.remind

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.media.DeleteGateway

/** 到点把这张截图推进待确认清单，并刷新那条合并询问通知。 */
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
            repo.refreshReviewNotification(app)
            return Result.success()
        }

        store.upsert(shot.copy(state = ShotState.ASKED))
        // 无论这张是第一个还是第五个到期的，都只更新同一条通知，不新增第二条
        repo.refreshReviewNotification(app)
        Settings.of(app).appendLog("已询问：${shot.name}")
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
