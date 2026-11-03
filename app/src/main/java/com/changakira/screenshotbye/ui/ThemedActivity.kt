package com.changakira.screenshotbye.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.ui.theme.Palette

/**
 * 所有对外可见的页面都继承它：在 super.onCreate 之前把配色主题设好。
 * 布局是在 super.onCreate 之后才 inflate 的，晚一步就会用错颜色。
 */
abstract class ThemedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Palette.current(this).themeRes)
        super.onCreate(savedInstanceState)
    }

    /** 换配色：记下选择并重建当前页，新颜色当场铺满整屏，不用重开应用。 */
    protected fun selectPalette(palette: Palette) {
        val settings = Settings.of(this)
        if (settings.themePalette == palette.id) return
        settings.themePalette = palette.id
        settings.appendLog("主题颜色切换为 ${getString(palette.nameRes)}")
        recreate()
    }
}
