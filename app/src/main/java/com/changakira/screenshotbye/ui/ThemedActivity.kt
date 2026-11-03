package com.changakira.screenshotbye.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.ui.theme.Palette

/**
 * 所有对外可见的页面都继承它：在 super.onCreate 之前把配色主题设好。
 * 布局是在 super.onCreate 之后才 inflate 的，晚一步就会用错颜色。
 */
abstract class ThemedActivity : AppCompatActivity() {

    /** 本页 onCreate 时实际铺上去的配色 id。 */
    private var appliedPalette: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val palette = Palette.current(this)
        setTheme(palette.themeRes)
        appliedPalette = palette.id
        super.onCreate(savedInstanceState)
    }

    /**
     * 从别的页面（比如设置页）返回时只会走 onResume，不会重建，于是这里可能还顶着旧配色。
     * 比对一下当前生效的配色：变了就重建本页，让新主题铺满——重建后 onCreate 会刷新
     * [appliedPalette]，所以不会反复触发。
     */
    override fun onResume() {
        super.onResume()
        if (appliedPalette != Palette.current(this).id) recreate()
    }

    /** 换配色：记下选择并重建当前页，新颜色当场铺满整屏，不用重开应用。 */
    protected fun selectPalette(palette: Palette) {
        val settings = Settings.of(this)
        if (settings.themePalette == palette.id) return
        settings.themePalette = palette.id
        settings.appendLog(getString(R.string.log_theme_changed, getString(palette.nameRes)))
        recreate()
    }
}
