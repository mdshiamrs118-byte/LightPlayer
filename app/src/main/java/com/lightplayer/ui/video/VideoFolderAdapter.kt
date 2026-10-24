package com.lightplayer.ui.video

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lightplayer.R
import com.lightplayer.databinding.ItemFolderBinding
import com.lightplayer.databinding.ItemVideoBinding
import com.lightplayer.theme.ThemeManager
import com.lightplayer.util.Formats
import com.lightplayer.util.PositionStore
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

    /** Folders in display order; kept so a single folder can be opened/closed with an animation. */
    private var folders: List<FolderItem> = emptyList()

    /** Opens or closes one folder, animating only the rows that appear or disappear. */
    fun setExpanded(folder: FolderItem, open: Boolean) {
        val index = rows.indexOfFirst { it is Row.FolderRow && it.folder.id == folder.id }
        if (index < 0) return
        val current = rows[index] as Row.FolderRow
        if (current.expanded == open) return
        rows[index] = Row.FolderRow(folder, open)
        if (open) {
            val added = folder.videos.map { Row.VideoRow(it) }
            rows.addAll(index + 1, added)
            notifyItemChanged(index, PAYLOAD_CHEVRON)
            notifyItemRangeInserted(index + 1, added.size)
        } else {
            var count = 0
            while (index + 1 < rows.size && rows[index + 1] is Row.VideoRow) {
                rows.removeAt(index + 1)
                count++
            }
            notifyItemChanged(index, PAYLOAD_CHEVRON)
            notifyItemRangeRemoved(index + 1, count)
        }
    }

    fun submit(folders: List<FolderItem>, expanded: Set<String>) {
        this.folders = folders
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
        ThemeManager.ensure(holder.itemView)
        when (val row = rows[position]) {
            is Row.FolderRow -> (holder as FolderVH).bind(row, onFolderClick, animateChevron = false)
            is Row.VideoRow -> (holder as VideoVH).bind(row, onVideoClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_CHEVRON) && holder is FolderVH) {
            val row = rows[position] as? Row.FolderRow
            if (row != null) {
                holder.bind(row, onFolderClick, animateChevron = true)
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private class FolderVH(private val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.FolderRow, click: (FolderItem) -> Unit, animateChevron: Boolean) {
            val f = row.folder
            val ctx = itemView.context
            b.folderName.text = f.name
            b.folderPath.text = f.path
            b.folderDate.text = Formats.date(f.latestDate)
            b.folderCount.text =
                if (f.videos.size == 1) ctx.getString(R.string.video_one_count)
                else ctx.getString(R.string.video_count_fmt, f.videos.size)
            val target = if (row.expanded) 90f else 0f
            if (animateChevron) b.chevron.animate().rotation(target).setDuration(250).start()
            else { b.chevron.animate().cancel(); b.chevron.rotation = target }
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
            val watched = PositionStore.fraction(itemView.context, v.uri.toString(), v.durationMs)
            if (watched > 0.01f) {
                b.watchProgress.max = 1000
                b.watchProgress.progress = (watched * 1000).toInt()
                b.watchProgress.visibility = android.view.View.VISIBLE
            } else {
                b.watchProgress.visibility = android.view.View.GONE
            }
            b.root.setOnClickListener { click(v) }
        }
    }

    private companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_VIDEO = 1
        const val PAYLOAD_CHEVRON = "chevron"
    }
}