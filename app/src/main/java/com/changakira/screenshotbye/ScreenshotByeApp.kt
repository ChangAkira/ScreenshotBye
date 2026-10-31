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
        Channels.ensure(this)
        // 看门狗：即使服务被 ROM 杀掉，也能在下个周期把它拉回来（拉不动时发通知求助）
        if (Settings.of(this).enabled) scheduleWatchdog(this)
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
