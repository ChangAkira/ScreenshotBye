package com.changakira.screenshotbye.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.media.DeleteGateway
import com.changakira.screenshotbye.media.DeleteMode

/**
 * 无界面的中转页。
 *
 * Android 11+ 删掉系统创建的截图必须由用户在系统对话框里亲自点头，而这个 IntentSender
 * 只能由 Activity 发起。通知或逐个确认页把要删的媒体 id 交给这里，由它一次拉起系统弹窗
 * （一批只弹一次），弹窗同意后原件进系统相册的回收站，本应用不再留副本。
 */
class DeleteConfirmActivity : AppCompatActivity() {

    /** 这次请求是「移入回收站」还是「永久删除」，决定结果提示的措辞。 */
    private var mode = DeleteMode.TRASH

    private val systemDelete = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        val ids = mediaIds()
        val (done, cancelled) = ScreenshotRepository(this).onBatchConfirmResult(ids, mode)
        // 全删掉了就不回执：系统弹窗刚演完这一幕，用户看得见。只有「有东西没删成」才要说，
        // 否则用户会以为清单已经清空，实际那几张还留在待确认里。
        val msg = when {
            done > 0 && cancelled > 0 -> getString(R.string.toast_confirm_partial, done, cancelled)
            cancelled > 0 -> getString(R.string.toast_confirm_cancelled)
            else -> null
        }
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ids = mediaIds()
        val store = PendingShotStore.of(this)
        val shots = store.all().filter { it.mediaId in ids }
        if (shots.isEmpty()) {
            finish()
            return
        }

        val request = DeleteGateway(this).confirmRequest(shots.map { it.contentUri })
        if (request == null) {
            // 系统不给回收站也不给删除入口：这些截图还都在，把状态从「等待系统确认」退回「已询问」
            ScreenshotRepository(this).onBatchConfirmResult(ids, mode)
            Toast.makeText(this, R.string.toast_no_delete_support, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        mode = request.second
        if (savedInstanceState == null) {
            runCatching { systemDelete.launch(IntentSenderRequest.Builder(request.first).build()) }
                .onFailure {
                    ScreenshotRepository(this).onBatchConfirmResult(ids, mode)
                    Toast.makeText(this, R.string.toast_no_delete_support, Toast.LENGTH_LONG).show()
                    finish()
                }
        }
    }

    private fun mediaIds(): List<Long> =
        (intent.getLongArrayExtra(EXTRA_MEDIA_IDS) ?: LongArray(0)).toList()

    companion object {
        /** 要请系统确认删除的媒体 id 列表，一批一次弹窗。 */
        const val EXTRA_MEDIA_IDS = "media_ids"
    }
}
