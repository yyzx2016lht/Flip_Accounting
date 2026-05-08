package tao.test.tapaccounting

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.File

/**
 * 透明中间人 Activity，专门用于从 Service/OverlayManager 中触发系统图片选择器。
 * Service 无法直接 startActivityForResult，通过此 Activity 做桥接。
 *
 * 使用方式：
 *   OverlayManager 通过广播或接口回调通知本 Activity，
 *   本 Activity 拿到图片 URI 后通过静态回调传回给 OverlayManager。
 */
class ImagePickerActivity : Activity() {

    companion object {
        private const val REQUEST_PICK_IMAGE = 1001

        /** 图片选择结果回调（由 OverlayManager 注册） */
        var onImagePicked: ((Uri) -> Unit)? = null
        var onPickCancelled: (() -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明窗口，不显示任何界面
        openImagePicker()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "选择小票图片"), REQUEST_PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGE) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                val stableUri = runCatching { copyImageToCache(uri) }
                    .getOrElse { err ->
                        err.printStackTrace()
                        Toast.makeText(this, "读取图片失败，请重试", Toast.LENGTH_SHORT).show()
                        onPickCancelled?.invoke()
                        finish()
                        return
                    }
                onImagePicked?.invoke(stableUri)
            } else {
                onPickCancelled?.invoke()
            }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun copyImageToCache(sourceUri: Uri): Uri {
        val input = contentResolver.openInputStream(sourceUri)
            ?: throw IllegalArgumentException("无法打开图片流")
        val imageDir = File(cacheDir, "picked_images").apply { mkdirs() }
        val outFile = File(imageDir, "receipt_${System.currentTimeMillis()}.jpg")

        input.use { ins ->
            outFile.outputStream().use { outs ->
                ins.copyTo(outs)
            }
        }
        return Uri.fromFile(outFile)
    }
}
