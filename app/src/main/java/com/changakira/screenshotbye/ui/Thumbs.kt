package com.changakira.screenshotbye.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import com.changakira.screenshotbye.R
import java.io.File
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

    fun file(view: ImageView, file: File) {
        into(view, "file:" + file.absolutePath) { if (file.exists()) Uri.fromFile(file) else null }
    }

    fun media(view: ImageView, uri: Uri, maxPx: Int = 128, cache: Boolean = true) {
        into(view, "media:$uri:$maxPx", maxPx, cache) { uri }
    }

    private fun decode(ctx: Context, uri: Uri, maxPx: Int): Bitmap? {
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
}
