package com.changakira.screenshotbye.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.ScreenshotByeApp
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.notify.Channels
import com.changakira.screenshotbye.service.WatchService

/** 开机 / 覆盖安装后自动恢复监控。BOOT_COMPLETED 是系统允许后台拉起前台服务的少数场景之一。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Channels.ensure(app)
                if (!Settings.of(app).enabled) return
                WatchService.start(app)
                ScreenshotByeApp.scheduleWatchdog(app)
                Settings.of(app).appendLog(app.getString(R.string.log_boot_restored))
            }
        }
    }
}
