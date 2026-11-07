package com.changakira.screenshotbye.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import com.changakira.screenshotbye.R
import java.util.Collections
import java.util.concurrent.Executors

/** 列表缩略图：后台线程按需降采样加载，避免主线程解码大图。 */
object Thumbs {

    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "thumb").apply { isDaemon = true } }
    private val lru = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>) = size > 24
        }
    )

    private var appContext: Context? = null

    /** ImageView 拿不到 Context，由 Application 启动时注入一次。 */
    fun init(ctx: Context) { appContext = ctx.applicationContext }

    /**
     * source 返回 null 表示文件已不存在，此时把视图清成占位图。
     *
     * 缓存键带上 maxPx，避免列表的 128px 小图和详情页的大图互相串用；
     * 大图传 cache=false 不进 LRU，否则几张 1000px 的位图就能把内存吃满。
     */
    fun into(view: ImageView, key: String, maxPx: Int = 128, cache: Boolean = true, source: () -> Uri?) {
        view.tag = key
        if (cache) lru[key]?.let { bitmap ->
            view.setImageDrawable(null)
            view.setImageBitmap(bitmap)
            return
        }
        io.execute {
            val ctx = appContext ?: return@execute
            val uri = source()
            val bitmap = if (uri == null) null else runCatching { decode(ctx, uri, maxPx) }.getOrNull()
            if (bitmap != null && cache) lru[key] = bitmap
            view.post {
                if (view.tag != key) return@post
                view.setImageDrawable(null)
                if (bitmap != null) view.setImageBitmap(bitmap)
                else view.setBackgroundResource(R.drawable.thumb_placeholder)
            }
        }
    }

    fun media(view: ImageView, uri: Uri, maxPx: Int = 128, cache: Boolean = true) {
        into(view, "media:$uri:$maxPx", maxPx, cache) { uri }
    }

    /**
     * 先按图片解，解不动再当视频抽一帧。internal 是为了给「录屏也要有封面」这条路径单独写测试。
     *
     * 为什么要兜这一层：`BitmapFactory` 拿到 mp4 的字节流只会返回 null（bounds 都读不出来），
     * 于是录屏那几条一律落到深色占位图上，看着就是「预览是黑的」。这里不靠 MIME 判断，
     * 直接按「图片这条路走不通」触发，省得给清单再加一个字段。
     * 图片那段整个包在 runCatching 里：视频 URI 连流都可能打不开（抛 FileNotFoundException），
     * 那也算「走不通」，不能让它把抽帧的机会一起带走。
     */
    internal fun decode(ctx: Context, uri: Uri, maxPx: Int): Bitmap? =
        if (isVideoUri(uri)) decodeVideoFrame(ctx, uri, maxPx)
        else runCatching { decodeImage(ctx, uri, maxPx) }.getOrNull() ?: decodeVideoFrame(ctx, uri, maxPx)

    /**
     * 清单里存的是 MediaStore 原样 URI，录屏那批挂在 `.../external/video/media/<id>` 下，
     * 看路径段就认得出来。先认再解，省得拿 BitmapFactory 去啃一遍几十兆的 mp4；
     * 认漏了也不要紧，图片那条路走不通照样会退到抽帧。
     */
    private fun isVideoUri(uri: Uri) = uri.pathSegments.contains("video")

    private fun decodeImage(ctx: Context, uri: Uri, maxPx: Int): Bitmap? {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    /** 抽一帧当封面。只要一张静态图，不做播放。 */
    private fun decodeVideoFrame(ctx: Context, uri: Uri, maxPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, uri)
            // 视频开头常有黑帧或淡入，往一秒半那里取，取不到再退回首帧
            retriever.frameAt(FRAME_OFFSET_US, maxPx) ?: retriever.frameAt(0L, maxPx)
        } catch (e: Exception) {
            // 损坏的录屏、解码器不支持的编码：没有封面，别把缩略图线程带崩
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.frameAt(timeUs: Long, maxPx: Int): Bitmap? {
        // 27 起能直接解出小图，避免先解一张整帧再缩
        val scaled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
            runCatching { getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, maxPx, maxPx) }.getOrNull()
        else null
        if (scaled != null) return scaled
        val full = runCatching { getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull() ?: return null
        val ratio = maxPx.toFloat() / maxOf(full.width, full.height)
        if (ratio >= 1f) return full
        return Bitmap.createScaledBitmap(
            full,
            (full.width * ratio).toInt().coerceAtLeast(1),
            (full.height * ratio).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== full) full.recycle() }
    }

    private const val FRAME_OFFSET_US = 1_500_000L
}
