package com.changakira.screenshotbye.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.DecisionOutcome
import com.changakira.screenshotbye.core.PendingShotStore
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.media.DeleteGateway
import com.changakira.screenshotbye.notify.Actions

/**
 * 无界面的中转页。
 *
 * Android 11+ 没拿到「所有文件访问」时，删掉系统创建的截图必须由用户在系统对话框里亲自确认，
 * 而这个 IntentSender 只能由 Activity 发起。于是通知的「点按确认」先落到这个透明页面，
 * 由它拉起系统弹窗，再把结果回报给 ScreenshotRepository。
 */
class DeleteConfirmActivity : AppCompatActivity() {

    private val systemDelete = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        val mediaId = intent.getLongExtra(Actions.EXTRA_MEDIA_ID, -1L)
        val outcome = ScreenshotRepository(this).onConfirmResult(mediaId)
        val msg = when (outcome) {
            is DecisionOutcome.Deleted -> getString(
                if (outcome.inTrash) R.string.toast_deleted_trash else R.string.toast_deleted
            )
            DecisionOutcome.Kept -> getString(R.string.toast_kept)
            is DecisionOutcome.Error -> outcome.reason
            else -> null
        }
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mediaId = intent.getLongExtra(Actions.EXTRA_MEDIA_ID, -1L)
        val shot = if (mediaId > 0) PendingShotStore.of(this).find(mediaId) else null
        if (shot == null) {
            finish()
            return
        }

        val sender = DeleteGateway(this).deleteRequestIntentSender(listOf(shot.contentUri))
        if (sender == null) {
            Toast.makeText(this, R.string.toast_no_delete_support, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (savedInstanceState == null) {
            runCatching { systemDelete.launch(IntentSenderRequest.Builder(sender).build()) }
                .onFailure {
                    Toast.makeText(this, R.string.toast_no_delete_support, Toast.LENGTH_LONG).show()
                    finish()
                }
        }
    }
}
