package com.changakira.screenshotbye

import android.content.Context
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.ui.LogActivity
import com.changakira.screenshotbye.ui.MainActivity
import com.changakira.screenshotbye.ui.SettingsActivity
import com.changakira.screenshotbye.ui.theme.Palette
import com.changakira.screenshotbye.ui.widget.DetentDial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 页面改版后的运行期证据：旋钮档位、设置页与日志页能不能真的起来、配色是不是在
 * super.onCreate 之前就设好了。这些都是「编译通过但一开就崩」的高发区。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiReflowTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        dropSingleton(Settings::class.java)
        dropSingleton(PendingShotStore::class.java)
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        PendingShotStore.of(ctx).replaceAll(emptyList())
    }

    private fun dropSingleton(clazz: Class<*>) {
        runCatching {
            val f = clazz.getDeclaredField("instance").apply { isAccessible = true }
            f.set(null, null)
        }
    }

    // ---- 档位 ----

    @Test
    fun `可调项只有那几档，读写都会吸附`() {
        assertEquals(listOf(2, 5, 15, 30, 90), Settings.DELAY_PRESETS)
        assertEquals(listOf(0, 1, 3, 5, 7), Settings.EXPIRY_PRESETS)
        assertEquals(listOf(0, 1, 2, 5, 10), Settings.REPEAT_PRESETS)
        assertEquals(listOf(1, 2, 3), Settings.MAX_TIMES_PRESETS)
        assertEquals(listOf(1, 3, 5, 7, 30), Settings.TRASH_PRESETS)

        val s = Settings.of(ctx)
        assertEquals("默认仍然是 5 分钟问一次", 5, s.delayMinutes)
        assertEquals(7, s.trashDays)
        // 旧版本存的是连续值，读出来必须自己回到格子上，不能显示一个「8 分钟」
        s.delayMinutes = 60
        assertEquals(30, s.delayMinutes)
        s.delayMinutes = 3
        assertEquals(2, s.delayMinutes)
        s.askExpiryDays = 2
        assertEquals("平局时留下较小的那档", 1, s.askExpiryDays)
        s.alertRepeatMinutes = 4
        assertEquals(5, s.alertRepeatMinutes)
        s.trashDays = 14
        assertEquals(7, s.trashDays)
    }

    // ---- 页面 ----

    @Test
    fun `设置页四段都在，旋钮停在当前档位上`() {
        val s = Settings.of(ctx)
        s.delayMinutes = 15
        s.askExpiryDays = 0
        s.alertRepeatMinutes = 2
        s.alertMaxTimes = 3
        s.trashDays = 30

        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals(15, activity.findViewById<DetentDial>(R.id.askDial).value)
        assertEquals(0, activity.findViewById<DetentDial>(R.id.keepDial).value)
        assertEquals(2, activity.findViewById<DetentDial>(R.id.gapDial).value)
        assertEquals(3, activity.findViewById<DetentDial>(R.id.timesDial).value)
        // 保留天数是 1/3/5/7/30 五档，滑块走的是下标
        assertEquals(4f, activity.findViewById<com.google.android.material.slider.Slider>(R.id.trashDaysSlider).value, 0f)
        assertEquals(5, (activity.findViewById<View>(R.id.swatchRow) as ViewGroup).childCount)
        assertNotNull(activity.findViewById<View>(R.id.pendingRow))
        assertNotNull(activity.findViewById<View>(R.id.trashRow))
        assertNotNull(activity.findViewById<View>(R.id.logRow))
        // 行尾的计数在 onResume 就该填好，不能是空白
        assertFalse(activity.findViewById<android.widget.TextView>(R.id.pendingValue).text.isBlank())
        assertFalse(activity.findViewById<android.widget.TextView>(R.id.logValue).text.isBlank())
        assertEquals(15, s.delayMinutes)
    }

    @Test
    fun `拨旋钮会把值写进设置`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val dial = activity.findViewById<DetentDial>(R.id.askDial)
        // Robolectric 不会自动跑布局，手动给一个宽度，否则点在空气上
        val width = 600
        dial.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST)
        )
        dial.layout(0, 0, width, dial.measuredHeight)
        val down = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_DOWN, width / 2f, 20f, 0)
        val up = MotionEvent.obtain(0L, 2L, MotionEvent.ACTION_UP, width / 2f, 20f, 0)
        dial.dispatchTouchEvent(down)
        dial.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        // 正上方对应弧的中点，五档里就是第三档 15 分钟
        assertEquals(15, dial.value)
        assertEquals(15, Settings.of(ctx).delayMinutes)
    }

    @Test
    fun `改首次询问会把还在计时的条目一起重排`() {
        val store = PendingShotStore.of(ctx)
        val now = System.currentTimeMillis()
        store.replaceAll(
            listOf(
                com.changakira.screenshotbye.core.PendingShot(
                    mediaId = 7L,
                    uri = "content://media/external/images/media/7",
                    name = "Screenshot_7.png",
                    folder = "DCIM/Screenshots",
                    relativePath = "Screenshots/Screenshot_7.png",
                    size = 1024L,
                    takenAt = now - 60_000L,
                    askAt = now + 2 * 60_000L,
                    state = ShotState.WAITING,
                    stateAt = now,
                )
            )
        )
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val dial = activity.findViewById<DetentDial>(R.id.askDial)
        val width = 600
        dial.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST)
        )
        dial.layout(0, 0, width, dial.measuredHeight)
        dial.dispatchTouchEvent(MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_DOWN, width / 2f, 20f, 0))
        dial.dispatchTouchEvent(MotionEvent.obtain(0L, 2L, MotionEvent.ACTION_UP, width / 2f, 20f, 0))
        assertEquals(15, dial.value)
        // 重排在后台线程做，等到它落盘为止
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && (store.find(7L)?.askAt ?: 0L) < now + 14 * 60_000L) {
            Thread.sleep(50)
        }
        val updated = store.find(7L)!!
        assertTrue("按新档位重排计时", updated.askAt >= now + 14 * 60_000L)
    }

    @Test
    fun `日志页能打开，空日志不显示空白`() {
        val activity = Robolectric.buildActivity(LogActivity::class.java).setup().get()
        assertEquals(
            ctx.getString(R.string.log_empty),
            activity.findViewById<android.widget.TextView>(R.id.logText).text.toString()
        )
        Settings.of(ctx).appendLog("监控已开启")
        val second = Robolectric.buildActivity(LogActivity::class.java).setup().get()
        val text = second.findViewById<android.widget.TextView>(R.id.logText).text.toString()
        assertFalse(text.isBlank())
        assertEquals(1, text.lines().count { it.isNotBlank() })
    }

    @Test
    fun `首页只剩权限与入口`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(activity.findViewById<View>(R.id.askPermsBtn))
        assertNotNull(activity.findViewById<View>(R.id.settingsBtn))
        assertNotNull(activity.findViewById<View>(R.id.guideBtn))
        assertNotNull(activity.findViewById<View>(R.id.permContainer))
    }

    // ---- 配色 ----

    @Test
    fun `选中的配色在 onCreate 之前就已经套上`() {
        Settings.of(ctx).themePalette = "graphite"
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val out = TypedValue()
        assertTrue(activity.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, out, true))
        assertEquals(ContextCompat.getColor(activity, R.color.graphite_primary), activity.getColor(out.resourceId))
    }

    @Test
    fun `认不出的配色 id 退回默认而不是崩`() {
        Settings.of(ctx).themePalette = "no-such-palette"
        assertEquals(Palette.Beige, Palette.current(ctx))
        assertEquals(Palette.Indigo, Palette.fromId("indigo"))
    }
}
