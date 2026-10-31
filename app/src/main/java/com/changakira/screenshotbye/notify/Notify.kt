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
import com.changakira.screenshotbye.ui.TrashActivity
import java.text.DateFormat
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
    const val ACTION_SNOOZE = "com.changakira.screenshotbye.SNOOZE"
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

    /** 五分钟到点后问用户：这张截图还需要吗？ */
    fun review(ctx: Context, shot: PendingShot): Notification {
        val openShot = PendingIntent.getActivity(
            ctx, shot.mediaId.toInt(),
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_MEDIA_ID, shot.mediaId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val preview = loadPreview(ctx, shot.contentUri)
        val style = NotificationCompat.BigTextStyle().bigText(
            ctx.getString(R.string.notif_review_body, shot.name, formatWhen(shot))
        )
        return NotificationCompat.Builder(ctx, Channels.REVIEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_review_title))
            .setContentText(ctx.getString(R.string.notif_review_short, shot.name))
            .setStyle(style)
            .setSubText(shot.name)
            .setContentIntent(openShot)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                R.drawable.ic_delete,
                ctx.getString(R.string.act_delete),
                Actions.shotIntent(ctx, Actions.ACTION_DELETE, shot.mediaId)
            )
            .addAction(
                R.drawable.ic_keep,
                ctx.getString(R.string.act_keep),
                Actions.shotIntent(ctx, Actions.ACTION_KEEP, shot.mediaId)
            )
            .addAction(
                R.drawable.ic_clock,
                ctx.getString(R.string.act_snooze),
                Actions.shotIntent(ctx, Actions.ACTION_SNOOZE, shot.mediaId)
            )
            .apply { if (preview != null) setLargeIcon(preview) }
            .build()
    }

    /** Android 11+ 没给「所有文件访问」时，删除需要系统弹窗确认，由这条通知承接点按。 */
    fun needsSystemConfirm(ctx: Context, shot: PendingShot): Notification {
        val tap = PendingIntent.getActivity(
            ctx, (shot.mediaId.toInt() + 7),
            Intent(ctx, DeleteConfirmActivity::class.java)
                .putExtra(Actions.EXTRA_MEDIA_ID, shot.mediaId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, Channels.REVIEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_confirm_title))
            .setContentText(ctx.getString(R.string.notif_confirm_body, shot.name))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(ctx.getString(R.string.notif_confirm_body, shot.name)))
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

    fun reviewNotifId(mediaId: Long): Int = (mediaId and 0x7FFF_FFFFL).toInt() xor 0x5000_0000

    private fun formatWhen(shot: PendingShot): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(shot.takenAt))

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
