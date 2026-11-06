package com.changakira.screenshotbye.diagnostic

import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateFormat
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.Reminders
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.notify.Channels
import com.changakira.screenshotbye.notify.Notify
import com.changakira.screenshotbye.perm.Perms
import com.changakira.screenshotbye.service.WatchService
import androidx.work.WorkManager

/**
 * 应用内自检。
 *
 * 这个应用的运行结果高度依赖真机与 ROM：前台服务有没有被杀、MediaStore 有没有回调、
 * 横幅通知有没有被系统压掉，站在代码边上全都看不出来。把状态摊成一份可复制的纯文本，
 * 出问题时用户能直接把证据贴出来，而不是靠猜。
 *
 * 所有内容都是本机状态，不写外部存储、不联网。报告里的每一句话都取自字符串资源，
 * 代码里不留字面文案。
 */
object Diagnostics {

    /** 采集必须在后台线程：要查 MediaStore、WorkManager 和文件是否存在。 */
    fun collect(ctx: Context): String {
        val app = ctx.applicationContext
        val settings = Settings.of(app)
        val store = PendingShotStore.of(app)
        val repo = ScreenshotRepository(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()

        return buildString {
            line(app.getString(R.string.diag_report_title, app.getString(R.string.app_name)))
            line(version(app))
            line(
                app.getString(
                    R.string.diag_system_line,
                    Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL
                )
            )
            line(app.getString(R.string.diag_time_line, stamp(now)))

            section(app, R.string.diag_sec_notify)
            row(
                app, R.string.diag_row_notify_master, nm?.areNotificationsEnabled() ?: false,
                if (nm != null && nm.areNotificationsEnabled()) "" else app.getString(R.string.diag_detail_notify_off)
            )
            row(
                app, R.string.diag_row_dnd,
                nm?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL,
                interruptionLabel(app, nm)
            )
            line(
                app.getString(
                    R.string.diag_channel_line, Channels.REVIEW, channelImportance(app, nm, Channels.REVIEW)
                )
            )
            line(app.getString(R.string.diag_channel_note1))
            line(app.getString(R.string.diag_channel_note2))

            val crash = settings.lastCrash
            if (crash.isNotBlank()) {
                section(app, R.string.diag_sec_crash)
                line(crash)
                line(app.getString(R.string.diag_crash_note))
            }

            section(app, R.string.diag_sec_watch)
            row(
                app, R.string.diag_row_fg_service, WatchService.running,
                if (WatchService.running) "" else app.getString(R.string.diag_detail_service_down)
            )
            row(app, R.string.diag_row_master_switch, settings.enabled, "")
            line(app.getString(R.string.diag_last_observer, ago(app, settings.lastObserverAt, now)))
            line(app.getString(R.string.diag_last_scan, ago(app, settings.lastScanAt, now)))
            line(
                app.getString(
                    R.string.diag_watermark, timeOrNever(app, settings.baselineDateAdded * 1000L, now)
                )
            )
            val newest = newestMediaDateAdded(app)
            if (newest == null) line(app.getString(R.string.diag_newest_unreadable))
            else {
                val lag = newest - settings.baselineDateAdded
                line(
                    app.getString(
                        R.string.diag_newest,
                        timeOrNever(app, newest * 1000L, now),
                        if (lag < 0) 0 else lag
                    )
                )
            }
            line(app.getString(R.string.diag_seen_count, settings.seenCount()))
            line(app.getString(R.string.diag_work_retry, workState(app, Reminders.ALERT_RETRY)))
            line(
                app.getString(
                    R.string.diag_work_watchdog,
                    workState(app, com.changakira.screenshotbye.ScreenshotByeApp.WATCHDOG)
                )
            )

            section(app, R.string.diag_sec_perms)
            row(app, R.string.diag_row_read_media, Perms.hasMediaRead(app), "")
            row(app, R.string.diag_row_notify_perm, Perms.hasNotification(app), "")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val allFiles = Perms.hasAllFilesAccess(app)
                row(
                    app, R.string.diag_row_all_files, allFiles,
                    app.getString(if (allFiles) R.string.diag_detail_all_files_on else R.string.diag_detail_all_files_off)
                )
            } else {
                row(
                    app, R.string.diag_row_write_perm, Perms.canDeleteSilently(app),
                    if (Perms.canDeleteSilently(app)) "" else app.getString(R.string.diag_detail_no_write_perm)
                )
            }
            row(app, R.string.diag_row_battery, Perms.canIgnoreBattery(app), "")

            section(app, R.string.diag_sec_throttle)
            line(app.getString(R.string.diag_throttle_first, settings.delayMinutes))
            line(
                app.getString(R.string.diag_throttle_repeat, settings.alertRepeatMinutes)
            )
            line(
                app.getString(
                    R.string.diag_throttle_max,
                    if (settings.alertMaxTimes <= 0) app.getString(R.string.diag_repeat_never)
                    else app.getString(R.string.diag_repeat_times, settings.alertMaxTimes)
                )
            )
            line(app.getString(R.string.diag_throttle_last_alert, ago(app, settings.lastAlertAt, now)))
            line(
                app.getString(
                    R.string.diag_keep,
                    if (settings.askExpiryDays == 0) app.getString(R.string.diag_keep_wait)
                    else app.getString(R.string.diag_keep_days, settings.askExpiryDays)
                )
            )

            val all = store.all()
            section(app, R.string.diag_sec_queue, all.size)
            if (all.isEmpty()) line(app.getString(R.string.diag_queue_empty))
            all.forEach {
                val stillThere = repo.find(it.mediaId)?.let { s -> existsSafe(app, s) } ?: true
                line(
                    app.getString(
                        if (stillThere) R.string.diag_queue_item else R.string.diag_queue_item_gone,
                        it.mediaId, it.name, stateName(app, it.state),
                        timeOrNever(app, it.askAt, now), it.askCount
                    )
                )
            }

            section(app, R.string.diag_sec_delete)
            line(
                when {
                    // 无感删除只保证不弹确认框，原件去向以本机实测为准；没实测过就照实说没实测过
                    Perms.canDeleteSilently(app) -> app.getString(
                        when (settings.silentDeleteOutcome) {
                            Settings.OUTCOME_TRASHED -> R.string.diag_delete_silent_trashed
                            Settings.OUTCOME_GONE -> R.string.diag_delete_silent_gone
                            else -> R.string.diag_delete_silent_unknown
                        }
                    )

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> app.getString(R.string.diag_delete_r_plus)
                    else -> app.getString(R.string.diag_delete_legacy_no_perm)
                }
            )

            section(app, R.string.diag_sec_recent)
            val log = settings.log.lines().filter { it.isNotBlank() }.takeLast(RECENT_LOG_LINES)
            if (log.isEmpty()) line(app.getString(R.string.diag_recent_empty)) else log.forEach { line(it) }
        }.trimEnd()
    }

    /**
     * 按当前强度发一条示例提醒，和真实询问走同一条渠道。
     * 返回是否真的发出去了——通知被系统或用户关掉时发不出，界面要能说实话。
     */
    fun sendTest(ctx: Context): Boolean {
        Channels.ensure(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled() || !Perms.hasNotification(ctx)) return false
        Notify.notify(ctx, Notify.ID_TEST, Notify.testAlert(ctx))
        return true
    }

    /** 供界面解释「为什么没响」：区分权限、勿扰、渠道设置三类原因。 */
    fun alertBlockers(ctx: Context): String {
        val app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        val out = mutableListOf<String>()
        if (nm == null || !nm.areNotificationsEnabled()) out.add(app.getString(R.string.diag_block_notify_off))
        if (!Perms.hasNotification(app)) out.add(app.getString(R.string.diag_block_no_perm))
        if (nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            out.add(app.getString(R.string.diag_block_dnd, interruptionLabel(app, nm)))
        }
        val importance = nm?.getNotificationChannel(Channels.REVIEW)?.importance
        if (importance == null) out.add(app.getString(R.string.diag_block_no_channel))
        else if (importance <= NotificationManager.IMPORTANCE_LOW) {
            out.add(app.getString(R.string.diag_block_low_importance, importanceName(app, importance)))
        }
        return out.joinToString(app.getString(R.string.diag_block_sep))
    }

    private fun existsSafe(ctx: Context, shot: com.changakira.screenshotbye.core.PendingShot): Boolean =
        runCatching {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            ctx.contentResolver.query(
                shot.contentUri, projection, null, null, null
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(true)

    private fun newestMediaDateAdded(ctx: Context): Long? = runCatching {
        val args = android.os.Bundle().apply {
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns.DATE_ADDED)
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DATE_ADDED), args, null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()

    private fun workState(ctx: Context, name: String): String = runCatching {
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(name).get()
        if (infos.isEmpty()) ctx.getString(R.string.diag_work_unscheduled)
        else infos.joinToString(WORK_STATE_SEP) { it.state.name }
    }.getOrDefault(ctx.getString(R.string.diag_work_query_failed))

    private fun channelImportance(ctx: Context, nm: NotificationManager?, id: String): String =
        nm?.getNotificationChannel(id)?.importance
            ?.let { ctx.getString(R.string.diag_importance_fmt, it, importanceName(ctx, it)) }
            ?: ctx.getString(R.string.diag_channel_missing)

    private fun interruptionLabel(ctx: Context, nm: NotificationManager?): String =
        when (nm?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> R.string.diag_dnd_all
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> R.string.diag_dnd_priority
            NotificationManager.INTERRUPTION_FILTER_NONE -> R.string.diag_dnd_none
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> R.string.diag_dnd_alarms
            null -> R.string.diag_unknown
            else -> R.string.diag_dnd_limited
        }.let { ctx.getString(it) }

    private fun importanceName(ctx: Context, v: Int) = when (v) {
        NotificationManager.IMPORTANCE_NONE -> ctx.getString(R.string.diag_imp_none)
        NotificationManager.IMPORTANCE_MIN -> ctx.getString(R.string.diag_imp_min)
        NotificationManager.IMPORTANCE_LOW -> ctx.getString(R.string.diag_imp_low)
        NotificationManager.IMPORTANCE_DEFAULT -> ctx.getString(R.string.diag_imp_default)
        NotificationManager.IMPORTANCE_HIGH -> ctx.getString(R.string.diag_imp_high)
        else -> v.toString()
    }

    private fun stateName(ctx: Context, s: ShotState) = ctx.getString(
        when (s) {
            ShotState.WAITING -> R.string.diag_state_waiting
            ShotState.ASKED -> R.string.diag_state_asked
            ShotState.DELETING -> R.string.diag_state_deleting
        }
    )

    private fun version(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        ctx.getString(R.string.diag_version_line, pi.versionName, code)
    }.getOrDefault(ctx.getString(R.string.diag_version_unknown))

    private fun stamp(ms: Long): String =
        DateFormat.format(STAMP_PATTERN, ms).toString()

    private fun timeOrNever(ctx: Context, ms: Long, now: Long): String =
        if (ms <= 0L) ctx.getString(R.string.diag_never)
        else ctx.getString(R.string.diag_time_ago_fmt, stamp(ms), ago(ctx, ms, now))

    private fun ago(ctx: Context, ms: Long, now: Long): String = when {
        ms <= 0L -> ctx.getString(R.string.diag_never)
        else -> {
            val d = (now - ms) / 1000L
            when {
                d < 0 -> ctx.getString(R.string.diag_future_sec, 0 - d)
                d < 60 -> ctx.getString(R.string.diag_ago_sec, d)
                d < 3600 -> ctx.getString(R.string.diag_ago_min, d / 60)
                d < 86400 -> ctx.getString(R.string.diag_ago_hour, d / 3600)
                else -> ctx.getString(R.string.diag_ago_day, d / 86400)
            }
        }
    }

    private fun StringBuilder.section(ctx: Context, title: Int, vararg args: Any) {
        blank()
        line(ctx.getString(R.string.diag_section_fmt, ctx.getString(title, *args)))
    }

    private fun StringBuilder.line(text: String) { append(text).append('\n') }
    private fun StringBuilder.blank() { append('\n') }

    private fun StringBuilder.row(ctx: Context, label: Int, ok: Boolean, detail: String) {
        val mark = ctx.getString(if (ok) R.string.diag_mark_ok else R.string.diag_mark_bad)
        append(ctx.getString(R.string.diag_row_fmt, mark, ctx.getString(label)))
        if (detail.isNotBlank()) append(ctx.getString(R.string.diag_row_detail_fmt, detail))
        append('\n')
    }

    private const val RECENT_LOG_LINES = 25
    private const val STAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private const val WORK_STATE_SEP = ","
}
