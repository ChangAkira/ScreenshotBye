package com.changakira.screenshotbye.media

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** 回收站里的一条备份。 */
data class TrashEntry(
    val mediaId: Long,
    val name: String,
    val relativePath: String?,
    val backupFile: File,
    val deletedAt: Long,
    val takenAt: Long,
    val size: Long,
    val mimeType: String,
)

/**
 * 应用私有目录里的回收站。
 *
 * 为什么自己实现而不是用系统回收站：MediaStore 没有对外公开的「移入回收站」API，
 * 而本应用要删的文件不是自己创建的，直接删除不可逆。删除前先把字节复制到
 * filesDir/trash/<mediaId>/，用户在保留期内可一键还原，过期由清理任务彻底抹掉。
 */
class TrashStore(ctx: Context) {

    private val app = ctx.applicationContext
    private val settings = Settings.of(app)
    private val root = File(app.filesDir, "trash").apply { mkdirs() }

    /**
     * 复制原图到回收站。读取失败（权限不足、文件已消失）返回 null，
     * 调用方据此决定是否继续删除 —— 宁可不删，也不要删掉找不回来的文件。
     */
    fun backUp(shot: PendingShot): TrashEntry? {
        val dir = File(root, shot.mediaId.toString()).apply { mkdirs() }
        val dst = File(dir, safeName(shot.name))
        val mime = guessMime(shot.name)
        return runCatching {
            app.contentResolver.openInputStream(shot.contentUri)?.use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out, 64 * 1024) }
            } ?: error("openInputStream 返回 null")
            val size = dst.length()
            if (size <= 0L) error("备份为空")
            val deletedAt = System.currentTimeMillis()
            File(dir, META).writeText(JSONObject().apply {
                put("mediaId", shot.mediaId)
                put("name", shot.name)
                put("relativePath", shot.relativePath ?: JSONObject.NULL)
                put("deletedAt", deletedAt)
                put("takenAt", shot.takenAt)
                put("size", size)
                put("mimeType", mime)
            }.toString())
            TrashEntry(shot.mediaId, shot.name, shot.relativePath, dst, deletedAt, shot.takenAt, size, mime)
        }.getOrElse {
            dst.delete()
            dir.delete()
            null
        }
    }

    fun list(): List<TrashEntry> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val meta = File(dir, META).takeIf { it.isFile } ?: return@mapNotNull null
            val img = dir.listFiles { f -> f.isFile && f.name != META }?.firstOrNull() ?: return@mapNotNull null
            runCatching {
                val o = JSONObject(meta.readText())
                TrashEntry(
                    mediaId = o.getLong("mediaId"),
                    name = o.optString("name", img.name),
                    relativePath = if (o.isNull("relativePath")) null else o.optString("relativePath"),
                    backupFile = img,
                    deletedAt = o.optLong("deletedAt", img.lastModified()),
                    takenAt = o.optLong("takenAt", 0L),
                    size = img.length(),
                    mimeType = o.optString("mimeType", guessMime(img.name)),
                )
            }.getOrNull()
        }.sortedByDescending { it.deletedAt }
    }

    /** 还原到相册。返回 true 表示已写回并触发媒体索引。 */
    fun restore(entry: TrashEntry): Boolean {
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) restoreViaMediaStore(entry)
            else restoreViaFile(entry)
            true
        }.getOrDefault(false)
        if (ok) {
            // 还原会让相册多出一张同名截图，必须屏蔽掉，否则会被再次检测并再次询问删除
            settings.markRestored(entry.name)
        }
        return ok
    }

    private fun restoreViaMediaStore(entry: TrashEntry) {
        val resolver = app.contentResolver
        val cv = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
            if (!entry.relativePath.isNullOrBlank()) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, entry.relativePath)
            } else {
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/Screenshots/")
            }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val base = if (entry.mimeType.startsWith("video/"))
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val target = resolver.insert(base, cv) ?: error("MediaStore insert 失败")
        resolver.openOutputStream(target)?.use { out ->
            entry.backupFile.inputStream().use { it.copyTo(out, 64 * 1024) }
        } ?: error("openOutputStream 失败")
        resolver.update(target, android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        purge(entry.mediaId)
    }

    private fun restoreViaFile(entry: TrashEntry) {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            if (entry.mimeType.startsWith("video/")) android.os.Environment.DIRECTORY_DCIM
            else android.os.Environment.DIRECTORY_PICTURES
        )
        val sub = entry.relativePath?.trimEnd('/')?.substringAfterLast('/') ?: "Screenshots"
        val outDir = File(dir, sub).apply { mkdirs() }
        val dst = File(outDir, safeName(entry.name))
        entry.backupFile.copyTo(dst, overwrite = true)
        android.media.MediaScannerConnection.scanFile(
            app, arrayOf(dst.absolutePath), arrayOf(entry.mimeType), null
        )
        purge(entry.mediaId)
    }

    /** 超过保留期的备份彻底删除。 */
    fun purgeExpired(retentionDays: Int): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600_000L
        var n = 0
        list().forEach { if (it.deletedAt < cutoff && purge(it.mediaId)) n++ }
        return n
    }

    fun purge(mediaId: Long): Boolean {
        val dir = File(root, mediaId.toString())
        return dir.exists() && runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    fun usedBytes(): Long = list().sumOf { it.size }

    private fun safeName(name: String): String {
        val cleaned = buildString(name.length) {
            for (c in name) append(if (c in ILLEGAL_CHARS) '_' else c)
        }
        return cleaned.ifBlank { "screenshot.png" }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp4", "mov", "mkv", "3gp" -> "video/mp4"
        else -> "image/png"
    }

    private companion object {
        const val META = "meta.json"
        val ILLEGAL_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    }
}
