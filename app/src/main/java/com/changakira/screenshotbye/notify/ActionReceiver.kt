package com.changakira.screenshotbye.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.changakira.screenshotbye.core.Decision
import com.changakira.screenshotbye.core.Reminders
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
                    Actions.ACTION_SNOOZE_ALL -> ScreenshotRepository(app).snoozeAllDue()
                    Actions.ACTION_ON_DISMISS -> onNotificationDismissed(app)
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

    /**
     * 划掉询问通知不代表已经回答。把节流计时清零，并排一个延时补提醒，
     * 否则这批截图会一直沉在通知历史里没人管。
     *
     * internal 只为了能被单元测试直接调用：Robolectric 只按 intent-filter 匹配清单里的
     * receiver，而这条广播在生产里是显式点名投递的，走不到这个方法。
     */
    internal fun onNotificationDismissed(app: Context) {
        val settings = Settings.of(app)
        if (ScreenshotRepository(app).dueQueue().isEmpty()) return
        settings.lastAlertAt = 0L
        Reminders.scheduleAlertRetry(app, settings.alertRepeatMinutes)
        settings.appendLog("询问通知被划掉，已安排稍后补提醒")
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "shot-action").apply { isDaemon = true } }
    }
}
