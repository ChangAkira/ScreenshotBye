package com.changakira.screenshotbye.media

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.perm.Perms
import java.io.File

sealed interface DeleteResult {
    /** 已直接删除；backup 非空表示回收站里留了一份。 */
    data class Removed(val backup: TrashEntry?) : DeleteResult
    /** 系统要求用户亲自确认（Android 11+ 未授予「所有文件访问」时必然走这条）。 */
    data class NeedsConfirm(val backup: TrashEntry?) : DeleteResult
    data class Failed(val reason: String) : DeleteResult
}

/**
 * 删除入口。要删的截图由系统创建、不属于本应用，因此：
 * - 有「所有文件访问」(Android 11+) 或 WRITE(Android 10 及以下，配合 requestLegacyExternalStorage)
 *   → contentResolver.delete 直接生效；
 * - 否则 → 生成 MediaStore.createDeleteRequest，由系统弹窗让用户确认；
 * - 任何一条路都先把原图备份进私有回收站，备份失败就绝不删除。
 */
class DeleteGateway(ctx: Context) {

    private val app = ctx.applicationContext
    private val resolver = app.contentResolver
    private val settings = Settings.of(app)
    val trash = TrashStore(app)

    fun deleteNow(shot: PendingShot): DeleteResult {
        val backup: TrashEntry? = if (settings.useTrash) {
            trash.backUp(shot) ?: return DeleteResult.Failed("备份失败，已取消删除")
        } else null

        if (Perms.canDeleteSilently(app)) {
            try {
                resolver.delete(shot.contentUri, null, null)
            } catch (e: SecurityException) {
                // RecoverableSecurityException 是 API 29 才有的类，直接在 catch 里写类型
                // 会让 Android 8/9 在异常传播时报类找不到，因此按类名判断。
                return if (isRecoverable(e)) needsConfirm(shot, backup)
                else DeleteResult.Failed("删除被拒绝：${e.message ?: e.javaClass.simpleName}")
            } catch (e: Exception) {
                return DeleteResult.Failed("删除异常：${e.javaClass.simpleName}")
            }
            // 极少数 ROM 只删了 MediaStore 行、文件仍在；有写权限时补一刀
            shot.absolutePath?.let { path ->
                runCatching { File(path).takeIf { it.exists() }?.delete() }
            }
        }

        return if (!exists(shot.contentUri)) DeleteResult.Removed(backup)
        else needsConfirm(shot, backup)
    }

    /**
     * 系统确认弹窗结束后调用。以「原图是否还在」为准：
     * 还在说明用户点了取消，回收站里那份副本是多余的，要抹掉；
     * 不在了才保留备份，供日后还原。
     */
    fun finalizeAfterConfirm(shot: PendingShot): Boolean {
        return if (exists(shot.contentUri)) {
            trash.purge(shot.mediaId)
            false
        } else true
    }

    fun exists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count > 0 } == true
    }.getOrDefault(false)

    /** Android 11+ 的系统删除确认请求；10 及以下返回 null（走 legacy 直删）。 */
    fun deleteRequestIntentSender(uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            MediaStore.createDeleteRequest(resolver, uris).intentSender
        }.getOrNull()
    }

    private fun needsConfirm(shot: PendingShot, backup: TrashEntry?): DeleteResult =
        if (deleteRequestIntentSender(listOf(shot.contentUri)) != null) DeleteResult.NeedsConfirm(backup)
        else DeleteResult.Failed("系统拒绝直接删除，且当前版本不支持发起删除确认")

    /**
     * 是否属于「用户确认后就能删」的拒绝。不能直接写 `e is RecoverableSecurityException`：
     * 这个类在 Android 9 及以下不存在，引用它会让整段删除逻辑在低版本上验证失败。
     */
    private fun isRecoverable(e: SecurityException): Boolean {
        var c: Class<*>? = e.javaClass
        while (c != null && c != Throwable::class.java) {
            if (c.name == RECOVERABLE_EXCEPTION) return true
            c = c.superclass
        }
        return false
    }

    private companion object {
        const val RECOVERABLE_EXCEPTION = "android.app.RecoverableSecurityException"
    }
}
