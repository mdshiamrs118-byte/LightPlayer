package com.lightplayer.ui.video

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.lightplayer.MainActivity
import com.lightplayer.R
import com.lightplayer.databinding.FragmentVideoBinding
import com.lightplayer.player.PlayerActivity
import com.lightplayer.theme.ThemeManager
import com.lightplayer.util.DurationCache
import com.lightplayer.util.FavoritesStore
import com.lightplayer.util.Formats
import com.lightplayer.util.NaturalOrder
import com.lightplayer.util.PositionStore
import com.lightplayer.util.Prefs
import com.lightplayer.util.Thumbs
import java.util.LinkedHashSet

class VideoFragment : Fragment() {

    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoFolderAdapter
    private val expanded = LinkedHashSet<String>()
    private var allFolders: List<FolderItem> = emptyList()
    private var query = ""
    private var sort = SortMode.NAME
    private var searchOpen = false
    private var listAnimated = false
    private var lastGeneration = -1L
    private var pendingDelete: VideoItem? = null

    // Android's system file picker: lets the user play any video, even ones the
    // media library doesn't list.
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openPicked(uri)
        }

    // Result of the system "allow deleting this video?" screen.
    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val video = pendingDelete
            pendingDelete = null
            if (video == null || _binding == null) return@registerForActivityResult
            if (result.resultCode == Activity.RESULT_OK) {
                // Android 10 only grants access here; Android 11+ already deleted the file.
                if (Build.VERSION.SDK_INT < 30) {
                    try {
                        requireContext().contentResolver.delete(video.uri, null, null)
                    } catch (_: Throwable) {
                    }
                }
                onDeleted(video)
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        ThemeManager.apply(view, ThemeManager.palette(ctx))
        sort = try { SortMode.valueOf(Prefs.videoSort(ctx)) } catch (_: Throwable) { SortMode.NAME }

        adapter = VideoFolderAdapter(
            onFolderClick = { folder -> toggle(folder) },
            onVideoClick = { video -> open(video) },
            onVideoLongClick = { video -> showVideoMenu(video) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(ctx)
        binding.recycler.adapter = adapter

        binding.openFileFab.setOnClickListener {
            try {
                filePicker.launch(arrayOf("video/*", "application/x-matroska", "application/octet-stream"))
            } catch (t: Throwable) {
                Toast.makeText(ctx, R.string.video_pick_error, Toast.LENGTH_SHORT).show()
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

        binding.btnSort.setOnClickListener { showSortDialog() }
        binding.btnSearch.setOnClickListener { setSearchOpen(!searchOpen) }
        binding.btnClearSearch.setOnClickListener {
            if (binding.searchInput.text.isNullOrEmpty()) setSearchOpen(false)
            else binding.searchInput.setText("")
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                rebuild()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null || !isAdded) return
        val ctx = requireContext()
        if (MainActivity.hasVideoPermission(ctx)) {
            binding.limitedBanner.visibility =
                if (MainActivity.hasPartialVideoAccess(ctx)) View.VISIBLE else View.GONE

            // Re-reading the media library is only needed when it actually changed
            // (Android 11+ can tell us). Otherwise just refresh progress bars and sections.
            val generation = currentGeneration(ctx)
            if (generation != -1L && generation == lastGeneration && allFolders.isNotEmpty()) {
                rebuild()
            } else {
                scan()
            }
        } else {
            showPermissionState()
        }
    }

    override fun onDestroyView() {
        binding.recycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun currentGeneration(ctx: Context): Long =
        if (Build.VERSION.SDK_INT >= 30) {
            try { MediaStore.getGeneration(ctx, MediaStore.VOLUME_EXTERNAL) } catch (_: Throwable) { -1L }
        } else {
            -1L
        }

    private fun showPermissionState() {
        binding.progress.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyText.setText(R.string.video_permission_r)
        binding.grantButton.visibility = View.VISIBLE
    }

    // ---------- loading and showing the library ----------

    private fun scan() {
        binding.emptyState.visibility = View.GONE
        binding.grantButton.visibility = View.GONE
        if (allFolders.isEmpty()) binding.progress.visibility = View.VISIBLE

        val appCtx = requireContext().applicationContext
        val generation = currentGeneration(appCtx)
        Thread {
            val folders = try {
                VideoRepository.scan(appCtx)
            } catch (t: Throwable) {
                emptyList<FolderItem>()
            }
            activity?.runOnUiThread {
                if (_binding == null || !isAdded) return@runOnUiThread
                binding.progress.visibility = View.GONE
                allFolders = folders
                lastGeneration = generation
                rebuild()
            }
        }.start()
    }

    /** Applies search + sort to the scanned library and shows the result. */
    private fun rebuild() {
        if (_binding == null || !isAdded) return
        val ctx = requireContext()
        val searching = query.isNotBlank()
        val folders = LibraryBuilder.build(ctx, allFolders, query, sort)

        val totalVideos = allFolders.sumOf { it.videos.size }
        binding.headerCount.text = when {
            searching -> getString(R.string.video_results_fmt, folders.sumOf { it.videos.size })
            totalVideos == 1 -> getString(R.string.video_one_count)
            else -> getString(R.string.video_count_fmt, totalVideos)
        }

        if (folders.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.emptyText.setText(if (searching) R.string.video_no_results else R.string.video_empty)
        } else {
            binding.emptyState.visibility = View.GONE
        }

        // Search results open all their folders so every match is visible.
        val open: Set<String> = if (searching) folders.map { it.id }.toSet() else expanded
        adapter.submit(folders, open)

        if (!listAnimated && folders.isNotEmpty()) {
            listAnimated = true
            binding.recycler.scheduleLayoutAnimation()
        }
    }

    private fun toggle(folder: FolderItem) {
        val open = !adapter.isExpanded(folder.id)
        if (query.isBlank()) {
            if (open) expanded.add(folder.id) else expanded.remove(folder.id)
        }
        adapter.setExpanded(folder, open)
    }

    // ---------- search and sort ----------

    private fun setSearchOpen(open: Boolean) {
        searchOpen = open
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
        binding.searchBar.visibility = if (open) View.VISIBLE else View.GONE
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (open) {
            binding.searchInput.requestFocus()
            imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            binding.searchInput.setText("")
            imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        }
    }

    private fun showSortDialog() {
        val ctx = requireContext()
        val modes = SortMode.values()
        val labels = arrayOf(
            getString(R.string.sort_name),
            getString(R.string.sort_date),
            getString(R.string.sort_size),
            getString(R.string.sort_duration)
        )
        ThemeManager.themed(
            ThemeManager.dialogBuilder(ctx)
                .setTitle(R.string.library_sort)
                .setSingleChoiceItems(labels, modes.indexOf(sort)) { dialog, which ->
                    sort = modes[which]
                    Prefs.setVideoSort(ctx, sort.name)
                    rebuild()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .create()
        ).show()
    }

    // ---------- long-press menu ----------

    private fun showVideoMenu(video: VideoItem) {
        val ctx = requireContext()
        val favorite = FavoritesStore.isFavorite(ctx, video.uri.toString())
        val labels = arrayOf(
            getString(R.string.menu_play),
            getString(if (favorite) R.string.menu_favorite_remove else R.string.menu_favorite_add),
            getString(R.string.menu_share),
            getString(R.string.menu_details),
            getString(R.string.menu_delete)
        )
        ThemeManager.themed(
            ThemeManager.dialogBuilder(ctx)
                .setTitle(video.title)
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> open(video)
                        1 -> toggleFavorite(video)
                        2 -> share(video)
                        3 -> showDetails(video)
                        4 -> confirmDelete(video)
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .create()
        ).show()
    }

    private fun toggleFavorite(video: VideoItem) {
        val now = FavoritesStore.toggle(requireContext(), video.uri.toString())
        Toast.makeText(
            requireContext(),
            if (now) R.string.toast_favorite_added else R.string.toast_favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
        rebuild()
    }

    private fun share(video: VideoItem) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, video.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, video.title))
        } catch (_: Throwable) {
        }
    }

    private fun showDetails(video: VideoItem) {
        val ctx = requireContext()
        val folder = allFolders.firstOrNull { f -> f.videos.any { it.id == video.id } }
        val message = getString(
            R.string.details_fmt,
            video.title,
            folder?.path ?: "—",
            Formats.size(video.size),
            Formats.time(LibraryBuilder.effectiveDuration(ctx, video)),
            Formats.date(video.dateAdded)
        )
        ThemeManager.themed(
            ThemeManager.dialogBuilder(ctx)
                .setTitle(R.string.details_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_done, null)
                .create()
        ).show()
    }

    private fun confirmDelete(video: VideoItem) {
        ThemeManager.themed(
            ThemeManager.dialogBuilder(requireContext())
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, video.title))
                .setPositiveButton(R.string.menu_delete) { _, _ -> performDelete(video) }
                .setNegativeButton(R.string.action_cancel, null)
                .create()
        ).show()
    }

    private fun performDelete(video: VideoItem) {
        val ctx = requireContext()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                // Android shows its own "allow deleting this video?" screen.
                val request = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(video.uri))
                pendingDelete = video
                deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else if (ctx.contentResolver.delete(video.uri, null, null) > 0) {
                onDeleted(video)
            } else {
                Toast.makeText(ctx, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            if (e is RecoverableSecurityException) {
                pendingDelete = video
                deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } else {
                Toast.makeText(ctx, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            Toast.makeText(ctx, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDeleted(video: VideoItem) {
        val ctx = requireContext()
        val key = video.uri.toString()
        PositionStore.clear(ctx, key)
        FavoritesStore.remove(ctx, key)
        DurationCache.remove(ctx, key)
        Thumbs.forget(ctx, video.uri)
        Toast.makeText(ctx, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
        scan()
    }

    // ---------- playing ----------

    private fun open(video: VideoItem) {
        // Everything in the same real folder, in natural name order (Episode 2 before Episode 10),
        // so the player can go to the previous/next video.
        val folder = allFolders.firstOrNull { f -> f.videos.any { it.id == video.id } }
        val siblings = (folder?.videos ?: listOf(video)).sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
        var index = siblings.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
        var list = siblings
        if (list.size > MAX_PLAYLIST) {
            val from = (index - MAX_PLAYLIST / 2).coerceIn(0, list.size - MAX_PLAYLIST)
            list = list.subList(from, from + MAX_PLAYLIST)
            index -= from
        }

        val ctx = requireContext()
        val intent = Intent(ctx, PlayerActivity::class.java).apply {
            data = video.uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(PlayerActivity.EXTRA_TITLE, video.title)
            putExtra(PlayerActivity.EXTRA_DURATION, LibraryBuilder.effectiveDuration(ctx, video))
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_URIS, ArrayList(list.map { it.uri.toString() }))
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, ArrayList(list.map { it.title }))
            putExtra(
                PlayerActivity.EXTRA_PLAYLIST_DURATIONS,
                list.map { LibraryBuilder.effectiveDuration(ctx, it) }.toLongArray()
            )
            putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, index)
        }
        startActivity(intent)
    }

    private fun openPicked(uri: Uri) {
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
