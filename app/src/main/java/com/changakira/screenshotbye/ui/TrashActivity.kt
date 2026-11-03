package com.changakira.screenshotbye.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.Settings
import com.changakira.screenshotbye.databinding.ActivityTrashBinding
import com.changakira.screenshotbye.media.TrashEntry
import com.changakira.screenshotbye.media.TrashStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** 删除前后的备份都落在这里，用户可以在保留期内反悔。 */
class TrashActivity : ThemedActivity() {

    private lateinit var b: ActivityTrashBinding
    private lateinit var store: TrashStore
    private lateinit var settings: Settings
    private lateinit var adapter: TrashAdapter
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "trash-ui").apply { isDaemon = true } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thumbs.init(this)
        b = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.title = getString(R.string.trash_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        store = TrashStore(this)
        settings = Settings.of(this)
        adapter = TrashAdapter(
            onRestore = { entry -> restore(entry) },
            onPurge = { entry -> confirmPurge(entry) },
        )
        b.trashList.layoutManager = LinearLayoutManager(this)
        b.trashList.adapter = adapter

        b.purgeAllBtn.setOnClickListener {
            val count = store.list().size
            if (count == 0) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_purge_all)
                .setMessage(getString(R.string.dlg_purge_all_desc, count))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    io.execute {
                        store.list().forEach { store.purge(it.mediaId) }
                        runOnUiThread { refresh() }
                    }
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun restore(entry: TrashEntry) {
        io.execute {
            val ok = store.restore(entry)
            settings.appendLog(if (ok) "已还原 ${entry.name}" else "还原失败 ${entry.name}")
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(if (ok) R.string.trash_restored else R.string.trash_restore_failed, entry.name),
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
        }
    }

    private fun confirmPurge(entry: TrashEntry) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.trash_purge_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                io.execute {
                    store.purge(entry.mediaId)
                    settings.appendLog("彻底删除备份 ${entry.name}")
                    runOnUiThread {
                        Toast.makeText(this, R.string.trash_purged, Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun refresh() {
        val items = store.list()
        adapter.submit(items)
        b.trashEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        b.purgeAllBtn.isEnabled = items.isNotEmpty()
    }
}
