package com.changakira.screenshotbye.diagnostic

import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateFormat
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
 * 所有内容都是本机状态，不写外部存储、不联网。
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
            line("ScreenshotBye 自检")
            line(version(app))
            line("系统 ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            line("时间 " + stamp(now))

            section("通知能不能出来")
            row("通知总开关", nm?.areNotificationsEnabled() ?: false, if (nm != null && nm.areNotificationsEnabled()) "" else "系统里已关闭本应用通知")
            row("打扰模式", nm?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL, interruptionLabel(nm))
            line("提醒渠道：${Channels.REVIEW} · importance=" + channelImportance(nm, Channels.REVIEW))
            line("声音、震动、横幅、锁屏显示都归这条渠道管，在系统通知设置里改，应用不代做决定。")
            line("「发送测试提醒」走同一条渠道真发一条，用来区分「应用没发」还是「系统压掉了」。")

            val crash = settings.lastCrash
            if (crash.isNotBlank()) {
                section("上次未捕获异常")
                line(crash)
                line("（复制整份报告反馈即可，这条记录比界面截图有用得多）")
            }

            section("监控是否在跑")
            row("前台服务存活", WatchService.running, if (WatchService.running) "" else "服务没在跑，看门狗会尝试拉起")
            row("总开关", settings.enabled, "")
            line("最近一次相册监听回调：" + ago(settings.lastObserverAt, now))
            line("最近一次扫描：" + ago(settings.lastScanAt, now))
            line("检测水位线（DATE_ADDED）：" + timeOrNever(settings.baselineDateAdded * 1000L, now))
            val newest = newestMediaDateAdded(app)
            if (newest == null) line("相册最新条目：读不到（缺媒体权限或被系统拒绝）")
            else {
                val lag = newest - settings.baselineDateAdded
                line("相册最新条目：" + timeOrNever(newest * 1000L, now) + "（水位线滞后 ${if (lag < 0) 0 else lag} 秒）")
            }
            line("已登记媒体 id 数：" + settings.seenCount())
            line("定时任务：${workState(app, Reminders.ALERT_RETRY)}")
            line("看门狗：${workState(app, com.changakira.screenshotbye.ScreenshotByeApp.WATCHDOG)}")

            section("权限")
            row("读取相册", Perms.hasMediaRead(app), "")
            row("通知权限", Perms.hasNotification(app), "")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                row("存储写入权限（决定能否直接删除）", Perms.canDeleteSilently(app), if (Perms.canDeleteSilently(app)) "" else "没有写权限就删不掉")
            }
            row("电池优化白名单", Perms.canIgnoreBattery(app), "")

            section("提醒节流")
            line("首次询问：入库后 ${settings.delayMinutes} 分钟")
            line("重复提醒间隔：${if (settings.alertRepeatMinutes == 0) "关闭（只问一次）" else "${settings.alertRepeatMinutes} 分钟"}")
            line("最多提醒次数：${settings.alertMaxTimes}")
            line("上次出声提醒：" + ago(settings.lastAlertAt, now))
            line("默认保留：${if (settings.askExpiryDays == 0) "继续等待处理，不会自动出队" else "${settings.askExpiryDays} 天没回答就当作保留"}")

            section("待确认队列（${store.all().size}）")
            val all = store.all()
            if (all.isEmpty()) line("空")
            all.forEach {
                val stillThere = repo.find(it.mediaId)?.let { s -> existsSafe(app, s) } ?: true
                line(
                    "#${it.mediaId} ${it.name} · ${stateName(it.state)} · 到期 " +
                        timeOrNever(it.askAt, now) + " · 已提醒 ${it.askCount} 次" +
                        if (stillThere) "" else " · 原图已不在"
                )
            }

            section("删除去向")
            line(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    "Android 11+：系统弹窗确认后移入系统相册的回收站，保留多久由系统决定，本应用不存副本"
                else "Android 10 及以下：系统没有回收站，删掉即消失"
            )

            section("最近事件")
            val log = settings.log.lines().filter { it.isNotBlank() }.takeLast(25)
            if (log.isEmpty()) line("（空）") else log.forEach { line(it) }
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
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val out = mutableListOf<String>()
        if (nm == null || !nm.areNotificationsEnabled()) out.add("本应用通知总开关已关闭")
        if (!Perms.hasNotification(ctx)) out.add("缺少通知权限（Android 13+）")
        if (nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            out.add("系统处于勿扰/静音模式：" + interruptionLabel(nm))
        }
        val importance = nm?.getNotificationChannel(Channels.REVIEW)?.importance
        if (importance == null) out.add("提醒渠道不存在，去系统通知设置里确认")
        else if (importance <= NotificationManager.IMPORTANCE_LOW) {
            out.add("提醒渠道已被系统设为「${importanceName(importance)}」，横幅和声音是在这里被关掉的，需要在系统通知设置里调回「高」")
        }
        return out.joinToString("；")
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
        if (infos.isEmpty()) "未排期" else infos.joinToString(",") { it.state.name }
    }.getOrDefault("查询失败")

    private fun channelImportance(nm: NotificationManager?, id: String): String =
        nm?.getNotificationChannel(id)?.importance?.let { "$it（${importanceName(it)}）" } ?: "不存在"

    private fun interruptionLabel(nm: NotificationManager?): String = when (nm?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "全部允许"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "仅优先级（勿扰）"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "全部静音"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "仅闹钟"
        null -> "未知"
        else -> "受限（通知栏折叠/静音类模式）"
    }

    private fun importanceName(v: Int) = when (v) {
        NotificationManager.IMPORTANCE_NONE -> "已关闭"
        NotificationManager.IMPORTANCE_MIN -> "最低"
        NotificationManager.IMPORTANCE_LOW -> "低（不弹横幅）"
        NotificationManager.IMPORTANCE_DEFAULT -> "默认"
        NotificationManager.IMPORTANCE_HIGH -> "高（会弹横幅）"
        else -> "$v"
    }

    private fun stateName(s: ShotState) = when (s) {
        ShotState.WAITING -> "计时中"
        ShotState.ASKED -> "待回答"
        ShotState.DELETING -> "等系统确认"
    }

    private fun version(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "版本 ${pi.versionName}（build $code）"
    }.getOrDefault("版本未知")

    private fun stamp(ms: Long): String =
        DateFormat.format("yyyy-MM-dd HH:mm:ss", ms).toString()

    private fun timeOrNever(ms: Long, now: Long): String = if (ms <= 0L) "从未" else "${stamp(ms)}（${ago(ms, now)}）"

    private fun ago(ms: Long, now: Long): String = when {
        ms <= 0L -> "从未"
        else -> {
            val d = (now - ms) / 1000L
            when {
                d < 0 -> "未来 ${(0 - d)} 秒后"
                d < 60 -> "${d} 秒前"
                d < 3600 -> "${d / 60} 分钟前"
                d < 86400 -> "${d / 3600} 小时前"
                else -> "${d / 86400} 天前"
            }
        }
    }

    private fun StringBuilder.section(title: String) {
        blank()
        line("== $title")
    }

    private fun StringBuilder.line(text: String) { append(text).append('\n') }
    private fun StringBuilder.blank() { append('\n') }

    private fun StringBuilder.row(label: String, ok: Boolean, detail: String) {
        val mark = if (ok) "✓" else "✗"
        append("$mark $label")
        if (detail.isNotBlank()) append("（$detail）")
        append('\n')
    }
}
