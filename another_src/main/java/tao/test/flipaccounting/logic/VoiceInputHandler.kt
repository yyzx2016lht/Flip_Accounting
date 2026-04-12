package tao.test.flipaccounting.logic

import android.annotation.SuppressLint
import android.content.Context
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
import tao.test.flipaccounting.AIService
import tao.test.flipaccounting.AiAssistant
import tao.test.flipaccounting.Logger
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.Utils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class VoiceInputHandler(
    private val ctx: Context,
    private val aiAssistant: AiAssistant,
    private val isMultiModeProvider: () -> Boolean,
    private val onResult: (JSONObject) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isWannaCancel = false

    private val sampleRate = 16000 // Whisper 标准要求
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var baseText = ""

    fun setupVoiceButton(btnVoice: View) {
        btnVoice.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 检查麦克风权限
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            Utils.toast(ctx, "需要麦克风权限才能录制语音")
                            if (ctx is android.app.Activity) {
                                ctx.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
                            }
                            return@setOnTouchListener true
                        }
                    }

                    v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).start()
                    
                    // 记录原有文本
                    baseText = aiAssistant.getCurrentInputText()

                    // 2. 延迟判断长按
                    isWannaCancel = false
                    handler.postDelayed({
                        Utils.vibrate(ctx) 
                        
                        aiAssistant.showInputPanel(
                            mode = AiAssistant.MODE_RECORDING, 
                            isMultiMode = isMultiModeProvider()
                        ) { resultJson ->
                            onResult(resultJson)
                        }

                        try {
                            startRecording()
                        } catch (e: Exception) {
                            aiAssistant.dismiss()
                            Utils.toast(ctx, "录音启动失败")
                            isRecording = false
                        }
                    }, 200) // 200ms 作为触发阈值
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
                                    isMultiMode = isMultiModeProvider()
                                ) { onResult(it) }
                            }
                        } else {
                            if (isWannaCancel) {
                                isWannaCancel = false
                                Utils.vibrate(ctx, 10) 
                                aiAssistant.showInputPanel(
                                    mode = AiAssistant.MODE_RECORDING, 
                                    isMultiMode = isMultiModeProvider()
                                ) { onResult(it) }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    if (isRecording) {
                        if (isWannaCancel) {
                            stopRecording { /* discard */ }
                            tao.test.flipaccounting.LocalAsrService.finishStreaming()
                            aiAssistant.dismiss()
                            Utils.toast(ctx, "已取消")
                        } else {
                            stopRecording { file ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    val asrMode = Prefs.getAsrMode(ctx)
                                    val text = if (asrMode == Prefs.ASR_MODE_WHISPER) {
                                        val finalResult = tao.test.flipaccounting.LocalAsrService.finishStreaming()
                                        if (finalResult.isNullOrEmpty()) {
                                            tao.test.flipaccounting.LocalAsrService.speechToText(ctx, file)
                                        } else {
                                            finalResult
                                        }
                                    } else {
                                        AIService.speechToText(ctx, file)
                                    }

                                    withContext(Dispatchers.Main) {
                                        if (!text.isNullOrEmpty() && text != "WHISPER_NOT_SETUP" && text != "MODEL_DOWNLOADING") {
                                            // 语音转文字成功后，不再直接查询，而是回到输入框
                                            val finalText = if (baseText.isNotEmpty()) "$baseText $text" else text
                                            
                                            aiAssistant.showInputPanel(
                                                defaultText = finalText,
                                                mode = AiAssistant.MODE_INPUT,
                                                isMultiMode = isMultiModeProvider()
                                            ) { resultJson ->
                                                onResult(resultJson)
                                            }
                                        } else if (text == "MODEL_DOWNLOADING") {
                                            aiAssistant.dismiss()
                                            Utils.toast(ctx, "系统正在后台下载离线语音模型(约40M)，请稍后重试！")
                                        } else if (text == "WHISPER_NOT_SETUP") {
                                            aiAssistant.dismiss()
                                            Utils.toast(ctx, "离线语音模型尚未准备就绪，请检查网络或日志。")
                                        } else {
                                            aiAssistant.dismiss()
                                            Utils.toast(ctx, "未检测到清晰语音/解析失败")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) return
        audioFile = File(ctx.cacheDir, "voice_input.wav")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Logger.d(ctx, "VoiceInputHandler", "AudioRecord 初始化失败")
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        recordingThread = Thread {
            writeAudioDataToFile(audioFile!!)
        }
        recordingThread?.start()
    }

    private fun writeAudioDataToFile(file: File) {
        val data = ByteArray(bufferSize)
        
        val asrMode = Prefs.getAsrMode(ctx)
        val useStreaming = asrMode == Prefs.ASR_MODE_WHISPER
        var streamStarted = false
        if (useStreaming) {
            streamStarted = kotlinx.coroutines.runBlocking {
                tao.test.flipaccounting.LocalAsrService.startStreaming(ctx)
            }
        }

        try {
            val os = FileOutputStream(file)
            // 写入占位 WAV 头 (44 byte)
            val header = ByteArray(44)
            os.write(header, 0, 44)

            var totalAudioLen = 0L
            while (isRecording) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    os.write(data, 0, read)
                    totalAudioLen += read
                    
                    if (streamStarted) {
                        val currentText = tao.test.flipaccounting.LocalAsrService.acceptStreamingData(data, read)
                        if (!currentText.isNullOrEmpty()) {
                            handler.post {
                                if (isRecording && !isWannaCancel) {
                                    aiAssistant.showInputPanel(
                                        defaultText = currentText,
                                        mode = AiAssistant.MODE_RECORDING,
                                        isMultiMode = isMultiModeProvider()
                                    ) {}
                                }
                            }
                        }
                    }
                }
            }

            os.close()
            // 重新写真实的 WAV 头
            updateWavHeader(file, totalAudioLen)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val channels = 1
        val byteRate = 16 * longSampleRate * channels / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF/WAVE header
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
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1
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
        header[32] = (1 * 16 / 8).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
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
            e.printStackTrace()
        }
    }

    private fun stopRecording(onFileReady: (File) -> Unit) {
        if (!isRecording) return
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
        } finally {
            audioRecord?.release()
            audioRecord = null
        }
        
        recordingThread?.join(500)
        recordingThread = null

        audioFile?.let { if (it.exists() && it.length() > 44) onFileReady(it) }
    }

    fun release() {
        stopRecording { }
    }
}
