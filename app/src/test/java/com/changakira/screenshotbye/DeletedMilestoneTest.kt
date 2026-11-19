package com.changakira.screenshotbye

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.changakira.screenshotbye.core.Decision
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.media.DeleteMode
import com.changakira.screenshotbye.notify.Notify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 累计删除统计与「破百喜报」。
 *
 * 两件事一起测，因为它们互为代价：单张删除不再各发一条通知，代价是用户看不到「删了几张」，
 * 于是统计要攒着，只在跨过整百时出一次声。数错了就变成漏报或者天天报。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletedMilestoneTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val settings: Settings get() = Settings.of(ctx)

    @Before
    fun setUp() {
        dropSingleton(Settings::class.java)
        dropSingleton(PendingShotStore::class.java)
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        PendingShotStore.of(ctx).replaceAll(emptyList())
        Shadows.shadowOf(ctx as android.app.Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notificationManager().cancelAll()
    }

    private fun dropSingleton(clazz: Class<*>) {
        runCatching {
            clazz.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }

    private fun notificationManager(): NotificationManager =
        ctx.getSystemService(NotificationManager::class.java)

    private fun posted(): List<Notification> = Shadows.shadowOf(notificationManager()).allNotifications

    private fun milestoneBody(): String? = Shadows.shadowOf(notificationManager())
        .getNotification(Notify.ID_MILESTONE)
        ?.extras?.getString(Notification.EXTRA_TEXT)

    private fun shot(id: Long, state: ShotState = ShotState.ASKED) = PendingShot(
        mediaId = id,
        uri = "content://media/external/images/media/$id",
        name = "Screenshot_$id.png",
        folder = "DCIM/Screenshots",
        relativePath = "Screenshots/Screenshot_$id.png",
        size = 2048L,
        takenAt = System.currentTimeMillis() - 60_000L,
        askAt = System.currentTimeMillis() - 1_000L,
        state = state,
        stateAt = System.currentTimeMillis(),
    )

    /** 让 [n] 张「已经离开相册」的条目走一遍系统确认收尾，也就是真的删掉了 n 张。 */
    private fun confirmGone(n: Int, from: Long = 900L): Int {
        val ids = (from until from + n).toList()
        PendingShotStore.of(ctx).replaceAll(ids.map { shot(it, ShotState.DELETING) })
        val (done, _) = ScreenshotRepository(ctx).onBatchConfirmResult(ids, DeleteMode.TRASH)
        return done
    }

    // ---- 破百判定本身 ----

    @Test
    fun `跨过整百才报，报的就是那个整百数`() {
        assertEquals(100, Settings.milestoneReached(99, 100))
        assertEquals(100, Settings.milestoneReached(99, 103))
        assertEquals(300, Settings.milestoneReached(298, 301))
    }

    @Test
    fun `一次跨好几个百只报最高的那个`() {
        // 喜报只有一条槽位，逐个整百发一遍等于刷屏
        assertEquals(400, Settings.milestoneReached(150, 420))
    }

    @Test
    fun `同一个百里怎么涨都只报一次`() {
        assertNull(Settings.milestoneReached(100, 199))
        assertNull(Settings.milestoneReached(120, 130))
    }

    @Test
    fun `头一百张之内的累加不报`() {
        assertNull(Settings.milestoneReached(0, 1))
        assertNull(Settings.milestoneReached(0, 99))
        assertEquals("刚好攒满一百张要报", 100, Settings.milestoneReached(0, 100))
    }

    @Test
    fun `总数没涨或被改回去都不报`() {
        assertNull(Settings.milestoneReached(100, 100))
        assertNull(Settings.milestoneReached(300, 250))
        assertNull(Settings.milestoneReached(-5, 0))
    }

    @Test
    fun `步长就是一百`() {
        assertEquals(100, Settings.DELETED_MILESTONE_STEP)
    }

    // ---- 计数器 ----

    @Test
    fun `累加返回加完之后的总数，零和负数原样忽略`() {
        assertEquals(3, settings.addDeleted(3))
        assertEquals(5, settings.addDeleted(2))
        assertEquals(5, settings.deletedTotal)
        assertEquals("没有成功项就不该留下痕迹", 5, settings.addDeleted(0))
        assertEquals(5, settings.addDeleted(-4))
        assertEquals(5, settings.deletedTotal)
    }

    @Test
    fun `删多少记多少，跨百那一次发一条喜报`() {
        assertEquals(3, confirmGone(3))
        assertEquals(3, settings.deletedTotal)
        assertNull("还没破百，不该出声", milestoneBody())

        assertEquals(97, confirmGone(97))
        assertEquals(100, settings.deletedTotal)
        assertEquals("已帮您删除超过 100 张图片啦！o(^▽^)o", milestoneBody())
        assertTrue(
            "喜报要写进事件日志，通知被系统压掉时用户还查得到",
            settings.log.contains("累计删除破 100 张")
        )
    }

    @Test
    fun `下一次破百覆盖上一条喜报，不越攒越多`() {
        confirmGone(100)
        assertEquals("已帮您删除超过 100 张图片啦！o(^▽^)o", milestoneBody())

        confirmGone(100, from = 2000L)
        assertEquals(200, settings.deletedTotal)
        assertEquals("已帮您删除超过 200 张图片啦！o(^▽^)o", milestoneBody())
        assertEquals(
            "喜报只占 ID_MILESTONE 一个槽位，第二次是覆盖不是新增",
            1, posted().count { it.extras.getString(Notification.EXTRA_TEXT)?.contains("张图片啦") == true }
        )
    }

    @Test
    fun `一批直接跨了两个百，只报最高的那个`() {
        settings.addDeleted(95)
        assertEquals(210, confirmGone(210))
        assertEquals(305, settings.deletedTotal)
        assertEquals("已帮您删除超过 300 张图片啦！o(^▽^)o", milestoneBody())
    }

    // ---- 每删一张一条通知：这个毛病要没了 ----

    @Test
    fun `单张交回系统确认时不计数，也不再发「已删除」回执`() {
        PendingShotStore.of(ctx).replaceAll(listOf(shot(41)))
        // 测试环境里没有「所有文件访问」，所以这一张只是交回系统弹窗，还没真删掉
        ScreenshotRepository(ctx).decide(41L, Decision.DELETE)
        assertEquals("没真删掉就不该计数", 0, settings.deletedTotal)
        assertNull(milestoneBody())
        assertTrue(
            "不该再有按媒体 id 分槽的删除回执：${posted().map { it.extras.getString(Notification.EXTRA_TITLE) }}",
            posted().none { it.extras.getString(Notification.EXTRA_TEXT)?.endsWith("已删除") == true }
        )
    }

    @Config(sdk = [29])
    @Test
    fun `没有写权限删不动时不计数`() {
        PendingShotStore.of(ctx).replaceAll(listOf(shot(51)))
        ScreenshotRepository(ctx).decide(51L, Decision.DELETE)
        assertEquals(0, settings.deletedTotal)
        assertNull(milestoneBody())
    }

    @Test
    fun `清单里查不到的条目不计数`() {
        val (done, cancelled) = ScreenshotRepository(ctx).onBatchConfirmResult(listOf(77L), DeleteMode.TRASH)
        assertEquals(0, done)
        assertEquals(0, cancelled)
        assertEquals(0, settings.deletedTotal)
    }
}
