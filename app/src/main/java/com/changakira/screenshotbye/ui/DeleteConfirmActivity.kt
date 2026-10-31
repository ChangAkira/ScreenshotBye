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

/**
 * 无界面的中转页。
 *
 * Android 11+ 没拿到「所有文件访问」时，删掉系统创建的截图必须由用户在系统对话框里亲自确认，
 * 而这个 IntentSender 只能由 Activity 发起。通知或逐个确认页把要删的媒体 id 交给这里，
 * 由它一次拉起系统弹窗（一批只弹一次），再把结果回报给 ScreenshotRepository。
 */
class DeleteConfirmActivity : AppCompatActivity() {

    private val systemDelete = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        val ids = mediaIds()
        val (deleted, cancelled) = ScreenshotRepository(this).onBatchConfirmResult(ids)
        val msg = when {
            deleted > 0 && cancelled > 0 -> getString(R.string.toast_confirm_partial, deleted, cancelled)
            deleted > 0 -> getString(R.string.toast_deleted_n, deleted)
            else -> getString(R.string.toast_confirm_cancelled)
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

        val sender = DeleteGateway(this).deleteRequestIntentSender(shots.map { it.contentUri })
        if (sender == null) {
            // 系统不给删除入口：这些截图还都在，把状态从「等待系统确认」退回「已询问」
            ScreenshotRepository(this).onBatchConfirmResult(ids)
            Toast.makeText(this, R.string.toast_no_delete_support, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (savedInstanceState == null) {
            runCatching { systemDelete.launch(IntentSenderRequest.Builder(sender).build()) }
                .onFailure {
                    ScreenshotRepository(this).onBatchConfirmResult(ids)
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
