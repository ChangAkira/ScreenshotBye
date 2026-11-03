package com.changakira.screenshotbye.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.ScreenshotByeApp
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.databinding.ActivityOnboardingBinding
import com.changakira.screenshotbye.notify.Channels
import com.changakira.screenshotbye.perm.Perms
import com.changakira.screenshotbye.service.WatchService

/**
 * 首次启动的分页说明。
 *
 * 这个应用要的东西比较多（后台常驻、读取相册、最好还要「所有文件访问」），
 * 直接把用户扔进一面权限开关墙，大多数人会关掉就走。所以先把「它什么时候会问你、
 * 删除前会不会备份、不授权会怎样」讲明白，最后一页再问要不要立刻开启监控。
 */
class OnboardingActivity : ThemedActivity() {

    private lateinit var b: ActivityOnboardingBinding
    private lateinit var settings: Settings
    private lateinit var titles: Array<String>
    private lateinit var bodies: Array<String>
    private var page = 0

    private val askPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startIfAllowed()
        openMain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Channels.ensure(this)
        settings = Settings.of(this)
        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)

        titles = resources.getStringArray(R.array.onb_titles)
        bodies = resources.getStringArray(R.array.onb_bodies)
        buildDots()

        b.prevBtn.setOnClickListener { if (page > 0) render(page - 1) }
        b.nextBtn.setOnClickListener { if (page < titles.lastIndex) render(page + 1) else askPermissions() }
        // 跳过：不自动开监控、也不弹权限，回主界面由用户自己点
        b.skipBtn.setOnClickListener {
            settings.onboarded = true
            openMain()
        }

        render(0)
    }

    private fun render(target: Int) {
        page = target
        b.pageLabel.text = getString(R.string.onb_page_label, page + 1, titles.size)
        b.onbTitle.text = titles[page]
        b.onbBody.text = bodies[page]
        b.lastBox.visibility = if (page == titles.lastIndex) View.VISIBLE else View.GONE
        b.prevBtn.isEnabled = page > 0
        b.nextBtn.text = getString(
            if (page == titles.lastIndex) R.string.onb_start else R.string.onb_next
        )
        b.dots.children().forEachIndexed { i, dot ->
            dot.setBackgroundResource(if (i == page) R.drawable.dot_active else R.drawable.dot_idle)
            val size = dp(if (i == page) 10 else 6)
            val gap = dp(4)
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = gap
                marginEnd = gap
            }
        }
    }

    private fun buildDots() {
        b.dots.removeAllViews()
        repeat(titles.size) { b.dots.addView(View(this)) }
    }

    /** 引导结束时一次问齐运行期权限，之后 MainActivity 不再重复弹窗。 */
    private fun askPermissions() {
        settings.onboarded = true
        settings.permsAsked = true
        val needed = mutableListOf<String>()
        needed += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            needed += Manifest.permission.ACCESS_MEDIA_LOCATION
        }
        if (Perms.hasMediaRead(this) && Perms.hasNotification(this)) {
            startIfAllowed()
            openMain()
        } else {
            askPerms.launch(needed.distinct().toTypedArray())
        }
    }

    private fun startIfAllowed() {
        if (!b.watchSwitch.isChecked || !Perms.hasMediaRead(this)) return
        settings.enabled = true
        WatchService.start(this)
        ScreenshotByeApp.scheduleWatchdog(this)
        settings.appendLog("引导完成后开启监控")
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun LinearLayout.children(): List<View> =
        (0 until childCount).map { getChildAt(it) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
