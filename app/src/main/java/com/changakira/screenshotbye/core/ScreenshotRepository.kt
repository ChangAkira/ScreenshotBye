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
            stateAt = now,
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

    /** 到期的那一条（最早问的），常驻通知点击时定位到它。 */
    fun oldestPending(): PendingShot? = store.all().minByOrNull { it.askAt }

    /** 定时任务到点：推进 ASKED，并让刷新去判断这次要不要重新出声。 */
    fun onReminderFired(mediaId: Long): Boolean {
        val shot = store.find(mediaId) ?: return false
        store.upsert(shot.copy(state = ShotState.ASKED, stateAt = System.currentTimeMillis()))
        settings.appendLog("已询问：${shot.name}")
        refreshReviewNotification(app, newDue = true)
        return true
    }

    fun decide(mediaId: Long, decision: Decision): DecisionOutcome {
        val shot = store.find(mediaId)
            ?: return DecisionOutcome.Error("这条记录已经处理过了")
        return when (decision) {
            Decision.KEEP -> {
                Reminders.cancel(app, mediaId)
                store.remove(mediaId)
                settings.appendLog("保留 ${shot.name}")
                updateReviewNotification(app)
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
            updateReviewNotification(app)
        }
        return due.size
    }

    private fun snooze(shot: PendingShot, refresh: Boolean = true) {
        val askAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        // 用户主动要求再问，提醒次数清零，否则封顶规则会让「稍后再问」变成永久静音
        store.upsert(shot.copy(state = ShotState.WAITING, askAt = askAt, stateAt = System.currentTimeMillis(), askCount = 0))
        Reminders.rescheduleAt(app, shot.mediaId, askAt)
        settings.appendLog("稍后再问 ${shot.name}")
        if (refresh) updateReviewNotification(app)
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
                updateReviewNotification(app)
                DecisionOutcome.Deleted(inTrash = r.backup != null)
            }

            is DeleteResult.NeedsConfirm -> {
                markDeleting(shot)
                settings.appendLog("等待系统确认：${shot.name}")
                updateReviewNotification(app)
                DecisionOutcome.NeedsSystemConfirm
            }

            is DeleteResult.Failed -> {
                // 备份失败或权限被拒时保留条目，让用户能重试，绝不静默丢弃
                store.upsert(shot.copy(state = ShotState.ASKED, stateAt = System.currentTimeMillis()))
                settings.appendLog("删除失败：${r.reason}")
                updateReviewNotification(app)
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
                        markDeleting(shot)
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
        updateReviewNotification(app)
        return BatchPlan(deleted, awaiting, failed)
    }

    /** 无静默权限时的准备：只备份并标记状态，真正删除交给系统弹窗。备份失败就不许删。 */
    private fun prepareForSystemConfirm(shot: PendingShot, failed: MutableList<String>): DeleteResult {
        if (settings.useTrash && gateway.trash.backUp(shot) == null) {
            failed += "${shot.name}：备份失败，已取消删除"
            return DeleteResult.Failed("备份失败，已取消删除")
        }
        return if (gateway.deleteRequestIntentSender(listOf(shot.contentUri)) != null) {
            markDeleting(shot)
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
                store.upsert(shot.copy(state = ShotState.ASKED, stateAt = System.currentTimeMillis()))
                cancelled++
                settings.appendLog("你在系统弹窗中取消了删除：${shot.name}")
            }
        }
        updateReviewNotification(app)
        return deleted to cancelled
    }

    /**
     * 队列卫生，由看门狗周期性调用，兜住三种「静默卡住」：
     * - WAITING 但到期时间早就过了（定时任务被 ROM 清掉）→ 立即补问
     * - ASKED 长期没人回答 → 按「保留」处理出队，通知不再无限挂着
     * - DELETING 超时没拿到系统确认结果（弹窗开着时进程被杀）→ 退回 ASKED
     */
    fun sweepStale(): Int {
        val now = System.currentTimeMillis()
        val expiryMs = if (settings.askExpiryDays > 0) settings.askExpiryDays * DAY_MS else 0L
        var changed = 0
        store.all().forEach { shot ->
            when (shot.state) {
                ShotState.WAITING -> if (shot.askAt <= now - LATE_GRACE_MS) {
                    store.upsert(shot.copy(state = ShotState.ASKED, stateAt = now))
                    settings.appendLog("补发已到期的询问：${shot.name}")
                    changed++
                }

                ShotState.ASKED -> if (expiryMs > 0L && now - shot.sinceStateAt >= expiryMs) {
                    Reminders.cancel(app, shot.mediaId)
                    store.remove(shot.mediaId)
                    settings.appendLog("超过 ${settings.askExpiryDays} 天未回答，按保留处理：${shot.name}")
                    changed++
                }

                ShotState.DELETING -> if (now - shot.sinceStateAt >= DELETING_TIMEOUT_MS) {
                    store.upsert(shot.copy(state = ShotState.ASKED, stateAt = now))
                    settings.appendLog("系统确认超时未完成，退回待确认：${shot.name}")
                    changed++
                }
            }
        }
        if (changed > 0) updateReviewNotification(app)
        return changed
    }

    private fun markDeleting(shot: PendingShot) =
        store.upsert(shot.copy(state = ShotState.DELETING, stateAt = System.currentTimeMillis()))

    /**
     * 把清单同步到通知栏，并允许按节流规则重新出声。
     * 只有「刚有截图到期」「看门狗心跳」「通知被划掉后的补提醒」这三类调用会走到这里；
     * 用户在应用内自己操作后的同步走 [updateReviewNotification]，别再打扰。
     */
    fun refreshReviewNotification(ctx: Context, newDue: Boolean = false) = syncNotifications(ctx, canAlert = true, newDue = newDue)

    /** 只同步内容，不出声。 */
    fun updateReviewNotification(ctx: Context) = syncNotifications(ctx, canAlert = false, newDue = false)

    private fun syncNotifications(ctx: Context, canAlert: Boolean, newDue: Boolean) {
        val now = System.currentTimeMillis()
        val due = dueQueue()
        if (due.isEmpty()) {
            settings.lastAlertAt = 0L
            Reminders.cancelAlertRetry(app)
            Notify.cancel(ctx, Notify.ID_REVIEW_QUEUE)
        } else {
            val alert = canAlert && shouldAlert(due, newDue, now)
            Notify.notify(ctx, Notify.ID_REVIEW_QUEUE, Notify.review(ctx, due, alert))
            if (alert) {
                settings.lastAlertAt = now
                due.forEach { store.upsert(it.copy(askCount = it.askCount + 1)) }
            }
        }

        val awaiting = store.all().filter { it.state == ShotState.DELETING }.sortedBy { it.askAt }
        if (awaiting.isEmpty()) Notify.cancel(ctx, Notify.ID_CONFIRM)
        else Notify.notify(ctx, Notify.ID_CONFIRM, Notify.needsSystemConfirm(ctx, awaiting))

        WatchService.refreshNotification(ctx)
    }

    /**
     * 要不要重新出声。同一条通知默认只响第一次，所以必须显式再响一次，
     * 否则一天里的第二张、第三张截图到期就完全无感。
     */
    private fun shouldAlert(due: List<PendingShot>, newDue: Boolean, now: Long): Boolean {
        val last = settings.lastAlertAt
        if (last == 0L) return true
        // 提醒够次数了就只留通知不出声，避免变成骚扰
        if (due.minOf { it.askCount } >= settings.alertMaxTimes) return false
        if (newDue) return now - last >= BURST_GUARD_MS
        val repeat = settings.alertRepeatMinutes
        return repeat > 0 && now - last >= repeat * 60_000L
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
        private const val DAY_MS = 24 * 60 * 60_000L

        /** 连拍保护：几秒内多张一起到期只响一次。 */
        private const val BURST_GUARD_MS = 90_000L

        /** 定时任务丢失的容忍窗口。 */
        private const val LATE_GRACE_MS = 2 * 60_000L

        /** 系统确认弹窗长时间没结果（进程被杀等），退回待确认。 */
        private const val DELETING_TIMEOUT_MS = 6 * 60 * 60_000L
    }
}
