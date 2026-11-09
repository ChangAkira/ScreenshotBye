package com.changakira.screenshotbye.core

import android.content.Context
import android.content.SharedPreferences

/** 用户可配置项，全部落在 SharedPreferences 里。 */
class Settings private constructor(private val sp: SharedPreferences) {

    companion object {
        @Volatile private var instance: Settings? = null

        fun of(ctx: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(ctx.applicationContext.getSharedPreferences("screenshotbye", Context.MODE_PRIVATE)).also { instance = it }
        }

        const val DEFAULT_DELAY_MIN = 5
        const val DEFAULT_ALERT_REPEAT_MIN = 5
        const val DEFAULT_ALERT_MAX_TIMES = 2
        const val DEFAULT_ASK_EXPIRY_DAYS = 3

        /**
         * 固定档位。旋钮只在这几个值之间吸附，理由和参考仓库一样：
         * 「8 分钟」和「12 分钟」体感没区别，连续滑块等于让用户猜。
         * 旧版本存的是 1–60 / 0–120 这种连续值，读取时一律吸附到最近的一格，不做迁移脚本。
         */
        val DELAY_PRESETS = listOf(2, 5, 15, 30, 90)

        /**
         * 「默认保留」的档位，顺序就是旋钮上从左到右的顺序。
         * 末尾的 0 不是"0 天就出队"，而是不设到期——界面上画成 ∞（无限天后自动保留），
         * 放在最右一档，跟其它档位一样"越往右等得越久"。存进 SharedPreferences 的仍是 0。
         */
        val EXPIRY_PRESETS = listOf(1, 3, 5, 7, 0)
        val REPEAT_PRESETS = listOf(1, 2, 5, 10, 20)
        val MAX_TIMES_PRESETS = listOf(0, 1, 2)

        /**
         * 吸附到最近的一格：按列表顺序遍历 + 严格小于，平局时留下先出现的那一档。
         * 档位基本升序，所以平局就是"留下较小的那档"；EXPIRY_PRESETS 里代表 ∞ 的 0 排在最后，
         * 只在它和别的档位等距时才影响取舍，而它只可能靠 1 最近，实际不会碰到。
         */
        fun nearest(value: Int, presets: List<Int>): Int {
            var best = presets.first()
            var bestGap = Int.MAX_VALUE
            presets.forEach {
                val gap = kotlin.math.abs(it - value)
                if (gap < bestGap) {
                    bestGap = gap
                    best = it
                }
            }
            return best
        }

        private const val MAX_SEEN = 800

        private const val K_ENABLED = "watch_enabled"
        private const val K_DELAY = "delay_minutes"
        private const val K_BY_FOLDER = "match_by_folder"
        private const val K_BY_NAME = "match_by_name"
        private const val K_THEME = "theme_palette"
        private const val K_INCLUDE_VIDEO = "include_video"
        private const val K_BASELINE = "baseline_date_added"
        private const val K_SEEN = "seen_ids"
        private const val K_LOG = "event_log"
        private const val K_ONBOARDED = "onboarded"
        private const val K_PERMS_ASKED = "perms_asked"
        private const val K_ALERT_REPEAT = "alert_repeat_minutes"
        private const val K_ALERT_MAX = "alert_max_times"
        private const val K_ASK_EXPIRY = "ask_expiry_days"
        private const val K_LAST_ALERT = "last_alert_at"
        private const val K_LAST_OBSERVER = "last_observer_at"
        private const val K_LAST_SCAN = "last_scan_at"
        private const val K_LAST_CRASH = "last_crash"
    }

    /** 首次启动是否已走完引导页。 */
    var onboarded: Boolean
        get() = sp.getBoolean(K_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(K_ONBOARDED, v).apply()

    /** 运行期权限是否已经问过一次，避免每次打开都弹一遍。 */
    var permsAsked: Boolean
        get() = sp.getBoolean(K_PERMS_ASKED, false)
        set(v) = sp.edit().putBoolean(K_PERMS_ASKED, v).apply()

    /** 总开关；关闭时前台服务只保留通知栏入口，不做任何扫描。 */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * 检测到截图后，过多少分钟询问「还需要这张截图吗」。
     * 读写都吸附到 DELAY_PRESETS，所以旧版 1–60 的连续值进来也不会带出界。
     */
    var delayMinutes: Int
        get() = nearest(sp.getInt(K_DELAY, DEFAULT_DELAY_MIN), DELAY_PRESETS)
        set(v) = sp.edit().putInt(K_DELAY, nearest(v, DELAY_PRESETS)).apply()

    /** 还有没回答的截图时，隔多少分钟再提醒一次。关不关由 alertMaxTimes 决定，这里不再兼职开关。 */
    var alertRepeatMinutes: Int
        get() = nearest(sp.getInt(K_ALERT_REPEAT, DEFAULT_ALERT_REPEAT_MIN), REPEAT_PRESETS)
        set(v) = sp.edit().putInt(K_ALERT_REPEAT, nearest(v, REPEAT_PRESETS)).apply()

    /**
     * 第一次问之外还允许重复提醒几次；0 就是不重复。超过就只留通知不再出声，避免变成骚扰。
     * 「不重复」这件事由次数表达，间隔只负责隔多久——旧版本里间隔 0 分钟兼职当开关，
     * 于是「间隔 0」和「次数 0」两种关法并存，读起来很别扭。
     */
    var alertMaxTimes: Int
        get() = nearest(sp.getInt(K_ALERT_MAX, DEFAULT_ALERT_MAX_TIMES), MAX_TIMES_PRESETS)
        set(v) = sp.edit().putInt(K_ALERT_MAX, nearest(v, MAX_TIMES_PRESETS)).apply()

    /** 一直不回答的询问过多少天按「保留」处理；EXPIRY_PRESETS 末尾那个 0 表示无限期等着（界面上是 ∞）。 */
    var askExpiryDays: Int
        get() = nearest(sp.getInt(K_ASK_EXPIRY, DEFAULT_ASK_EXPIRY_DAYS), EXPIRY_PRESETS)
        set(v) = sp.edit().putInt(K_ASK_EXPIRY, nearest(v, EXPIRY_PRESETS)).apply()

    /** 上一次真正发出提醒（出声/震动）的时刻，用于节流；队列为空时归零。 */
    var lastAlertAt: Long
        get() = sp.getLong(K_LAST_ALERT, 0L)
        set(v) = sp.edit().putLong(K_LAST_ALERT, v).apply()

    /** 诊断用：相册监听最近一次回调、最近一次扫描的时间戳。 */
    var lastObserverAt: Long
        get() = sp.getLong(K_LAST_OBSERVER, 0L)
        set(v) = sp.edit().putLong(K_LAST_OBSERVER, v).apply()

    var lastScanAt: Long
        get() = sp.getLong(K_LAST_SCAN, 0L)
        set(v) = sp.edit().putLong(K_LAST_SCAN, v).apply()

    /**
     * 上一次未捕获异常。作者拿不到用户的机型和 logcat，闪退只能靠应用自己记下来，
     * 自检页里能直接看到并复制走。
     */
    val lastCrash: String
        get() = sp.getString(K_LAST_CRASH, "") ?: ""

    fun recordCrash(threadName: String, e: Throwable) {
        val trace = e.stackTrace.take(6).joinToString(" ← ") { "${it.className}.${it.methodName}" }
        val cause = e.cause?.let { " caused by ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        val stamp = android.text.format.DateFormat.format("MM-dd HH:mm:ss", System.currentTimeMillis())
        // 必须同步落盘：uncaughtException 之后进程马上就被杀掉，apply() 的异步写会丢
        sp.edit().putString(K_LAST_CRASH, "[$stamp] $threadName · ${e.javaClass.name}: ${e.message}$cause\n$trace".take(2000)).commit()
    }

    var matchByFolder: Boolean
        get() = sp.getBoolean(K_BY_FOLDER, true)
        set(v) = sp.edit().putBoolean(K_BY_FOLDER, v).apply()

    var matchByName: Boolean
        get() = sp.getBoolean(K_BY_NAME, true)
        set(v) = sp.edit().putBoolean(K_BY_NAME, v).apply()

    var includeVideo: Boolean
        get() = sp.getBoolean(K_INCLUDE_VIDEO, false)
        set(v) = sp.edit().putBoolean(K_INCLUDE_VIDEO, v).apply()

    /**
     * 配色主题 id（beige / slate / terracotta / indigo / graphite）。
     * 深色模式不在这里管，跟着系统走，所以一套主题必须备好亮暗两份颜色。
     */
    var themePalette: String
        get() = sp.getString(K_THEME, "beige") ?: "beige"
        set(v) = sp.edit().putString(K_THEME, v).apply()

    /** 首次启用时记下时间基线，避免把历史截图全当成新截图。 */
    var baselineDateAdded: Long
        get() = sp.getLong(K_BASELINE, 0L)
        set(v) = sp.edit().putLong(K_BASELINE, v).apply()

    /**
     * 记录已处理过的媒体 id。用逗号拼接的有序串而不是 StringSet：
     * SharedPreferences 不保证 Set 的读出顺序，只有串能稳定地「丢最旧、留最新」。
     */
    fun markSeen(id: Long): Boolean = synchronized(this) {
        val key = id.toString()
        val current = (sp.getString(K_SEEN, "") ?: "").split(',').filter { it.isNotBlank() }
        if (key in current) return false
        val next = (current + key).takeLast(MAX_SEEN)
        sp.edit().putString(K_SEEN, next.joinToString(",")).apply()
        true
    }

    /** 诊断用：已登记过的媒体 id 数量（上限 MAX_SEEN，超出后丢最旧）。 */
    fun seenCount(): Int = synchronized(this) {
        (sp.getString(K_SEEN, "") ?: "").split(',').count { it.isNotBlank() }
    }

    fun appendLog(line: String) {
        val stamp = android.text.format.DateFormat.format("MM-dd HH:mm:ss", System.currentTimeMillis())
        val entry = "[$stamp] $line"
        val cur = sp.getString(K_LOG, "") ?: ""
        val next = if (cur.isEmpty()) entry else (cur + "\n" + entry)
        val capped = next.lines().takeLast(120).joinToString("\n")
        sp.edit().putString(K_LOG, capped).apply()
    }

    val log: String get() = sp.getString(K_LOG, "") ?: ""
    fun clearLog() = sp.edit().remove(K_LOG).apply()
}
