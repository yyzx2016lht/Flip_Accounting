package tao.test.flipaccounting

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
object LocalAsrService {
    private var sherpaRecognizer: OfflineRecognizer? = null
    private var isDownloading = false
    @Volatile private var cancelDownload = false

    // SenseVoice 模型下载地址 (Int8 轻量级量化版本，下载约 45M，解压约 145M，大幅降低存储压力)

    private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    private const val MODEL_DIR_NAME = "sherpa-onnx-sense-voice"
    private const val EXTRACTED_FOLDER_NAME = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"

    fun installLocalModelWithUI(ctx: Context, uri: android.net.Uri, onComplete: () -> Unit = {}) {
        val targetDir = File(ctx.filesDir, MODEL_DIR_NAME)
        cancelDownload = false
        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("导入本地模型")
            .setMessage("正在准备解压...")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> cancelDownload = true }
            .create()
        dialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) { dialog.setMessage("正在读取文件...") }
                val inputStream = ctx.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        Utils.toast(ctx, "无法读取该文件")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { dialog.setMessage("正在解压模型文件，请稍候...") }
                targetDir.mkdirs()

                BufferedInputStream(inputStream).use { bis ->
                    BZip2CompressorInputStream(bis).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry = tarIn.nextTarEntry
                            while (entry != null) {
                                if (cancelDownload) break
                                val destPath = File(targetDir, entry.name)
                                if (entry.isDirectory) {
                                    destPath.mkdirs()
                                } else {
                                    destPath.parentFile?.mkdirs()
                                    FileOutputStream(destPath).use { out ->
                                        val buffer = ByteArray(8192)
                                        var len: Int
                                        while (tarIn.read(buffer).also { len = it } != -1) {
                                            if (cancelDownload) break
                                            out.write(buffer, 0, len)
                                        }
                                    }
                                }
                                entry = tarIn.nextTarEntry
                            }
                        }
                    }
                }

                if (cancelDownload) {
                    targetDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        Utils.toast(ctx, "已取消导入")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { dialog.setMessage("正在初始化模型...") }
                initModel(ctx)

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Utils.toast(ctx, "离线模型准备完毕！现在可以使用了")
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                targetDir.deleteRecursively()
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Utils.toast(ctx, "导入失败: ${e.message}")
                }
            }
        }
    }

    // 检查模型是否已存在
    fun isModelReady(ctx: Context): Boolean {
        val modelDir = File(ctx.filesDir, MODEL_DIR_NAME)
        val extractedFolder = File(modelDir, EXTRACTED_FOLDER_NAME)
        val onnxFile = File(extractedFolder, "model.int8.onnx")
        val altOnnxFile = File(extractedFolder, "model.onnx")
        val tokensFile = File(extractedFolder, "tokens.txt")
        // onnx 和 tokens.txt 必须同时存在，缺少 tokens.txt 会导致 native 层 abort 崩溃
        return (onnxFile.exists() || altOnnxFile.exists()) && tokensFile.exists()
    }

    fun deleteModel(ctx: Context) {
        val modelDir = File(ctx.filesDir, MODEL_DIR_NAME)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        sherpaRecognizer?.release()
        sherpaRecognizer = null
    }

    /**
     * 核心识别方法（支持传入 WAV PCM 16kHz 文件）
     */
    suspend fun speechToText(ctx: Context, audioFile: File): String? {
        // 1. 尝试初始化模型
        if (sherpaRecognizer == null) {
            val success = initModel(ctx)
            if (!success) {
                return if (isDownloading) "MODEL_DOWNLOADING" else "WHISPER_NOT_SETUP"
            }
        }

        // 2. 识别音频文件
        return withContext(Dispatchers.IO) {
            try {
                val recognizer = sherpaRecognizer ?: return@withContext null
                val stream = recognizer.createStream()

                val fis = FileInputStream(audioFile)
                val buf = ByteArray(4096)
                // 简单的 WAV 头部跳过 (44 字节)
                fis.skip(44)

                var nbytes: Int
                val floatList = mutableListOf<Float>()
                while (fis.read(buf).also { nbytes = it } > 0) {
                    // PCM 16bit 转 Float
                    for (i in 0 until nbytes step 2) {
                        val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                        val floatSample = sample.toShort().toFloat() / 32768.0f
                        floatList.add(floatSample)
                    }
                }
                fis.close()

                stream.acceptWaveform(floatList.toFloatArray(), 16000)
                recognizer.decode(stream)

                val text = recognizer.getResult(stream).text
                stream.release()

                if (text.isNotEmpty()) text else null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * 初始化下载/加载模型
     */
    private suspend fun initModel(ctx: Context): Boolean {
        val modelDir = File(ctx.filesDir, MODEL_DIR_NAME)
        val extractedFolder = File(modelDir, EXTRACTED_FOLDER_NAME)
        
        // 优先找 model.int8.onnx，如果没有再找 model.onnx
        var onnxFile = File(extractedFolder, "model.int8.onnx")
        if (!onnxFile.exists()) {
            onnxFile = File(extractedFolder, "model.onnx")
        }
        val tokensFile = File(extractedFolder, "tokens.txt")

        // 两个文件都必须存在且可读，否则 native 层会 abort 崩溃
        if (!onnxFile.exists() || !tokensFile.exists()) {
            Logger.d(ctx, "LocalAsrService", "模型文件不完整: onnx=${onnxFile.exists()}, tokens=${tokensFile.exists()}, 路径=${extractedFolder.absolutePath}")
            if (!isDownloading) {
                downloadModelWithProgress(ctx, modelDir, {_,_->}, {}, {})
            }
            return false
        }
        if (!onnxFile.canRead() || !tokensFile.canRead()) {
            Logger.d(ctx, "LocalAsrService", "模型文件无读取权限: onnx=${onnxFile.canRead()}, tokens=${tokensFile.canRead()}")
            return false
        }

        if (onnxFile.exists() && tokensFile.exists()) {
            return try {
                val modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = onnxFile.absolutePath,
                        language = "",
                        useInverseTextNormalization = true
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = 2,
                    debug = false
                )

                val config = OfflineRecognizerConfig(
                    modelConfig = modelConfig
                )

                // 必须传 assetManager = null：
                // AAR 内部字节码判断：null → newFromFile（按绝对路径读取）
                //                    非 null → newFromAsset（按 assets 相对路径读取）
                // 模型文件在 filesDir 下，必须用绝对路径，所以传 null。
                sherpaRecognizer = OfflineRecognizer(assetManager = null, config = config)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        // 如果不存在则尝试后台下载
        if (!isDownloading) {
            downloadModelWithProgress(ctx, modelDir, {_,_->}, {}, {})
        }
        return false
    }

    /**
     * SenseVoice (即 Offline 等非流式模型) 不完全适用于实时流式输入。
     * 但我们可以通过累积数据块到内存中并在结束时统一识别来模拟。
     */
    private val stFloatList = mutableListOf<Float>()
    
    suspend fun startStreaming(ctx: Context): Boolean {
        if (sherpaRecognizer == null) {
            val success = initModel(ctx)
            if (!success) return false
        }
        stFloatList.clear()
        return true
    }

    fun acceptStreamingData(data: ByteArray, length: Int): String? {
        // 由于 SenseVoice 主要针对的是完整句子（离线），在实际的流式传输期间
        // 若强行切片可能出现截断效果。
        // 为保持性能，我们在 accept 阶段只累积数据，在 finish 时出结果。
        for (i in 0 until length step 2) {
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            stFloatList.add(sample.toShort().toFloat() / 32768.0f)
        }
        return null // 暂不返回局部结果
    }

    fun finishStreaming(): String? {
        val rec = sherpaRecognizer ?: return null
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(stFloatList.toFloatArray(), 16000)
            rec.decode(stream)
            
            val text = rec.getResult(stream).text
            stream.release()
            
            stFloatList.clear()
            if (text.isNotBlank()) text else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun downloadModelWithUI(ctx: Context, onComplete: () -> Unit = {}) {
        val targetDir = File(ctx.filesDir, MODEL_DIR_NAME)
        cancelDownload = false
        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("下载阿里 SenseVoice 模型")
            .setMessage("正在连接...")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> cancelDownload = true }
            .create()
        dialog.show()
        downloadModelWithProgress(ctx, targetDir,
            onProgress = { progress, msg ->
                dialog.setMessage(msg)
            },
            onComplete = {
                dialog.dismiss()
                Utils.toast(ctx, "离线模型准备完毕！现在可以使用了")    
                onComplete()
            },
            onError = {
                dialog.dismiss()
                Utils.toast(ctx, "下载失败: $it")
            }
        )
    }

    fun downloadModelWithProgress(
        ctx: Context,
        targetDir: File = File(ctx.filesDir, MODEL_DIR_NAME),
        onProgress: (Int, String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isDownloading) {
            onError("正在下载中，请稍候...")
            return
        }
        isDownloading = true
        CoroutineScope(Dispatchers.IO).launch {
            val tarFile = File(ctx.cacheDir, "sense_voice.tar.bz2")
            try {
                // 1. 下载
                Logger.d(ctx, "LocalAsrService", "开始下载模型文件...")
                withContext(Dispatchers.Main) { onProgress(0, "正在连接...") }    
                val url = URL(MODEL_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                val fileLength = connection.contentLength
                val input = BufferedInputStream(url.openStream())
                val output = FileOutputStream(tarFile)
                val data = ByteArray(8192)
                var count: Int
                var total = 0L

                var lastProgress = 0
                while (input.read(data).also { count = it } != -1) {
                    if (cancelDownload) {
                        break
                    }
                    total += count
                    output.write(data, 0, count)
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        if (progress > lastProgress) {
                            lastProgress = progress
                            withContext(Dispatchers.Main) { onProgress(progress, "正在下载模型... $progress%") }
                        }
                    }
                }
                if (cancelDownload) {
                    output.flush()
                    output.close()
                    input.close()
                    tarFile.delete()
                    isDownloading = false
                    withContext(Dispatchers.Main) { onError("取消下载") }
                    return@launch
                }
                output.flush()
                output.close()
                input.close()

                // 2. 解压 tar.bz2
                Logger.d(ctx, "LocalAsrService", "开始解压模型...")
                withContext(Dispatchers.Main) { onProgress(100, "正在解压模型文件，请稍候...") }                                                                                  
                targetDir.mkdirs()
                
                FileInputStream(tarFile).use { fis ->
                    BufferedInputStream(fis).use { bis ->
                        BZip2CompressorInputStream(bis).use { bzIn ->
                            TarArchiveInputStream(bzIn).use { tarIn ->
                                var entry = tarIn.nextTarEntry
                                while (entry != null) {
                                    if (cancelDownload) break
                                    val destPath = File(targetDir, entry.name)
                                    if (entry.isDirectory) {
                                        destPath.mkdirs()
                                    } else {
                                        destPath.parentFile?.mkdirs()
                                        FileOutputStream(destPath).use { out ->
                                            val buffer = ByteArray(8192)
                                            var len: Int
                                            while (tarIn.read(buffer).also { len = it } != -1) {
                                                if (cancelDownload) break
                                                out.write(buffer, 0, len)
                                            }
                                        }
                                    }
                                    entry = tarIn.nextTarEntry
                                }
                            }
                        }
                    }
                }
                tarFile.delete()

                if (cancelDownload) {
                    targetDir.deleteRecursively()
                    isDownloading = false
                    withContext(Dispatchers.Main) { onError("已取消解压") }
                    return@launch
                }

                // 3. 初始化
                withContext(Dispatchers.Main) { onProgress(100, "正在初始化模型...") }                                                                                                  
                initModel(ctx)
                Logger.d(ctx, "LocalAsrService", "SenseVoice 模型初始化成功")

                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                Logger.d(ctx, "LocalAsrService", "下载/解压失败: ${e.message}") 
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "未知错误")
                }
            } finally {
                isDownloading = false
            }
        }
    }
}