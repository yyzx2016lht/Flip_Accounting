package tao.test.flipaccounting

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object LocalAsrService {
    private var sherpaRecognizer: OfflineRecognizer? = null
    private var isDownloading = false
    @Volatile private var cancelDownload = false
    @Volatile private var isInitializing = false
    @Volatile private var switchToMirrorRequested = false
    @Volatile private var slowPromptShown = false
    private val initMutex = Mutex()

    private const val MODEL_URL_GITHUB =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    private const val MODEL_MIRROR_BASE =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"
    private const val MODEL_DIR_NAME = "sherpa-onnx-sense-voice"
    private const val EXTRACTED_FOLDER_NAME = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    private const val SLOW_SWITCH_MS = 60_000L
    private const val SLOW_SWITCH_PROGRESS = 10

    private const val MIN_ONNX_BYTES = 5L * 1024L * 1024L
    private const val MIN_TOKENS_BYTES = 16L

    @Volatile private var lastInitError: String? = null

    private data class ModelFiles(
        val dir: File,
        val onnx: File,
        val tokens: File
    )

    private enum class DownloadSource {
        GITHUB_ARCHIVE,
        MIRROR_FILES;

        fun toPrefValue(): String = when (this) {
            GITHUB_ARCHIVE -> "github"
            MIRROR_FILES -> "mirror"
        }

        companion object {
            fun fromPrefValue(value: String?): DownloadSource = when (value) {
                "mirror" -> MIRROR_FILES
                else -> GITHUB_ARCHIVE
            }
        }
    }

    fun getLastInitError(): String? = lastInitError

    fun isRecognizerReady(): Boolean = sherpaRecognizer != null

    private fun pickOnnxFile(dir: File): File? {
        val int8 = File(dir, "model.int8.onnx")
        if (int8.exists()) return int8
        val fp32 = File(dir, "model.onnx")
        if (fp32.exists()) return fp32
        return null
    }

    private fun isModelFilePlausible(file: File, minBytes: Long): Boolean {
        return file.isFile && file.canRead() && file.length() >= minBytes
    }

    private fun hasModelFiles(dir: File): Boolean {
        val tokens = File(dir, "tokens.txt")
        val onnx = pickOnnxFile(dir)
        return onnx != null && tokens.exists()
    }

    private fun findModelFolder(modelDir: File): File? {
        if (!modelDir.exists() || !modelDir.isDirectory) return null

        val scanTargets = LinkedHashSet<File>()
        val defaultFolder = File(modelDir, EXTRACTED_FOLDER_NAME)
        if (defaultFolder.exists()) scanTargets += defaultFolder
        scanTargets += modelDir

        for (dir in modelDir.walkTopDown().maxDepth(6)) {
            if (dir.isDirectory) scanTargets += dir
        }

        var bestDir: File? = null
        var bestScore = Long.MIN_VALUE

        for (dir in scanTargets) {
            if (!dir.isDirectory || !hasModelFiles(dir)) continue

            val onnx = pickOnnxFile(dir) ?: continue
            val tokens = File(dir, "tokens.txt")
            val plausibilityBonus =
                (if (isModelFilePlausible(onnx, MIN_ONNX_BYTES)) 2_000_000_000L else 0L) +
                (if (isModelFilePlausible(tokens, MIN_TOKENS_BYTES)) 1_000_000_000L else 0L)
            val score = plausibilityBonus + onnx.length().coerceAtLeast(0L) + tokens.length().coerceAtLeast(0L)

            if (score > bestScore) {
                bestScore = score
                bestDir = dir
            }
        }

        return bestDir
    }

    private fun resolveModelFiles(modelDir: File): ModelFiles? {
        val folder = findModelFolder(modelDir) ?: return null
        val onnx = pickOnnxFile(folder) ?: return null
        val tokens = File(folder, "tokens.txt")
        if (!tokens.exists()) return null
        return ModelFiles(folder, onnx, tokens)
    }

    private fun dumpModelDirTree(ctx: Context, modelDir: File) {
        if (!modelDir.exists()) {
            Logger.d(ctx, "LocalAsrService", "modelDir does not exist: ${modelDir.absolutePath}")
            return
        }
        Logger.d(ctx, "LocalAsrService", "=== modelDir tree start ===")
        for (f in modelDir.walkTopDown().maxDepth(6)) {
            val relative = f.absolutePath.removePrefix(modelDir.absolutePath)
            val depth = relative.count { it == '/' || it == '\\' }
            val indent = "  ".repeat(depth)
            val tag = if (f.isDirectory) "[DIR]" else "[FILE]"
            val size = if (f.isFile) " (${f.length()}B)" else ""
            Logger.d(ctx, "LocalAsrService", "$indent$tag ${f.name}$size")
        }
        Logger.d(ctx, "LocalAsrService", "=== modelDir tree end ===")
    }

    private fun sanitizeEntryName(rawName: String): String {
        return rawName
            .replace('\\', '/')
            .trimStart('/')
            .removePrefix("./")
    }

    private fun resolveDestPath(targetDir: File, entryName: String): File? {
        if (entryName.isBlank()) return null
        val base = targetDir.canonicalFile
        val dest = File(base, entryName).canonicalFile
        val basePath = base.path + File.separator
        val inside = dest.path == base.path || dest.path.startsWith(basePath)
        return if (inside) dest else null
    }

    private fun extractTarBz2(ctx: Context, source: InputStream, targetDir: File) {
        BufferedInputStream(source).use { bis ->
            BZip2CompressorInputStream(bis).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        if (cancelDownload) break

                        val entryName = sanitizeEntryName(entry.name)
                        val destPath = resolveDestPath(targetDir, entryName)
                        if (destPath == null) {
                            Logger.d(ctx, "LocalAsrService", "Skip suspicious tar entry: ${entry.name}")
                            entry = tarIn.nextTarEntry
                            continue
                        }

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

    fun installLocalModelWithUI(ctx: Context, uri: android.net.Uri, onComplete: () -> Unit = {}) {
        val targetDir = File(ctx.filesDir, MODEL_DIR_NAME)
        val importArchive = File(ctx.cacheDir, "sense_voice_import.tar.bz2")
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
                        Utils.toast(ctx, "无法读取所选文件")
                    }
                    return@launch
                }

                importArchive.delete()
                inputStream.use { ins ->
                    FileOutputStream(importArchive).use { out ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        var total = 0L
                        while (ins.read(buffer).also { len = it } != -1) {
                            if (cancelDownload) break
                            out.write(buffer, 0, len)
                            total += len
                        }
                        out.flush()
                        if (total <= 0L) {
                            throw IllegalStateException("archive is empty")
                        }
                    }
                }

                if (cancelDownload) {
                    importArchive.delete()
                    targetDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        Utils.toast(ctx, "已取消导入")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { dialog.setMessage("正在解压模型文件，请稍候...") }
                targetDir.deleteRecursively()
                targetDir.mkdirs()

                FileInputStream(importArchive).use { fis ->
                    extractTarBz2(ctx, fis, targetDir)
                }
                importArchive.delete()

                if (cancelDownload) {
                    targetDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        Utils.toast(ctx, "已取消导入")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { dialog.setMessage("正在初始化模型...") }
                val ok = initModel(ctx, allowAutoDownload = false)

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    if (ok) {
                        Utils.toast(ctx, "离线语音模型已准备完成")
                        onComplete()
                    } else {
                        Utils.toast(ctx, "导入完成但初始化失败: ${lastInitError ?: "未知错误"}")
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                importArchive.delete()
                targetDir.deleteRecursively()
                lastInitError = "导入失败: ${e.message ?: e.javaClass.simpleName}"
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Utils.toast(ctx, lastInitError ?: "导入失败")
                }
            }
        }
    }

    fun isModelReady(ctx: Context): Boolean {
        val modelDir = File(ctx.filesDir, MODEL_DIR_NAME)
        val files = resolveModelFiles(modelDir) ?: return false
        return isModelFilePlausible(files.onnx, MIN_ONNX_BYTES) &&
            isModelFilePlausible(files.tokens, MIN_TOKENS_BYTES)
    }

    fun deleteModel(ctx: Context) {
        val modelDir = File(ctx.filesDir, MODEL_DIR_NAME)
        if (modelDir.exists()) modelDir.deleteRecursively()
        sherpaRecognizer?.release()
        sherpaRecognizer = null
        isInitializing = false
        lastInitError = null
    }

    suspend fun speechToText(ctx: Context, audioFile: File): String? {
        if (sherpaRecognizer == null) {
            val success = initModel(ctx)
            if (!success) {
                return if (isDownloading) "MODEL_DOWNLOADING" else "WHISPER_NOT_SETUP"
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                val recognizer = sherpaRecognizer ?: return@withContext null
                val stream = recognizer.createStream()

                val floatList = mutableListOf<Float>()
                FileInputStream(audioFile).use { fis ->
                    fis.skip(44)
                    val buf = ByteArray(4096)
                    var nbytes: Int
                    while (fis.read(buf).also { nbytes = it } > 0) {
                        var i = 0
                        while (i + 1 < nbytes) {
                            val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                            floatList.add(sample.toShort().toFloat() / 32768.0f)
                            i += 2
                        }
                    }
                }

                stream.acceptWaveform(floatList.toFloatArray(), 16000)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text
                stream.release()

                if (text.isNotBlank()) text else null
            } catch (e: Throwable) {
                e.printStackTrace()
                lastInitError = "识别失败: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
    }

    private fun formatInitError(e: Throwable): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val type = e.javaClass.simpleName
        return if (e is UnsatisfiedLinkError) {
            "离线识别库加载失败($type): $raw"
        } else if (e is NoClassDefFoundError || e is ClassNotFoundException) {
            "离线识别类缺失($type): $raw"
        } else {
            "离线模型初始化失败($type): $raw"
        }
    }

    private suspend fun initModel(ctx: Context, allowAutoDownload: Boolean = true): Boolean {
        val modelDir = File(ctx.filesDir, MODEL_DIR_NAME)

        val files = resolveModelFiles(modelDir)
        if (files == null) {
            lastInitError = "未找到离线模型文件"
            Logger.d(ctx, "LocalAsrService", "Model folder not found under: ${modelDir.absolutePath}")
            dumpModelDirTree(ctx, modelDir)

            if (allowAutoDownload && !isDownloading) {
                downloadModelWithProgress(
                    ctx = ctx,
                    targetDir = modelDir,
                    onProgress = { _, _ -> },
                    onComplete = {},
                    onError = { err -> lastInitError = "模型下载失败: $err" }
                )
            }
            return false
        }

        val onnxOk = isModelFilePlausible(files.onnx, MIN_ONNX_BYTES)
        val tokensOk = isModelFilePlausible(files.tokens, MIN_TOKENS_BYTES)
        if (!onnxOk || !tokensOk) {
            lastInitError = "模型文件不完整: onnx=${files.onnx.length()}B, tokens=${files.tokens.length()}B"
            Logger.d(ctx, "LocalAsrService", lastInitError ?: "Model files invalid")
            dumpModelDirTree(ctx, files.dir)

            if (allowAutoDownload && !isDownloading) {
                modelDir.deleteRecursively()
                downloadModelWithProgress(
                    ctx = ctx,
                    targetDir = modelDir,
                    onProgress = { _, _ -> },
                    onComplete = {},
                    onError = { err -> lastInitError = "模型下载失败: $err" }
                )
            }
            return false
        }

        return initMutex.withLock {
            if (sherpaRecognizer != null) {
                Logger.d(ctx, "LocalAsrService", "initModel: already initialized, skip")
                return@withLock true
            }

            isInitializing = true
            val t0 = System.currentTimeMillis()
            Logger.d(ctx, "LocalAsrService", "initModel: start loading model from ${files.dir.absolutePath}")
            Logger.d(ctx, "LocalAsrService", "initModel: onnx=${files.onnx.name}(${files.onnx.length()}B) tokens=${files.tokens.name}(${files.tokens.length()}B)")
            try {
                val modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = files.onnx.absolutePath,
                        language = "",
                        useInverseTextNormalization = true
                    ),
                    tokens = files.tokens.absolutePath,
                    numThreads = 2,
                    debug = false
                )

                val config = OfflineRecognizerConfig(modelConfig = modelConfig)
                sherpaRecognizer = OfflineRecognizer(assetManager = null, config = config)

                val elapsed = System.currentTimeMillis() - t0
                lastInitError = null
                Logger.d(ctx, "LocalAsrService", "initModel success: elapsed=${elapsed}ms path=${files.dir.absolutePath}")
                true
            } catch (e: Throwable) {
                val elapsed = System.currentTimeMillis() - t0
                e.printStackTrace()
                lastInitError = formatInitError(e)
                val cause = e.cause?.toString() ?: "no cause"
                Logger.d(
                    ctx,
                    "LocalAsrService",
                    "initModel failed: elapsed=${elapsed}ms [${e.javaClass.simpleName}] ${e.message} | cause=$cause"
                )
                false
            } finally {
                isInitializing = false
            }
        }
    }

    private val streamSamples = mutableListOf<Float>()

    /**
     * 在后台提前加载 recognizer（不阻塞调用线程）。
     * 应在用户按下录音按钮时立即调用，使 initModel 的 2 秒耗时与"长按等待 200ms + 用户开口"并发执行。
     */
    fun warmUp(ctx: Context) {
        if (sherpaRecognizer != null || isInitializing || isDownloading) return
        Logger.d(ctx, "LocalAsrService", "warmUp: triggering background initModel")
        CoroutineScope(Dispatchers.IO).launch {
            initModel(ctx, allowAutoDownload = false)
        }
    }

    suspend fun startStreaming(ctx: Context): Boolean {
        if (sherpaRecognizer == null) {
            Logger.d(ctx, "LocalAsrService", "startStreaming: recognizer null, calling initModel...")
            val t0 = System.currentTimeMillis()
            val success = initModel(ctx)
            val elapsed = System.currentTimeMillis() - t0
            Logger.d(ctx, "LocalAsrService", "startStreaming: initModel done success=$success elapsed=${elapsed}ms")
            if (!success) return false
        } else {
            Logger.d(ctx, "LocalAsrService", "startStreaming: recognizer already initialized")
        }
        if (sherpaRecognizer == null) return false
        streamSamples.clear()
        return true
    }

    fun acceptStreamingData(data: ByteArray, length: Int): String? {
        var i = 0
        while (i + 1 < length) {
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            streamSamples.add(sample.toShort().toFloat() / 32768.0f)
            i += 2
        }
        return null
    }

    fun finishStreaming(): String? {
        val rec = sherpaRecognizer ?: return null
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(streamSamples.toFloatArray(), 16000)
            rec.decode(stream)

            val text = rec.getResult(stream).text
            stream.release()

            streamSamples.clear()
            if (text.isNotBlank()) text else null
        } catch (e: Throwable) {
            e.printStackTrace()
            lastInitError = "娴佸紡璇嗗埆澶辫触: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    fun downloadModelWithUI(ctx: Context, onComplete: () -> Unit = {}) {
        val targetDir = File(ctx.filesDir, MODEL_DIR_NAME)
        cancelDownload = false
        switchToMirrorRequested = false
        slowPromptShown = false

        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("下载离线语音模型")
            .setMessage("正在连接...")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> cancelDownload = true }
            .create()
        dialog.show()

        downloadModelWithProgress(
            ctx = ctx,
            targetDir = targetDir,
            onProgress = { _, msg -> dialog.setMessage(msg) },
            onSlowGithub = { progress, requestSwitch ->
                if (slowPromptShown) return@downloadModelWithProgress
                slowPromptShown = true
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("下载较慢")
                    .setMessage("当前 GitHub 下载 1 分钟仅完成 ${progress}%，可能对国内用户较慢。是否切换到非 GitHub 镜像源继续下载？")
                    .setPositiveButton("切换") { _, _ -> requestSwitch() }
                    .setNegativeButton("继续等待", null)
                    .show()
            },
            onComplete = {
                dialog.dismiss()
                Utils.toast(ctx, "离线语音模型已准备完成")
                onComplete()
            },
            onError = { err ->
                dialog.dismiss()
                Utils.toast(ctx, "下载失败: $err")
            }
        )
    }

    fun downloadModelWithProgress(
        ctx: Context,
        targetDir: File = File(ctx.filesDir, MODEL_DIR_NAME),
        onProgress: (Int, String) -> Unit,
        onSlowGithub: ((Int, () -> Unit) -> Unit)? = null,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isDownloading) {
            onError("正在下载中，请稍候")
            return
        }

        isDownloading = true
        CoroutineScope(Dispatchers.IO).launch {
            val tarFile = File(ctx.cacheDir, "sense_voice.tar.bz2")
            val extractedDir = File(targetDir, EXTRACTED_FOLDER_NAME)
            val preferredSource = DownloadSource.fromPrefValue(Prefs.getAsrDownloadSource(ctx))

            try {
                withContext(Dispatchers.Main) { onProgress(0, "正在连接...") }

                val sourceUsed = downloadWithFallbackStrategy(
                    ctx = ctx,
                    tarFile = tarFile,
                    extractedDir = extractedDir,
                    preferredSource = preferredSource,
                    onProgress = onProgress,
                    onSlowGithub = onSlowGithub
                )

                if (cancelDownload) {
                    tarFile.delete()
                    extractedDir.deleteRecursively()
                    withContext(Dispatchers.Main) { onError("已取消下载") }
                    return@launch
                }

                if (sourceUsed == DownloadSource.GITHUB_ARCHIVE) {
                    withContext(Dispatchers.Main) { onProgress(100, "正在解压模型文件，请稍候...") }
                    targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    FileInputStream(tarFile).use { fis ->
                        extractTarBz2(ctx, fis, targetDir)
                    }
                    tarFile.delete()
                } else {
                    targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    val finalDir = File(targetDir, EXTRACTED_FOLDER_NAME)
                    finalDir.deleteRecursively()
                    extractedDir.copyRecursively(finalDir, overwrite = true)
                }

                if (cancelDownload) {
                    targetDir.deleteRecursively()
                    withContext(Dispatchers.Main) { onError("已取消解压") }
                    return@launch
                }

                withContext(Dispatchers.Main) { onProgress(100, "正在初始化模型...") }
                val ok = initModel(ctx, allowAutoDownload = false)
                if (!ok) {
                    val msg = lastInitError ?: "模型初始化失败"
                    withContext(Dispatchers.Main) { onError(msg) }
                    return@launch
                }

                Prefs.setAsrDownloadSource(ctx, sourceUsed.toPrefValue())
                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Throwable) {
                e.printStackTrace()
                lastInitError = e.message ?: e.javaClass.simpleName
                targetDir.deleteRecursively()
                tarFile.delete()
                extractedDir.deleteRecursively()
                withContext(Dispatchers.Main) {
                    onError(lastInitError ?: "未知错误")
                }
            } finally {
                isDownloading = false
                switchToMirrorRequested = false
                slowPromptShown = false
            }
        }
    }

    private suspend fun downloadWithFallbackStrategy(
        ctx: Context,
        tarFile: File,
        extractedDir: File,
        preferredSource: DownloadSource,
        onProgress: (Int, String) -> Unit,
        onSlowGithub: ((Int, () -> Unit) -> Unit)?
    ): DownloadSource {
        switchToMirrorRequested = false
        tarFile.delete()
        extractedDir.deleteRecursively()

        return if (preferredSource == DownloadSource.MIRROR_FILES) {
            runCatching {
                withContext(Dispatchers.Main) { onProgress(0, "正在连接上次成功的镜像源...") }
                downloadMirrorFiles(extractedDir, onProgress)
                DownloadSource.MIRROR_FILES
            }.getOrElse {
                if (cancelDownload) return DownloadSource.MIRROR_FILES
                extractedDir.deleteRecursively()
                withContext(Dispatchers.Main) { onProgress(0, "镜像源暂不可用，正在切换 GitHub...") }
                val githubDone = downloadGithubArchive(
                    tarFile = tarFile,
                    onProgress = onProgress,
                    onSlowGithub = onSlowGithub
                )
                if (githubDone || cancelDownload) DownloadSource.GITHUB_ARCHIVE
                else {
                    withContext(Dispatchers.Main) { onProgress(0, "GitHub 较慢，正在切换非 GitHub 镜像源...") }
                    downloadMirrorFiles(extractedDir, onProgress)
                    DownloadSource.MIRROR_FILES
                }
            }
        } else {
            val githubDone = downloadGithubArchive(
                tarFile = tarFile,
                onProgress = onProgress,
                onSlowGithub = onSlowGithub
            )
            if (githubDone || cancelDownload) {
                DownloadSource.GITHUB_ARCHIVE
            } else {
                withContext(Dispatchers.Main) { onProgress(0, "正在切换非 GitHub 镜像源...") }
                downloadMirrorFiles(extractedDir, onProgress)
                DownloadSource.MIRROR_FILES
            }
        }
    }

    private suspend fun downloadGithubArchive(
        tarFile: File,
        onProgress: (Int, String) -> Unit,
        onSlowGithub: ((Int, () -> Unit) -> Unit)?
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val connection = (URL(MODEL_URL_GITHUB).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            connect()
        }

        val totalLength = connection.contentLengthLong
        connection.inputStream.use { input ->
            BufferedInputStream(input).use { bis ->
                FileOutputStream(tarFile).use { output ->
                    val data = ByteArray(8192)
                    var count: Int
                    var total = 0L
                    var lastProgress = -1

                    while (bis.read(data).also { count = it } != -1) {
                        if (cancelDownload) return false
                        output.write(data, 0, count)
                        total += count

                        if (totalLength > 0) {
                            val progress = ((total * 100) / totalLength).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) {
                                    onProgress(progress, "正在下载模型... $progress%")
                                }
                            }
                            if (!slowPromptShown &&
                                onSlowGithub != null &&
                                System.currentTimeMillis() - startTime >= SLOW_SWITCH_MS &&
                                progress < SLOW_SWITCH_PROGRESS
                            ) {
                                withContext(Dispatchers.Main) {
                                    onSlowGithub.invoke(progress) { switchToMirrorRequested = true }
                                }
                                slowPromptShown = true
                            }
                        }

                        if (switchToMirrorRequested) {
                            output.flush()
                            tarFile.delete()
                            return false
                        }
                    }
                }
            }
        }
        return true
    }

    private suspend fun downloadMirrorFiles(
        extractedDir: File,
        onProgress: (Int, String) -> Unit
    ) {
        extractedDir.deleteRecursively()
        extractedDir.mkdirs()

        val files = listOf(
            "model.int8.onnx" to File(extractedDir, "model.int8.onnx"),
            "tokens.txt" to File(extractedDir, "tokens.txt")
        )

        var totalBytes = 0L
        files.forEach { (name, _) ->
            val conn = (URL(MODEL_MIRROR_BASE + name).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 120_000
                connect()
            }
            val len = conn.contentLengthLong.coerceAtLeast(0L)
            if (len > 0) totalBytes += len
            conn.disconnect()
        }

        var downloaded = 0L
        var lastProgress = -1
        files.forEach { (name, target) ->
            val conn = (URL(MODEL_MIRROR_BASE + name).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                connect()
            }
            conn.inputStream.use { input ->
                BufferedInputStream(input).use { bis ->
                    BufferedOutputStream(FileOutputStream(target)).use { output ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        while (bis.read(buffer).also { count = it } != -1) {
                            if (cancelDownload) return
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (totalBytes > 0) {
                                val progress = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress, "正在从镜像源下载模型... $progress%")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
