package com.lightplayer.ui.video

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lightplayer.R
import com.lightplayer.databinding.ItemFolderBinding
import com.lightplayer.databinding.ItemVideoBinding
import com.lightplayer.util.Formats
import com.lightplayer.util.Thumbs

class VideoFolderAdapter(
    private val onFolderClick: (FolderItem) -> Unit,
    private val onVideoClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class FolderRow(val folder: FolderItem, val expanded: Boolean) : Row()
        data class VideoRow(val video: VideoItem) : Row()
    }

    private val rows = ArrayList<Row>()

    fun submit(folders: List<FolderItem>, expanded: Set<String>) {
        rows.clear()
        for (f in folders) {
            val isOpen = expanded.contains(f.id)
            rows.add(Row.FolderRow(f, isOpen))
            if (isOpen) f.videos.forEach { rows.add(Row.VideoRow(it)) }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.FolderRow) TYPE_FOLDER else TYPE_VIDEO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) FolderVH(ItemFolderBinding.inflate(inflater, parent, false))
        else VideoVH(ItemVideoBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.FolderRow -> (holder as FolderVH).bind(row, onFolderClick)
            is Row.VideoRow -> (holder as VideoVH).bind(row, onVideoClick)
        }
    }

    private class FolderVH(private val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.FolderRow, click: (FolderItem) -> Unit) {
            val f = row.folder
            val ctx = itemView.context
            b.folderName.text = f.name
            b.folderPath.text = f.path
            b.folderDate.text = Formats.date(f.latestDate)
            b.folderCount.text =
                if (f.videos.size == 1) ctx.getString(R.string.video_one_count)
                else ctx.getString(R.string.video_count_fmt, f.videos.size)
            b.chevron.rotation = if (row.expanded) 90f else 0f
            b.root.setOnClickListener { click(f) }
        }
    }

    private class VideoVH(private val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.VideoRow, click: (VideoItem) -> Unit) {
            val v = row.video
            b.videoTitle.text = v.title
            b.videoMeta.text = "${Formats.date(v.dateAdded)} • ${Formats.size(v.size)}"
            b.durationBadge.text = Formats.time(v.durationMs)
            Thumbs.load(itemView.context, v.uri, b.thumb, R.drawable.ic_video)
            b.root.setOnClickListener { click(v) }
        }
    }

    private companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_VIDEO = 1
    }
}