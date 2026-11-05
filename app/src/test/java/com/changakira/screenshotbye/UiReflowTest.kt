package com.changakira.screenshotbye

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.Reminders
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.notify.Notify
import com.changakira.screenshotbye.ui.LogActivity
import com.changakira.screenshotbye.ui.MainActivity
import com.changakira.screenshotbye.ui.OnboardingActivity
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
import org.robolectric.Shadows
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

    /** 把一棵 View 树摊平成列表，用于「界面上再也没提到某件事」这种断言。 */
    private fun View.flatten(): List<View> = if (this !is ViewGroup) listOf(this)
    else listOf(this) + (0 until childCount).flatMap { getChildAt(it).flatten() }

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
        assertEquals(listOf(1, 2, 5, 10, 20), Settings.REPEAT_PRESETS)
        assertEquals(listOf(0, 1, 2), Settings.MAX_TIMES_PRESETS)

        val s = Settings.of(ctx)
        assertEquals("默认仍然是 5 分钟问一次", 5, s.delayMinutes)
        // 旧版本存的是连续值，读出来必须自己回到格子上，不能显示一个「8 分钟」
        s.delayMinutes = 60
        assertEquals(30, s.delayMinutes)
        s.delayMinutes = 3
        assertEquals(2, s.delayMinutes)
        s.askExpiryDays = 2
        assertEquals("平局时留下较小的那档", 1, s.askExpiryDays)
        s.alertRepeatMinutes = 4
        assertEquals(5, s.alertRepeatMinutes)
    }

    // ---- 页面 ----

    @Test
    fun `设置页四块都在，旋钮停在当前档位上`() {
        val s = Settings.of(ctx)
        s.delayMinutes = 15
        s.askExpiryDays = 0
        s.alertRepeatMinutes = 2
        s.alertMaxTimes = 1

        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals(15, activity.findViewById<DetentDial>(R.id.askDial).value)
        assertEquals(0, activity.findViewById<DetentDial>(R.id.keepDial).value)
        assertEquals(2, activity.findViewById<DetentDial>(R.id.gapDial).value)
        assertEquals(1, activity.findViewById<DetentDial>(R.id.timesDial).value)
        assertEquals(5, (activity.findViewById<View>(R.id.swatchRow) as ViewGroup).childCount)
        assertNotNull(activity.findViewById<View>(R.id.pendingRow))
        assertNotNull(activity.findViewById<View>(R.id.logRow))
        // 使用引导从首页挪进了设置页，放在「主题颜色」下方
        assertNotNull(activity.findViewById<View>(R.id.guideRow))
        // 第一行就是「启用服务」
        val texts = activity.findViewById<View>(android.R.id.content).flatten()
            .filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue(texts.any { it == ctx.getString(R.string.main_watch_switch) })
        // 「删除免确认」这一行只在 Android 11+ 出现，值里当场写清代价
        val allFiles = activity.findViewById<View>(R.id.allFilesRow)
        assertEquals(
            "Android 11 以下没有「所有文件访问」，整行该藏起来",
            View.VISIBLE, allFiles.visibility
        )
        assertTrue(
            texts.any { it == ctx.getString(R.string.settings_all_files) },
        )
        assertEquals(
            "Robolectric 里没授过这个权限，应当显示未授予",
            ctx.getString(R.string.settings_all_files_off),
            activity.findViewById<android.widget.TextView>(R.id.allFilesValue).text.toString()
        )
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
    fun `首页照参考仓库摆，权限说明整块撤掉`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(activity.findViewById<View>(R.id.askPermsBtn))
        assertNotNull(activity.findViewById<View>(R.id.settingsBtn))
        // 顶部一条应用名，和参考仓库的 TopAppBar 一致
        assertEquals(
            ctx.getString(R.string.app_name),
            activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topBar).title.toString()
        )
        // 电池优化卡（仅未放行时出现）与自启动卡（常驻）都还在
        assertNotNull(activity.findViewById<View>(R.id.batteryCard))
        val autostart = activity.findViewById<View>(R.id.autostartCard)
        assertNotNull(autostart)
        // 自启动卡照参考仓库只放说明文字，不再有跳转系统设置的按钮
        assertFalse(
            autostart.flatten().any { it is com.google.android.material.button.MaterialButton }
        )
        val homeTexts = activity.findViewById<View>(android.R.id.content).flatten()
            .filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        // 使用引导挪进了设置页，首页不再放入口
        assertFalse(homeTexts.any { it == ctx.getString(R.string.main_open_guide) })
        // 逐行的权限说明文字全部删掉：授予状态只由那一个按钮体现
        assertFalse(homeTexts.any { "读取相册" in it || "通知权限" in it })
    }

    @Test
    fun `卡片背景不随主题配色变化，主题色只管控件`() {
        fun autostartCardColor(paletteId: String): Int {
            dropSingleton(Settings::class.java)
            Settings.of(ctx).themePalette = paletteId
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val card = activity.findViewById<com.google.android.material.card.MaterialCardView>(R.id.autostartCard)
            return card.cardBackgroundColor.defaultColor
        }
        // 陶土偏粉、靛蓝偏蓝，若卡片跟着配色走这两者会不同；现在应为同一个中性色
        assertEquals(autostartCardColor("terracotta"), autostartCardColor("indigo"))
        assertEquals(ctx.getColor(R.color.card_surface), autostartCardColor("graphite"))
    }

    @Test
    fun `从设置页重看引导，跳过只回既有主页而不新建一个`() {
        val intent = Intent(ctx, OnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_FROM_SETTINGS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = Robolectric.buildActivity(OnboardingActivity::class.java, intent).setup().get()
        activity.findViewById<View>(R.id.skipBtn).performClick()
        assertTrue("跳过后引导页应关闭", activity.isFinishing)
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertTrue(
            "从设置页进来的引导，结束后不该再拉起一个新的 MainActivity",
            started == null || started.component?.className != MainActivity::class.java.name
        )
    }

    @Test
    fun `首次启动的引导，跳过后才把主页拉起来`() {
        val intent = Intent(ctx, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = Robolectric.buildActivity(OnboardingActivity::class.java, intent).setup().get()
        activity.findViewById<View>(R.id.skipBtn).performClick()
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(MainActivity::class.java.name, started.component?.className)
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

    // ---- 本轮回归：旋钮让位滚动 / 页面背景 / 关于页 / 文案不硬编码 ----

    /** 量出一个能接住触摸的旋钮：Robolectric 不跑布局，得手动 measure + layout。 */
    private fun laidOutDial(activity: SettingsActivity, id: Int): DetentDial {
        val dial = activity.findViewById<DetentDial>(id)
        val width = 600
        dial.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST)
        )
        dial.layout(0, 0, width, dial.measuredHeight)
        return dial
    }

    private fun down(dial: DetentDial, x: Float, y: Float): Boolean {
        val e = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_DOWN, x, y, 0)
        val handled = dial.onTouchEvent(e)
        e.recycle()
        return handled
    }

    @Test
    fun `只有旋钮圆轨附近才吃触摸，卡片空白处照样能上下滑`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val dial = laidOutDial(activity, R.id.askDial)
        val w = dial.width.toFloat()
        val h = dial.height.toFloat()
        // 轨道正上方那点：必须吃下触摸，否则旋钮拨不动
        assertTrue("轨道上要能拨", down(dial, w / 2f, 20f))
        // 圆心、四角这些空白处一律不吃，让 NestedScrollView 接管竖直滑动
        assertFalse("圆心空白处要让给页面滚动", down(dial, w / 2f, h / 2f))
        assertFalse("左上角要让给页面滚动", down(dial, 1f, 1f))
        assertFalse("右下角要让给页面滚动", down(dial, w - 1f, h - 1f))
        // 弧只张 240°，正下方那 120° 连轨道都没画——那一片同样得让给页面滚动
        assertFalse("弧下方的缺口是空白，不该吃触摸", down(dial, w / 2f, h - 20f))
        assertFalse("弧下方偏左也是空白", down(dial, w / 2f - 55f, h - 25f))
        assertFalse("弧下方偏右也是空白", down(dial, w / 2f + 55f, h - 25f))
    }

    @Test
    fun `前三个旋钮的单位是「分钟后」和「天后」`() {
        val app = "http://schemas.android.com/apk/res-auto"
        val android = "http://schemas.android.com/apk/res/android"
        val parser = ctx.resources.getLayout(R.layout.activity_settings)
        val units = mutableMapOf<String, Int>()
        val presets = mutableMapOf<String, String>()
        var ev = parser.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                val id = parser.getAttributeResourceValue(android, "id", 0)
                if (id != 0) {
                    val name = ctx.resources.getResourceEntryName(id)
                    parser.getAttributeResourceValue(app, "dialUnit", 0)
                        .takeIf { it != 0 }?.let { units[name] = it }
                    parser.getAttributeValue(app, "dialPresets")?.let { presets[name] = it }
                }
            }
            ev = parser.next()
        }
        parser.close()
        assertEquals(R.string.unit_minutes, units["askDial"])
        assertEquals(R.string.unit_days, units["keepDial"])
        assertEquals(R.string.unit_minutes, units["gapDial"])
        assertEquals(R.string.unit_times, units["timesDial"])
        assertEquals("分钟后", ctx.getString(R.string.unit_minutes))
        assertEquals("天后", ctx.getString(R.string.unit_days))
        // 布局里的档位必须和 Settings 的常量一致，否则改了一头另一头还按旧格子吸附
        assertEquals(Settings.DELAY_PRESETS.joinToString(","), presets["askDial"])
        assertEquals(Settings.EXPIRY_PRESETS.joinToString(","), presets["keepDial"])
        assertEquals(Settings.REPEAT_PRESETS.joinToString(","), presets["gapDial"])
        assertEquals(Settings.MAX_TIMES_PRESETS.joinToString(","), presets["timesDial"])
    }

    @Test
    fun `重复次数 0 档就是不重复`() {
        val s = Settings.of(ctx)
        s.alertMaxTimes = 0
        assertEquals("0 档必须原样留在 0，不能吸附到 1", 0, s.alertMaxTimes)
        // 「不重复」和间隔多大无关：间隔那一档不再兼职开关
        s.alertRepeatMinutes = 1
        assertEquals(0, s.alertMaxTimes)

        // 已经问过一次（askCount=1）之后再不出声，只留通知
        val now = System.currentTimeMillis()
        val store = PendingShotStore.of(ctx)
        store.replaceAll(
            listOf(
                com.changakira.screenshotbye.core.PendingShot(
                    mediaId = 81L,
                    uri = "content://media/external/images/media/81",
                    name = "Screenshot_81.png",
                    folder = "DCIM/Screenshots",
                    relativePath = "Screenshots/Screenshot_81.png",
                    size = 1024L,
                    takenAt = now - 60_000L,
                    askAt = now - 1_000L,
                    state = ShotState.ASKED,
                    stateAt = now - 1_000L,
                    askCount = 1,
                )
            )
        )
        s.lastAlertAt = now - 3_600_000L
        ScreenshotRepository(ctx).refreshReviewNotification(ctx)
        assertEquals("不重复就不该再出声", now - 3_600_000L, s.lastAlertAt)
        assertEquals("也不该再加提醒次数", 1, store.find(81L)?.askCount)

        // 划掉通知同样不再安排补提醒
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(Reminders.ALERT_RETRY).get()
        assertEquals(0, infos.size)
    }

    @Config(sdk = [29])
    @Test
    fun `Android 10 上没有「删除免确认」那一行`() {
        // 那一版没有「所有文件访问」这项特殊访问，直删靠的是 WRITE 权限
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.allFilesRow).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.allFilesDivider).visibility)
    }

    @Test
    fun `页面背景也不跟着主题配色走`() {
        fun windowBg(paletteId: String): Int {
            dropSingleton(Settings::class.java)
            Settings.of(ctx).themePalette = paletteId
            val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
            val out = TypedValue()
            assertTrue(activity.theme.resolveAttribute(android.R.attr.windowBackground, out, true))
            return out.resourceId
        }
        assertEquals(R.color.page_background, windowBg("terracotta"))
        assertEquals(R.color.page_background, windowBg("indigo"))
        // 首页同理：背景是那个固定色，主题色只落在按钮和旋钮上
        dropSingleton(Settings::class.java)
        Settings.of(ctx).themePalette = "graphite"
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val out = TypedValue()
        assertTrue(home.theme.resolveAttribute(android.R.attr.windowBackground, out, true))
        assertEquals(R.color.page_background, out.resourceId)
        assertEquals(ctx.getColor(R.color.page_background), home.getColor(out.resourceId))
    }

    @Test
    fun `应用描述不带句号`() {
        assertFalse(ctx.getString(R.string.home_subtitle).endsWith("。"))
    }

    @Test
    fun `按钮和通知上的字数要省`() {
        // 通知动作按钮：一两个词，别把整句话塞进去
        mapOf(
            R.string.act_delete to "删除",
            R.string.act_keep to "保留",
            R.string.act_snooze to "稍后",
            R.string.act_snooze_all to "全部稍后",
            R.string.notif_handle_now to "去处理",
            R.string.notif_stop_watch to "停止监控",
        ).forEach { (res, want) ->
            assertEquals(want, ctx.getString(res))
            assertTrue("「${ctx.getString(res)}」太长", ctx.getString(res).length <= 4)
        }
        // 通知正文一行放得下：短到不用展开
        listOf(
            R.string.notif_review_title, R.string.notif_watch_on, R.string.notif_watch_off,
            R.string.notif_deleted_title, R.string.notif_confirm_title, R.string.notif_test_title,
        ).forEach { assertTrue(ctx.getString(it).length <= 10) }
        // 说明性的长句一律不带句号收尾的碎碎念：界面提示里最多一句
        assertTrue(ctx.getString(R.string.notif_test_body).length < 40)
        assertTrue(ctx.getString(R.string.home_battery_body).length < 40)
        assertFalse(ctx.getString(R.string.review_empty).endsWith("。"))
    }

    @Test
    fun `设置页自检下面有「关于」，点进去是占位正文加版本号`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val aboutRow = activity.findViewById<View>(R.id.aboutRow)
        assertNotNull(aboutRow)
        // 顺序：运行自检在前，关于在后
        val labels = activity.findViewById<View>(android.R.id.content).flatten()
            .filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue(
            "关于要排在运行自检后面",
            labels.indexOf(ctx.getString(R.string.main_open_diag)) <
                labels.indexOf(ctx.getString(R.string.main_open_about))
        )
        aboutRow.performClick()
        assertEquals(
            com.changakira.screenshotbye.ui.AboutActivity::class.java.name,
            Shadows.shadowOf(activity).nextStartedActivity.component?.className
        )

        val about = Robolectric.buildActivity(com.changakira.screenshotbye.ui.AboutActivity::class.java)
            .setup().get()
        val texts = about.findViewById<View>(android.R.id.content).flatten()
            .filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue(texts.any { it == ctx.getString(R.string.about_placeholder) })
        assertTrue("关于页要写清版本", texts.any { it.startsWith("版本 ") })
    }

    @Test
    fun `界面文案一律走字符串资源，源码里不再硬编码`() {
        val root = listOf("src/main/java", "app/src/main/java")
            .map { java.io.File(it) }.firstOrNull { it.isDirectory }
        assertNotNull("找不到主源码目录", root)
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        // 只有这两处例外：目录名/文件名匹配规则，翻译了就识别不出截图
        val allowedFiles = setOf("ScreenshotRule.kt", "MediaScanner.kt")
        val offenders = mutableListOf<String>()
        root!!.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            if (f.name in allowedFiles) return@forEach
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                literal.findAll(raw).forEach { m ->
                    if (cjk.containsMatchIn(m.groupValues[1])) offenders.add("${f.name}:${i + 1} ${m.groupValues[1]}")
                }
            }
        }
        assertTrue("这些中文文案还留在代码里：$offenders", offenders.isEmpty())
    }

    @Test
    fun `通知按钮和逐个确认页的按钮是同一个顺序`() {
        // 用户学一次就该够用：两处都必须是 保留 · 稍后 · 删除
        val pageOrder = reviewActionLabels()
        assertEquals(
            listOf(
                ctx.getString(R.string.act_keep),
                ctx.getString(R.string.act_snooze),
                ctx.getString(R.string.act_delete),
            ),
            pageOrder,
        )

        val now = System.currentTimeMillis()
        PendingShotStore.of(ctx).replaceAll(listOf(pending(91L, now)))
        val one = Notify.review(ctx, ScreenshotRepository(ctx).dueQueue(), alert = true).actions!!.toList()
        assertEquals("横幅上的顺序要和页面一致", pageOrder, one.map { it.title.toString() })

        // 队列多于一张时中间那颗换成「全部稍后」，位置不能跟着挪
        PendingShotStore.of(ctx).replaceAll(listOf(pending(91L, now), pending(92L, now)))
        val many = Notify.review(ctx, ScreenshotRepository(ctx).dueQueue(), alert = true).actions!!.toList()
        assertEquals(
            listOf(
                ctx.getString(R.string.act_keep),
                ctx.getString(R.string.act_snooze_all),
                ctx.getString(R.string.act_delete),
            ),
            many.map { it.title.toString() },
        )
        assertEquals(listOf(R.drawable.ic_keep, R.drawable.ic_clock, R.drawable.ic_delete), many.map { it.icon })
    }

    private fun pending(id: Long, now: Long) = com.changakira.screenshotbye.core.PendingShot(
        mediaId = id,
        uri = "content://media/external/images/media/$id",
        name = "Screenshot_$id.png",
        folder = "DCIM/Screenshots",
        relativePath = "Screenshots/Screenshot_$id.png",
        size = 1024L,
        takenAt = now - 60_000L,
        askAt = now - 1_000L,
        state = ShotState.ASKED,
        stateAt = now - 1_000L,
        askCount = 1,
    )

    /**
     * 逐个确认页底部那一排按钮的文案，按从左到右的顺序读出来。
     * 直接读布局而不是 inflate：这些按钮要 Material 主题，用应用上下文 inflate 会当场抛异常。
     */
    private fun reviewActionLabels(): List<String> {
        val ns = "http://schemas.android.com/apk/res/android"
        val parser = ctx.resources.getLayout(R.layout.activity_review)
        val out = mutableListOf<String>()
        var inside = false
        var ev = parser.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                if (parser.getAttributeResourceValue(ns, "id", 0) == R.id.reviewActions) inside = true
                if (inside) parser.getAttributeResourceValue(ns, "text", 0)
                    .takeIf { it != 0 }?.let { out.add(ctx.getString(it)) }
            } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && inside && parser.name == "LinearLayout") {
                inside = false
            }
            ev = parser.next()
        }
        parser.close()
        return out
    }

    @Test
    fun `逐个确认页不出现在最近任务里`() {
        // Robolectric 的 PackageManager 不回填 excludeFromRecents，只能直接读清单
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map { java.io.File(it) }.firstOrNull { it.isFile }
        assertNotNull("找不到清单文件", file)
        val text = file!!.readText()

        fun block(activity: String): String {
            val m = Regex("<activity\\b[^>]*android:name=\"\\.ui\\.$activity\"[^>]*>").find(text)
            assertNotNull("清单里没有 $activity", m)
            return m!!.value
        }

        fun excluded(activity: String) =
            Regex("android:excludeFromRecents\\s*=\\s*\"true\"").containsMatchIn(block(activity))

        // 主页那行本来就有，顺带确认正则真的贴到了属性上
        assertTrue("主页应当也不在最近任务里，说明读取方式没生效", excluded("MainActivity"))
        assertTrue("逐个确认页处理完就走，不该留在最近任务里：${block("ReviewActivity")}", excluded("ReviewActivity"))
    }
}
