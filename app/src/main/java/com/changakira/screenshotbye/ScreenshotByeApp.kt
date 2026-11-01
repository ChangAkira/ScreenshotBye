package com.changakira.screenshotbye

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.notify.Channels
import com.changakira.screenshotbye.service.WatchdogWorker
import java.util.concurrent.TimeUnit

class ScreenshotByeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val settings = Settings.of(this)
        watchCrashes(settings)
        Channels.ensure(this)
        // 看门狗：即使服务被 ROM 杀掉，也能在下个周期把它拉回来（拉不动时发通知求助）
        if (settings.enabled) runCatching { scheduleWatchdog(this) }
            .onFailure { settings.appendLog("看门狗排期失败：${it.javaClass.simpleName}") }
    }

    /**
     * 未捕获异常先记进设置里再交给系统。作者的沙箱起不了模拟器，也拿不到用户的 logcat，
     * 闪退在现场没有任何证据；记一条下来，自检页就能复制出堆栈。
     */
    private fun watchCrashes(settings: Settings) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { settings.recordCrash(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val WATCHDOG = "screenshotbye-watchdog"

        fun scheduleWatchdog(ctx: android.content.Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WATCHDOG, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun cancelWatchdog(ctx: android.content.Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WATCHDOG)
        }
    }
}
