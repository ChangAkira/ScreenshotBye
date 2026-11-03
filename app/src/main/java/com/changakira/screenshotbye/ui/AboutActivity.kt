package com.changakira.screenshotbye.ui

import android.os.Bundle
import androidx.core.content.pm.PackageInfoCompat
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.databinding.ActivityAboutBinding

/**
 * 「关于」二级页面：设置页「运行自检」下面一行入口进到这里。
 * 目前正文先用占位文本填充，后续再补应用说明、致谢与开源许可。
 */
class AboutActivity : ThemedActivity() {

    private lateinit var b: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.title = getString(R.string.about_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.versionText.text = getString(R.string.about_version, versionName(), versionCode())
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun versionCode(): Int = runCatching {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0)).toInt()
    }.getOrDefault(0)
}
