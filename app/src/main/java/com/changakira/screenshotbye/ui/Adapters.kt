package com.changakira.screenshotbye.ui

import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.databinding.ItemShotBinding
import com.changakira.screenshotbye.databinding.ItemTrashBinding
import com.changakira.screenshotbye.media.TrashEntry
import java.util.Date
import java.util.concurrent.TimeUnit

class ShotAdapter(
    private val onDelete: (PendingShot) -> Unit,
    private val onKeep: (PendingShot) -> Unit,
    private val onPreview: (PendingShot) -> Unit,
) : RecyclerView.Adapter<ShotAdapter.VH>() {

    private var items: List<PendingShot> = emptyList()

    fun submit(newItems: List<PendingShot>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(val b: ItemShotBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemShotBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val shot = items[position]
        val ctx = holder.itemView.context
        holder.b.shotName.text = shot.name
        holder.b.shotMeta.text = ctx.getString(
            R.string.shot_meta,
            shot.folder.ifBlank { "-" },
            sizeOf(ctx, shot.size),
            DateFormat.getTimeFormat(ctx).format(Date(shot.takenAt))
        )
        holder.b.shotState.text = when (shot.state) {
            ShotState.WAITING -> ctx.getString(
                R.string.state_waiting,
                TimeUnit.MILLISECONDS.toMinutes((shot.askAt - System.currentTimeMillis()).coerceAtLeast(0)) + 1
            )
            ShotState.ASKED -> ctx.getString(R.string.state_asked)
            ShotState.DELETING -> ctx.getString(R.string.state_deleting)
        }
        Thumbs.media(holder.b.thumb, shot.contentUri)
        holder.b.btnDelete.setOnClickListener { onDelete(shot) }
        holder.b.btnKeep.setOnClickListener { onKeep(shot) }
        holder.b.root.setOnClickListener { onPreview(shot) }
        holder.b.thumb.visibility = if (shot.state == ShotState.DELETING) View.INVISIBLE else View.VISIBLE
    }
}

class TrashAdapter(
    private val onRestore: (TrashEntry) -> Unit,
    private val onPurge: (TrashEntry) -> Unit,
) : RecyclerView.Adapter<TrashAdapter.VH>() {

    private var items: List<TrashEntry> = emptyList()

    fun submit(newItems: List<TrashEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(val b: ItemTrashBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val ctx = holder.itemView.context
        holder.b.shotName.text = entry.name
        holder.b.shotMeta.text = ctx.getString(
            R.string.trash_meta,
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(Date(entry.deletedAt)),
            entry.relativePath?.trimEnd('/') ?: "-"
        ) + " · " + sizeOf(ctx, entry.size)
        Thumbs.file(holder.b.thumb, entry.backupFile)
        holder.b.btnRestore.setOnClickListener { onRestore(entry) }
        holder.b.btnPurge.setOnClickListener { onPurge(entry) }
    }
}

internal fun sizeOf(ctx: android.content.Context, bytes: Long): String =
    if (bytes <= 0) ctx.getString(R.string.size_unknown) else Formatter.formatShortFileSize(ctx, bytes)
