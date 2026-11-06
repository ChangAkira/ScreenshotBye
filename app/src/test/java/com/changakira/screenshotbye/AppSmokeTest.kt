package com.changakira.screenshotbye

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.Reminders
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.diagnostic.Diagnostics
import com.changakira.screenshotbye.notify.ActionReceiver
import com.changakira.screenshotbye.notify.Channels
import com.changakira.screenshotbye.notify.Notify
import com.changakira.screenshotbye.service.WatchService
import com.changakira.screenshotbye.ui.DiagnosticsActivity
import com.changakira.screenshotbye.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
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
 * 沙箱里跑不了模拟器（无 /dev/kvm），Robolectric 是唯一能真正执行一遍启动路径的手段。
 * 1.0.2 的开屏闪退就是因为只有编译期证据、没人真的把 onCreate 跑过一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSmokeTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Robolectric 的 sandbox 会在测试方法之间复用类加载器，Settings / PendingShotStore
        // 的单例可能还指向上一个用例的 Application，读不到本用例刚写进去的数据。
        dropSingleton(Settings::class.java)
        dropSingleton(PendingShotStore::class.java)
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        PendingShotStore.of(ctx).replaceAll(emptyList())
        Settings.of(ctx).lastAlertAt = 0L
    }

    private fun dropSingleton(clazz: Class<*>) {
        runCatching {
            clazz.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }

    private fun shot(id: Long, state: ShotState, askAt: Long, stateAt: Long = askAt, askCount: Int = 0) =
        PendingShot(
            mediaId = id,
            uri = "content://media/external/images/media/$id",
            name = "Screenshot_$id.png",
            folder = "Pictures/Screenshots",
            relativePath = "Screenshots/Screenshot_$id.png",
            size = 1024L,
            takenAt = askAt - 60_000L,
            askAt = askAt,
            state = state,
            stateAt = stateAt,
            askCount = askCount,
        )

    @Test
    fun `启动只留常驻和询问两条渠道`() {
        Channels.ensure(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)!!
        assertNotNull(nm.getNotificationChannel(Channels.SERVICE))
        assertNotNull(nm.getNotificationChannel(Channels.REVIEW))
        // 1.0.2 的三档强度渠道不该再出现在系统设置里
        listOf("alert.quiet", "alert.vibrate", "alert.sound").forEach {
            assertNull(nm.getNotificationChannel(it))
        }
    }

    @Test
    fun `主界面起得来`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(activity)
        assertTrue(WorkManager.getInstance(ctx) != null)
    }

    @Test
    @Config(sdk = [26])
    fun `Android 8 上主界面和监控服务起得来`() {
        // minSdk 26，但 FileObserver(File, int) 和 RecoverableSecurityException 都是 API 29 才有的，
        // 引用它们会让 Android 8/9 在启动监听时直接抛 NoSuchMethodError。
        assertNotNull(Robolectric.buildActivity(MainActivity::class.java).setup().get())
        // 造出截图目录，保证 FileObserver 真的会被创建，而不是被 isDirectory 过滤掉
        listOf(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
        ).forEach { dir ->
            java.io.File(dir, "Screenshots").mkdirs()
            dir.mkdirs()
        }
        Settings.of(ctx).enabled = true
        val service = Robolectric.buildService(WatchService::class.java).create().startCommand(0, 0).get()
        assertNotNull(service)
        assertTrue("前台服务没能进入 running", WatchService.running)
    }

    @Test
    fun `自检页起得来并产出报告`() {
        PendingShotStore.of(ctx).upsert(shot(1L, ShotState.ASKED, System.currentTimeMillis() - 60_000L))
        val activity = Robolectric.buildActivity(DiagnosticsActivity::class.java).setup().get()
        assertNotNull(activity)
        val report = Diagnostics.collect(ctx)
        assertTrue(report, report.contains("提醒渠道"))
        assertTrue(report, report.contains("待确认队列"))
        // Android 11+ 的自检必须交代「所有文件访问」和它换来的免确认直删
        assertTrue(report, report.contains("所有文件访问"))
        assertTrue(report, report.contains("回收站"))
    }

    @Test
    fun `询问通知走单一提醒渠道并能重发出声`() {
        Channels.ensure(ctx)
        val now = System.currentTimeMillis()
        val store = PendingShotStore.of(ctx)
        store.replaceAll(listOf(shot(2L, ShotState.ASKED, now - 1000L)))

        val repo = ScreenshotRepository(ctx)
        assertEquals(Channels.REVIEW, Notify.review(ctx, repo.dueQueue(), alert = true).channelId)

        repo.refreshReviewNotification(ctx, newDue = true)
        assertTrue(Settings.of(ctx).lastAlertAt > 0L)
        assertEquals(1, store.find(2L)?.askCount)
    }

    @Test
    fun `读得懂旧版本没有 stateAt 和 askCount 的存档`() {
        val legacy = JSONObject().apply {
            put("mediaId", 3L)
            put("uri", "content://media/external/images/media/3")
            put("name", "Screenshot_legacy.png")
            put("folder", "DCIM/Screenshots")
            put("size", 10L)
            put("takenAt", 1_700_000_000_000L)
            put("askAt", 1_700_000_300_000L)
            put("state", "ASKED")
        }
        ctx.getSharedPreferences("screenshotbye_pending", Context.MODE_PRIVATE).edit()
            .putString("items", JSONArray().put(legacy).toString()).commit()

        val old = PendingShotStore.of(ctx).find(3L)
        assertNotNull(old)
        assertEquals(0L, old!!.stateAt)
        // 没有 stateAt 时超时判定退化用 askAt，不能算成「刚进入状态」
        assertEquals(1_700_000_300_000L, old.sinceStateAt)
    }

    @Test
    fun `队列卫生清掉三种卡住`() {
        val now = System.currentTimeMillis()
        val settings = Settings.of(ctx)
        settings.askExpiryDays = 3
        val store = PendingShotStore.of(ctx)
        store.replaceAll(
            listOf(
                // 早就该问却没问：定时任务被 ROM 清掉
                shot(10L, ShotState.WAITING, now - 10 * 60_000L),
                // 问了没人回答，超过 3 天
                shot(11L, ShotState.ASKED, now - 4 * 86_400_000L),
                // 系统确认弹窗卡了 6 小时以上
                shot(12L, ShotState.DELETING, now - 7 * 3_600_000L),
            )
        )

        val changed = ScreenshotRepository(ctx).sweepStale()
        assertEquals(3, changed)
        val after = store.all().associateBy { it.mediaId }
        assertEquals(ShotState.ASKED, after[10L]?.state)
        assertNull(after[11L])
        assertEquals(ShotState.ASKED, after[12L]?.state)
    }

    @Test
    fun `提醒次数封顶后不再出声但条目仍在`() {
        val now = System.currentTimeMillis()
        val settings = Settings.of(ctx)
        settings.alertMaxTimes = 1
        settings.lastAlertAt = now - 3_600_000L
        val store = PendingShotStore.of(ctx)
        store.replaceAll(listOf(shot(20L, ShotState.ASKED, now - 1000L, askCount = 5)))

        ScreenshotRepository(ctx).refreshReviewNotification(ctx, newDue = true)
        assertEquals("封顶后不该再加提醒次数", 5, store.find(20L)?.askCount)
        assertEquals("封顶后不该更新出声时间", now - 3_600_000L, settings.lastAlertAt)
        assertEquals("条目必须留着等用户回答", 1, store.all().size)
    }

    @Test
    fun `到点会把条目推进成待回答并算一次提醒`() {
        Channels.ensure(ctx)
        val now = System.currentTimeMillis()
        val store = PendingShotStore.of(ctx)
        val id = 30L
        store.replaceAll(listOf(shot(id, ShotState.WAITING, now - 1000L)))

        // ReminderWorker 只是这步的壳：MediaStore 在测试里查不到那条 uri，
        // 会走进「原图已不在」的分支，所以直接验仓库的状态机。
        assertTrue(ScreenshotRepository(ctx).onReminderFired(id))
        val after = store.find(id)
        assertEquals(ShotState.ASKED, after?.state)
        assertEquals("到点应当算一次提醒", 1, after?.askCount)
    }

    @Test
    fun `划掉通知会安排补提醒而不是让条目沉底`() {
        Channels.ensure(ctx)
        val store = PendingShotStore.of(ctx)
        store.replaceAll(listOf(shot(31L, ShotState.ASKED, System.currentTimeMillis() - 1000L)))
        Settings.of(ctx).lastAlertAt = System.currentTimeMillis()
        Settings.of(ctx).alertRepeatMinutes = 20

        assertNotNull(
            "询问通知必须带 deleteIntent，否则划掉就无声无息了",
            Notify.review(ctx, ScreenshotRepository(ctx).dueQueue(), alert = true).deleteIntent,
        )

        ActionReceiver().onNotificationDismissed(ctx)
        assertEquals("节流计时必须清零，不然补提醒会被自己挡住", 0L, Settings.of(ctx).lastAlertAt)
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(Reminders.ALERT_RETRY).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
    }

    @Test
    fun `队列空了就不用再安排补提醒`() {
        val settings = Settings.of(ctx)
        val before = System.currentTimeMillis()
        settings.lastAlertAt = before
        PendingShotStore.of(ctx).replaceAll(emptyList())

        ActionReceiver().onNotificationDismissed(ctx)
        assertEquals("没有待办条目时不该动节流计时", before, settings.lastAlertAt)
        assertEquals(0, WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(Reminders.ALERT_RETRY).get().size)
    }

    /**
     * 自检里「删除去向」这一节不许承诺去向：无感删除换来的只有「不弹确认框」，
     * 原件是当场没了还是被系统挪进不被媒体库索引的回收站目录，三方应用分不出来。
     * SDK 29 上用 WRITE 权限当无感删除的替身。
     */
    @Config(sdk = [29])
    @Test
    fun `自检在有静默删除权限时不谈原件去向`() {
        Shadows.shadowOf(ctx as android.app.Application)
            .grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        val report = Diagnostics.collect(ctx)
        assertTrue(report, report.contains("无感删除已开启"))
        assertTrue(report, report.contains("不弹系统确认框"))
        assertFalse("不许说找不回来：$report", report.contains("找不回来"))
        assertFalse("不许说不进回收站：$report", report.contains("不进回收站"))
        assertFalse("没有能核实去向的读数，别写实测：$report", report.contains("实测"))
    }

    @Test
    fun `闪退记录留得下来`() {
        Settings.of(ctx).recordCrash("main", IllegalStateException("boom"))
        assertTrue(Settings.of(ctx).lastCrash, Settings.of(ctx).lastCrash.contains("boom"))
        assertTrue(Diagnostics.collect(ctx).contains("上次未捕获异常"))
    }
}
