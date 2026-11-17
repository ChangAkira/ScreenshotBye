package com.changakira.screenshotbye

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.ui.DeleteConfirmActivity
import com.changakira.screenshotbye.ui.ReviewActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 逐个确认页的翻页、收口与改判。
 *
 * 两件事在这儿钉死：
 * 1. 收口只认「本轮还有没有欠答的」，不认位置——用户连报三版「以为问完了」，都是位置判断惹的；
 * 2. 答过的卡片不许从本轮消失（照 PhotosManager 的 PhotoSelection.action 那套）：
 *    选了保留只是记成已保留并出队，卡片还在，滑回去能改判删除，批量屏里也列着它。
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
        val decision = activity.findViewById<TextView>(R.id.shotDecision)
        val confirm = activity.findViewById<View>(R.id.confirmList)
        val list = activity.findViewById<RecyclerView>(R.id.confirmList)
        val actions = activity.findViewById<View>(R.id.reviewActions)
        val commit = activity.findViewById<TextView>(R.id.btnConfirmDelete)
        fun label(): String = name.text.toString()
        fun note(): String = decision.text.toString()
        fun rows(): Int = list.adapter?.itemCount ?: 0
    }

    /** 后台线程干完活得把主线程消息跑掉，页面才更新。 */
    private fun await(what: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
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
    private fun ReviewActivity.back() = findViewById<View>(R.id.btnConfirmBack).performClick()
    private fun ReviewActivity.commit() = findViewById<View>(R.id.btnConfirmDelete).performClick()
    private fun ReviewActivity.swipeBack() = goTo(-1)
    private fun ReviewActivity.swipeNext() = goTo(1)

    /** 批量屏的行要先量一遍才绑定得出来。 */
    private fun row(p: Page, i: Int): View {
        p.list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        p.list.layout(0, 0, 1080, 1920)
        val vh = p.list.findViewHolderForAdapterPosition(i)
        assertNotNull("批量屏第 $i 行没上屏", vh)
        return vh!!.itemView
    }

    private fun rowNote(p: Page, i: Int): String =
        row(p, i).findViewById<TextView>(R.id.shotMeta).text.toString()

    private fun toggle(p: Page, i: Int) = row(p, i).performClick()

    // ---- 收口：整轮一起数，不看位置 ----

    @Test
    fun `三张 - 标记第一张再改判保留，剩下两张照样逐个问`() {
        val (a, p) = open(41, 42, 43)
        a.delete()                                   // 标记 41
        await("第二张") { p.label() == "Screenshot_42.png" }
        a.swipeBack()                                // 翻回 41
        assertEquals("Screenshot_41.png", p.label())
        assertEquals("待删除", p.note())
        a.keep()                                     // 改判保留

        await("回到第二张") { p.label() == "Screenshot_42.png" }
        assertEquals("不该提前收口", View.VISIBLE, p.actions.visibility)
        a.delete()                                   // 标记 42
        await("第三张") { p.label() == "Screenshot_43.png" }
        a.keep()                                     // 保留 43

        await("进批量屏") { p.confirm.visibility == View.VISIBLE }
        val store = PendingShotStore.of(ctx)
        assertNotNull("42 还该在清单里等删除", store.find(42L))
        assertNull("41 已按保留出队", store.find(41L))
        assertNull("43 已按保留出队", store.find(43L))
        assertEquals("本轮三张都该列着", 3, p.rows())
        assertTrue("只该勾 42：${p.commit.text}", p.commit.text.toString().contains("1"))
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

        // 51 答完了，后面还挂着 52(标记)、53(没答)——必须接着问 53
        await("问到第三张") { p.label() == "Screenshot_53.png" }
        a.keep()
        await("进批量屏") { p.confirm.visibility == View.VISIBLE }
        val store = PendingShotStore.of(ctx)
        assertNotNull("52 仍等删除确认", store.find(52L))
        assertNull("53 已保留出队", store.find(53L))
    }

    @Test
    fun `三张 - 往后翻两张再保留第三张，前面欠问的两张还得回来问`() {
        val (a, p) = open(81, 82, 83)
        a.swipeNext()
        await("翻到第二张") { p.label() == "Screenshot_82.png" }
        a.swipeNext()
        await("翻到第三张") { p.label() == "Screenshot_83.png" }
        a.swipeNext()                                // 已经在最后一格，再翻也不动
        assertEquals("Screenshot_83.png", p.label())

        a.keep()
        await("绕回第一张") { p.label() == "Screenshot_81.png" }
        assertEquals("还没问完不许收口", View.VISIBLE, p.actions.visibility)
        assertEquals("不该进批量屏", View.GONE, p.confirm.visibility)
        a.keep()
        await("接着问第二张") { p.label() == "Screenshot_82.png" }
        a.keep()
        await("问完了才进批量屏") { p.confirm.visibility == View.VISIBLE }
        assertEquals(0, PendingShotStore.of(ctx).all().size)
    }

    @Test
    fun `三张 - 只标最后那张，前面两张没答完前不进批量屏`() {
        val (a, p) = open(91, 92, 93)
        a.swipeNext(); a.swipeNext()
        await("翻到第三张") { p.label() == "Screenshot_93.png" }
        a.delete()

        await("绕回第一张") { p.label() == "Screenshot_91.png" }
        assertEquals("不该进批量屏", View.GONE, p.confirm.visibility)
        a.delete()
        await("接着问第二张") { p.label() == "Screenshot_92.png" }
        a.keep()
        await("都问完了才批量确认") { p.confirm.visibility == View.VISIBLE }
        assertTrue("勾着的该是 91、93 两张：${p.commit.text}", p.commit.text.toString().contains("2"))
    }

    @Test
    fun `两张 - 先翻到第二张保留，第一张仍要被问`() {
        val (a, p) = open(31, 32)
        a.swipeNext()
        await("翻到第二张") { p.label() == "Screenshot_32.png" }
        a.keep()
        await("绕回第一张") { p.label() == "Screenshot_31.png" }
        assertEquals("还没问完不许收口", View.VISIBLE, p.actions.visibility)
    }

    @Test
    fun `稍后再问的那张不再欠问，其余照旧逐个问`() {
        val (a, p) = open(71, 72, 73)
        a.snooze(); await("第二张") { p.label() == "Screenshot_72.png" }
        a.delete()
        await("第三张") { p.label() == "Screenshot_73.png" }
        a.keep()
        await("进批量屏") { p.confirm.visibility == View.VISIBLE }
        val store = PendingShotStore.of(ctx)
        assertEquals("71 稍后被排回等待", ShotState.WAITING, store.find(71L)?.state)
        assertNull("73 保留后出队", store.find(73L))
        assertEquals("稍后那张仍列在本轮里", 3, p.rows())
    }

    @Test
    fun `记录被后台收走的那张不再欠问`() {
        val (a, p) = open(31, 32)
        PendingShotStore.of(ctx).remove(31L)
        a.keep()
        await("接着问下一张") { p.label() == "Screenshot_32.png" }
        assertEquals("不该停在批量屏", View.GONE, p.confirm.visibility)
        a.keep()
        await("问完了才进批量屏") { p.confirm.visibility == View.VISIBLE }
    }

    // ---- 答过的卡片留在本轮，随时改判 ----

    @Test
    fun `保留过的卡片还在，滑回去改判删除，收口时照样删得掉`() {
        val (a, p) = open(41, 42)
        a.keep()
        await("第二张") { p.label() == "Screenshot_42.png" }
        assertNull("保留当场出队", PendingShotStore.of(ctx).find(41L))

        a.swipeBack()
        assertEquals("答过的卡片不许消失", "Screenshot_41.png", p.label())
        assertEquals("已保留", p.note())
        a.delete()                                   // 反悔：改成删除
        await("接着问第二张") { p.label() == "Screenshot_42.png" }
        a.keep()
        await("进批量屏") { p.confirm.visibility == View.VISIBLE }
        assertTrue("该勾着 41：${p.commit.text}", p.commit.text.toString().contains("1"))

        a.commit()
        // 41 的记录早就按保留出队了，删除只能靠本轮快照兜住；
        // 走系统确认这条路时快照会被写回清单，弹窗结果才有据可查
        await("交给系统确认") { PendingShotStore.of(ctx).find(41L)?.state == ShotState.DELETING }
        val started = Shadows.shadowOf(a).nextStartedActivity
        assertEquals(
            DeleteConfirmActivity::class.java.name,
            started?.component?.className
        )
        assertTrue(
            "系统弹窗该收到 41",
            started!!.getLongArrayExtra(DeleteConfirmActivity.EXTRA_MEDIA_IDS)!!.contains(41L)
        )
        assertNull("42 仍是保留出队", PendingShotStore.of(ctx).find(42L))
    }

    @Test
    fun `批量屏列着本轮每一张，勾上已保留的那张也删得掉`() {
        val (a, p) = open(61, 62)
        a.keep(); await("第二张") { p.label() == "Screenshot_62.png" }
        a.keep()
        await("进批量屏") { p.confirm.visibility == View.VISIBLE }

        assertEquals("两张都保留也要列出来", 2, p.rows())
        assertTrue("行尾要写明已保留：${rowNote(p, 0)}", rowNote(p, 0).contains("已保留"))
        assertTrue(rowNote(p, 1).contains("已保留"))
        assertEquals("删除 0 张", p.commit.text.toString())
        assertFalse("一张没勾就不该按得动", p.commit.isEnabled)

        toggle(p, 0)                                 // 反悔：把已保留的 61 勾成删除
        assertEquals("删除 1 张", p.commit.text.toString())
        assertTrue(p.commit.isEnabled)
        assertTrue("勾上了状态就该改口：${rowNote(p, 0)}", rowNote(p, 0).contains("待删除"))

        a.commit()
        await("交给系统确认") { PendingShotStore.of(ctx).find(61L)?.state == ShotState.DELETING }
        val started = Shadows.shadowOf(a).nextStartedActivity
        assertTrue(
            "系统弹窗该收到 61",
            started!!.getLongArrayExtra(DeleteConfirmActivity.EXTRA_MEDIA_IDS)!!.contains(61L)
        )
    }

    @Test
    fun `取消勾选的那张退回还没答，返回卡片接着问`() {
        val (a, p) = open(71, 72)
        a.delete(); await("第二张") { p.label() == "Screenshot_72.png" }
        a.delete()
        await("进批量屏") { p.confirm.visibility == View.VISIBLE }
        assertEquals("删除 2 张", p.commit.text.toString())

        toggle(p, 0)                                 // 71 不删了
        assertEquals("删除 1 张", p.commit.text.toString())
        a.back()
        await("回到卡片") { p.actions.visibility == View.VISIBLE }
        assertEquals("Screenshot_71.png", p.label())
        assertEquals("取消勾选就是退回还没答", "还没答", p.note())

        a.delete()
        await("再进批量屏") { p.confirm.visibility == View.VISIBLE }
        assertEquals("删除 2 张", p.commit.text.toString())
    }

    @Test
    fun `保留后改判稍后，计时要重新排上`() {
        val (a, p) = open(81, 82)
        a.keep(); await("第二张") { p.label() == "Screenshot_82.png" }
        a.swipeBack()
        assertEquals("已保留", p.note())
        a.snooze()

        await("排回等待") { PendingShotStore.of(ctx).find(81L)?.state == ShotState.WAITING }
        val shot = PendingShotStore.of(ctx).find(81L)!!
        assertTrue("稍后就得真的往后排", shot.askAt > System.currentTimeMillis())
        await("翻到第二张") { p.label() == "Screenshot_82.png" }
        a.swipeBack()
        assertEquals("稍后再问", p.note())
    }

    @Test
    fun `都答完后右滑到尽头就回批量屏`() {
        val (a, p) = open(91, 92)
        a.keep(); await("第二张") { p.label() == "Screenshot_92.png" }
        a.keep()
        await("进批量屏") { p.confirm.visibility == View.VISIBLE }

        a.back()
        await("回到卡片") { p.label() == "Screenshot_91.png" }
        a.swipeNext()
        await("第二张") { p.label() == "Screenshot_92.png" }
        a.swipeNext()                                // 尽头再右滑：都答完了，回批量屏
        await("回批量屏") { p.confirm.visibility == View.VISIBLE }
    }
}
