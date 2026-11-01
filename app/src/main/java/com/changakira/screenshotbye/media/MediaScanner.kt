package com.changakira.screenshotbye.media

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.changakira.screenshotbye.core.Settings
import java.io.File
import java.util.concurrent.Executors

/** 一条从 MediaStore 读到的候选媒体记录。 */
data class MediaHit(
    val mediaId: Long,
    val uri: Uri,
    val displayName: String,
    val relativePath: String?,
    val folder: String,
    val absolutePath: String?,
    val size: Long,
    val dateAddedSec: Long,
    val isVideo: Boolean,
)

/**
 * 截图发现器。
 *
 * 主信号：MediaStore 集合 URI 上的 ContentObserver —— 系统截图落盘后被 MediaProvider 索引，
 * onChange 随即触发，这是三方应用在 Android 10+ 上做全局截图识别唯一可靠的入口。
 * 辅助信号：FileObserver 盯常见截图目录，仅用于「催一次扫描」以缩短延迟；
 * Android 11+ 分区存储下它可能收不到事件，因此不参与判定。
 *
 * 去重与续扫：dateAdded 水位线 + seen 集合。服务重启后从水位线补扫，
 * 覆盖进程被杀期间产生的截图；首次启用则把水位线设为当前时刻，避免把历史截图当新截图。
 */
class MediaScanner(
    ctx: Context,
    private val onHit: (MediaHit) -> Unit,
    private val onNote: (String) -> Unit = {},
) {

    private val app = ctx.applicationContext
    private val resolver: ContentResolver = app.contentResolver
    private val settings = Settings.of(app)
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "shot-scan").apply { isDaemon = true } }

    private var observer: ContentObserver? = null
    private var fileObserver: FileObserver? = null
    private var queuedScan: Runnable? = null

    @Volatile private var running = false
    @Volatile private var watermarkSec = 0L

    fun start() {
        if (running) return
        running = true
        watermarkSec = settings.baselineDateAdded
        if (watermarkSec <= 0L) {
            watermarkSec = System.currentTimeMillis() / 1000L
            settings.baselineDateAdded = watermarkSec
            onNote("已设定检测基线，只处理此后新增的截图")
        }

        val obs = object : ContentObserver(ui) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // 诊断页会看这个时间戳，用来区分「监听没回调」和「回调了但扫描没跟上」
                settings.lastObserverAt = System.currentTimeMillis()
                scheduleScan(DEBOUNCE_MS)
            }
        }
        observer = obs
        for (uri in watchedUris()) {
            runCatching { resolver.registerContentObserver(uri, true, obs) }
                .onFailure { onNote("注册相册监听失败：${it.javaClass.simpleName}") }
        }
        startFileObserver()
        scheduleScan(STARTUP_SCAN_DELAY_MS)
    }

    fun stop() {
        running = false
        queuedScan?.let { ui.removeCallbacks(it) }
        queuedScan = null
        observer?.let { runCatching { resolver.unregisterContentObserver(it) } }
        observer = null
        runCatching { fileObserver?.stopWatching() }
        fileObserver = null
    }

    /** 检测规则改动后可调用，立即补扫一次。 */
    fun rescanNow() = scheduleScan(0L)

    private fun watchedUris(): List<Uri> = buildList {
        add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        if (settings.includeVideo) add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    }

    /** MediaProvider 经常一次入库回调多次，合并成一次扫描。 */
    private fun scheduleScan(delayMs: Long) {
        if (!running) return
        queuedScan?.let { ui.removeCallbacks(it) }
        val r = Runnable { if (running) io.execute(this::scan) }
        queuedScan = r
        ui.postDelayed(r, delayMs)
    }

    private val baseProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATA,
    )

    private val relativePathColumn: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else null

    private fun scan() {
        if (!settings.enabled) return
        settings.lastScanAt = System.currentTimeMillis()
        var highest = watermarkSec
        for (video in booleanArrayOf(false, true)) {
            if (video && !settings.includeVideo) continue
            for (row in querySince(video, watermarkSec)) {
                if (row.dateAddedSec > highest) highest = row.dateAddedSec
                if (!isScreenshot(row)) continue
                if (!settings.markSeen(row.mediaId)) continue
                onHit(row)
            }
        }
        if (highest > watermarkSec) {
            watermarkSec = highest
            settings.baselineDateAdded = highest
        }
    }

    private fun querySince(video: Boolean, sinceSec: Long): List<MediaHit> {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val columns = baseProjection.toMutableList()
        relativePathColumn?.let { columns.add(it) }

        val sb = StringBuilder("${MediaStore.MediaColumns.DATE_ADDED} >= ?")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sb.append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
        }
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sb.toString())
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(sinceSec.toString()))
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns._ID)
            )
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, SCAN_LIMIT)
        }

        return runCatching { resolver.query(base, columns.toTypedArray(), queryArgs, null) }
            .onFailure { onNote("读取相册失败：${it.javaClass.simpleName}") }
            .getOrNull()
            ?.use { it.readHits(video) }
            ?: emptyList()
    }

    private fun Cursor.readHits(video: Boolean): List<MediaHit> {
        val iId = getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val iName = getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val iAdded = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val iSize = getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val iData = getColumnIndex(MediaStore.MediaColumns.DATA)
        val iRel = relativePathColumn?.let { getColumnIndex(it) } ?: -1

        val out = ArrayList<MediaHit>(count)
        while (moveToNext()) {
            val id = getLong(iId)
            val name = getString(iName) ?: continue
            val rel = if (iRel >= 0 && !isNull(iRel)) getString(iRel) else null
            val abs = if (iData >= 0 && !isNull(iData)) getString(iData) else null
            out += MediaHit(
                mediaId = id,
                uri = ContentUris.withAppendedId(
                    if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                ),
                displayName = name,
                relativePath = rel,
                folder = rel?.trimEnd('/')?.substringAfterLast('/')
                    ?: ScreenshotRule.folderFromPath(abs),
                absolutePath = abs,
                size = getLong(iSize),
                dateAddedSec = getLong(iAdded),
                isVideo = video,
            )
        }
        return out
    }

    private fun isScreenshot(h: MediaHit): Boolean {
        // 刚从回收站还原回来的文件不重复处理，否则「还原」会被当成一次新截图
        if (settings.isRecentlyRestored(h.displayName)) return false
        return ScreenshotRule.matches(
            displayName = h.displayName,
            folderLabel = h.folder,
            relativePath = h.relativePath,
            allowVideo = settings.includeVideo,
            extraKeywords = settings.extraKeywords,
            matchByFolder = settings.matchByFolder,
            matchByName = settings.matchByName,
        )
    }

    private fun startFileObserver() {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val roots = listOf(pictures, File(pictures, "Screenshots"), dcim, File(dcim, "Screenshots"))
            .filter { it.isDirectory }
        if (roots.isEmpty()) return

        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        val observer = object : FileObserver(roots.first(), mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val looksLikeShot = NAME_HINTS.any { path.contains(it, ignoreCase = true) }
                if (looksLikeShot) scheduleScan(120L)
            }
        }
        runCatching { observer.startWatching() }.onSuccess { fileObserver = observer }
    }

    companion object {
        private const val DEBOUNCE_MS = 350L
        private const val STARTUP_SCAN_DELAY_MS = 1200L
        private const val SCAN_LIMIT = 400
        private val NAME_HINTS = listOf("screenshot", "screen_shot", "screenshot_", "截图", "screenrecord")
    }
}
