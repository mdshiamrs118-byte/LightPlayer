package com.lightplayer.ui.video

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.lightplayer.MainActivity
import com.lightplayer.R
import com.lightplayer.databinding.FragmentVideoBinding
import com.lightplayer.player.PlayerActivity
import java.util.LinkedHashSet

class VideoFragment : Fragment() {

    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoFolderAdapter
    private val expanded = LinkedHashSet<String>()
    private var currentFolders: List<FolderItem> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = VideoFolderAdapter(
            onFolderClick = { folder -> toggle(folder) },
            onVideoClick = { video -> open(video) }
        )
        binding.recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.grantButton.setOnClickListener {
            (activity as? MainActivity)?.requestVideoPermission { granted ->
                if (granted) scan()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null || !isAdded) return
        if (MainActivity.hasVideoPermission(requireContext())) scan()
        else showPermissionState()
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
                if (folders.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.emptyText.setText(R.string.video_empty)
                } else {
                    binding.emptyState.visibility = View.GONE
                }
                adapter.submit(folders, expanded)
            }
        }.start()
    }

    private fun toggle(folder: FolderItem) {
        if (!expanded.remove(folder.id)) expanded.add(folder.id)
        adapter.submit(currentFolders, expanded)
    }

    private fun open(video: VideoItem) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            data = video.uri
            putExtra(PlayerActivity.EXTRA_TITLE, video.title)
            putExtra(PlayerActivity.EXTRA_DURATION, video.durationMs)
        }
        startActivity(intent)
    }
}