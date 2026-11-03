package com.changakira.screenshotbye.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.databinding.ActivityLogBinding

/** 事件日志二级页面：设置页只留一行入口，正文和「复制走」都在这里。 */
class LogActivity : ThemedActivity() {

    private lateinit var b: ActivityLogBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.title = getString(R.string.log_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        settings = Settings.of(this)
        render()

        b.copyBtn.setOnClickListener {
            val text = settings.log
            if (text.isBlank()) {
                toast(getString(R.string.log_empty)); return@setOnClickListener
            }
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("screenshotbye-log", text))
            toast(getString(R.string.diag_copied))
        }
        b.clearBtn.setOnClickListener {
            settings.clearLog()
            render()
            toast(getString(R.string.log_cleared))
        }
    }

    private fun render() {
        b.logText.text = settings.log.ifBlank { getString(R.string.log_empty) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
