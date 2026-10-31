package com.changakira.screenshotbye.core

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.changakira.screenshotbye.media.DeleteGateway
import com.changakira.screenshotbye.media.DeleteResult
import com.changakira.screenshotbye.media.MediaHit
import com.changakira.screenshotbye.notify.Notify
import com.changakira.screenshotbye.remind.TrashCleanupWorker
import com.changakira.screenshotbye.service.WatchService
import java.util.concurrent.TimeUnit

enum class Decision { DELETE, KEEP, SNOOZE }

/** 一次决策的结果，UI 用它决定 Toast 文案。 */
sealed interface DecisionOutcome {
    data class Deleted(val inTrash: Boolean) : DecisionOutcome
    data object Kept : DecisionOutcome
    data object Snoozed : DecisionOutcome
    /** 需要系统弹窗确认，已发出一条「点按以确认删除」的通知。 */
    data object NeedsSystemConfirm : DecisionOutcome
    data class Error(val reason: String) : DecisionOutcome
}

/**
 * 截图从「被发现」到「被处置」的中枢。通知按钮、应用内按钮、定时任务都调这里，
 * 保证删除逻辑只有一份。
 */
class ScreenshotRepository(ctx: Context) {

    private val app = ctx.applicationContext
    private val store = PendingShotStore.of(app)
    private val settings = Settings.of(app)
    private val gateway = DeleteGateway(app)

    /** MediaStore 里冒出一张新截图：登记、起 N 分钟计时。 */
    fun onDetected(hit: MediaHit) {
        val now = System.currentTimeMillis()
        val shot = PendingShot(
            mediaId = hit.mediaId,
            uri = hit.uri.toString(),
            name = hit.displayName,
            folder = hit.folder,
            relativePath = hit.relativePath,
            size = hit.size,
            takenAt = hit.dateAddedSec * 1000L,
            askAt = now + settings.delayMinutes * 60_000L,
            state = ShotState.WAITING,
            absolutePath = hit.absolutePath,
        )
        store.upsert(shot)
        Reminders.schedule(app, shot.mediaId, settings.delayMinutes)
        settings.appendLog("检测到截图 ${shot.name}，${settings.delayMinutes} 分钟后询问")
        WatchService.refreshNotification(app)
    }

    fun pending(): List<PendingShot> = store.all()
    fun find(mediaId: Long): PendingShot? = store.find(mediaId)

    fun decide(mediaId: Long, decision: Decision): DecisionOutcome {
        val shot = store.find(mediaId) ?: run {
            Notify.cancel(app, Notify.reviewNotifId(mediaId))
            return DecisionOutcome.Error("这条记录已经处理过了")
        }
        return when (decision) {
            Decision.KEEP -> {
                Reminders.cancel(app, mediaId)
                store.remove(mediaId)
                Notify.cancel(app, Notify.reviewNotifId(mediaId))
                settings.appendLog("保留 ${shot.name}")
                WatchService.refreshNotification(app)
                DecisionOutcome.Kept
            }

            Decision.SNOOZE -> {
                val askAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
                store.upsert(shot.copy(state = ShotState.WAITING, askAt = askAt))
                Reminders.rescheduleAt(app, mediaId, askAt)
                Notify.cancel(app, Notify.reviewNotifId(mediaId))
                settings.appendLog("稍后再问 ${shot.name}")
                WatchService.refreshNotification(app)
                DecisionOutcome.Snoozed
            }

            Decision.DELETE -> delete(shot)
        }
    }

    private fun delete(shot: PendingShot): DecisionOutcome {
        Reminders.cancel(app, shot.mediaId)
        return when (val r = gateway.deleteNow(shot)) {
            is DeleteResult.Removed -> {
                store.remove(shot.mediaId)
                Notify.notify(
                    app, Notify.reviewNotifId(shot.mediaId),
                    Notify.deleted(app, shot, r.backup != null)
                )
                settings.appendLog(
                    if (r.backup != null) "已删除 ${shot.name}（回收站可找回）" else "已删除 ${shot.name}"
                )
                WatchService.refreshNotification(app)
                DecisionOutcome.Deleted(inTrash = r.backup != null)
            }

            is DeleteResult.NeedsConfirm -> {
                store.upsert(shot.copy(state = ShotState.DELETING))
                Notify.notify(
                    app, Notify.reviewNotifId(shot.mediaId),
                    Notify.needsSystemConfirm(app, shot)
                )
                settings.appendLog("等待系统确认：${shot.name}")
                DecisionOutcome.NeedsSystemConfirm
            }

            is DeleteResult.Failed -> {
                // 备份失败或权限被拒时保留条目，让用户能重试，绝不静默丢弃
                store.upsert(shot.copy(state = ShotState.ASKED))
                settings.appendLog("删除失败：${r.reason}")
                DecisionOutcome.Error(r.reason)
            }
        }
    }

    /**
     * 系统删除确认弹窗结束后调用。以「原图还在不在」判断用户是否真的确认了删除：
     * 还在 = 用户取消，抹掉多余备份并把条目退回已询问状态；
     * 不在 = 删除成功，移出待确认列表，备份留在回收站。
     */
    fun onConfirmResult(mediaId: Long): DecisionOutcome {
        val shot = store.find(mediaId) ?: return DecisionOutcome.Error("这条记录已经处理过了")
        val gone = gateway.finalizeAfterConfirm(shot)
        return if (gone) {
            store.remove(mediaId)
            Notify.notify(app, Notify.reviewNotifId(mediaId), Notify.deleted(app, shot, settings.useTrash))
            settings.appendLog("系统确认后已删除：${shot.name}")
            WatchService.refreshNotification(app)
            DecisionOutcome.Deleted(inTrash = settings.useTrash)
        } else {
            store.upsert(shot.copy(state = ShotState.ASKED))
            settings.appendLog("用户在系统弹窗中取消了删除：${shot.name}")
            DecisionOutcome.Kept
        }
    }

    val trash get() = gateway.trash

    fun ensureCleanupSchedule() {
        val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "trash-cleanup", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    companion object {
        const val SNOOZE_MINUTES = 10
    }
}
