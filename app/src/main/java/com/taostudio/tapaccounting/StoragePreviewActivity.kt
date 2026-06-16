package com.taostudio.tapaccounting

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StoragePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INCLUDE_VOICE = "extra_include_voice"
        const val EXTRA_INCLUDE_IMAGES = "extra_include_images"
        const val EXTRA_INCLUDE_CHAT_AVATAR = "extra_include_chat_avatar"
        const val EXTRA_INCLUDE_BOOK_COVER_BG = "extra_include_book_cover_bg"
        const val EXTRA_INCLUDE_TEMP_CACHE = "extra_include_temp_cache"
        const val EXTRA_OLDER_ONLY = "extra_older_only"
    }

    private enum class PreviewTab { IMAGES, AUDIOS }

    private lateinit var btnImages: MaterialButton
    private lateinit var btnAudios: MaterialButton
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var recyclerView: RecyclerView

    private var allFiles: List<File> = emptyList()
    private var imageFiles: List<File> = emptyList()
    private var audioFiles: List<File> = emptyList()
    private var currentTab: PreviewTab = PreviewTab.IMAGES

    private val imageAdapter = ImageGridAdapter(
        onClick = { file, index -> openImageViewer(index) }
    )
    private val audioAdapter = AudioListAdapter(
        onPlayClick = { file -> togglePlay(file) }
    )

    private var mediaPlayer: MediaPlayer? = null
    private var playingPath: String? = null
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_preview)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        btnImages = findViewById(R.id.btn_tab_images)
        btnAudios = findViewById(R.id.btn_tab_audios)
        tvSummary = findViewById(R.id.tv_preview_summary)
        tvEmpty = findViewById(R.id.tv_preview_empty)
        recyclerView = findViewById(R.id.rv_preview)

        btnImages.setOnClickListener { switchTab(PreviewTab.IMAGES) }
        btnAudios.setOnClickListener { switchTab(PreviewTab.AUDIOS) }

        loadFiles()
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    private fun loadFiles() {
        val includeVoice = intent.getBooleanExtra(EXTRA_INCLUDE_VOICE, true)
        val includeImages = intent.getBooleanExtra(EXTRA_INCLUDE_IMAGES, true)
        val includeChatAvatar = intent.getBooleanExtra(EXTRA_INCLUDE_CHAT_AVATAR, false)
        val includeBookCoverBg = intent.getBooleanExtra(EXTRA_INCLUDE_BOOK_COVER_BG, false)
        val includeTempCache = intent.getBooleanExtra(EXTRA_INCLUDE_TEMP_CACHE, true)
        val olderOnly = intent.getBooleanExtra(EXTRA_OLDER_ONLY, false)

        lifecycleScope.launch(Dispatchers.IO) {
            val selected = mutableListOf<File>()
            if (includeVoice) {
                selected += listFilesInDir(File(filesDir, "chat_voice"))
                    .filter { !olderOnly || isOlderThanDays(it, 30) }
            }
            if (includeImages) {
                selected += listFilesInDir(File(filesDir, "chat_images")).filter { !olderOnly || isOlderThanDays(it, 30) }
                selected += listFilesInDir(File(filesDir, "chat_pics")).filter { !olderOnly || isOlderThanDays(it, 30) }
            }
            if (includeChatAvatar) {
                addIfFileExists(selected, File(filesDir, "chat_ai_avatar.jpg"), olderOnly)
                addIfFileExists(selected, File(filesDir, "chat_user_avatar.jpg"), olderOnly)
                addIfFileExists(selected, File(filesDir, "chat_ai_avatar.png"), olderOnly)
                addIfFileExists(selected, File(filesDir, "chat_user_avatar.png"), olderOnly)
            }
            if (includeBookCoverBg) {
                selected += listFilesInDir(File(filesDir, "chat_bg")).filter { !olderOnly || isOlderThanDays(it, 30) }
                addIfFileExists(selected, File(filesDir, "chat_bg.jpg"), olderOnly)
                addIfFileExists(selected, File(filesDir, "chat_bg.png"), olderOnly)
                addIfFileExists(selected, File(filesDir, "chat_bg.webp"), olderOnly)
                addIfFileExists(selected, File(filesDir, "chat_bg.jpeg"), olderOnly)
                addIfFileExists(selected, File(filesDir, "chat_bg.bmp"), olderOnly)
                addIfFileExists(selected, File(filesDir, "chat_bg.gif"), olderOnly)
                selected += listFilesInDir(File(filesDir, "banners")).filter { !olderOnly || isOlderThanDays(it, 30) }
            }
            if (includeTempCache) {
                selected += listFilesInDir(File(cacheDir, "picked_images"))
                selected += listFilesInDir(File(cacheDir, "avatar_crop"))
                selected += listFilesInDir(File(cacheDir, "banner_crop"))
                selected += cacheDir.listFiles()
                    ?.filter { it.isFile && shouldCleanupCacheFile(it.name) }
                    .orEmpty()
            }

            val dedup = selected.distinctBy { it.absolutePath }
            val images = dedup.filter { isImageFile(it) }.sortedByDescending { it.lastModified() }
            val audios = dedup.filter { isAudioFile(it) }.sortedByDescending { it.lastModified() }

            withContext(Dispatchers.Main) {
                allFiles = dedup
                imageFiles = images
                audioFiles = audios
                tvSummary.text = getString(R.string.preview_summary_fmt, allFiles.size, imageFiles.size, audioFiles.size)
                switchTab(if (imageFiles.isNotEmpty()) PreviewTab.IMAGES else PreviewTab.AUDIOS)
            }
        }
    }

    private fun switchTab(tab: PreviewTab) {
        currentTab = tab
        stopPlayback()
        updateTabStyle()

        when (tab) {
            PreviewTab.IMAGES -> {
                recyclerView.layoutManager = GridLayoutManager(this, 3)
                recyclerView.adapter = imageAdapter
                imageAdapter.submit(imageFiles)
                tvEmpty.visibility = if (imageFiles.isEmpty()) View.VISIBLE else View.GONE
                tvEmpty.text = getString(R.string.no_preview_image)
            }
            PreviewTab.AUDIOS -> {
                recyclerView.layoutManager = LinearLayoutManager(this)
                recyclerView.adapter = audioAdapter
                audioAdapter.submit(audioFiles, playingPath)
                tvEmpty.visibility = if (audioFiles.isEmpty()) View.VISIBLE else View.GONE
                tvEmpty.text = getString(R.string.no_preview_audio)
            }
        }
    }

    private fun updateTabStyle() {
        val selectedBg = 0xFF5C80EA.toInt()
        val selectedText = 0xFFFFFFFF.toInt()
        val normalBg = 0xFFEAF0FB.toInt()
        val normalText = 0xFF44556E.toInt()
        if (currentTab == PreviewTab.IMAGES) {
            btnImages.setBackgroundColor(selectedBg)
            btnImages.setTextColor(selectedText)
            btnAudios.setBackgroundColor(normalBg)
            btnAudios.setTextColor(normalText)
        } else {
            btnAudios.setBackgroundColor(selectedBg)
            btnAudios.setTextColor(selectedText)
            btnImages.setBackgroundColor(normalBg)
            btnImages.setTextColor(normalText)
        }
    }

    private fun openImageViewer(index: Int) {
        if (imageFiles.isEmpty()) return
        val paths = ArrayList(imageFiles.map { it.absolutePath })
        val intent = Intent(this, StorageImageViewerActivity::class.java).apply {
            putStringArrayListExtra(StorageImageViewerActivity.EXTRA_IMAGE_PATHS, paths)
            putExtra(StorageImageViewerActivity.EXTRA_INDEX, index)
        }
        startActivity(intent)
    }

    private fun togglePlay(file: File) {
        val path = file.absolutePath
        if (playingPath == path && mediaPlayer?.isPlaying == true) {
            stopPlayback()
            audioAdapter.submit(audioFiles, playingPath)
            return
        }
        stopPlayback()
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(path)
            player.prepare()
            player.setOnCompletionListener {
                stopPlayback()
                audioAdapter.submit(audioFiles, playingPath)
            }
            player.start()
            mediaPlayer = player
            playingPath = path
            audioAdapter.submit(audioFiles, playingPath)
        }.onFailure {
            runCatching { player.release() }
            Utils.toast(this, getString(R.string.play_failed))
        }
    }

    private fun stopPlayback() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        playingPath = null
    }

    private fun shouldCleanupCacheFile(name: String): Boolean {
        val n = name.lowercase(Locale.getDefault())
        return n.startsWith("temp_") ||
            n == "voice_input.wav" ||
            n == "chat_voice_input.wav" ||
            n.startsWith("sense_voice") ||
            n.endsWith(".bak")
    }

    private fun listFilesInDir(dir: File): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile }.toList()
    }

    private fun addIfFileExists(target: MutableList<File>, file: File, olderOnly: Boolean) {
        if (file.exists() && file.isFile && (!olderOnly || isOlderThanDays(file, 30))) target += file
    }

    private fun isOlderThanDays(file: File, days: Int): Boolean {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        return file.lastModified() in 1 until cutoff
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }

    private fun isImageFile(file: File): Boolean {
        val n = file.name.lowercase(Locale.getDefault())
        return n.endsWith(".jpg") ||
            n.endsWith(".jpeg") ||
            n.endsWith(".png") ||
            n.endsWith(".webp") ||
            n.endsWith(".bmp") ||
            n.endsWith(".gif")
    }

    private fun isAudioFile(file: File): Boolean {
        val n = file.name.lowercase(Locale.getDefault())
        return n.endsWith(".wav") ||
            n.endsWith(".mp3") ||
            n.endsWith(".m4a") ||
            n.endsWith(".amr") ||
            n.endsWith(".aac") ||
            n.endsWith(".ogg") ||
            n.endsWith(".opus")
    }

    private inner class ImageGridAdapter(
        private val onClick: (File, Int) -> Unit
    ) : RecyclerView.Adapter<ImageGridAdapter.VH>() {
        private val data = mutableListOf<File>()

        fun submit(list: List<File>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_storage_preview_image, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = data[position]
            holder.tvName.text = file.name
            holder.ivThumb.setImageBitmap(decodeSampledBitmap(file, 320))
            holder.itemView.setOnClickListener { onClick(file, position) }
        }

        override fun getItemCount(): Int = data.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivThumb: ImageView = v.findViewById(R.id.iv_preview_thumb)
            val tvName: TextView = v.findViewById(R.id.tv_preview_thumb_name)
        }
    }

    private inner class AudioListAdapter(
        private val onPlayClick: (File) -> Unit
    ) : RecyclerView.Adapter<AudioListAdapter.VH>() {
        private val data = mutableListOf<File>()
        private var currentPlayingPath: String? = null

        fun submit(list: List<File>, playingPath: String?) {
            data.clear()
            data.addAll(list)
            currentPlayingPath = playingPath
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_storage_preview_audio, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = data[position]
            val modified = if (file.lastModified() > 0) dateFormat.format(Date(file.lastModified())) else getString(R.string.unknown_time)
            holder.tvTitle.text = file.name
            holder.tvMeta.text = "${formatBytes(file.length())} · $modified"
            holder.btnPlay.text = if (file.absolutePath == currentPlayingPath) getString(R.string.stop) else getString(R.string.play)
            holder.btnPlay.setOnClickListener { onPlayClick(file) }
        }

        override fun getItemCount(): Int = data.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tv_audio_title)
            val tvMeta: TextView = v.findViewById(R.id.tv_audio_meta)
            val btnPlay: MaterialButton = v.findViewById(R.id.btn_audio_play)
        }
    }

    private fun decodeSampledBitmap(file: File, reqSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = calculateInSampleSize(bounds, reqSize, reqSize)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
                halfHeight /= 2
                halfWidth /= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}

