package com.changakira.screenshotbye

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import com.changakira.screenshotbye.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 夜间模式看不清字，是这一版要修的问题之一，所以把它写成能跑的断言而不是「我看着挺清楚」。
 *
 * 检查的是主题属性解析出来的实际颜色，因此 values-night 有没有接上、五套配色有没有漏色，
 * 都会在这里暴露出来（硬写 @color/ink 那种写法根本走不到这条路上，正是问题所在）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeContrastTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun theme(palette: Palette): Resources.Theme =
        ctx.resources.newTheme().apply { applyStyle(palette.themeRes, true) }

    private fun color(theme: Resources.Theme, attr: Int): Int {
        val out = TypedValue()
        assertTrue("主题里解析不到 attr=$attr", theme.resolveAttribute(attr, out, true))
        assertNotEquals("attr=$attr 必须指向一个颜色资源，否则 values-night 换不了", 0, out.resourceId)
        return ctx.getColor(out.resourceId)
    }

    private fun ratio(fg: Int, bg: Int): Double {
        fun lum(c: Int): Double {
            val channels = intArrayOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF).map {
                val s = it / 255.0
                if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
        }
        val a = max(lum(fg), lum(bg))
        val b = min(lum(fg), lum(bg))
        return (a + 0.05) / (b + 0.05)
    }

    private fun withNight(night: Boolean, block: () -> Unit) {
        val base = "w411dp-h890dp-xhdpi"
        RuntimeEnvironment.setQualifiers(if (night) "w411dp-h890dp-night-xhdpi" else base)
        try {
            block()
        } finally {
            RuntimeEnvironment.setQualifiers(base)
        }
    }

    @Test
    fun `正文和次要文字在任何一层背景上都读得清`() {
        Palette.entries.forEach { palette ->
            listOf(false, true).forEach { night ->
                withNight(night) {
                    val t = theme(palette)
                    val onSurface = color(t, com.google.android.material.R.attr.colorOnSurface)
                    val onVariant = color(t, com.google.android.material.R.attr.colorOnSurfaceVariant)
                    listOf(
                        com.google.android.material.R.attr.colorSurface,
                        android.R.attr.colorBackground,
                        com.google.android.material.R.attr.colorSurfaceContainer,
                        com.google.android.material.R.attr.colorSurfaceContainerLow,
                        com.google.android.material.R.attr.colorSurfaceContainerHigh,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    ).forEach { surface ->
                        val bg = color(t, surface)
                        val name = "${palette.id}/${if (night) "night" else "day"}/$surface"
                        assertTrue("$name 正文对比度 ${ratio(onSurface, bg)}", ratio(onSurface, bg) >= 4.5)
                        assertTrue("$name 次要文字对比度 ${ratio(onVariant, bg)}", ratio(onVariant, bg) >= 4.5)
                    }
                }
            }
        }
    }

    @Test
    fun `夜间模式确实用了另一份色表`() {
        Palette.entries.forEach { palette ->
            var daySurface = 0
            var nightSurface = 0
            withNight(false) { daySurface = color(theme(palette), com.google.android.material.R.attr.colorSurface) }
            withNight(true) { nightSurface = color(theme(palette), com.google.android.material.R.attr.colorSurface) }
            assertNotEquals("${palette.id} 的深色背景应当随系统夜间模式改变", daySurface, nightSurface)
        }
    }

    @Test
    fun `主色在深色底上仍然显眼，提示符号不刺眼`() {
        Palette.entries.forEach { palette ->
            listOf(false, true).forEach { night ->
                withNight(night) {
                    val t = theme(palette)
                    val bg = color(t, com.google.android.material.R.attr.colorSurfaceContainerHigh)
                    // 旋钮的读数和指针是大字号，按 3.0 的大字标准
                    assertTrue(
                        "${palette.id}/${if (night) "night" else "day"} 主色太淡",
                        ratio(color(t, com.google.android.material.R.attr.colorPrimary), bg) >= 3.0
                    )
                    // 「>」只是装饰性提示，但也不能低到看不见
                    assertTrue(
                        "${palette.id}/${if (night) "night" else "day"} 的 > 太淡",
                        ratio(color(t, com.google.android.material.R.attr.colorOutline), bg) >= 1.9
                    )
                }
            }
        }
    }

    @Test
    fun `每套配色的色块和主色是同一个色系`() {
        Palette.entries.forEach { palette ->
            val swatch = ctx.getColor(palette.swatchRes)
            val primary = color(theme(palette), com.google.android.material.R.attr.colorPrimary)
            // 色块就是给用户认的那颗点，取的就是这套配色的主色，对不上说明五套里抄错了行
            assertEquals("${palette.id} 色块与主色不符", primary, swatch)
        }
    }
}
