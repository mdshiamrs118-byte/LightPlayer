package com.lightplayer.ui.video

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.lightplayer.MainActivity
import com.lightplayer.R
import com.lightplayer.databinding.FragmentVideoBinding
import com.lightplayer.player.PlayerActivity
import com.lightplayer.theme.ThemeManager
import com.lightplayer.util.NaturalOrder
import java.util.LinkedHashSet

class VideoFragment : Fragment() {

    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoFolderAdapter
    private val expanded = LinkedHashSet<String>()
    private var currentFolders: List<FolderItem> = emptyList()
    private var listAnimated = false

    // Android's system file picker: lets the user play any video, even ones the
    // media library doesn't list.
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openPicked(uri)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ThemeManager.apply(view, ThemeManager.palette(requireContext()))
        adapter = VideoFolderAdapter(
            onFolderClick = { folder -> toggle(folder) },
            onVideoClick = { video -> open(video) }
        )
        binding.recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.openFileFab.setOnClickListener {
            try {
                filePicker.launch(arrayOf("video/*", "application/x-matroska", "application/octet-stream"))
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), R.string.video_pick_error, Toast.LENGTH_SHORT).show()
            }
        }
        binding.limitedBanner.setOnClickListener {
            (activity as? MainActivity)?.requestVideoPermission { scan() }
        }
        binding.grantButton.setOnClickListener {
            (activity as? MainActivity)?.requestVideoPermission { granted ->
                if (granted) scan()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null || !isAdded) return
        if (MainActivity.hasVideoPermission(requireContext())) {
            binding.limitedBanner.visibility =
                if (MainActivity.hasPartialVideoAccess(requireContext())) View.VISIBLE else View.GONE
            scan()
        } else {
            showPermissionState()
        }
    }

    override fun onDestroyView() {
        binding.recycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun showPermissionState() {
        binding.progress.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyText.setText(R.string.video_permission_r)
        binding.grantButton.visibility = View.VISIBLE
    }

    private fun scan() {
        binding.emptyState.visibility = View.GONE
        binding.grantButton.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE

        val appCtx = requireContext().applicationContext
        Thread {
            val folders = try {
                VideoRepository.scan(appCtx)
            } catch (t: Throwable) {
                emptyList<FolderItem>()
            }
            activity?.runOnUiThread {
                if (_binding == null || !isAdded) return@runOnUiThread
                binding.progress.visibility = View.GONE
                currentFolders = folders
                val total = folders.sumOf { it.videos.size }
                binding.headerCount.text = if (total == 1) getString(R.string.video_one_count)
                else getString(R.string.video_count_fmt, total)
                if (folders.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.emptyText.setText(R.string.video_empty)
                } else {
                    binding.emptyState.visibility = View.GONE
                }
                adapter.submit(folders, expanded)
                if (!listAnimated && folders.isNotEmpty()) {
                    listAnimated = true
                    binding.recycler.scheduleLayoutAnimation()
                }
            }
        }.start()
    }

    private fun toggle(folder: FolderItem) {
        val open = !expanded.remove(folder.id)
        if (open) expanded.add(folder.id)
        adapter.setExpanded(folder, open)
    }

    private fun open(video: VideoItem) {
        // Everything in the same folder, in natural name order (Episode 2 before Episode 10),
        // so the player can go to the previous/next video.
        val folder = currentFolders.firstOrNull { f -> f.videos.any { it.id == video.id } }
        val siblings = (folder?.videos ?: listOf(video)).sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
        var index = siblings.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
        var list = siblings
        if (list.size > MAX_PLAYLIST) {
            val from = (index - MAX_PLAYLIST / 2).coerceIn(0, list.size - MAX_PLAYLIST)
            list = list.subList(from, from + MAX_PLAYLIST)
            index -= from
        }

        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            data = video.uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(PlayerActivity.EXTRA_TITLE, video.title)
            putExtra(PlayerActivity.EXTRA_DURATION, video.durationMs)
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_URIS, ArrayList(list.map { it.uri.toString() }))
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, ArrayList(list.map { it.title }))
            putExtra(PlayerActivity.EXTRA_PLAYLIST_DURATIONS, list.map { it.durationMs }.toLongArray())
            putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, index)
        }
        startActivity(intent)
    }

    private fun openPicked(uri: android.net.Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) { /* not every provider offers persistable grants */ }
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private companion object {
        const val MAX_PLAYLIST = 300
    }
}
