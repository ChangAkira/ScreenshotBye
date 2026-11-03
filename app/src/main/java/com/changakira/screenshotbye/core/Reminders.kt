package com.changakira.screenshotbye.core

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.changakira.screenshotbye.remind.AlertRetryWorker
import com.changakira.screenshotbye.remind.ReminderWorker
import java.util.concurrent.TimeUnit

/**
 * 「过 N 分钟问一次」的定时器。
 *
 * 用 WorkManager 而不是 Handler.postDelayed：进程被系统杀掉或重启后，Handler 的排队任务
 * 会丢失，而 WorkManager 的任务持久化在数据库里，到点仍会执行（Doze 下可能延后，
 * 因此配合前台服务保活，并在服务重启时补发已到期的询问）。
 */
object Reminders {

    private fun workName(mediaId: Long) = "ask-$mediaId"

    fun schedule(ctx: Context, mediaId: Long, delayMinutes: Int) {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMinutes.coerceAtLeast(1).toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf(ReminderWorker.KEY_MEDIA_ID to mediaId))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(workName(mediaId), ExistingWorkPolicy.REPLACE, request)
    }

    /** 稍后再问：按剩余时间重新排队。 */
    fun rescheduleAt(ctx: Context, mediaId: Long, askAtMillis: Long) {
        val minutes = ((askAtMillis - System.currentTimeMillis()) / 60_000L).toInt()
        schedule(ctx, mediaId, if (minutes < 1) 1 else minutes)
    }

    fun cancel(ctx: Context, mediaId: Long) {
        WorkManager.getInstance(ctx).cancelUniqueWork(workName(mediaId))
    }

    /** 服务重启时，把已经过期但没发出的询问立即补上。 */
    fun catchUpOverdue(ctx: Context, store: PendingShotStore) {
        val now = System.currentTimeMillis()
        store.all().filter { it.state == ShotState.WAITING }.forEach { shot ->
            if (shot.askAt <= now) {
                WorkManager.getInstance(ctx)
                    .enqueueUniqueWork(
                        workName(shot.mediaId), ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<ReminderWorker>()
                            .setInputData(workDataOf(ReminderWorker.KEY_MEDIA_ID to shot.mediaId))
                            .addTag(TAG).build()
                    )
            } else {
                schedule(ctx, shot.mediaId, ((shot.askAt - now) / 60_000L).toInt().coerceAtLeast(1))
            }
        }
    }

    /**
     * 询问通知被划掉后，过几分钟再提醒一次。用唯一任务名，反复划掉也只留一个待办。
     * 至少 5 分钟，避免用户刚清完通知栏就被第二次铃声砸到。
     */
    fun scheduleAlertRetry(ctx: Context, delayMinutes: Int) {
        // 补提醒不能贴着上一声立刻再来（1 分钟是档位下限），也不能拖到像没安排一样
        val minutes = delayMinutes.coerceIn(1, 180).toLong()
        val request = OneTimeWorkRequestBuilder<AlertRetryWorker>()
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(ALERT_RETRY, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAlertRetry(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(ALERT_RETRY)
    }

    const val TAG = "screenshotbye-reminders"
    const val ALERT_RETRY = "screenshotbye-alert-retry"
}
