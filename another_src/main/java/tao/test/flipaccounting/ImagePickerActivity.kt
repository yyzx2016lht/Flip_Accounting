package tao.test.flipaccounting

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

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
                onImagePicked?.invoke(uri)
            } else {
                onPickCancelled?.invoke()
            }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
