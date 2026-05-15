package com.taostudio.tapaccounting.chat.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

class VoicePlaybackController(
    private val context: Context,
    private val onStateChanged: () -> Unit
) {
    private var currentPlayingPathInternal: String? = null
    private var pausedVoicePath: String? = null
    private var pausedVoicePositionMs: Int = 0
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFocusGranted = false

    val currentPlayingPath: String?
        get() = currentPlayingPathInternal

    fun isPlaying(path: String): Boolean {
        return currentPlayingPathInternal == path && (mediaPlayer?.isPlaying == true)
    }

    fun toggle(path: String): Boolean {
        if (currentPlayingPathInternal == path) {
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
                onStateChanged()
                return true
            }
            stop()
            onStateChanged()
        }

        stop()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        run {
            audioManager?.isSpeakerphoneOn = true
        }
        @Suppress("DEPRECATION")
        run {
            currentAudioFocusGranted =
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        return runCatching {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(path)
                setOnCompletionListener {
                    stop()
                    onStateChanged()
                }
                prepare()
                start()
            }
            currentPlayingPathInternal = path
            pausedVoicePath = null
            pausedVoicePositionMs = 0
            onStateChanged()
            true
        }.getOrElse {
            stop()
            onStateChanged()
            false
        }
    }

    fun stop() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (currentAudioFocusGranted) {
            @Suppress("DEPRECATION")
            runCatching { audioManager?.abandonAudioFocus(null) }
        }
        audioManager?.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        run {
            audioManager?.isSpeakerphoneOn = false
        }

        currentAudioFocusGranted = false
        currentPlayingPathInternal = null
        pausedVoicePath = null
        pausedVoicePositionMs = 0
    }
}

