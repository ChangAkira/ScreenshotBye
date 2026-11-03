package com.changakira.screenshotbye.media

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.perm.Perms
import java.io.File

/** 系统弹窗同意后要做的事：移进系统回收站，还是直接抹掉。 */
enum class DeleteMode {
    /** Android 11+：交给系统的照片回收站，用户在相册里还能找回。 */
    TRASH,

    /** Android 10 及以下：系统没有回收站，删了就没了。 */
    PERMANENT,
}

sealed interface DeleteResult {
    /** 已经直接删掉（只有 Android 10 及以下有写权限时才可能）。 */
    data object Removed : DeleteResult

    /** 需要系统弹窗确认，调用方去拿 IntentSender。 */
    data object NeedsConfirm : DeleteResult

    data class Failed(val reason: String) : DeleteResult
}

/**
 * 删除入口。要删的截图由系统创建、不属于本应用，所以：
 * - Android 11+：一律不自作主张。发起 MediaStore 的回收站请求，由系统弹窗让用户点头，
 *   点头之后原件进系统相册的回收站（保留多久由系统决定，本应用不再自己存副本）。
 *   个别 ROM 不支持回收站，退回永久删除请求。
 * - Android 10 及以下：没有回收站这套机制，有 WRITE 权限就直接删，删掉即消失；
 *   系统拒绝时（scoped storage）只能报错，因为低版本连删除确认弹窗都发不起来。
 */
class DeleteGateway(ctx: Context) {

    private val app = ctx.applicationContext
    private val resolver = app.contentResolver

    /**
     * 单张处置。Android 11+ 永远返回 NeedsConfirm：真正动手的是系统弹窗之后的
     * [confirmRequest]，本应用不再有任何静默删除的路径。
     */
    fun deleteNow(shot: PendingShot): DeleteResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return DeleteResult.NeedsConfirm
        if (!Perms.canDeleteSilently(app)) {
            return DeleteResult.Failed("Android 10 及以下需要存储写入权限才能删除")
        }
        try {
            resolver.delete(shot.contentUri, null, null)
        } catch (e: SecurityException) {
            // RecoverableSecurityException 是 API 29 才有的类，直接在 catch 里写类型
            // 会让 Android 8/9 在异常传播时报类找不到，因此按类名判断。
            return if (isRecoverable(e)) DeleteResult.Failed("系统拒绝删除该文件")
            else DeleteResult.Failed("删除被拒绝：${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            return DeleteResult.Failed("删除异常：${e.javaClass.simpleName}")
        }
        // 极少数 ROM 只删了 MediaStore 行、文件仍在；有写权限时补一刀
        shot.absolutePath?.let { path ->
            runCatching { File(path).takeIf { it.exists() }?.delete() }
        }
        return if (exists(shot.contentUri)) DeleteResult.Failed("相册记录未移除，删除未完成")
        else DeleteResult.Removed
    }

    /**
     * 系统确认弹窗用的 IntentSender，附带这次是「移入回收站」还是「永久删除」。
     * 10 及以下返回 null——那一版没有删除确认弹窗，也不该由本应用静默删。
     *
     * 两个请求都必须在这道 SDK 判断之后发起，所以取值也放在这里，lint 才认得出前置条件；
     * 先回收站后永久删除的优先级交给 [pickRequest]，那一步能单独测。
     */
    fun confirmRequest(uris: List<Uri>): Pair<IntentSender, DeleteMode>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // ROM 不支持回收站时这里会抛，兜住返回 null，退回永久删除请求
        val trash = runCatching { MediaStore.createTrashRequest(resolver, uris, true).intentSender }.getOrNull()
        val delete = runCatching { MediaStore.createDeleteRequest(resolver, uris).intentSender }.getOrNull()
        return pickRequest({ trash }, { delete })
    }

    /** 先要回收站入口，拿不到再退回永久删除入口。 */
    internal fun pickRequest(trash: () -> IntentSender?, delete: () -> IntentSender?): Pair<IntentSender, DeleteMode>? =
        trash()?.let { it to DeleteMode.TRASH } ?: delete()?.let { it to DeleteMode.PERMANENT }

    /**
     * 系统弹窗结束后的核对：这张截图是否已经「离开相册」。
     *
     * 不能只靠 [exists] 判断——回收站语义在各家 ROM 上不一致：Android 11+ 按「集合」查询
     * （content://media/external/images/media）时默认会把已回收的条目过滤掉，但按「单条」
     * 直查（…/images/media/<id>）时很多 ROM 仍会把这条已回收的记录返回回来。于是「用户点了
     * 确认、图也确实从相册消失了」会被误判成「还查得到 = 取消」。
     *
     * 这里改成直接读 is_trashed 列，两种行为都能覆盖：
     * - 行没了（moveToFirst 失败）→ 被彻底删除，算离开相册；
     * - 行还在但 is_trashed != 0 → 已进回收站，同样算离开相册；
     * - 行还在且 is_trashed == 0 → 用户真的取消了。
     * 列名用字面量 "is_trashed"（API 30 才有该列常量），避免在未加 SDK 判断的方法上触发 lint。
     * 查询本身异常时按「已离开」处理，宁可少留一张也不把成功的删除回滚成取消。
     */
    fun finalizeAfterConfirm(shot: PendingShot): Boolean = runCatching {
        resolver.query(shot.contentUri, arrayOf(MediaStore.MediaColumns._ID, IS_TRASHED), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) true
                else {
                    val ti = c.getColumnIndex(IS_TRASHED)
                    ti >= 0 && c.getInt(ti) != 0
                }
            } ?: true
    }.getOrDefault(true)

    fun exists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count > 0 } == true
    }.getOrDefault(false)

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
        /** MediaStore.MediaColumns.IS_TRASHED 是 API 30 常量，用字面量避开 lint NewApi。 */
        const val IS_TRASHED = "is_trashed"
    }
}
