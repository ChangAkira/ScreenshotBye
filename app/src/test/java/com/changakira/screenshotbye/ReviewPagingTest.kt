package com.changakira.screenshotbye

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.ui.ReviewActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 逐个确认页的翻页与收口。用户连报两版「以为问完了」，所以这里把能撞的顺序都摆一遍：
 * 标记删除不缩短列表、答完一张会缩短列表，两者叠着滑动来回翻的时候最容易漏问。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewPagingTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        dropSingleton(Settings::class.java)
        dropSingleton(PendingShotStore::class.java)
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        PendingShotStore.of(ctx).replaceAll(emptyList())
    }

    private fun dropSingleton(clazz: Class<*>) {
        runCatching { clazz.getDeclaredField("instance").apply { isAccessible = true }.set(null, null) }
    }

    private fun asked(id: Long) = PendingShot(
        mediaId = id,
        uri = "content://media/external/images/media/$id",
        name = "Screenshot_$id.png",
        folder = "DCIM/Screenshots",
        relativePath = "Screenshots/Screenshot_$id.png",
        size = 2048L,
        takenAt = System.currentTimeMillis() - 60_000L,
        askAt = System.currentTimeMillis() - 1_000L,
        state = ShotState.ASKED,
        stateAt = System.currentTimeMillis(),
    )

    private class Page(activity: ReviewActivity) {
        val name = activity.findViewById<TextView>(R.id.shotName)
        val confirm = activity.findViewById<View>(R.id.confirmList)
        val empty = activity.findViewById<View>(R.id.reviewEmpty)
        val actions = activity.findViewById<View>(R.id.reviewActions)
        fun label(): String = name.text.toString()
    }

    /** 后台线程干完活得把主线程消息跑掉，页面才更新。 */
    private fun await(what: String, timeoutMs: Long = 4_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(15)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue("等不到$what", condition())
    }

    private fun open(vararg ids: Long): Pair<ReviewActivity, Page> {
        PendingShotStore.of(ctx).replaceAll(ids.map { asked(it) })
        val a = Robolectric.buildActivity(ReviewActivity::class.java).setup().get()
        val p = Page(a)
        await("第一张上屏") { p.label() == "Screenshot_${ids.first()}.png" }
        return a to p
    }

    private fun ReviewActivity.keep() = findViewById<View>(R.id.btnKeep).performClick()
    private fun ReviewActivity.snooze() = findViewById<View>(R.id.btnSnooze).performClick()
    private fun ReviewActivity.delete() = findViewById<View>(R.id.btnDelete).performClick()
    private fun ReviewActivity.swipeBack() = goTo(-1)

    @Test
    fun `三张 - 标记第一张再改判保留，剩下两张照样逐个问`() {
        val (a, p) = open(41, 42, 43)
        a.delete()                                   // 标记 41
        await("第二张") { p.label() == "Screenshot_42.png" }
        a.swipeBack()                                // 翻回 41
        assertEquals("Screenshot_41.png", p.label())
        a.keep()                                     // 改判保留

        await("回到第二张") { p.label() == "Screenshot_42.png" }
        assertEquals("不该提前收口", View.VISIBLE, p.actions.visibility)
        a.delete()                                   // 标记 42
        await("第三张") { p.label() == "Screenshot_43.png" }
        a.keep()                                     // 保留 43

        await("进反选屏") { p.confirm.visibility == View.VISIBLE }
        val store = PendingShotStore.of(ctx)
        assertNotNull("42 还该在清单里等删除", store.find(42L))
        assertNull("41 已按保留出队", store.find(41L))
        assertNull("43 已按保留出队", store.find(43L))
        val label = a.findViewById<TextView>(R.id.btnConfirmDelete).text.toString()
        assertTrue("反选屏只该勾 42：$label", label.contains("1"))
    }

    @Test
    fun `三张 - 连着标记两张再翻回去改判，仍要问第三张`() {
        val (a, p) = open(51, 52, 53)
        a.delete(); await("第二张") { p.label() == "Screenshot_52.png" }
        a.delete()                                   // 标记 52，此时最后一张是 53
        await("第三张") { p.label() == "Screenshot_53.png" }
        a.swipeBack(); a.swipeBack()                 // 一路翻回 51
        assertEquals("Screenshot_51.png", p.label())
        a.keep()                                     // 保留 51

        // 51 一移出列表，后面还挂着 52(标记)、53(没答)——必须接着问 53
        await("问到第三张") { p.label() == "Screenshot_53.png" }
        a.keep()
        await("进反选屏") { p.confirm.visibility == View.VISIBLE }
        val store = PendingShotStore.of(ctx)
        assertNotNull("52 仍等删除确认", store.find(52L))
        assertNull("53 已保留出队", store.find(53L))
    }

    @Test
    fun `两张都保留后干净收场`() {
        val (a, p) = open(61, 62)
        a.keep(); await("第二张") { p.label() == "Screenshot_62.png" }
        a.keep()
        await("空态") { p.empty.visibility == View.VISIBLE }
        assertEquals(0, PendingShotStore.of(ctx).all().size)
    }

    @Test
    fun `稍后再问的那张不再占本轮，其余照旧逐个问`() {
        val (a, p) = open(71, 72, 73)
        a.snooze(); await("第二张") { p.label() == "Screenshot_72.png" }
        a.delete()
        await("第三张") { p.label() == "Screenshot_73.png" }
        a.keep()
        await("进反选屏") { p.confirm.visibility == View.VISIBLE }
        val store = PendingShotStore.of(ctx)
        assertEquals("71 稍后被排回等待", ShotState.WAITING, store.find(71L)?.state)
        assertEquals("73 保留后出队", null, store.find(73L))
    }

    /**
     * 往后翻过去、一张没答，再答当前这张——被翻过去的那两张仍是欠问的，
     * 必须绕回来问完才许收口。旧写法只从当前格往后看，这里就以为问完了。
     */
    @Test
    fun `三张 - 往后翻两张再保留第三张，前面欠问的两张还得回来问`() {
        val (a, p) = open(81, 82, 83)
        a.goTo(1)
        await("翻到第二张") { p.label() == "Screenshot_82.png" }
        a.goTo(1)
        await("翻到第三张") { p.label() == "Screenshot_83.png" }
        a.goTo(1) // 已经在最后一格，再翻也不动
        assertEquals("Screenshot_83.png", p.label())

        a.keep()
        await("绕回第一张") { p.label() == "Screenshot_81.png" }
        assertEquals("还没问完不许收口", View.VISIBLE, p.actions.visibility)
        assertEquals("不该进反选屏", View.GONE, p.confirm.visibility)
        a.keep()
        await("接着问第二张") { p.label() == "Screenshot_82.png" }
        a.keep()
        await("问完了才空场") { p.empty.visibility == View.VISIBLE }
        assertEquals(0, PendingShotStore.of(ctx).all().size)
    }

    /**
     * 最后那张标了删除，前面还有没答的：这时不该跳到反选屏——反选屏只列标记过的，
     * 提前过去就等于把没问过的悄悄放过了。
     */
    @Test
    fun `三张 - 只标最后那张，前面两张没答完前不进反选屏`() {
        val (a, p) = open(91, 92, 93)
        a.goTo(1); a.goTo(1)
        await("翻到第三张") { p.label() == "Screenshot_93.png" }
        a.delete()

        await("绕回第一张") { p.label() == "Screenshot_91.png" }
        assertEquals("不该进反选屏", View.GONE, p.confirm.visibility)
        a.delete()
        await("接着问第二张") { p.label() == "Screenshot_92.png" }
        a.keep()
        await("都问完了才反选") { p.confirm.visibility == View.VISIBLE }
        val label = a.findViewById<TextView>(R.id.btnConfirmDelete).text.toString()
        assertTrue("勾着的该是 91、93 两张：$label", label.contains("2"))
    }

    /** 两张时同样的坑：翻到第二张直接保留，第一张还欠着问。 */
    @Test
    fun `两张 - 先翻到第二张保留，第一张仍要被问`() {
        val (a, p) = open(31, 32)
        a.goTo(1)
        await("翻到第二张") { p.label() == "Screenshot_32.png" }
        a.keep()
        await("绕回第一张") { p.label() == "Screenshot_31.png" }
        assertEquals("还没问完不许收口", View.VISIBLE, p.actions.visibility)
    }

    /**
     * 记录在页面背后被别人收走了（到期自动保留、通知上的快捷操作）：这张已经没得问，
     * 不能再占本轮——否则每次点保留都只弹一句「记录已不存在」，整轮永远走不完。
     */
    @Test
    fun `记录被后台收走的那张不再占本轮`() {
        val (a, p) = open(31, 32)
        PendingShotStore.of(ctx).remove(31L)
        a.keep()
        await("接着问下一张") { p.label() == "Screenshot_32.png" }
        assertEquals("不该停在空态或反选屏", View.GONE, p.confirm.visibility)
        a.keep()
        await("问完了才空场") { p.empty.visibility == View.VISIBLE }
    }
}
