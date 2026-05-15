package com.taostudio.tapaccounting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 小票 OCR 识别帮助类
 * 支持两种模式：
 *   - OCR_MODE_LOCAL: 本地 ML Kit 提取文字 → 发给 AI 文本分析
 *   - OCR_MODE_MULTIMODAL: 直接将图片 Base64 发给多模态 AI
 */
object ReceiptOcrHelper {

    /**
     * 主入口：根据配置的 OCR 模式，分析一张图片并返回自然语言摘要（String）
     *
     * @param ctx        Context
     * @param imageUri   用户选择的图片 URI
     * @param onProgress 进度回调，用于更新 UI 提示
     */
    suspend fun analyzeImage(
        ctx: Context,
        imageUri: Uri,
        onProgress: (String) -> Unit = {}
    ): String {
        return analyzeByMultimodal(ctx, imageUri, onProgress)
    }

    suspend fun analyzeImageByMultimodal(
        ctx: Context,
        imageUri: Uri,
        onProgress: (String) -> Unit = {}
    ): String {
        return analyzeByMultimodal(ctx, imageUri, onProgress)
    }

    /**
     * 仅执行本地 OCR，返回原始文字，不调用 AI。
     * 供「预览模式」使用：OCR 完展示给用户确认，用户点发送后再调 AI。
     */
    suspend fun runOcrOnly(
        ctx: Context,
        imageUri: Uri,
        onProgress: (String) -> Unit = {}
    ): String {
        onProgress("正在本地识别图片文字...")
        val bitmap = loadBitmapFromUri(ctx, imageUri)
        val ocrText = recognizeTextFromBitmap(bitmap)
        if (ocrText.isBlank()) {
            throw IllegalArgumentException("未能从图片中识别到任何文字，请确保图片清晰且光线充足")
        }
        Logger.d(ctx, "ReceiptOcrHelper", "OCR-only done: textLen=${ocrText.length}")
        return ocrText
    }

    /**
     * 模式一：本地 ML Kit OCR → AI 文本分析（返回自然语言摘要）
     */
    private suspend fun analyzeByLocalOcr(
        ctx: Context,
        imageUri: Uri,
        onProgress: (String) -> Unit
    ): String {
        onProgress("正在本地识别图片文字...")

        // 1. 用 ML Kit 提取文本
        val bitmap = loadBitmapFromUri(ctx, imageUri)
        val ocrText = recognizeTextFromBitmap(bitmap)

        if (ocrText.isBlank()) {
            throw IllegalArgumentException("未能从图片中识别到任何文字，请确保图片清晰")
        }

        Logger.d(ctx, "ReceiptOcrHelper", "OCR done: textLen=${ocrText.length}")
        if (Prefs.isSaveOcrDebugEnabled(ctx)) {
            Prefs.addOcrDebugRecord(ctx, ocrText, source = "local_ocr_before_ai")
        }
        onProgress("文字识别完成，正在 AI 解析账单...")

        // 2. 将 OCR 文本发给 AI
        // 返回 String
        return AIService.analyzeReceiptByOcrText(ctx, ocrText)
    }

    /**
     * 模式二：多模态 AI（图片 Base64 直发）（返回自然语言摘要）
     */
    private suspend fun analyzeByMultimodal(
        ctx: Context,
        imageUri: Uri,
        onProgress: (String) -> Unit
    ): String {
        onProgress("正在压缩图片...")

        val bitmap = loadBitmapFromUri(ctx, imageUri)
        val base64 = bitmapToBase64(bitmap)

        onProgress("正在发送图片给 AI 分析...")

        // 返回 String
        return AIService.analyzeReceiptByImage(ctx, base64, "image/jpeg")
    }

    /**
     * 使用 ML Kit Chinese 识别器提取文字
     * ChineseTextRecognizerOptions 同时支持中文 + 拉丁字符，完美覆盖中英波兰语小票
     */
    private suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            // 中文识别器同时支持拉丁字符（波兰语/英文/数字），一个识别器搞定所有小票
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // 将识别到的文字按行整理
                    val sb = StringBuilder()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            sb.appendLine(line.text)
                        }
                        sb.appendLine() // 块之间加空行，便于 AI 理解分段
                    }
                    recognizer.close()
                    cont.resume(sb.toString().trim())
                }
                .addOnFailureListener { e ->
                    recognizer.close()
                    cont.resumeWithException(e)
                }

            cont.invokeOnCancellation { recognizer.close() }
        }

    /**
     * 从 URI 加载 Bitmap，并自动缩放到合理大小（避免内存爆炸）
     */
    private suspend fun loadBitmapFromUri(ctx: Context, uri: Uri): Bitmap =
        withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            // 计算合适的缩放比例（目标最长边 2048px，足够 OCR 精度）
            val maxDim = 2048
            val scale = maxOf(
                1,
                maxOf(
                    (options.outWidth + maxDim - 1) / maxDim,
                    (options.outHeight + maxDim - 1) / maxDim
                )
            )

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = scale
            }

            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: throw IllegalArgumentException("无法读取图片文件")
        }

    /**
     * 将 Bitmap 转为 Base64 字符串（用于多模态模式）
     * 压缩到 1024px 以内，质量 80%，避免请求体过大
     */
    private suspend fun bitmapToBase64(bitmap: Bitmap): String =
        withContext(Dispatchers.IO) {
            val maxDim = 1024
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = minOf(
                    maxDim.toFloat() / bitmap.width,
                    maxDim.toFloat() / bitmap.height
                )
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt(),
                    (bitmap.height * ratio).toInt(),
                    true
                )
            } else {
                bitmap
            }
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }
}

