package com.changakira.screenshotbye.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.ScreenshotByeApp
import com.changakira.screenshotbye.core.Decision
import com.changakira.screenshotbye.core.DecisionOutcome
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.databinding.ActivityMainBinding
import com.changakira.screenshotbye.notify.Channels
import com.changakira.screenshotbye.notify.Notify
import com.changakira.screenshotbye.perm.Perms
import com.changakira.screenshotbye.service.WatchService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var repo: ScreenshotRepository
    private lateinit var adapter: ShotAdapter
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "shot-ui").apply { isDaemon = true } }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thumbs.init(this)
        Channels.ensure(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        settings = Settings.of(this)
        repo = ScreenshotRepository(this)

        adapter = ShotAdapter(
            onDelete = { decide(it, Decision.DELETE) },
            onKeep = { decide(it, Decision.KEEP) },
            onPreview = { showPreview(it) },
        )
        b.pendingList.layoutManager = LinearLayoutManager(this)
        b.pendingList.adapter = adapter
        b.pendingList.isNestedScrollingEnabled = false

        wireControls()
        if (settings.onboarded) {
            // 从旧版本升上来、还没问过权限的用户：进页面问一次，之后不再重复弹
            requestCorePermissions()
        } else {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun wireControls() {
        b.watchSwitch.setOnClickListener { setWatching(b.watchSwitch.isChecked) }
        b.openReviewBtn.setOnClickListener { startActivity(Intent(this, ReviewActivity::class.java)) }
        b.guideBtn.setOnClickListener { startActivity(Intent(this, OnboardingActivity::class.java)) }

        b.delaySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.delayMinutes = value.toInt()
                b.delayLabel.text = getString(R.string.main_delay_value, value.toInt())
            }
        }
        b.trashDaysSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) settings.trashDays = value.toInt()
        }
        b.trashSwitch.setOnClickListener { settings.useTrash = b.trashSwitch.isChecked }

        b.ruleFolder.isChecked = settings.matchByFolder
        b.ruleName.isChecked = settings.matchByName
        b.ruleVideo.isChecked = settings.includeVideo
        b.keywordsInput.setText(settings.extraKeywords.joinToString(", "))
        b.saveRulesBtn.setOnClickListener {
            settings.matchByFolder = b.ruleFolder.isChecked
            settings.matchByName = b.ruleName.isChecked
            settings.includeVideo = b.ruleVideo.isChecked
            settings.extraKeywords = b.keywordsInput.text?.toString()
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            settings.appendLog("识别规则已更新")
            WatchService.rescan(this)
            toast(getString(R.string.main_saved))
        }

        b.openTrashBtn.setOnClickListener { startActivity(Intent(this, TrashActivity::class.java)) }
        b.diagBtn.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        b.clearLogBtn.setOnClickListener { settings.clearLog(); b.logText.text = "" }

        b.expirySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.askExpiryDays = value.toInt()
                b.expiryLabel.text = expiryLabel(value.toInt())
            }
        }
        b.repeatSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.alertRepeatMinutes = value.toInt()
                b.repeatLabel.text = repeatLabel(value.toInt())
            }
        }
        b.maxTimesSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.alertMaxTimes = value.toInt()
                b.maxTimesLabel.text = getString(R.string.main_max_times_value, value.toInt())
            }
        }
        b.notifySettingsBtn.setOnClickListener { Perms.openChannelSettings(this, Channels.REVIEW) }
    }

    private fun expiryLabel(days: Int): String =
        if (days == 0) getString(R.string.main_expiry_never)
        else getString(R.string.main_expiry_value, days)

    private fun repeatLabel(minutes: Int): String =
        if (minutes == 0) getString(R.string.main_repeat_never)
        else getString(R.string.main_repeat_value, minutes)

    private fun setWatching(on: Boolean) {
        if (on) {
            if (!Perms.hasMediaRead(this)) {
                toast(getString(R.string.toast_need_read_media))
                requestCorePermissions(force = true)
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

    private fun decide(shot: PendingShot, decision: Decision) {
        io.execute {
            val outcome = repo.decide(shot.mediaId, decision)
            runOnUiThread {
                when (outcome) {
                    is DecisionOutcome.Deleted -> toast(
                        if (outcome.inTrash) getString(R.string.toast_deleted_trash) else getString(R.string.toast_deleted)
                    )
                    DecisionOutcome.Kept -> toast(getString(R.string.toast_kept))
                    DecisionOutcome.Snoozed -> toast(getString(R.string.toast_snoozed))
                    DecisionOutcome.NeedsSystemConfirm -> toast(getString(R.string.toast_needs_confirm))
                    is DecisionOutcome.Error -> toast(outcome.reason)
                }
                refreshAll()
            }
        }
    }

    private fun showPreview(shot: PendingShot) {
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 360)
        }
        Thumbs.media(image, shot.contentUri, maxPx = 720, cache = false)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_preview)
            .setMessage(shot.name)
            .setView(image)
            .setPositiveButton(R.string.act_keep) { _, _ -> decide(shot, Decision.KEEP) }
            .setNegativeButton(R.string.act_delete) { _, _ -> decide(shot, Decision.DELETE) }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        val pending = repo.pending()
        b.pendingTitle.text = getString(R.string.main_pending, pending.size)
        b.pendingEmpty.visibility = if (pending.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(pending)

        b.watchSwitch.isChecked = settings.enabled
        b.statusText.text = if (WatchService.running) {
            getString(R.string.main_running, pending.size)
        } else getString(R.string.main_stopped)

        b.delaySlider.value = settings.delayMinutes.coerceIn(1, 60).toFloat()
        b.delayLabel.text = getString(R.string.main_delay_value, settings.delayMinutes)
        b.trashSwitch.isChecked = settings.useTrash
        b.trashDaysSlider.value = settings.trashDays.coerceIn(1, 30).toFloat()
        b.expirySlider.value = settings.askExpiryDays.coerceIn(0, 14).toFloat()
        b.expiryLabel.text = expiryLabel(settings.askExpiryDays)
        b.repeatSlider.value = settings.alertRepeatMinutes.coerceIn(0, 120).toFloat()
        b.repeatLabel.text = repeatLabel(settings.alertRepeatMinutes)
        b.maxTimesSlider.value = settings.alertMaxTimes.coerceIn(1, 10).toFloat()
        b.maxTimesLabel.text = getString(R.string.main_max_times_value, settings.alertMaxTimes)

        io.execute {
            // 打开应用顺便清一次过期/卡住的条目：监控关闭或定时任务丢失时看门狗不在，这里是唯一兜底
            ScreenshotRepository(this).sweepStale()
            val entries = repo.trash.list()
            val summary = getString(R.string.main_trash_summary, entries.size, sizeOf(this, entries.sumOf { it.size }))
            runOnUiThread {
                b.trashSummary.text = summary
                b.logText.text = settings.log.ifBlank { "—" }
            }
        }
        buildPermissionRows()
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
            Row(getString(R.string.perm_all_files), Perms.hasAllFilesAccess(), visible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Perms.openAllFilesAccess(this)
            },
            Row(getString(R.string.perm_legacy_write), Perms.canDeleteSilently(this), visible = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                askPerms.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            },
            Row(getString(R.string.perm_battery), Perms.canIgnoreBattery(this)) {
                Perms.requestIgnoreBattery(this)
            },
            Row(getString(R.string.perm_autostart), null) {
                Perms.openOemAutoStart(this)
            },
        )
        b.permContainer.removeAllViews()
        rows.filter { it.visible }.forEach { row -> addPermRow(row) }
    }

    private data class Row(
        val label: String,
        /** null 表示系统没提供可查询的开关状态，只能让用户自己去确认 */
        val granted: Boolean?,
        val visible: Boolean = true,
        val onClick: () -> Unit,
    )

    private fun addPermRow(row: Row) {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val label = TextView(ctx).apply {
            text = buildString {
                append(row.label)
                append(when (row.granted) {
                    true -> "\n" + getString(R.string.perm_granted)
                    false -> "\n" + getString(R.string.perm_denied)
                    null -> ""
                })
            }
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(getColor(if (row.granted == false) R.color.warn else R.color.muted))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val button = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
