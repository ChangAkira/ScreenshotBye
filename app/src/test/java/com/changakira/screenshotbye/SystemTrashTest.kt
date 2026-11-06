package com.changakira.screenshotbye

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.changakira.screenshotbye.core.Decision
import com.changakira.screenshotbye.core.DecisionOutcome
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.media.AfterDelete
import com.changakira.screenshotbye.media.DeleteGateway
import com.changakira.screenshotbye.media.DeleteMode
import com.changakira.screenshotbye.media.DeleteResult
import com.changakira.screenshotbye.perm.Perms
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
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver
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
    fun `没授「所有文件访问」时 Android 11 之后不自己动手，只交回系统`() {
        // 这一项在测试环境里必然没授（特殊访问只能用户在系统页里开），所以只能交回系统弹窗
        assertFalse(Perms.hasAllFilesAccess(ctx))
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
        assertTrue(Settings.of(ctx).log.contains("已进回收站：Screenshot_14.png"))
    }

    @Config(sdk = [29])
    @Test
    fun `Android 10 及以下没有系统弹窗，也没有写权限就删不动`() {
        assertNull(DeleteGateway(ctx).confirmRequest(listOf(shot(15).contentUri)))
        assertTrue(DeleteGateway(ctx).deleteNow(shot(15)) is DeleteResult.Failed)
    }

    // ---- 回收站语义：直查单条 URI 时，很多 ROM 仍会把已回收的行返回回来 ----

    /** 假装一个「单条直查也能返回、并带 is_trashed 列」的 media provider。 */
    private class FakeMediaProvider(private val trashed: Int) : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?
        ): Cursor = MatrixCursor(arrayOf("_id", "is_trashed")).apply { addRow(arrayOf(14, trashed)) }
        override fun getType(uri: Uri) = "vnd.android.cursor.item/media"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    }

    @Test
    fun `确认删除后即便记录仍能直查到，只要已进回收站就算离开相册`() {
        // 复现线上问题：用户点了确认、图也确实从相册消失，但直查单条 URI 仍返回该行。
        // 老实现只按「查不查得到」判断，会误判成取消；现在读 is_trashed 列，1 就是已回收。
        ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider(trashed = 1))
        assertTrue(DeleteGateway(ctx).finalizeAfterConfirm(shot(14)))
    }

    @Test
    fun `记录仍在且未回收，才算用户在系统弹窗里取消了`() {
        ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider(trashed = 0))
        assertFalse(DeleteGateway(ctx).finalizeAfterConfirm(shot(14)))
    }

    // ---- 无感删除：省下的是弹窗，原件去向应用查不到，日志里因此一律不下结论 ----

    /** Android 10 上有 WRITE 就能直删，用它当「无感删除」的替身跑静默路径。 */
    private fun grantWrite() =
        Shadows.shadowOf(ctx as android.app.Application)
            .grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private enum class Rom { GONE, TRASHED, STUCK }

    /** 三种系统行为：行没了（去向未知）/ 系统认账软删（行还在、is_trashed=1）/ 压根没删动。 */
    private class FakeRom(private val rom: Rom) : ContentProvider() {
        /** null = 行已经不在；0 = 还在且未回收；1 = 还在但被系统标成已回收。 */
        private var row: Int? = 0

        override fun onCreate() = true

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = when (rom) {
            Rom.GONE -> { row = null; 1 }
            Rom.TRASHED -> { row = 1; 1 }
            Rom.STUCK -> 0
        }

        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?
        ): Cursor = MatrixCursor(arrayOf("_id", "is_trashed")).apply { row?.let { addRow(arrayOf(14, it)) } }

        override fun getType(uri: Uri) = "vnd.android.cursor.item/media"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    }

    @Test
    fun `删完那一行的三种下场各自对应一种结论`() {
        val gw = DeleteGateway(ctx)
        assertEquals(AfterDelete.GONE, gw.classify(rowFound = false, trashedFlag = 0))
        assertEquals(AfterDelete.TRASHED, gw.classify(rowFound = true, trashedFlag = 1))
        assertEquals(AfterDelete.STILL_THERE, gw.classify(rowFound = true, trashedFlag = 0))
    }

    @Config(sdk = [29])
    @Test
    fun `只有系统认账的软删才写进回收站`() {
        grantWrite()
        ShadowContentResolver.registerProviderInternal("media", FakeRom(Rom.TRASHED))

        val r = DeleteGateway(ctx).deleteNow(shot(21))
        assertTrue("软删也算删掉了：$r", r is DeleteResult.Removed && r.trashed)

        val store = PendingShotStore.of(ctx)
        store.replaceAll(listOf(shot(21)))
        assertEquals(DecisionOutcome.Deleted, ScreenshotRepository(ctx).decide(21L, Decision.DELETE))
        assertNull("条目该出队", store.find(21L))
        assertTrue(Settings.of(ctx).log, Settings.of(ctx).log.contains("已进回收站：Screenshot_21.png"))
    }

    @Config(sdk = [29])
    @Test
    fun `行没了只说已删除——真删与搬进隐藏回收站分不清`() {
        grantWrite()
        ShadowContentResolver.registerProviderInternal("media", FakeRom(Rom.GONE))

        val r = DeleteGateway(ctx).deleteNow(shot(22))
        assertTrue("删掉了：$r", r is DeleteResult.Removed && !r.trashed)

        val store = PendingShotStore.of(ctx)
        store.replaceAll(listOf(shot(22)))
        ScreenshotRepository(ctx).decide(22L, Decision.DELETE)
        val log = Settings.of(ctx).log
        assertTrue(log, log.contains("已删除：Screenshot_22.png"))
        // 小米那类 ROM 把文件挪进不被索引的目录，媒体库里同样是「行没了」，相册里却还能还原。
        // 所以这条日志里出现任何去向断言都是撒谎。
        assertFalse("不许断言彻底删了：$log", log.contains("彻底"))
        assertFalse("不许断言不进回收站：$log", log.contains("回收站"))
        assertFalse("不许断言直接删除：$log", log.contains("直接删除"))
    }

    @Config(sdk = [29])
    @Test
    fun `行没了但文件还在原目录时照实多说一句`() {
        grantWrite()
        ShadowContentResolver.registerProviderInternal("media", FakeRom(Rom.GONE))
        val leftover = File(ctx.cacheDir, "Screenshot_26.png")
        leftover.writeText("x")

        val store = PendingShotStore.of(ctx)
        store.replaceAll(listOf(shot(26).copy(absolutePath = leftover.absolutePath)))
        ScreenshotRepository(ctx).decide(26L, Decision.DELETE)
        val log = Settings.of(ctx).log
        assertTrue(log, log.contains("已删除，相册记录已移除、文件仍留在原目录：Screenshot_26.png"))

        leftover.delete()
    }

    @Config(sdk = [29])
    @Test
    fun `行还在又没被标记就是没删动，条目留在队列里`() {
        grantWrite()
        ShadowContentResolver.registerProviderInternal("media", FakeRom(Rom.STUCK))

        assertTrue(DeleteGateway(ctx).deleteNow(shot(23)) is DeleteResult.Failed)

        val store = PendingShotStore.of(ctx)
        store.replaceAll(listOf(shot(23)))
        ScreenshotRepository(ctx).decide(23L, Decision.DELETE)
        assertEquals("删不动就别把条目丢掉", ShotState.ASKED, store.find(23L)?.state)
    }

    @Config(sdk = [29])
    @Test
    fun `批量删除走的也是同一套措辞`() {
        grantWrite()
        ShadowContentResolver.registerProviderInternal("media", FakeRom(Rom.TRASHED))
        val store = PendingShotStore.of(ctx)
        store.replaceAll(listOf(shot(24), shot(25)))

        ScreenshotRepository(ctx).deleteBatch(listOf(24L, 25L))
        assertTrue(Settings.of(ctx).log, Settings.of(ctx).log.contains("已进回收站：Screenshot_24.png"))
    }
}
