package com.changakira.screenshotbye.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.ui.DeleteConfirmActivity
import com.changakira.screenshotbye.ui.MainActivity
import com.changakira.screenshotbye.ui.ReviewActivity
import com.changakira.screenshotbye.ui.TrashActivity
import java.util.Date

object Channels {
    const val SERVICE = "service"
    const val REVIEW = "review"

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(SERVICE, ctx.getString(R.string.ch_service), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.ch_service_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(REVIEW, ctx.getString(R.string.ch_review), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.ch_review_desc)
            }
        )
    }
}

object Actions {
    const val ACTION_DELETE = "com.changakira.screenshotbye.DELETE"
    const val ACTION_KEEP = "com.changakira.screenshotbye.KEEP"
    const val ACTION_SNOOZE_ALL = "com.changakira.screenshotbye.SNOOZE_ALL"
    const val ACTION_STOP_WATCH = "com.changakira.screenshotbye.STOP_WATCH"
    const val EXTRA_MEDIA_ID = "media_id"

    fun shotIntent(ctx: Context, action: String, mediaId: Long): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_MEDIA_ID, mediaId)
        }
        val requestCode = (action.hashCode() xor mediaId.toInt()) and 0xFFFF
        return PendingIntent.getBroadcast(
            ctx, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** 通知构造。 */
object Notify {

    const val ID_SERVICE = 1001
    const val ID_RECOVERY = 1002

    /** 待确认清单只占这一条通知，截图多了也不会刷屏。 */
    const val ID_REVIEW_QUEUE = 1003

    /** 看门狗无法从后台拉起服务时，请用户点一下恢复。 */
    fun recoveryNeeded(ctx: Context): Notification {
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, Channels.REVIEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_recovery_title))
            .setContentText(ctx.getString(R.string.notif_recovery_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.notif_recovery_body)))
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(open)
            .build()
    }

    fun serviceNotification(ctx: Context, watching: Boolean, pending: Int): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            !watching -> ctx.getString(R.string.notif_watch_off)
            pending > 0 -> ctx.getString(R.string.notif_watch_on_pending, pending)
            else -> ctx.getString(R.string.notif_watch_on)
        }
        return NotificationCompat.Builder(ctx, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(
                R.drawable.ic_delete,
                ctx.getString(R.string.notif_stop_watch),
                Actions.shotIntent(ctx, Actions.ACTION_STOP_WATCH, 0L)
            )
            .build()
    }

    /**
     * 五分钟到点后问用户：还需要这些截图吗？
     *
     * 整份待确认清单共用一条通知：连截十张也只问一次，点开就是逐个确认的卡片页。
     * 通知上的快捷按钮作用于最早到期的那一张，方便不动手时也能一张张清掉。
     */
    fun review(ctx: Context, queue: List<PendingShot>): Notification {
        val oldest = queue.first()
        val preview = loadPreview(ctx, oldest.contentUri)
        val title = if (queue.size == 1) ctx.getString(R.string.notif_review_title)
        else ctx.getString(R.string.notif_review_title_multi, queue.size)
        val body = buildString {
            queue.take(4).forEach { append(it.name).append("  · ").append(formatTime(ctx, it)).append('\n') }
            if (queue.size > 4) append(ctx.getString(R.string.notif_review_more, queue.size - 4))
            append(ctx.getString(R.string.notif_review_hint))
        }
        return NotificationCompat.Builder(ctx, Channels.REVIEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(
                if (queue.size == 1) ctx.getString(R.string.notif_review_short, oldest.name)
                else ctx.getString(R.string.notif_review_multi_short, oldest.name, queue.size)
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(oldest.name)
            .setOnlyAlertOnce(true)
            .setContentIntent(reviewIntent(ctx, oldest.mediaId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                R.drawable.ic_delete,
                ctx.getString(R.string.act_delete),
                Actions.shotIntent(ctx, Actions.ACTION_DELETE, oldest.mediaId)
            )
            .addAction(
                R.drawable.ic_keep,
                ctx.getString(R.string.act_keep),
                Actions.shotIntent(ctx, Actions.ACTION_KEEP, oldest.mediaId)
            )
            .addAction(
                R.drawable.ic_clock,
                if (queue.size == 1) ctx.getString(R.string.act_snooze) else ctx.getString(R.string.act_snooze_all),
                Actions.shotIntent(ctx, Actions.ACTION_SNOOZE_ALL, 0L)
            )
            .apply { if (preview != null) setLargeIcon(preview) }
            .build()
    }

    private fun formatTime(ctx: Context, shot: PendingShot): String =
        android.text.format.DateFormat.getTimeFormat(ctx).format(Date(shot.takenAt))

    fun reviewIntent(ctx: Context, focusMediaId: Long): PendingIntent {
        val i = Intent(ctx, ReviewActivity::class.java)
            .putExtra(ReviewActivity.EXTRA_FOCUS_ID, focusMediaId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            ctx, 0x3100, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Android 11+ 没给「所有文件访问」时，删除必须由用户在系统弹窗里确认。
     * 一批待确认的截图共用一次系统弹窗，所以这条通知带的是 id 列表。
     */
    fun needsSystemConfirm(ctx: Context, shots: List<PendingShot>): Notification {
        val first = shots.first()
        val body = if (shots.size == 1) ctx.getString(R.string.notif_confirm_body, first.name)
        else ctx.getString(R.string.notif_confirm_body_multi, shots.size)
        val tap = PendingIntent.getActivity(
            ctx, 0x3200,
            Intent(ctx, DeleteConfirmActivity::class.java)
                .putExtra(DeleteConfirmActivity.EXTRA_MEDIA_IDS, shots.map { it.mediaId }.toLongArray())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, Channels.REVIEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_confirm_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .build()
    }

    /** 删除结果回执，复用同一条通知槽位，点按即消失。 */
    fun deleted(ctx: Context, shot: PendingShot, inTrash: Boolean): Notification {
        val open = PendingIntent.getActivity(
            ctx, (shot.mediaId.toInt() + 11),
            (if (inTrash) Intent(ctx, TrashActivity::class.java) else Intent(ctx, MainActivity::class.java))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (inTrash) ctx.getString(R.string.notif_deleted_trash, shot.name)
        else ctx.getString(R.string.notif_deleted_permanent, shot.name)
        return NotificationCompat.Builder(ctx, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_deleted_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOngoing(false)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    fun notify(ctx: Context, id: Int, n: Notification) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        nm.notify(id, n)
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    /** 「点按完成系统确认」单独占一条；没有待确认删除的截图时由 refreshReviewNotification 撤掉。 */
    const val ID_CONFIRM = 1004

    fun reviewNotifId(mediaId: Long): Int = (mediaId and 0x7FFF_FFFFL).toInt() xor 0x5000_0000

    /** 缩略图：两遍解码，控制在 512px 以内，避免大图 OOM。 */
    private fun loadPreview(ctx: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample > 512) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()
}
