package com.changakira.screenshotbye.core

import android.content.Context
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.media.DeleteGateway
import com.changakira.screenshotbye.media.DeleteMode
import com.changakira.screenshotbye.media.DeleteResult
import com.changakira.screenshotbye.media.MediaHit
import com.changakira.screenshotbye.notify.Notify
import com.changakira.screenshotbye.service.WatchService

enum class Decision { DELETE, KEEP, SNOOZE }

/** 一次决策的结果，UI 用它决定 Toast 文案。 */
sealed interface DecisionOutcome {
    /** 有静默删除权限时直接删掉了；否则 Android 11+ 返回 NeedsSystemConfirm。 */
    data object Deleted : DecisionOutcome
    data object Kept : DecisionOutcome
    data object Snoozed : DecisionOutcome
    /** 需要系统弹窗确认，已发出一条「点按以确认删除」的通知。 */
    data object NeedsSystemConfirm : DecisionOutcome
    data class Error(val reason: String) : DecisionOutcome
}

/**
 * 批量处置的结果。
 * @param deleted 不经系统弹窗就删掉的张数（需要 WRITE 或「所有文件访问」）
 * @param awaitingConfirm 需要一次系统弹窗确认的媒体 id，调用方拿去拉起回收站请求
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
        settings.appendLog(app.getString(R.string.log_detected, shot.name, settings.delayMinutes))
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
        settings.appendLog(app.getString(R.string.log_asked, shot.name))
        refreshReviewNotification(app, newDue = true)
        return true
    }

    fun decide(mediaId: Long, decision: Decision): DecisionOutcome {
        val shot = store.find(mediaId)
            ?: return DecisionOutcome.Error(app.getString(R.string.err_record_gone))
        return when (decision) {
            Decision.KEEP -> {
                Reminders.cancel(app, mediaId)
                store.remove(mediaId)
                settings.appendLog(app.getString(R.string.log_kept, shot.name))
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
            settings.appendLog(app.getString(R.string.log_snooze_all, due.size))
            updateReviewNotification(app)
        }
        return due.size
    }

    private fun snooze(shot: PendingShot, refresh: Boolean = true) {
        val askAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        // 用户主动要求再问，提醒次数清零，否则封顶规则会让「稍后再问」变成永久静音
        store.upsert(shot.copy(state = ShotState.WAITING, askAt = askAt, stateAt = System.currentTimeMillis(), askCount = 0))
        Reminders.rescheduleAt(app, shot.mediaId, askAt)
        settings.appendLog(app.getString(R.string.log_snoozed, shot.name))
        if (refresh) updateReviewNotification(app)
    }

    private fun delete(shot: PendingShot): DecisionOutcome {
        Reminders.cancel(app, shot.mediaId)
        return when (val r = gateway.deleteNow(shot)) {
            is DeleteResult.Removed -> {
                store.remove(shot.mediaId)
                // 原件去向以本机实测为准，界面上那句提示跟着它变
                settings.silentDeleteOutcome =
                    if (r.trashed) Settings.OUTCOME_TRASHED else Settings.OUTCOME_GONE
                Notify.notify(app, Notify.reviewNotifId(shot.mediaId), Notify.deleted(app, shot))
                settings.appendLog(
                    app.getString(
                        if (r.trashed) R.string.log_trashed else R.string.log_deleted_permanent,
                        shot.name,
                    )
                )
                updateReviewNotification(app)
                DecisionOutcome.Deleted
            }

            DeleteResult.NeedsConfirm -> {
                markDeleting(shot)
                settings.appendLog(app.getString(R.string.log_wait_system, shot.name))
                updateReviewNotification(app)
                DecisionOutcome.NeedsSystemConfirm
            }

            is DeleteResult.Failed -> {
                // 权限被拒时保留条目，让用户能重试，绝不静默丢弃
                store.upsert(shot.copy(state = ShotState.ASKED, stateAt = System.currentTimeMillis()))
                settings.appendLog(app.getString(R.string.log_delete_failed, r.reason))
                updateReviewNotification(app)
                DecisionOutcome.Error(r.reason)
            }
        }
    }

    /**
     * 批量处置：逐个看时标为「不需要」的一整批在这里收口。
     *
     * Android 11+ 先把整批标记为待系统确认，再由调用方用一次回收站请求收口——避免每张弹一次系统框；
     * Android 10 及以下有写权限时逐张直删，那一版没有回收站，删掉就找不回来了。
     */
    fun deleteBatch(mediaIds: List<Long>): BatchPlan {
        var deleted = 0
        val awaiting = mutableListOf<Long>()
        val failed = mutableListOf<String>()

        mediaIds.forEach { id ->
            val shot = store.find(id) ?: return@forEach
            Reminders.cancel(app, id)
            when (val r = gateway.deleteNow(shot)) {
                is DeleteResult.Removed -> {
                    store.remove(id)
                    deleted++
                    settings.silentDeleteOutcome =
                        if (r.trashed) Settings.OUTCOME_TRASHED else Settings.OUTCOME_GONE
                    settings.appendLog(
                        app.getString(
                            if (r.trashed) R.string.log_trashed else R.string.log_deleted_permanent,
                            shot.name,
                        )
                    )
                }

                DeleteResult.NeedsConfirm -> {
                    markDeleting(shot)
                    awaiting += id
                }

                is DeleteResult.Failed -> failed += app.getString(R.string.log_batch_item_failed, shot.name, r.reason)
            }
        }

        settings.appendLog(
            when {
                awaiting.isNotEmpty() -> app.getString(R.string.log_batch_prepared, awaiting.size)
                deleted > 0 -> app.getString(R.string.log_batch_deleted, deleted)
                else -> null
            } ?: app.getString(R.string.log_batch_none)
        )
        updateReviewNotification(app)
        return BatchPlan(deleted, awaiting, failed)
    }

    /**
     * 系统删除确认弹窗结束后调用，支持一整批。
     * 以「相册里还查得到吗」为准：查不到 = 已进系统回收站（或已被真删），移出待确认清单；
     * 还查得到 = 用户在弹窗里点了取消，退回已询问状态，下次还能处理。
     */
    fun onBatchConfirmResult(mediaIds: List<Long>, mode: DeleteMode = DeleteMode.TRASH): Pair<Int, Int> {
        var done = 0
        var cancelled = 0
        mediaIds.forEach { id ->
            val shot = store.find(id) ?: return@forEach
            if (gateway.finalizeAfterConfirm(shot)) {
                store.remove(id)
                done++
                settings.appendLog(
                    if (mode == DeleteMode.TRASH) app.getString(R.string.log_trashed, shot.name)
                    else app.getString(R.string.log_confirmed_deleted, shot.name)
                )
            } else {
                store.upsert(shot.copy(state = ShotState.ASKED, stateAt = System.currentTimeMillis()))
                cancelled++
                settings.appendLog(app.getString(R.string.log_cancelled_in_dialog, shot.name))
            }
        }
        updateReviewNotification(app)
        return done to cancelled
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
                    settings.appendLog(app.getString(R.string.log_catch_up, shot.name))
                    changed++
                }

                ShotState.ASKED -> if (expiryMs > 0L && now - shot.sinceStateAt >= expiryMs) {
                    Reminders.cancel(app, shot.mediaId)
                    store.remove(shot.mediaId)
                    settings.appendLog(app.getString(R.string.log_expired_keep, settings.askExpiryDays, shot.name))
                    changed++
                }

                ShotState.DELETING -> if (now - shot.sinceStateAt >= DELETING_TIMEOUT_MS) {
                    store.upsert(shot.copy(state = ShotState.ASKED, stateAt = now))
                    settings.appendLog(app.getString(R.string.log_confirm_timeout, shot.name))
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
        // askCount 记的是已经问过的次数，第一次不算重复，所以比较的是「次数 + 1」
        if (due.minOf { it.askCount } > settings.alertMaxTimes) return false
        if (newDue) return now - last >= BURST_GUARD_MS
        return now - last >= settings.alertRepeatMinutes * 60_000L
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
