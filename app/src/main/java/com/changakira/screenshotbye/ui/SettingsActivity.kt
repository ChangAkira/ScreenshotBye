package com.changakira.screenshotbye.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.ScreenshotByeApp
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.Reminders
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.databinding.ActivitySettingsBinding
import com.changakira.screenshotbye.notify.Notify
import com.changakira.screenshotbye.perm.Perms
import com.changakira.screenshotbye.service.WatchService
import com.changakira.screenshotbye.ui.theme.Palette
import com.google.android.material.color.MaterialColors
import java.util.concurrent.Executors

/**
 * 设置页四块，照参考仓库的分卡：启用服务 / 识别规则与待确认 / 旋钮 / 主题颜色与引导日志。
 *
 * 询问、保留、间隔、重复用固定档位旋钮而不是滑块：可选值就三五个，摊成一圈数字比让用户
 * 在一条连续轨道上找「15 分钟那一格」快得多，也不会出现 8 分钟和 12 分钟这种没区别的取值。
 */
class SettingsActivity : ThemedActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var settings: Settings
    private lateinit var repo: ScreenshotRepository
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "shot-settings").apply { isDaemon = true } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)
        b.toolbar.setNavigationOnClickListener { finish() }

        settings = Settings.of(this)
        repo = ScreenshotRepository(this)

        wireService()
        wireDials()
        wireAppearance()
    }

    override fun onResume() {
        super.onResume()
        // 待确认张数、日志条数读的都是本地偏好，同步刷就行
        refreshCounts()
        io.execute { runCatching { repo.sweepStale() } }
    }

    // ---- ① 服务 + 识别规则 + 待确认 ----

    private fun wireService() {
        b.watchSwitch.isChecked = settings.enabled
        b.watchSwitch.setOnClickListener { setWatching(b.watchSwitch.isChecked) }

        b.ruleFolder.isChecked = settings.matchByFolder
        b.ruleName.isChecked = settings.matchByName
        b.ruleVideo.isChecked = settings.includeVideo
        val onRule = CompoundButton.OnCheckedChangeListener { _, _ ->
            settings.matchByFolder = b.ruleFolder.isChecked
            settings.matchByName = b.ruleName.isChecked
            settings.includeVideo = b.ruleVideo.isChecked
            settings.appendLog("识别规则已更新")
            // 规则一变，之前被漏掉/误收的条目要重新过一遍，否则得等到下一次相册变动
            WatchService.rescan(this)
            toast(getString(R.string.settings_rules_saved))
        }
        b.ruleFolder.setOnCheckedChangeListener(onRule)
        b.ruleName.setOnCheckedChangeListener(onRule)
        b.ruleVideo.setOnCheckedChangeListener(onRule)

        b.pendingRow.setOnClickListener { startActivity(Intent(this, ReviewActivity::class.java)) }
    }

    private fun setWatching(on: Boolean) {
        if (on) {
            if (!Perms.hasMediaRead(this)) {
                toast(getString(R.string.toast_need_read_media))
                b.watchSwitch.isChecked = false
                return
            }
            settings.enabled = true
            WatchService.start(this)
            ScreenshotByeApp.scheduleWatchdog(this)
            settings.appendLog("监控已开启")
        } else {
            settings.enabled = false
            WatchService.stop(this)
            ScreenshotByeApp.cancelWatchdog(this)
            Notify.cancel(this, Notify.ID_RECOVERY)
            settings.appendLog("监控已关闭")
        }
        toast(getString(if (on) R.string.settings_watch_toast_on else R.string.settings_watch_toast_off))
    }

    // ---- ② 旋钮 ----

    private fun wireDials() {
        b.askDial.value = settings.delayMinutes
        b.askDial.setOnValueChangeListener { minutes ->
            settings.delayMinutes = minutes
            // 队列里还在计时的条目跟着新延迟重排，不然改了要等下一张截图才见效
            io.execute {
                val store = PendingShotStore.of(this)
                val span = minutes * 60_000L
                store.replaceAll(
                    store.all().map {
                        if (it.state == ShotState.WAITING) it.copy(askAt = it.sinceStateAt + span) else it
                    }
                )
                // 重排后可能有几条已经「提前到期」了，交给同一个入口立刻补问，没到的重新计时
                Reminders.catchUpOverdue(this, store)
                WatchService.refreshNotification(this)
            }
        }

        b.keepDial.value = settings.askExpiryDays
        b.keepDial.setOnValueChangeListener { settings.askExpiryDays = it }

        b.gapDial.value = settings.alertRepeatMinutes
        b.gapDial.setOnValueChangeListener {
            settings.alertRepeatMinutes = it
            if (it > 0) Reminders.scheduleAlertRetry(this, it) else Reminders.cancelAlertRetry(this)
        }

        b.timesDial.value = settings.alertMaxTimes
        b.timesDial.setOnValueChangeListener { settings.alertMaxTimes = it }
    }

    // ---- ④ 主题颜色 + 使用引导 + 日志 + 自检 ----

    private fun wireAppearance() {
        buildSwatches()
        b.guideRow.setOnClickListener { startActivity(Intent(this, OnboardingActivity::class.java)) }
        b.logRow.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        b.diagRow.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
    }

    private fun buildSwatches() {
        val current = Palette.current(this)
        b.swatchRow.removeAllViews()
        Palette.entries.forEach { p -> b.swatchRow.addView(swatchView(p, p == current)) }
    }

    /** 色块 = 外圈选中环 + 内圆主色，跟参考仓库一样：只有选中那个带环，别的都是空环。 */
    private fun swatchView(palette: Palette, selected: Boolean): View {
        val dp = resources.displayMetrics.density
        val swatch = ContextCompat.getColor(this, palette.swatchRes)
        val ringColor = if (selected) swatch
        else MaterialColors.getColor(b.swatchRow, com.google.android.material.R.attr.colorOutline)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(((if (selected) 3f else 1.5f) * dp).toInt(), ringColor)
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(swatch)
        }
        val circle = View(this).apply {
            background = fill
            layoutParams = FrameLayout.LayoutParams((30f * dp).toInt(), (30f * dp).toInt()).apply {
                gravity = Gravity.CENTER
            }
        }
        val box = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams((40f * dp).toInt(), (40f * dp).toInt()).apply {
                marginEnd = (10f * dp).toInt()
            }
            background = ring
            contentDescription = getString(palette.nameRes)
            isClickable = true
            isFocusable = true
            // 主题色随点随变，靠的就是这一层 recreate
            setOnClickListener { selectPalette(palette) }
        }
        box.addView(circle)
        return box
    }

    private fun refreshCounts() {
        val pending = repo.pending().size
        b.pendingValue.text = if (pending == 0) getString(R.string.settings_pending_none)
        else getString(R.string.settings_pending_count, pending)
        b.logValue.text = getString(R.string.settings_log_count, settings.log.lines().count { it.isNotBlank() })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
