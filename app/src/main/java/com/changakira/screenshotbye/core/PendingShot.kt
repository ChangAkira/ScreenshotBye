package com.changakira.screenshotbye.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ShotState { WAITING, ASKED, DELETING }

/**
 * 一张「待确认」的截图。
 * 生命周期：检测到 -> WAITING(计时中) -> ASKED(已通知，等用户回答) -> 删除 / 保留后移出列表。
 * 两个兜底：一直不回答超过 askExpiryDays 按「保留」处理并出队；
 * DELETING（等系统弹窗）超时未完成则退回 ASKED，避免通知长期挂着。
 */
data class PendingShot(
    val mediaId: Long,
    val uri: String,
    val name: String,
    val folder: String,
    val relativePath: String?,
    val size: Long,
    val takenAt: Long,
    val askAt: Long,
    val state: ShotState,
    /** 进入当前状态的时间，用于「不回答就过期」和「系统确认卡住」的超时判定。 */
    val stateAt: Long = 0L,
    /** 这张已经被提醒（出声/震动）过几次，用于提醒次数封顶。 */
    val askCount: Int = 0,
    /** MediaStore DATA 列的绝对路径，Android 11+ 可能为 null。 */
    val absolutePath: String? = null,
) {
    val id: String get() = mediaId.toString()
    val contentUri: android.net.Uri get() = android.net.Uri.parse(uri)

    /** 旧版本存档里没有 stateAt，退化用 askAt，保证升级后超时判定仍然成立。 */
    val sinceStateAt: Long get() = if (stateAt > 0L) stateAt else askAt
}

/**
 * 待确认清单的持久化。用 JSON 存 SharedPreferences：条目少、结构扁平，
 * 不值得为此引入 Room + 迁移逻辑；进程被杀后重启可完整恢复计时。
 */
class PendingShotStore private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var instance: PendingShotStore? = null
        fun of(ctx: Context): PendingShotStore = instance ?: synchronized(this) {
            instance ?: PendingShotStore(ctx.applicationContext).also { instance = it }
        }
        private const val PREF = "screenshotbye_pending"
        private const val K_ITEMS = "items"
    }

    private val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<PendingShot> {
        val raw = sp.getString(K_ITEMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.askAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun find(mediaId: Long): PendingShot? = all().firstOrNull { it.mediaId == mediaId }

    @Synchronized
    fun upsert(shot: PendingShot) = write(all().let { cur ->
        cur.filter { it.mediaId != shot.mediaId } + shot
    })

    @Synchronized
    fun remove(mediaId: Long) = write(all().filter { it.mediaId != mediaId })

    @Synchronized
    fun replaceAll(items: List<PendingShot>) = write(items)

    private fun write(items: List<PendingShot>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        sp.edit().putString(K_ITEMS, arr.toString()).apply()
    }

    private fun toJson(s: PendingShot) = JSONObject().apply {
        put("mediaId", s.mediaId)
        put("uri", s.uri)
        put("name", s.name)
        put("folder", s.folder)
        put("relativePath", s.relativePath ?: JSONObject.NULL)
        put("size", s.size)
        put("takenAt", s.takenAt)
        put("askAt", s.askAt)
        put("state", s.state.name)
        put("stateAt", s.stateAt)
        put("askCount", s.askCount)
        put("absolutePath", s.absolutePath ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject) = PendingShot(
        mediaId = o.getLong("mediaId"),
        uri = o.getString("uri"),
        name = o.optString("name"),
        folder = o.optString("folder"),
        relativePath = o.nullableString("relativePath"),
        size = o.optLong("size"),
        takenAt = o.optLong("takenAt"),
        askAt = o.optLong("askAt"),
        state = runCatching { ShotState.valueOf(o.optString("state", "WAITING")) }.getOrDefault(ShotState.WAITING),
        stateAt = o.optLong("stateAt"),
        askCount = o.optInt("askCount"),
        absolutePath = o.nullableString("absolutePath"),
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }
}
