package com.changakira.screenshotbye.ui.theme

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.Settings

/**
 * 五套配色，色值取自参考仓库的 `ui/theme/Palette.kt`（米白 / 青灰 / 陶土 / 靛蓝 / 石墨）。
 *
 * Views 侧换主题只能是「挑一个 theme style + 重建 Activity」，不像 Compose 改一个可观察状态就整体重绘，
 * 所以点色块之后要把当前页 recreate 一次，别的页面下次进来自然是新配色。
 * 深色模式不在应用里做开关：系统开深色就该是深色，因此每套配色都在 values 和 values-night
 * 各备了一份颜色表，缺一份就会出现「背景变黑了控件还是亮的」。
 */
enum class Palette(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:ColorRes val swatchRes: Int,
    @param:StyleRes val themeRes: Int,
) {
    Beige("beige", R.string.palette_beige, R.color.beige_swatch, R.style.Theme_ScreenshotBye_Beige),
    Slate("slate", R.string.palette_slate, R.color.slate_swatch, R.style.Theme_ScreenshotBye_Slate),
    Terracotta("terracotta", R.string.palette_terracotta, R.color.terracotta_swatch, R.style.Theme_ScreenshotBye_Terracotta),
    Indigo("indigo", R.string.palette_indigo, R.color.indigo_swatch, R.style.Theme_ScreenshotBye_Indigo),
    Graphite("graphite", R.string.palette_graphite, R.color.graphite_swatch, R.style.Theme_ScreenshotBye_Graphite),
    ;

    companion object {
        fun fromId(id: String?): Palette = entries.firstOrNull { it.id == id } ?: Beige

        /** 用户存的选择不认得时退回默认，而不是崩在读配置上。 */
        fun current(ctx: android.content.Context): Palette = fromId(Settings.of(ctx).themePalette)
    }
}
