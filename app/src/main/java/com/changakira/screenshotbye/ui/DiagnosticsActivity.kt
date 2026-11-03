package com.changakira.screenshotbye.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.databinding.ActivityDiagnosticsBinding
import com.changakira.screenshotbye.diagnostic.Diagnostics
import com.changakira.screenshotbye.notify.Channels
import com.changakira.screenshotbye.perm.Perms
import java.util.concurrent.Executors

/**
 * 自检页：把「服务有没有在跑、监听有没有回调、通知能不能出来、队列卡在哪一步」摊开显示。
 *
 * 这个应用的行为极其取决于机型和 ROM，光看代码判断不了，所以把状态做成可复制的文本，
 * 反馈问题时能直接给出证据。「发送测试提醒」按当前提醒强度走真实渠道，用来区分
 * 是应用没发通知，还是系统把横幅和声音压掉了。
 */
class DiagnosticsActivity : ThemedActivity() {

    private lateinit var b: ActivityDiagnosticsBinding
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "shot-diag").apply { isDaemon = true } }
    private var report = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Channels.ensure(this)
        b = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.title = getString(R.string.diag_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.refreshBtn.setOnClickListener { reload() }
        b.testBtn.setOnClickListener {
            if (Diagnostics.sendTest(this)) {
                toast(getString(R.string.diag_test_sent))
            } else {
                val why = Diagnostics.alertBlockers(this)
                Toast.makeText(
                    this,
                    if (why.isEmpty()) getString(R.string.diag_test_blocked_unknown)
                    else getString(R.string.diag_test_blocked, why),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        b.copyBtn.setOnClickListener { copyReport() }
        b.notifySettingsBtn.setOnClickListener { Perms.openChannelSettings(this, Channels.REVIEW) }
        reload()
    }

    private fun reload() {
        if (busy) return
        busy = true
        b.reportText.setText(R.string.diag_loading)
        io.execute {
            val text = runCatching { Diagnostics.collect(this) }
                .getOrElse { e -> "采集失败：${e.javaClass.simpleName} ${e.message}" }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                report = text
                b.reportText.text = text
            }
        }
    }

    private fun copyReport() {
        if (report.isEmpty()) {
            toast(getString(R.string.diag_loading))
            return
        }
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.diag_title), report))
        toast(getString(R.string.diag_copied))
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
