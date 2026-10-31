package com.changakira.screenshotbye.core

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.changakira.screenshotbye.media.DeleteGateway
import com.changakira.screenshotbye.media.DeleteResult
import com.changakira.screenshotbye.media.MediaHit
import com.changakira.screenshotbye.notify.Notify
import com.changakira.screenshotbye.perm.Perms
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
 * 批量处置的结果。
 * @param deleted 已直接删掉的张数（有「所有文件访问」或 Android 10 及以下）
 * @param awaitingConfirm 需要一次系统弹窗确认的媒体 id，调用方拿去拉起 createDeleteRequest
 * @param failed 失败原因，逐条写进事件日志
 */
data class BatchPlan(val deleted: Int, val awaitingConfirm: List<Long>, val failed: List<String>)

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

    /** 到期了、还没得到答复的截图，按到期时间从早到晚。 */
    fun dueQueue(): List<PendingShot> =
        store.all().filter { it.state == ShotState.ASKED }.sortedBy { it.askAt }

    fun decide(mediaId: Long, decision: Decision): DecisionOutcome {
        val shot = store.find(mediaId)
            ?: return DecisionOutcome.Error("这条记录已经处理过了")
        return when (decision) {
            Decision.KEEP -> {
                Reminders.cancel(app, mediaId)
                store.remove(mediaId)
                settings.appendLog("保留 ${shot.name}")
                refreshReviewNotification(app)
                DecisionOutcome.Kept
            }

            Decision.SNOOZE -> {
                snooze(shot)
                DecisionOutcome.Snoozed
            }

            Decision.DELETE -> delete(shot)
        }
    }

    /** 「全部稍后再问」：把已到期的整批往后推，未到期（还在计时）的不动。 */
    fun snoozeAllDue(): Int {
        val due = dueQueue()
        due.forEach { snooze(it, refresh = false) }
        if (due.isNotEmpty()) {
            settings.appendLog("整批稍后再问：${due.size} 张")
            refreshReviewNotification(app)
        }
        return due.size
    }

    private fun snooze(shot: PendingShot, refresh: Boolean = true) {
        val askAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        store.upsert(shot.copy(state = ShotState.WAITING, askAt = askAt))
        Reminders.rescheduleAt(app, shot.mediaId, askAt)
        settings.appendLog("稍后再问 ${shot.name}")
        if (refresh) refreshReviewNotification(app)
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
                refreshReviewNotification(app)
                DecisionOutcome.Deleted(inTrash = r.backup != null)
            }

            is DeleteResult.NeedsConfirm -> {
                store.upsert(shot.copy(state = ShotState.DELETING))
                settings.appendLog("等待系统确认：${shot.name}")
                refreshReviewNotification(app)
                DecisionOutcome.NeedsSystemConfirm
            }

            is DeleteResult.Failed -> {
                // 备份失败或权限被拒时保留条目，让用户能重试，绝不静默丢弃
                store.upsert(shot.copy(state = ShotState.ASKED))
                settings.appendLog("删除失败：${r.reason}")
                refreshReviewNotification(app)
                DecisionOutcome.Error(r.reason)
            }
        }
    }

    /**
     * 批量处置：逐个确认页一次标记多张时走这里。
     *
     * 有静默删除权限时逐张备份 + 删除；没有时先把整批备份好、标记为待系统确认，
     * 再由调用方用一次 MediaStore.createDeleteRequest 收口——避免每张弹一次系统框。
     */
    fun deleteBatch(mediaIds: List<Long>): BatchPlan {
        var deleted = 0
        val awaiting = mutableListOf<Long>()
        val failed = mutableListOf<String>()
        val silent = Perms.canDeleteSilently(app)

        mediaIds.forEach { id ->
            val shot = store.find(id) ?: return@forEach
            Reminders.cancel(app, id)
            if (silent) {
                when (val r = gateway.deleteNow(shot)) {
                    is DeleteResult.Removed -> {
                        store.remove(id)
                        deleted++
                        settings.appendLog(
                            if (r.backup != null) "已删除 ${shot.name}（回收站可找回）" else "已删除 ${shot.name}"
                        )
                    }

                    is DeleteResult.NeedsConfirm -> {
                        store.upsert(shot.copy(state = ShotState.DELETING))
                        awaiting += id
                    }

                    is DeleteResult.Failed -> failed += "${shot.name}：${r.reason}"
                }
            } else {
                val outcome = prepareForSystemConfirm(shot, failed)
                if (outcome is DeleteResult.NeedsConfirm) awaiting += id
            }
        }

        settings.appendLog(
            when {
                awaiting.isNotEmpty() -> "已准备 ${awaiting.size} 张，等待系统确认"
                deleted > 0 -> "批量删除 $deleted 张"
                else -> null
            } ?: "批量删除：无成功项"
        )
        refreshReviewNotification(app)
        return BatchPlan(deleted, awaiting, failed)
    }

    /** 无静默权限时的准备：只备份并标记状态，真正删除交给系统弹窗。备份失败就不许删。 */
    private fun prepareForSystemConfirm(shot: PendingShot, failed: MutableList<String>): DeleteResult {
        if (settings.useTrash && gateway.trash.backUp(shot) == null) {
            failed += "${shot.name}：备份失败，已取消删除"
            return DeleteResult.Failed("备份失败，已取消删除")
        }
        return if (gateway.deleteRequestIntentSender(listOf(shot.contentUri)) != null) {
            store.upsert(shot.copy(state = ShotState.DELETING))
            DeleteResult.NeedsConfirm(null)
        } else {
            failed += "${shot.name}：系统不支持删除确认"
            DeleteResult.Failed("系统不支持删除确认")
        }
    }

    /**
     * 系统删除确认弹窗结束后调用，支持一整批。
     * 以「原图还在不在」为准：还在 = 用户点了取消，抹掉多余备份并退回已询问状态；
     * 不在 = 删除成功，移出待确认列表，备份留在回收站。
     */
    fun onBatchConfirmResult(mediaIds: List<Long>): Pair<Int, Int> {
        var deleted = 0
        var cancelled = 0
        mediaIds.forEach { id ->
            val shot = store.find(id) ?: return@forEach
            if (gateway.finalizeAfterConfirm(shot)) {
                store.remove(id)
                deleted++
                settings.appendLog("系统确认后已删除：${shot.name}")
            } else {
                store.upsert(shot.copy(state = ShotState.ASKED))
                cancelled++
                settings.appendLog("你在系统弹窗中取消了删除：${shot.name}")
            }
        }
        refreshReviewNotification(app)
        return deleted to cancelled
    }

    /**
     * 重建两条与待确认清单有关的通知：整批询问（一条）、等待系统确认（一条）。
     * 清单空了就撤销，避免残留。
     */
    fun refreshReviewNotification(ctx: Context) {
        val due = dueQueue()
        if (due.isEmpty()) Notify.cancel(ctx, Notify.ID_REVIEW_QUEUE)
        else Notify.notify(ctx, Notify.ID_REVIEW_QUEUE, Notify.review(ctx, due))

        val awaiting = store.all().filter { it.state == ShotState.DELETING }.sortedBy { it.askAt }
        if (awaiting.isEmpty()) Notify.cancel(ctx, Notify.ID_CONFIRM)
        else Notify.notify(ctx, Notify.ID_CONFIRM, Notify.needsSystemConfirm(ctx, awaiting))

        WatchService.refreshNotification(ctx)
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
