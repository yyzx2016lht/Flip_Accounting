package com.taostudio.tapaccounting

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

object ChatPdfRenderer {

    private const val MAX_RENDER_WIDTH = 1600
    private const val JPEG_QUALITY = 82

    /**
     * Renders PDF pages to JPEG bytes for vision APIs that only accept images.
     */
    fun renderPagesToJpeg(file: File, maxPages: Int): List<ByteArray> {
        require(maxPages > 0) { "maxPages must be positive" }
        if (!file.exists() || file.length() <= 0L) return emptyList()

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount <= 0) return emptyList()
                val pageCount = minOf(renderer.pageCount, maxPages)
                return (0 until pageCount).mapNotNull { index ->
                    renderer.openPage(index).use { page ->
                        renderPage(page)
                    }
                }
            }
        }
    }

    private fun renderPage(page: PdfRenderer.Page): ByteArray? {
        val pageWidth = page.width.coerceAtLeast(1)
        val pageHeight = page.height.coerceAtLeast(1)
        val scale = minOf(1f, MAX_RENDER_WIDTH.toFloat() / pageWidth.toFloat())
        val width = (pageWidth * scale).toInt().coerceAtLeast(1)
        val height = (pageHeight * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    return null
                }
                return output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
