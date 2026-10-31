package com.changakira.screenshotbye.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.os.Build

/** 用户可配置项，全部落在 SharedPreferences 里。 */
class Settings private constructor(private val sp: SharedPreferences) {

    companion object {
        @Volatile private var instance: Settings? = null

        fun of(ctx: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(ctx.applicationContext.getSharedPreferences("screenshotbye", Context.MODE_PRIVATE)).also { instance = it }
        }

        const val DEFAULT_DELAY_MIN = 5
        private const val MAX_SEEN = 800

        private const val K_ENABLED = "watch_enabled"
        private const val K_DELAY = "delay_minutes"
        private const val K_TRASH = "use_trash"
        private const val K_TRASH_DAYS = "trash_days"
        private const val K_BY_FOLDER = "match_by_folder"
        private const val K_BY_NAME = "match_by_name"
        private const val K_KEYWORDS = "extra_keywords"
        private const val K_INCLUDE_VIDEO = "include_video"
        private const val K_BASELINE = "baseline_date_added"
        private const val K_SEEN = "seen_ids"
        private const val K_LOG = "event_log"
        private const val K_RESTORED = "restored_names"
        private const val RESTORED_TTL_MS = 3 * 60 * 1000L
    }

    /** 总开关；关闭时前台服务只保留通知栏入口，不做任何扫描。 */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /** 检测到截图后，过多少分钟询问「还需要这张截图吗」。 */
    var delayMinutes: Int
        get() = sp.getInt(K_DELAY, DEFAULT_DELAY_MIN).coerceIn(1, 720)
        set(v) = sp.edit().putInt(K_DELAY, v.coerceIn(1, 720)).apply()

    /** 删除前先备份到应用私有目录，可在回收站找回。 */
    var useTrash: Boolean
        get() = sp.getBoolean(K_TRASH, true)
        set(v) = sp.edit().putBoolean(K_TRASH, v).apply()

    var trashDays: Int
        get() = sp.getInt(K_TRASH_DAYS, 7).coerceIn(1, 90)
        set(v) = sp.edit().putInt(K_TRASH_DAYS, v.coerceIn(1, 90)).apply()

    var matchByFolder: Boolean
        get() = sp.getBoolean(K_BY_FOLDER, true)
        set(v) = sp.edit().putBoolean(K_BY_FOLDER, v).apply()

    var matchByName: Boolean
        get() = sp.getBoolean(K_BY_NAME, true)
        set(v) = sp.edit().putBoolean(K_BY_NAME, v).apply()

    var includeVideo: Boolean
        get() = sp.getBoolean(K_INCLUDE_VIDEO, false)
        set(v) = sp.edit().putBoolean(K_INCLUDE_VIDEO, v).apply()

    /** 自定义关键词，逗号分隔；命中即视为截图（用于兼容小众 ROM / 第三方截图工具）。 */
    var extraKeywords: Set<String>
        get() = sp.getString(K_KEYWORDS, "")!!.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(v) = sp.edit().putString(K_KEYWORDS, v.joinToString(",")).apply()

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

    /**
     * 刚被用户「还原到相册」的文件名，短时间内不再当作新截图处理。
     * 否则还原动作本身会让相册多出一张 Screenshot_xxx.png，又被检测、又被问、甚至又被删。
     */
    fun markRestored(name: String) {
        val horizon = System.currentTimeMillis() + RESTORED_TTL_MS
        val kept = restoredList().filter { it.expireAt > System.currentTimeMillis() } + Restored(name, horizon)
        sp.edit().putString(
            K_RESTORED, kept.joinToString("|") { "${it.name}#${it.expireAt}" }
        ).apply()
    }

    fun isRecentlyRestored(name: String): Boolean =
        restoredList().any { it.name == name && it.expireAt > System.currentTimeMillis() }

    private fun restoredList(): List<Restored> =
        (sp.getString(K_RESTORED, "") ?: "").split('|').filter { it.isNotBlank() }.mapNotNull {
            val parts = it.split('#')
            if (parts.size != 2) null else Restored(parts[0], parts[1].toLongOrNull() ?: 0L)
        }

    private data class Restored(val name: String, val expireAt: Long)

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

    /** 系统是否允许外部存储写回（决定能否走 File 直删路径）。 */
    val externalWritable: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment
            .getExternalStorageDirectory().canWrite()
}
