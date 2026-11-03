package com.changakira.screenshotbye

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.changakira.screenshotbye.core.Decision
import com.changakira.screenshotbye.core.DecisionOutcome
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.media.DeleteGateway
import com.changakira.screenshotbye.media.DeleteMode
import com.changakira.screenshotbye.media.DeleteResult
import com.changakira.screenshotbye.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 删除改走系统照片回收站之后的核心不变量：
 * Android 11+ 由应用发起请求、由系统弹窗执行，本应用既不静默删，也不再往私有目录写副本。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemTrashTest {

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
            clazz.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }

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

    @Test
    fun `Android 11 之后不再自己动手删，只交回系统`() {
        assertEquals(DeleteResult.NeedsConfirm, DeleteGateway(ctx).deleteNow(shot(11)))
    }

    @Test
    fun `有回收站入口就用回收站，没有才退回永久删除`() {
        val a = sender()
        val b = sender()
        val gw = DeleteGateway(ctx)
        assertSame("系统给得出回收站就只要回收站", a, gw.pickRequest({ a }, { b })?.first)
        assertEquals(DeleteMode.TRASH, gw.pickRequest({ a }, { b })?.second)
        val fallback = gw.pickRequest({ null }, { b })
        assertSame("个别 ROM 不给回收站入口", b, fallback?.first)
        assertEquals(DeleteMode.PERMANENT, fallback?.second)
        assertNull("两个都不给就只能报错", gw.pickRequest({ null }, { null }))
    }

    private fun sender(): android.content.IntentSender = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    ).intentSender

    @Test
    fun `点删除只是把条目推进等系统确认，不写任何副本`() {
        val store = PendingShotStore.of(ctx)
        store.replaceAll(listOf(shot(13)))
        val outcome = ScreenshotRepository(ctx).decide(13L, Decision.DELETE)
        assertEquals(DecisionOutcome.NeedsSystemConfirm, outcome)
        assertEquals(ShotState.DELETING, store.find(13L)?.state)
        assertFalse("私有目录里不该再有应用自建回收站", File(ctx.filesDir, "trash").exists())
    }

    @Test
    fun `系统确认后按相册里查不查得到收尾`() {
        val store = PendingShotStore.of(ctx)
        store.replaceAll(listOf(shot(14, ShotState.DELETING)))
        // Robolectric 的 MediaStore 里没有这条记录，所以「查不到」= 已经离开相册
        val (done, cancelled) = ScreenshotRepository(ctx).onBatchConfirmResult(listOf(14L), DeleteMode.TRASH)
        assertEquals(1, done)
        assertEquals(0, cancelled)
        assertNull(store.find(14L))
        assertTrue(Settings.of(ctx).log.contains("已移入系统回收站：Screenshot_14.png"))
    }

    @Config(sdk = [29])
    @Test
    fun `Android 10 及以下没有系统弹窗，也没有写权限就删不动`() {
        assertNull(DeleteGateway(ctx).confirmRequest(listOf(shot(15).contentUri)))
        assertTrue(DeleteGateway(ctx).deleteNow(shot(15)) is DeleteResult.Failed)
    }
}
