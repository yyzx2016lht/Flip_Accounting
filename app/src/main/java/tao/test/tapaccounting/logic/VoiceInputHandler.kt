package tao.test.tapaccounting.logic

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tao.test.tapaccounting.AIService
import tao.test.tapaccounting.AiAssistant
import tao.test.tapaccounting.LocalAsrService
import tao.test.tapaccounting.Logger
import tao.test.tapaccounting.PermissionRequestActivity
import tao.test.tapaccounting.Prefs
import tao.test.tapaccounting.Utils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class VoiceInputHandler(
    private val ctx: Context,
    private val aiAssistant: AiAssistant,
    private val onResult: (JSONObject) -> Unit,
    private val onBeforeRecording: (() -> Boolean)? = null,
    private val onAfterRecording: (() -> Unit)? = null
) {
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isWannaCancel = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize =
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(sampleRate * 2)

    private var baseText = ""
    private var pendingLongPressRunnable: Runnable? = null
    private var longPressTriggered = false
    private var isFingerDown = false
    private var audioSupportProbeInFlight = false
    private var lastRecordingStartError: String? = null

    fun setupVoiceButton(btnVoice: View) {
        btnVoice.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        PermissionRequestActivity.onPermissionResult = { granted ->
                            if (granted) {
                                clearPendingLongPress()
                                isFingerDown = false
                                longPressTriggered = false
                                Utils.toast(ctx, "权限已授予，请重新按住麦克风说话")
                            } else {
                                Utils.toast(ctx, "需要麦克风权限才能录音")
                            }
                        }
                        ctx.startActivity(Intent(ctx, PermissionRequestActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        })
                        return@setOnTouchListener true
                    }

                    clearPendingLongPress()
                    longPressTriggered = false
                    isFingerDown = true
                    isWannaCancel = false
                    baseText = aiAssistant.getCurrentInputText()

                    v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).start()

                    // 立即触发后台预热，与"长按 200ms 等待 + 用户开口"并发，避免录音线程阻塞
                    if (Prefs.getAsrMode(ctx) == Prefs.ASR_MODE_WHISPER) {
                        LocalAsrService.warmUp(ctx)
                    }

                    val runnable = Runnable {
                        if (!isFingerDown || isRecording) return@Runnable
                        longPressTriggered = true
                        Utils.vibrate(ctx)

                        aiAssistant.showInputPanel(
                            defaultText = currentVoiceRecordingHint(),
                            mode = AiAssistant.MODE_RECORDING,
                            
                        ) { resultJson ->
                            onResult(resultJson)
                        }
                        maybeProbeCurrentModelAudioSupport()

                        val started = try {
                            startRecording()
                        } catch (_: Exception) {
                            false
                        }

                        if (!started) {
                            isRecording = false
                            clearPendingLongPress()
                            aiAssistant.dismiss()
                            Utils.toast(ctx, lastRecordingStartError ?: "录音启动失败")
                        }
                    }

                    pendingLongPressRunnable = runnable
                    handler.postDelayed(runnable, 200)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isRecording) {
                        if (event.y < -150f) {
                            if (!isWannaCancel) {
                                isWannaCancel = true
                                Utils.vibrate(ctx, 30)
                                aiAssistant.showInputPanel(
                                    mode = AiAssistant.MODE_CANCEL,
                                    
                                ) { onResult(it) }
                            }
                        } else if (isWannaCancel) {
                            isWannaCancel = false
                            Utils.vibrate(ctx, 10)
                            aiAssistant.showInputPanel(
                                defaultText = currentVoiceRecordingHint(),
                                mode = AiAssistant.MODE_RECORDING,
                                
                            ) { onResult(it) }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clearPendingLongPress()
                    isFingerDown = false
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()

                    if (!longPressTriggered && !isRecording) {
                        return@setOnTouchListener true
                    }

                    if (isRecording) {
                        if (isWannaCancel) {
                            stopRecording { _ -> }
                            LocalAsrService.finishStreaming()
                            aiAssistant.dismiss()
                            Utils.toast(ctx, "已取消")
                        } else {
                            stopRecording { file ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    if (currentModelSupportsDirectAudioInput() && file != null) {
                                        val result = runCatching {
                                            AIService.analyzeAccountingByAudio(
                                                ctx = ctx,
                                                audioFile = file,
                                                isMultiModeOverride = true
                                            )
                                        }.getOrNull()

                                        if (result != null) {
                                            withContext(Dispatchers.Main) {
                                                aiAssistant.dismiss()
                                                onResult(result)
                                            }
                                            return@launch
                                        }
                                    }

                                    val asrMode = Prefs.getAsrMode(ctx)
                                    val text = if (asrMode == Prefs.ASR_MODE_WHISPER) {
                                        LocalAsrService.finishStreaming()
                                        if (file != null) LocalAsrService.speechToText(ctx, file) else null
                                    } else {
                                        if (Prefs.getAiKey(ctx).isBlank()) {
                                            "API_KEY_NOT_SETUP"
                                        } else if (file != null) {
                                            AIService.speechToText(ctx, file)
                                        } else {
                                            null
                                        }
                                    }

                                    withContext(Dispatchers.Main) {
                                        if (!text.isNullOrEmpty() && text != "WHISPER_NOT_SETUP" && text != "MODEL_DOWNLOADING" && text != "API_KEY_NOT_SETUP") {
                                            val finalText = if (baseText.isNotEmpty()) "$baseText $text" else text
                                            aiAssistant.showInputPanel(
                                                defaultText = finalText,
                                                mode = AiAssistant.MODE_INPUT,
                                                
                                            ) { resultJson ->
                                                onResult(resultJson)
                                            }
                                        } else if (text == "MODEL_DOWNLOADING") {
                                            aiAssistant.dismiss()
                                            Utils.toast(ctx, "离线语音模型正在下载，请下载完成后再试")
                                        } else if (text == "WHISPER_NOT_SETUP") {
                                            aiAssistant.dismiss()
                                            Utils.toast(ctx, "需要先下载离线语音模型，才能使用本地语音识别")
                                        } else if (text == "API_KEY_NOT_SETUP") {
                                            aiAssistant.dismiss()
                                            Utils.toast(ctx, "需要先配置 API Key，才能使用云端语音识别")
                                        } else {
                                            aiAssistant.dismiss()
                                            Utils.toast(ctx, "未检测到清晰语音或解析失败")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    longPressTriggered = false
                    true
                }

                else -> false
            }
        }
    }

    private fun clearPendingLongPress() {
        pendingLongPressRunnable?.let { handler.removeCallbacks(it) }
        pendingLongPressRunnable = null
    }

    private fun currentAccountingModel(): String = Prefs.getAiMultiModel(ctx).ifBlank { Prefs.getAiModel(ctx) }

    private fun currentModelSupportsDirectAudioInput(): Boolean {
        if (Prefs.getAsrMode(ctx) == Prefs.ASR_MODE_WHISPER) return false
        val model = currentAccountingModel()
        return Prefs.getAiChatModelAudioSupport(ctx, model) == true
    }

    private fun currentVoiceRecordingHint(): String {
        val model = currentAccountingModel()
        return when (Prefs.getAiChatModelAudioSupport(ctx, model)) {
            true -> "当前模型支持直接语音输入，正在倾听..."
            false -> "当前模型不支持直接语音输入，将先转成文字"
            null -> "倾听中..."
        }
    }

    private fun maybeProbeCurrentModelAudioSupport() {
        val model = currentAccountingModel()
        if (model.isBlank() || Prefs.getAiChatModelAudioSupport(ctx, model) != null || audioSupportProbeInFlight) return
        audioSupportProbeInFlight = true
        CoroutineScope(Dispatchers.IO).launch {
            val support = runCatching { AIService.probeDirectAudioInputSupport(ctx, model) }.getOrDefault(false)
            Prefs.setAiChatModelAudioSupport(ctx, model, support)
            audioSupportProbeInFlight = false
            withContext(Dispatchers.Main) {
                if (isRecording && !isWannaCancel) {
                    aiAssistant.showInputPanel(
                        defaultText = currentVoiceRecordingHint(),
                        mode = AiAssistant.MODE_RECORDING,
                        
                    ) {}
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(): Boolean {
        if (isRecording) return true

        lastRecordingStartError = null
        audioFile = File(ctx.cacheDir, "voice_input.wav")
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        Logger.d(ctx, "VoiceInputHandler", "startRecording: sampleRate=$sampleRate bufferSize=$bufferSize minBufSize=$minBuf")

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        Logger.d(ctx, "VoiceInputHandler", "AudioRecord state=${record.state} (1=INITIALIZED)")
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Logger.d(ctx, "VoiceInputHandler", "AudioRecord init failed, state=${record.state}")
            lastRecordingStartError = "麦克风初始化失败"
            record.release()
            return false
        }

        return try {
            val microphoneModeReady = onBeforeRecording?.invoke() ?: true
            if (!microphoneModeReady) {
                Logger.d(ctx, "VoiceInputHandler", "startRecording aborted: microphone foreground service mode unavailable")
                lastRecordingStartError = "麦克风服务启动失败，请稍后重试"
                record.release()
                return false
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Logger.d(ctx, "VoiceInputHandler", "AudioRecord NOT in RECORDING state, state=${record.recordingState}")
                lastRecordingStartError = "麦克风未能开始录音"
                record.release()
                onAfterRecording?.invoke()
                return false
            }
            Logger.d(ctx, "VoiceInputHandler", "AudioRecord.startRecording() confirmed RECORDING, recordingState=${record.recordingState}")
            audioRecord = record
            isRecording = true

            val targetFile = audioFile ?: return false
            recordingThread = Thread {
                writeAudioDataToFile(targetFile)
            }
            recordingThread?.start()
            true
        } catch (e: Exception) {
            Logger.d(ctx, "VoiceInputHandler", "startRecording exception: ${e.message}")
            isRecording = false
            lastRecordingStartError = "录音启动失败"
            onAfterRecording?.invoke()
            try {
                record.release()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun writeAudioDataToFile(file: File) {
        val data = ByteArray(bufferSize)

        val asrMode = Prefs.getAsrMode(ctx)
        val useStreaming = asrMode == Prefs.ASR_MODE_WHISPER
        var streamStarted = false
        if (useStreaming) {
            // 检查 recognizer 是否已在 warmUp 中完成初始化，最多等待 200ms
            // 超时则放弃流式（仍正常录音，松手后用文件识别），保证录音不丢数据
            val t0 = System.currentTimeMillis()
            val deadline = 200L
            while (!LocalAsrService.isRecognizerReady() && System.currentTimeMillis() - t0 < deadline) {
                Thread.sleep(20)
            }
            val waited = System.currentTimeMillis() - t0
            if (LocalAsrService.isRecognizerReady()) {
                streamStarted = kotlinx.coroutines.runBlocking { LocalAsrService.startStreaming(ctx) }
                Logger.d(ctx, "VoiceInputHandler", "startStreaming done: streamStarted=$streamStarted waited=${waited}ms")
            } else {
                Logger.d(ctx, "VoiceInputHandler", "startStreaming skipped: recognizer not ready after ${waited}ms, will use file fallback")
            }
        } else {
            Logger.d(ctx, "VoiceInputHandler", "useStreaming=false asrMode=$asrMode")
        }

        try {
            FileOutputStream(file).use { os ->
                val header = ByteArray(44)
                os.write(header, 0, 44)

                var totalAudioLen = 0L
                var readCount = 0
                var lastReadLog = System.currentTimeMillis()
                while (isRecording) {
                    val read = audioRecord?.read(data, 0, data.size) ?: AudioRecord.ERROR_INVALID_OPERATION
                    val now = System.currentTimeMillis()
                    when {
                        read > 0 -> {
                            readCount++
                            // 每 50 次 read（约每秒一次）打一次日志，或有间隔超过 500ms 的卡顿
                            val gap = now - lastReadLog
                            if (readCount % 50 == 0 || gap > 500) {
                                Logger.d(ctx, "VoiceInputHandler", "read#$readCount: bytes=$read totalAudioLen=$totalAudioLen gap=${gap}ms")
                                lastReadLog = now
                            }
                            os.write(data, 0, read)
                            totalAudioLen += read

                            if (streamStarted) {
                                val currentText = LocalAsrService.acceptStreamingData(data, read)
                                if (!currentText.isNullOrEmpty()) {
                                    handler.post {
                                        if (isRecording && !isWannaCancel) {
                                            aiAssistant.showInputPanel(
                                                defaultText = currentText,
                                                mode = AiAssistant.MODE_RECORDING,
                                                
                                            ) {}
                                        }
                                    }
                                }
                            }
                        }

                        read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION -> {
                            Logger.d(ctx, "VoiceInputHandler", "AudioRecord read failed: $read readCount=$readCount totalAudioLen=$totalAudioLen")
                            break
                        }
                    }
                }

                Logger.d(ctx, "VoiceInputHandler", "recording loop ended: totalAudioLen=$totalAudioLen readCount=$readCount")
                updateWavHeader(file, totalAudioLen)
            }
        } catch (e: IOException) {
            Logger.d(ctx, "VoiceInputHandler", "writeAudioDataToFile failed: ${e.message}")
        }
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val channels = 1
        val byteRate = 16 * longSampleRate * channels / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = (totalDataLen shr 8 and 0xffL).toByte()
        header[6] = (totalDataLen shr 16 and 0xffL).toByte()
        header[7] = (totalDataLen shr 24 and 0xffL).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xffL).toByte()
        header[25] = (longSampleRate shr 8 and 0xffL).toByte()
        header[26] = (longSampleRate shr 16 and 0xffL).toByte()
        header[27] = (longSampleRate shr 24 and 0xffL).toByte()
        header[28] = (byteRate and 0xffL).toByte()
        header[29] = (byteRate shr 8 and 0xffL).toByte()
        header[30] = (byteRate shr 16 and 0xffL).toByte()
        header[31] = (byteRate shr 24 and 0xffL).toByte()
        header[32] = (1 * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = (totalAudioLen shr 8 and 0xffL).toByte()
        header[42] = (totalAudioLen shr 16 and 0xffL).toByte()
        header[43] = (totalAudioLen shr 24 and 0xffL).toByte()

        try {
            val randomAccessFile = java.io.RandomAccessFile(file, "rw")
            randomAccessFile.seek(0)
            randomAccessFile.write(header)
            randomAccessFile.close()
        } catch (e: Exception) {
            Logger.d(ctx, "VoiceInputHandler", "updateWavHeader failed: ${e.message}")
        }
    }

    private fun stopRecording(onFileReady: (File?) -> Unit) {
        clearPendingLongPress()

        if (!isRecording) {
            onFileReady(null)
            return
        }

        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        } finally {
            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }
            audioRecord = null
        }

        try {
            recordingThread?.join(500)
        } catch (_: Exception) {
        }
        recordingThread = null

        onAfterRecording?.invoke()

        val readyFile = audioFile?.takeIf { it.exists() && it.length() > 44 }
        onFileReady(readyFile)
    }

    fun release() {
        clearPendingLongPress()
        stopRecording { _ -> }
    }
}
