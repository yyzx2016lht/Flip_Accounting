package tao.test.flipaccounting

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tao.test.flipaccounting.data.local.AppDatabase
import java.io.File
import kotlin.math.abs

class ChatVoiceController(
    private val context: ChatActivity,
    private val db: AppDatabase,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val displayMessages: MutableList<ChatDisplayItem>,
    private val adapterProvider: () -> RecyclerView.Adapter<*>,
    private val layoutVoiceSelectionBarProvider: () -> View,
    private val tvVoiceSelectionCountProvider: () -> TextView,
    private val pendingTranscriptRevealAnimations: MutableSet<String>,
    private val transcribingPaths: MutableSet<String>,
    private val transcribeVoiceToTextWithFallback: suspend (File) -> String,
    private val scrollToBottom: () -> Unit,
    private val showCustomConfirmDialog: (
        title: String,
        message: String,
        confirmText: String,
        isDanger: Boolean,
        onConfirm: () -> Unit
    ) -> Unit,
    private val findDependentAssistantMessageIds: (List<Long>) -> List<Long>,
    private val refreshSessionRows: suspend () -> Unit
) {
    val selectedVoiceMessageIds: MutableSet<Long> = mutableSetOf()
    private var isVoiceSelectionMode: Boolean = false

    private var currentPlayingPath: String? = null
    private var pausedVoicePath: String? = null
    private var pausedVoicePositionMs: Int = 0
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFocusGranted: Boolean = false

    companion object {
        private const val VOICE_PAYLOAD_V2_PREFIX = "__voice_v2__:"
    }

    fun isVoiceSelectionMode(): Boolean = isVoiceSelectionMode

    fun currentPlayingPath(): String? = currentPlayingPath

    fun isMediaPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun isItemSelected(item: ChatDisplayItem): Boolean =
        selectedVoiceMessageIds.contains(selectionKey(item))

    fun buildVoicePayload(audioPath: String, durationSec: Int, transcript: String): String {
        val json = JSONObject().apply {
            put("audioPath", audioPath)
            put("durationSec", durationSec)
            put("transcript", transcript)
        }.toString()
        return VOICE_PAYLOAD_V2_PREFIX + json
    }

    fun parseVoicePayload(content: String): VoicePayload {
        val normalized = content.removePrefix(VOICE_PAYLOAD_V2_PREFIX).trim()
        return try {
            val obj = JSONObject(normalized)
            VoicePayload(
                audioPath = obj.optString("audioPath"),
                durationSec = obj.optInt("durationSec", 1).coerceAtLeast(1),
                transcript = obj.optString("transcript")
            )
        } catch (_: Exception) {
            parseLooseVoicePayload(normalized) ?: VoicePayload(transcript = content)
        }
    }

    fun parseVoicePayloadStrict(content: String): VoicePayload? {
        val normalized = content.removePrefix(VOICE_PAYLOAD_V2_PREFIX).trim()
        return try {
            val obj = JSONObject(normalized)
            val audioPath = obj.optString("audioPath").trim()
            val durationSec = obj.optInt("durationSec", -1)
            if (audioPath.isBlank() || durationSec <= 0) return null
            VoicePayload(
                audioPath = audioPath,
                durationSec = durationSec,
                transcript = obj.optString("transcript").trim()
            )
        } catch (_: Exception) {
            parseLooseVoicePayload(normalized)
        }
    }

    fun playVoiceMessage(item: ChatDisplayItem) {
        val voice = item.voice ?: parseVoicePayload(item.content)
        val path = voice.audioPath.takeIf { it.isNotBlank() } ?: run {
            Utils.toast(context, "未找到语音文件")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            if (voice.transcript.trim().isNotBlank()) {
                Utils.toast(context, "语音文件已不存在，可查看已缓存转写")
            } else {
                Utils.toast(context, "语音文件已不存在")
            }
            return
        }
        if (currentPlayingPath == path) {
            val player = mediaPlayer
            if (player != null) {
                if (player.isPlaying) {
                    pausedVoicePath = path
                    pausedVoicePositionMs = runCatching { player.currentPosition }.getOrDefault(0)
                    runCatching { player.pause() }
                } else {
                    if (pausedVoicePath == path && pausedVoicePositionMs > 0) {
                        runCatching { player.seekTo(pausedVoicePositionMs) }
                    }
                    runCatching { player.start() }
                    pausedVoicePath = null
                    pausedVoicePositionMs = 0
                }
                adapterProvider().notifyDataSetChanged()
                return
            }
            stopVoicePlayback()
            adapterProvider().notifyDataSetChanged()
        }
        stopVoicePlayback()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.mode = AudioManager.MODE_NORMAL
        audioManager?.isSpeakerphoneOn = true
        currentAudioFocusGranted =
            audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(path)
            setOnCompletionListener {
                stopVoicePlayback()
                adapterProvider().notifyDataSetChanged()
            }
            prepare()
            start()
        }
        currentPlayingPath = path
        pausedVoicePath = null
        pausedVoicePositionMs = 0
        adapterProvider().notifyDataSetChanged()
    }

    fun stopVoicePlayback() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (currentAudioFocusGranted) {
            runCatching { audioManager?.abandonAudioFocus(null) }
        }
        audioManager?.mode = AudioManager.MODE_NORMAL
        audioManager?.isSpeakerphoneOn = false
        currentAudioFocusGranted = false
        currentPlayingPath = null
        pausedVoicePath = null
        pausedVoicePositionMs = 0
    }

    fun enterVoiceSelectionMode(firstItem: ChatDisplayItem? = null) {
        isVoiceSelectionMode = true
        firstItem?.let { selectedVoiceMessageIds.add(selectionKey(it)) }
        updateVoiceSelectionUi()
        notifySelectionRowsChanged()
    }

    fun exitVoiceSelectionMode() {
        isVoiceSelectionMode = false
        selectedVoiceMessageIds.clear()
        updateVoiceSelectionUi()
        notifySelectionRowsChanged()
    }

    fun toggleVoiceSelection(item: ChatDisplayItem) {
        val key = selectionKey(item)
        if (selectedVoiceMessageIds.contains(key)) {
            selectedVoiceMessageIds.remove(key)
        } else {
            selectedVoiceMessageIds.add(key)
        }
        if (selectedVoiceMessageIds.isEmpty()) {
            exitVoiceSelectionMode()
        } else {
            updateVoiceSelectionUi()
            notifySelectionRowsChanged()
        }
    }

    fun deleteSelectedVoiceMessages() {
        if (selectedVoiceMessageIds.isEmpty()) {
            exitVoiceSelectionMode()
            return
        }
        val selectedItems = displayMessages.filter { isItemSelected(it) }
        deleteVoiceMessages(selectedItems)
    }

    fun deleteVoiceMessages(items: List<ChatDisplayItem>) {
        if (items.isEmpty()) return
        val itemKeys = items.map { selectionKey(it) }.toSet()
        val persistedIds = items.mapNotNull { it.dbId.takeIf { id -> id > 0L } }.distinct()
        showCustomConfirmDialog(
            "删除消息",
            "确定删除选中的消息吗？",
            "删除",
            true,
            {
                lifecycleScope.launch {
                    val extraAssistantMessageIds = if (persistedIds.isEmpty()) {
                        emptyList()
                    } else {
                        findDependentAssistantMessageIds(persistedIds)
                    }
                    val allIds = (persistedIds + extraAssistantMessageIds).distinct()
                    val files = displayMessages
                        .filter { itemKeys.contains(selectionKey(it)) || allIds.contains(it.dbId) }
                        .mapNotNull { it.voice?.audioPath?.takeIf { path -> path.isNotBlank() } }
                    withContext(Dispatchers.IO) {
                        if (allIds.isNotEmpty()) {
                            db.chatMessageDao().deleteByIds(allIds)
                        }
                        files.forEach { runCatching { File(it).delete() } }
                    }
                    displayMessages.removeAll {
                        itemKeys.contains(selectionKey(it)) || allIds.contains(it.dbId)
                    }
                    adapterProvider().notifyDataSetChanged()
                    exitVoiceSelectionMode()
                    refreshSessionRows()
                }
            }
        )
    }

    fun hideVoiceTranscript(item: ChatDisplayItem) {
        val voice = item.voice ?: parseVoicePayload(item.content)
        if (voice.transcript.isBlank()) return
        val idx = findVoiceItemIndex(item.dbId, voice.audioPath)
        if (idx >= 0) {
            pendingTranscriptRevealAnimations += voice.audioPath
            adapterProvider().notifyItemChanged(idx)
        }
        Utils.toast(context, "转写已保存，可直接复制")
    }

    fun transcribeVoiceMessage(item: ChatDisplayItem, showResult: Boolean, force: Boolean = false) {
        val voice = item.voice ?: parseVoicePayload(item.content)
        val cachedTranscript = voice.transcript.trim()
        if (!force && cachedTranscript.isNotBlank()) {
            val idx = findVoiceItemIndex(item.dbId, voice.audioPath)
            if (showResult && idx >= 0) {
                pendingTranscriptRevealAnimations += voice.audioPath
                adapterProvider().notifyItemChanged(idx)
                scrollToBottom()
            }
            return
        }
        val path = voice.audioPath.takeIf { it.isNotBlank() } ?: run {
            if (cachedTranscript.isNotBlank()) {
                Utils.toast(context, "未找到语音文件，已保留缓存转写")
            } else {
                Utils.toast(context, "未找到语音文件")
            }
            return
        }
        val file = File(path)
        if (!file.exists()) {
            if (cachedTranscript.isNotBlank()) {
                val idx = findVoiceItemIndex(item.dbId, path)
                if (showResult && idx >= 0) {
                    pendingTranscriptRevealAnimations += path
                    adapterProvider().notifyItemChanged(idx)
                    scrollToBottom()
                }
                Utils.toast(context, "语音文件已不存在，已使用缓存转写")
            } else {
                Utils.toast(context, "语音文件已不存在")
            }
            return
        }
        if (transcribingPaths.contains(path)) return

        lifecycleScope.launch {
            transcribingPaths.add(path)
            findVoiceItemIndex(item.dbId, voice.audioPath).takeIf { it >= 0 }?.let { adapterProvider().notifyItemChanged(it) }

            val text = try {
                withContext(Dispatchers.IO) {
                    transcribeVoiceToTextWithFallback(file)
                }.orEmpty().trim()
            } finally {
                transcribingPaths.remove(path)
                findVoiceItemIndex(item.dbId, voice.audioPath).takeIf { it >= 0 }?.let { adapterProvider().notifyItemChanged(it) }
            }

            if (text.isBlank()) {
                Utils.toast(context, "转文字失败，请稍后重试")
                return@launch
            }
            val updatedVoice = voice.copy(transcript = text)
            if (showResult) {
                pendingTranscriptRevealAnimations += updatedVoice.audioPath
            }
            updateVoiceMessageContent(item, updatedVoice)
            if (showResult) {
                scrollToBottom()
            }
        }
    }

    fun updateVoiceTranscriptByPath(audioPath: String, transcript: String, revealTranscript: Boolean) {
        val idx = findVoiceItemIndex(targetDbId = 0L, targetAudioPath = audioPath)
        if (idx < 0) return
        val item = displayMessages[idx]
        val voice = item.voice ?: parseVoicePayload(item.content)
        if (voice.transcript == transcript) return
        if (revealTranscript) pendingTranscriptRevealAnimations += audioPath
        updateVoiceMessageContent(item, voice.copy(transcript = transcript))
    }

    private fun updateVoiceSelectionUi() {
        val layoutVoiceSelectionBar = layoutVoiceSelectionBarProvider()
        val tvVoiceSelectionCount = tvVoiceSelectionCountProvider()
        layoutVoiceSelectionBar.visibility = if (isVoiceSelectionMode) View.VISIBLE else View.GONE
        tvVoiceSelectionCount.text = "已选择 ${selectedVoiceMessageIds.size} 条消息"
    }

    private fun notifySelectionRowsChanged() {
        val adapter = adapterProvider()
        displayMessages.forEachIndexed { index, item ->
            if (
                item.msgType == ChatActivity.MSG_TYPE_USER_TEXT ||
                item.msgType == ChatActivity.MSG_TYPE_USER_VOICE ||
                item.msgType == ChatActivity.MSG_TYPE_AI_TEXT
            ) {
                adapter.notifyItemChanged(index)
            }
        }
    }

    private fun updateVoiceMessageContent(item: ChatDisplayItem, voice: VoicePayload) {
        val idx = findVoiceItemIndex(
            targetDbId = item.dbId,
            targetAudioPath = voice.audioPath.ifBlank { item.voice?.audioPath.orEmpty() }
        )
        if (idx >= 0) {
            val dbId = displayMessages[idx].dbId
            displayMessages[idx] = displayMessages[idx].copy(
                dbId = dbId,
                content = buildVoicePayload(voice.audioPath, voice.durationSec, voice.transcript),
                voice = voice
            )
            adapterProvider().notifyItemChanged(idx)
        }
        val persistedId = if (idx >= 0) displayMessages[idx].dbId else item.dbId
        if (persistedId > 0L) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.chatMessageDao().getById(persistedId)?.let { msg ->
                    db.chatMessageDao().update(
                        msg.copy(content = buildVoicePayload(voice.audioPath, voice.durationSec, voice.transcript))
                    )
                }
            }
        }
    }

    private fun findVoiceItemIndex(targetDbId: Long, targetAudioPath: String): Int {
        val normalizedPath = targetAudioPath.trim()
        if (targetDbId > 0L) {
            val byId = displayMessages.indexOfFirst {
                it.msgType == ChatActivity.MSG_TYPE_USER_VOICE && it.dbId == targetDbId
            }
            if (byId >= 0) return byId
        }
        if (normalizedPath.isBlank()) return -1
        return displayMessages.indexOfLast {
            if (it.msgType != ChatActivity.MSG_TYPE_USER_VOICE) return@indexOfLast false
            val voice = it.voice ?: parseVoicePayload(it.content)
            voice.audioPath.trim() == normalizedPath
        }
    }

    private fun selectionKey(item: ChatDisplayItem): Long {
        if (item.dbId > 0L) return item.dbId
        val mixed = item.timestamp xor item.msgType.toLong() xor item.content.hashCode().toLong()
        val positive = abs(mixed).coerceAtLeast(1L)
        return -positive
    }

    private fun parseLooseVoicePayload(raw: String): VoicePayload? {
        val text = raw.trim()
        if (!text.contains("audioPath", ignoreCase = true)) return null
        val audioPath = Regex("['\\\"]audioPath['\\\"]\\s*:\\s*['\\\"]([^'\\\"]+)['\\\"]")
            .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val duration = Regex("['\\\"]durationSec['\\\"]\\s*:\\s*(\\d+)")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        val transcript = Regex("['\\\"]transcript['\\\"]\\s*:\\s*['\\\"]([^'\\\"]*)['\\\"]")
            .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (audioPath.isBlank() || duration <= 0) return null
        return VoicePayload(
            audioPath = audioPath,
            durationSec = duration.coerceAtLeast(1),
            transcript = transcript
        )
    }
}
