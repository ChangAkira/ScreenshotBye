package com.changakira.screenshotbye.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.PackageInfoCompat
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.databinding.ActivityMainBinding
import com.changakira.screenshotbye.notify.Channels
import com.changakira.screenshotbye.perm.Perms
import com.changakira.screenshotbye.service.WatchService
import com.google.android.material.color.MaterialColors
import java.util.concurrent.Executors

/**
 * 首页只管一件事：能不能正常干活。
 *
 * 权限、状态、引导、进设置的入口，其它可调项都在 SettingsActivity，
 * 免得打开应用先被一堆滑块淹没。
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
        b.guideBtn.setOnClickListener { startActivity(Intent(this, OnboardingActivity::class.java)) }
        b.oemBtn.setOnClickListener { Perms.requestIgnoreBattery(this) }
        b.oemAutostartBtn.setOnClickListener { Perms.openOemAutoStart(this) }
        b.versionText.text = getString(R.string.home_version, versionName(), versionCode())

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
        buildPermissionRows()

        // 权限还缺就继续问；全齐了把按钮按住，免得首页一直挂着个点了没反应的大按钮
        val coreMissing = !Perms.hasMediaRead(this) || !Perms.hasNotification(this)
        // Android 11+ 不需要任何「静默删除」权限：删除请求本来就交给系统弹窗
        val writeMissing = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !Perms.canDeleteSilently(this)
        b.askPermsBtn.isEnabled = coreMissing || writeMissing
        b.askPermsBtn.text = getString(
            if (b.askPermsBtn.isEnabled) R.string.home_request_perms else R.string.home_request_perms_done
        )

        // 国产 ROM 的自启动/电池优化不归应用管，只有没放行时才占这一块位置
        b.oemCard.visibility = if (Perms.canIgnoreBattery(this)) View.GONE else View.VISIBLE
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

    private fun buildPermissionRows() {
        val rows = listOf(
            Row(getString(R.string.perm_read_media), Perms.hasMediaRead(this)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    askPerms.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
                else askPerms.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            },
            Row(getString(R.string.perm_notification), Perms.hasNotification(this)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                else Perms.openAppNotificationSettings(this)
            },
            Row(getString(R.string.perm_legacy_write), Perms.canDeleteSilently(this), visible = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                askPerms.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            },
        )
        b.permContainer.removeAllViews()
        rows.filter { it.visible }.forEach { addPermRow(it) }
    }

    private data class Row(
        val label: String,
        /** null 表示系统没提供可查询的开关状态，只能让用户自己去确认 */
        val granted: Boolean?,
        val visible: Boolean = true,
        val onClick: () -> Unit,
    )

    private fun addPermRow(row: Row) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val label = TextView(this).apply {
            val name = row.label
            val status = when (row.granted) {
                true -> "\n" + getString(R.string.perm_granted)
                false -> "\n" + getString(R.string.perm_denied)
                null -> ""
            }
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
            // 走主题属性而不是 @color/muted：硬写的深灰在夜间模式的深底上几乎看不见
            setTextColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (status.isNotEmpty()) {
                val span = SpannableString(name + status)
                if (row.granted == false) {
                    span.setSpan(
                        ForegroundColorSpan(
                            MaterialColors.getColor(this, com.google.android.material.R.attr.colorError)
                        ),
                        name.length, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                text = span
            } else {
                text = name
            }
        }
        val button = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(if (row.granted == true) R.string.perm_go_grant else R.string.perm_grant_now)
            textSize = 12f
            isSingleLine = true
            setMinHeight(dp(36))
            minWidth = dp(88)
        }
        button.setOnClickListener { row.onClick() }
        container.addView(label)
        container.addView(button)
        b.permContainer.addView(container)
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun versionCode(): Int = runCatching {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0)).toInt()
    }.getOrDefault(0)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
