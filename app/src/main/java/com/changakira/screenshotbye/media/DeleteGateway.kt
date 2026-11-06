package com.changakira.screenshotbye.media

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.changakira.screenshotbye.R
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
    /**
     * 不经系统弹窗直接生效了。「无感删除」只保证**不弹确认框**，不保证原件的去向：
     * 厂商系统常常把三方应用的删除改写成软删（行还在、`is_trashed=1`），原生安卓则是真删。
     * [trashed] 就是这次删完当场探到的结果，界面上的说法一律以它为准。
     */
    data class Removed(val trashed: Boolean) : DeleteResult

    /** 需要系统弹窗确认，调用方去拿 IntentSender。 */
    data object NeedsConfirm : DeleteResult

    data class Failed(val reason: String) : DeleteResult
}

/** 删完之后那一行的三种可能，见 [DeleteGateway.classify]。 */
enum class AfterDelete {
    /** MediaStore 行没了：这台机器是真删。 */
    GONE,

    /** 行还在但被系统标成已回收：ROM 把删除改成了软删，原件进的是相册回收站。 */
    TRASHED,

    /** 行还在又没被标记：删除压根没生效。 */
    STILL_THERE,
}

/**
 * 删除入口。要删的截图由系统创建、不属于本应用，所以：
 * - 拿到「所有文件访问」（Android 11+ 的特殊访问，或 10 及以下的 WRITE）时直接删，不再弹系统确认框。
 *   这条路省下的是**弹窗**，原件去向由系统定：原生安卓删掉就是删掉，相册里找不回来；
 *   小米、三星这类 ROM 会把三方应用的删除改写成移入回收站，那种机器上还能还原。
 *   本应用不猜，删完读一次 `is_trashed` 把实际结果记下来给界面用（见 [DeleteResult.Removed]）。
 * - Android 11+ 没拿到时不自作主张：发起 MediaStore 的回收站请求，由系统弹窗让用户点头，
 *   点头之后原件进系统相册的回收站（保留多久由系统决定，本应用不再自己存副本）。
 *   个别 ROM 不支持回收站，退回永久删除请求。
 * - Android 10 及以下没有删除确认弹窗，也没有回收站：没拿到 WRITE 就直接报错，让用户去授权。
 */
class DeleteGateway(ctx: Context) {

    private val app = ctx.applicationContext
    private val resolver = app.contentResolver

    /**
     * 单张处置。没有静默删除权限时，Android 11+ 返回 [DeleteResult.NeedsConfirm]
     * （真正动手的是系统弹窗之后的 [confirmRequest]），10 及以下返回失败。
     */
    fun deleteNow(shot: PendingShot): DeleteResult {
        if (!Perms.canDeleteSilently(app)) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) DeleteResult.NeedsConfirm
            else DeleteResult.Failed(app.getString(R.string.err_need_write_perm))
        }
        try {
            resolver.delete(shot.contentUri, null, null)
        } catch (e: SecurityException) {
            // RecoverableSecurityException 是 API 29 才有的类，直接在 catch 里写类型
            // 会让 Android 8/9 在异常传播时报类找不到，因此按类名判断。
            return if (isRecoverable(e)) DeleteResult.Failed(app.getString(R.string.err_system_denied))
            else DeleteResult.Failed(app.getString(R.string.err_denied_reason, e.message ?: e.javaClass.simpleName))
        } catch (e: Exception) {
            return DeleteResult.Failed(app.getString(R.string.err_delete_exception, e.javaClass.simpleName))
        }
        // 系统把这次删除做成了软删就别再补刀：原件此刻是回收站里那份唯一的副本
        val (found, flag) = probe(shot.contentUri)
        when (classify(found, flag)) {
            AfterDelete.TRASHED -> return DeleteResult.Removed(trashed = true)
            // 极少数 ROM 只删了 MediaStore 行、文件仍在；有写权限时补一刀
            AfterDelete.GONE -> shot.absolutePath?.let { path ->
                runCatching { File(path).takeIf { it.exists() }?.delete() }
            }

            AfterDelete.STILL_THERE -> {}
        }
        return if (exists(shot.contentUri)) DeleteResult.Failed(app.getString(R.string.err_row_not_removed))
        else DeleteResult.Removed(trashed = false)
    }

    /** 探一次那一行还在不在、有没有被系统标成已回收。查询异常按「行没了」处理。 */
    private fun probe(uri: Uri): Pair<Boolean, Int> = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, IS_TRASHED), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) false to 0
                else {
                    val ti = c.getColumnIndex(IS_TRASHED)
                    true to (if (ti >= 0) c.getInt(ti) else 0)
                }
            } ?: (false to 0)
    }.getOrDefault(false to 0)

    /** 判断规则单独拆出来，界面上的「本机实测」才靠得住。 */
    internal fun classify(rowFound: Boolean, trashedFlag: Int): AfterDelete = when {
        !rowFound -> AfterDelete.GONE
        trashedFlag != 0 -> AfterDelete.TRASHED
        else -> AfterDelete.STILL_THERE
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
    fun finalizeAfterConfirm(shot: PendingShot): Boolean {
        val (found, flag) = probe(shot.contentUri)
        return classify(found, flag) != AfterDelete.STILL_THERE
    }

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
