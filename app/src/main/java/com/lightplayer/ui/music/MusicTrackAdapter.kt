package com.lightplayer.ui.music

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lightplayer.R
import com.lightplayer.databinding.ItemTrackBinding
import com.lightplayer.util.Formats

class MusicTrackAdapter(
    private val onTrackClick: (TtsTrack) -> Unit,
    private val onTrackLongClick: (TtsTrack) -> Unit
) : RecyclerView.Adapter<MusicTrackAdapter.VH>() {

    private val items = ArrayList<TtsTrack>()

    var playingUri: Uri? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun submit(list: List<TtsTrack>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(track: TtsTrack) {
            b.trackTitle.text = track.videoTitle
            b.trackSub.text =
                if (track.subtitleName.isNotBlank()) track.subtitleName else track.displayName

            val parts = ArrayList<String>()
            if (track.dateAdded > 0) parts.add(Formats.date(track.dateAdded))
            if (track.size > 0) parts.add(Formats.size(track.size))
            if (track.durationMs > 0) parts.add(Formats.time(track.durationMs))
            b.trackMeta.text = parts.joinToString(" • ")

            val isPlaying = playingUri == track.uri
            b.playBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

            b.root.setOnClickListener { onTrackClick(track) }
            b.playBtn.setOnClickListener { onTrackClick(track) }
            b.root.setOnLongClickListener {
                onTrackLongClick(track)
                true
            }
        }
    }
}