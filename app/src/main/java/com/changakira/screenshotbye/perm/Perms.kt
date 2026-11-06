package com.changakira.screenshotbye.perm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.core.content.ContextCompat

/** 权限状态查询 + 跳转引导。 */
object Perms {

    /** 33+ 用 READ_MEDIA_IMAGES，更低版本用 READ_EXTERNAL_STORAGE。 */
    fun hasMediaRead(ctx: Context): Boolean {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return granted(ctx, p)
    }

    fun hasNotification(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    /**
     * 能否不经系统弹窗就把文件从相册里抹掉（界面上叫「无感删除」）。
     * - Android 11+：只有用户手动授予「所有文件访问」才为真。这时删除当场生效、不弹确认框；
     *   省下的只是弹窗；原件进不进回收站由系统定，而且这一项三方应用查不到（真删与「挪进不被索引的
     *   回收站目录」在媒体库里同一个表现），所以只在设置页给个入口、不主动催，也不在界面上写去向。
     * - Android 10 及以下：有 WRITE 就能直删（10 上配合 requestLegacyExternalStorage），那一版本来也没有回收站。
     */
    fun canDeleteSilently(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) hasAllFilesAccess(ctx)
        else granted(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /**
     * 「所有文件访问」（特殊访问）。Android 11 以下没有这一项，返回真表示无需再管。
     * 系统方法在个别 ROM 上会抛，兜成 false——顶多少了「无感删除」，不该把设置页带崩。
     */
    @SuppressLint("MissingPermission")
    fun hasAllFilesAccess(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /**
     * 「所有文件访问」只能通过系统页面手动打开，应用侧无法弹窗申请。
     * 带包名的那条 action 在部分 ROM 上不存在，失败就退回总列表页。
     */
    fun openAllFilesAccess(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val direct = Intent(SysSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.fromParts("package", ctx.packageName, null))
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(direct) }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(SysSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun canIgnoreBattery(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun requestIgnoreBattery(ctx: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else null
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 会弹系统确认框，交由调用方决定是否用列表页替代
        runCatching {
            val direct = Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
                .setData(Uri.fromParts("package", ctx.packageName, null))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(direct)
        }.onFailure {
            runCatching { intent?.let { i -> ctx.startActivity(i) } }
        }
    }

    fun openAppNotificationSettings(ctx: Context) {
        val intent = Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    /**
     * 直接跳到某条通知渠道的系统设置页——声音、震动、横幅、锁屏显示都在那里改。
     * 提醒强度不做进应用：这些开关本来就该由用户在系统里管，某些 ROM 上应用侧也改不动。
     */
    fun openChannelSettings(ctx: Context, channelId: String) {
        val intent = Intent(SysSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(SysSettings.EXTRA_CHANNEL_ID, channelId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { openAppNotificationSettings(ctx) }
    }

    /**
     * 国产 ROM 的自启动 / 后台运行白名单入口。各家 Settings 页 action 不公开也不稳定，
     * 只能逐个尝试、失败即跳过；找不到时回退到应用信息页，让用户手动操作。
     */
    fun openOemAutoStart(ctx: Context): Boolean {
        val pkgs = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
        )
        val pm = ctx.packageManager
        for ((pkg, cls) in pkgs) {
            if (runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)) {
                val intent = Intent().setClassName(pkg, cls)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { ctx.startActivity(intent); true }.getOrDefault(false)) return true
            }
        }
        runCatching { ctx.startActivity(
            Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", ctx.packageName, null))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) }
        return false
    }

    private fun granted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}
