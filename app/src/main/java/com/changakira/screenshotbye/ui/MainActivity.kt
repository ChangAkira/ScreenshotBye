package com.changakira.screenshotbye.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.databinding.ActivityMainBinding
import com.changakira.screenshotbye.notify.Channels
import com.changakira.screenshotbye.perm.Perms
import com.changakira.screenshotbye.service.WatchService
import java.util.concurrent.Executors

/**
 * 首页照参考仓库 MainScreen 一比一：居中一列，标题 / 副标题 / 一行状态，
 * 两个整宽按钮（请求权限、设置），下面是电池优化卡（仅未放行时出现）与自启动卡（常驻）。
 *
 * 权限不再逐行说明——授予状态全部由「请求权限」这一个按钮的启用与文案体现，
 * 缺什么一次问齐；使用引导、可调项都在 SettingsActivity。
 */
class MainActivity : ThemedActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var repo: ScreenshotRepository
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "shot-home").apply { isDaemon = true } }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thumbs.init(this)
        Channels.ensure(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // 首页没有二级操作，标题就是页面里那行大字，不再叠一条 toolbar 上去

        settings = Settings.of(this)
        repo = ScreenshotRepository(this)

        b.askPermsBtn.setOnClickListener { onAskPerms() }
        b.settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.oemBtn.setOnClickListener { Perms.requestIgnoreBattery(this) }
        b.oemAutostartBtn.setOnClickListener { Perms.openOemAutoStart(this) }

        if (settings.onboarded) {
            // 从旧版本升上来、还没问过权限的用户：进页面问一次，之后不再重复弹
            requestCorePermissions()
        } else {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 先把界面填上，别等后台：sweepStale 万一抛异常，首页不该是一片空白
        refresh()
        io.execute {
            // 打开应用顺便清一次过期/卡住的条目：监控关闭或定时任务丢失时看门狗不在，这里是唯一兜底
            runCatching { repo.sweepStale() }
            runOnUiThread { refresh() }
        }
    }

    private fun refresh() {
        val pending = repo.pending()
        b.statusText.text = if (WatchService.running) {
            getString(R.string.main_running, pending.size)
        } else getString(R.string.main_stopped)

        // 权限还缺就继续问；全齐了把按钮按住，免得首页一直挂着个点了没反应的大按钮
        val coreMissing = !Perms.hasMediaRead(this) || !Perms.hasNotification(this)
        // Android 11+ 不需要任何「静默删除」权限：删除请求本来就交给系统弹窗
        val writeMissing = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !Perms.canDeleteSilently(this)
        val allGranted = !(coreMissing || writeMissing)
        b.askPermsBtn.isEnabled = !allGranted
        b.askPermsBtn.text = getString(
            if (allGranted) R.string.home_request_perms_done else R.string.home_request_perms
        )

        // 电池优化卡只在系统没放行时出现；自启动卡常驻，和参考仓库一致
        b.batteryCard.visibility = if (Perms.canIgnoreBattery(this)) View.GONE else View.VISIBLE
    }

    private fun onAskPerms() {
        requestCorePermissions(force = true)
    }

    /** 缺少关键权限时一次性问齐；引导页问过一次后不再重复弹，用户点授权按钮时照样能问。 */
    private fun requestCorePermissions(force: Boolean = false) {
        if (settings.permsAsked && !force) return
        val needed = mutableListOf<String>()
        if (!Perms.hasMediaRead(this)) {
            needed += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !Perms.canDeleteSilently(this)) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Perms.hasNotification(this)) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (!Perms.hasMediaRead(this) || !Perms.hasNotification(this)) {
            needed += Manifest.permission.ACCESS_MEDIA_LOCATION
        }
        if (needed.isNotEmpty()) {
            settings.permsAsked = true
            askPerms.launch(needed.distinct().toTypedArray())
        }
    }
}
