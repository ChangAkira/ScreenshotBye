package com.changakira.screenshotbye.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.changakira.screenshotbye.core.Decision
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.service.WatchService
import java.util.concurrent.Executors

/**
 * 通知上的「不需要，删除 / 还要留着 / 稍后再问」由这里处理。
 *
 * 复制备份 + 删除可能在毫秒级之外，因此用 goAsync() 把广播生命周期延长到后台线程结束，
 * 否则 receiver 一返回就销毁，删除会被中途掐断。
 */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val mediaId = intent.getLongExtra(Actions.EXTRA_MEDIA_ID, -1L)
        val finish = goAsync()
        executor.execute {
            try {
                when (intent.action) {
                    Actions.ACTION_DELETE -> ScreenshotRepository(app).decide(mediaId, Decision.DELETE)
                    Actions.ACTION_KEEP -> ScreenshotRepository(app).decide(mediaId, Decision.KEEP)
                    Actions.ACTION_SNOOZE -> ScreenshotRepository(app).decide(mediaId, Decision.SNOOZE)
                    Actions.ACTION_STOP_WATCH -> {
                        Settings.of(app).enabled = false
                        WatchService.stop(app)
                    }
                    else -> Settings.of(app).appendLog("未知通知动作：${intent.action}")
                }
            } catch (e: Exception) {
                Settings.of(app).appendLog("处理通知动作失败：${e.javaClass.simpleName}")
            } finally {
                finish.finish()
            }
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "shot-action").apply { isDaemon = true } }
    }
}
