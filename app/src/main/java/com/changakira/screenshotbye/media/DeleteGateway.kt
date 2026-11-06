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
     * 不经系统弹窗直接生效了。「无感删除」只保证**不弹确认框**，原件去哪儿由系统定。
     * [trashed] 只在系统当场认账时为真（行还在、`is_trashed != 0`）；
     * 行整个消失时它是 false，但 false 不等于「找不回来了」——定制系统把文件搬进
     * 自己隐藏的回收站目录也是这个表现，应用侧分辨不了，界面上因此一律不承诺去向。
     */
    data class Removed(val trashed: Boolean) : DeleteResult

    /** 需要系统弹窗确认，调用方去拿 IntentSender。 */
    data object NeedsConfirm : DeleteResult

    data class Failed(val reason: String) : DeleteResult
}

/** 删完之后那一行的三种可能，见 [DeleteGateway.classify]。 */
enum class AfterDelete {
    /**
     * MediaStore 行没了。**去向未知**：原生安卓的真删是这个样子，
     * 定制系统把文件搬进自己不被索引的隐藏回收站目录（小米 .globalTrash 等）也是这个样子，
     * 单看媒体库分辨不出来——想知道能不能还原，只能去相册回收站看一眼。
     */
    GONE,

    /** 行还在但 `is_trashed != 0`：系统认账这是软删，原件在相册回收站里，还能还原。 */
    TRASHED,

    /** 行还在又没被标记：删除压根没生效。 */
    STILL_THERE,
}

/**
 * 删除入口。要删的截图由系统创建、不属于本应用，所以：
 * - 拿到「所有文件访问」（Android 11+ 的特殊访问，或 10 及以下的 WRITE）时直接删，不再弹系统确认框。
 *   这条路省下的是**弹窗**，原件去向由系统定，而且应用查不全：原生安卓是真删；
 *   有的 ROM 会留下 `is_trashed=1` 的行（这种能确认）；更多国产 ROM 直接把文件搬进
 *   自己不被媒体库索引的隐藏目录，行和文件在原路径上一起消失，从三方应用里看不到任何区别。
 *   所以界面对这条路径只承诺「不弹确认」，去向交给感叹号说明，不替系统吹。
 *   也不再自己删文件：万一那套回收站还指着原路径，补这一刀就是把唯一的副本毁掉。
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
        // 删完回查那一行。这一步只用来区分两件事：系统当场认账是软删，以及删除压根没生效。
        // 「行没了」不额外说明任何去向——见 classify。
        val (found, flag) = probe(shot.contentUri)
        return when (classify(found, flag)) {
            // ROM 认账：原件进了系统回收站，还原入口在相册那边。绝不补删文件。
            AfterDelete.TRASHED -> DeleteResult.Removed(trashed = true)

            // 行没了：可能是真删，也可能是系统把文件搬进了自己的隐藏回收站目录
            // （小米 .globalTrash 那类不被媒体库索引，应用侧查不到任何痕迹）。分辨不了，
            // 所以这里只报「删掉了」，不报去向，也不再替系统去删文件。
            AfterDelete.GONE -> DeleteResult.Removed(trashed = false)

            // 行还在又没被标记 = 压根没删动，留着条目让用户重试
            AfterDelete.STILL_THERE -> DeleteResult.Failed(app.getString(R.string.err_row_not_removed))
        }
    }

    /**
     * 「行没了」之后原路径上还剩不还剩文件。
     * 剩着说明这套系统只删了索引、字节还在，界面可以照实提醒一句；
     * 不剩则是「真删」与「被搬进回收站」两种情况的共同表现，应用无从区分。
     */
    fun fileLeftBehind(shot: PendingShot): Boolean =
        shot.absolutePath?.let { runCatching { File(it).exists() }.getOrDefault(false) } == true

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

    /** 判断规则单独拆出来：只有这一层能分清「系统认账的软删」和「没删动」，界面上的措辞全押在它上面。 */
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
