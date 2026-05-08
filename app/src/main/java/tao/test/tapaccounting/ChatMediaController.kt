package tao.test.tapaccounting

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

class ChatMediaController(
    private val context: ChatActivity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val tvAiNameProvider: () -> TextView,
    private val ivAiAvatarProvider: () -> ImageView,
    private val ivChatBgProvider: () -> ImageView,
    private val adapterProvider: () -> RecyclerView.Adapter<*>,
    private val ensureAiImageFeatureEnabled: () -> Boolean,
    private val showPageCenterDialog: (AlertDialog, Float) -> Unit,
    private val updateConversationSubtitle: () -> Unit,
    private val appendUserMessage: (String, Int, String) -> Unit,
    private val callAiAccounting: (String, Boolean) -> Unit,
    private val appendAiTextMessage: (String, Boolean) -> Unit,
    private val reqPickImage: Int,
    private val reqPickBg: Int,
    private val reqCropBg: Int,
    private val reqPickAiAvatar: Int,
    private val reqPickUserAvatar: Int,
    private val reqCropAiAvatar: Int,
    private val reqCropUserAvatar: Int,
    private val msgTypeUserImage: Int
) {
    private var pendingEditAiAvatarView: ImageView? = null
    private val maxAiImageBytes = 4L * 1024L * 1024L
    private val maxOcrCharsForRouting = 1200

    fun showEditAiProfileDialog() {
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_edit_ai_profile, null)
        val avatarContainer = view.findViewById<android.view.View>(R.id.layout_ai_profile_avatar)
        val ivAvatar = view.findViewById<ImageView>(R.id.iv_ai_profile_avatar)
        val etName = view.findViewById<android.widget.EditText>(R.id.et_ai_profile_name)
        val etIdentity = view.findViewById<android.widget.EditText>(R.id.et_ai_profile_identity)

        val currentName = Prefs.getAiChatName(context).ifBlank { "小计" }
        etName.setText(currentName)
        etName.setSelection(currentName.length)
        etIdentity.setText(Prefs.getAiChatIdentity(context))

        val avatarPath = Prefs.getAiChatAvatarPath(context)
        if (avatarPath.isNotBlank()) {
            Glide.with(context)
                .load(Uri.fromFile(File(avatarPath)))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .circleCrop()
                .placeholder(R.drawable.ic_ai_default_avatar)
                .into(ivAvatar)
        } else {
            ivAvatar.setImageResource(R.drawable.ic_ai_default_avatar)
        }

        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
            .setTitle("编辑 AI 资料")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                Prefs.setAiChatName(context, etName.text?.toString()?.trim().orEmpty().ifBlank { "小计" })
                Prefs.setAiChatIdentity(context, etIdentity.text?.toString()?.trim().orEmpty())
                refreshAiProfile()
            }
            .create()

        val pickAiAvatar = android.view.View.OnClickListener {
            pendingEditAiAvatarView = ivAvatar
            dialog.dismiss()
            openImagePicker(reqPickAiAvatar, "选择 AI 头像")
        }
        avatarContainer.setOnClickListener(pickAiAvatar)
        ivAvatar.setOnClickListener(pickAiAvatar)

        showPageCenterDialog(dialog, 0.88f)
    }

    fun pickImage() {
        if (!ensureAiImageFeatureEnabled()) return
        context.startActivityForResult(Intent(Intent.ACTION_PICK).apply { type = "image/*" }, reqPickImage)
    }

    fun pickBgImage() {
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_chat_bg_setting, null)
        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
            .setView(view)
            .create()

        view.findViewById<TextView>(R.id.item_bg_pick_image).setOnClickListener {
            dialog.dismiss()
            openImagePicker(reqPickBg, "选择聊天背景")
        }
        view.findViewById<TextView>(R.id.item_bg_reset_default).setOnClickListener {
            Prefs.setAiChatBgPath(context, "")
            Glide.with(context).clear(ivChatBgProvider())
            ivChatBgProvider().visibility = android.view.View.INVISIBLE
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.btn_bg_setting_cancel).setOnClickListener {
            dialog.dismiss()
        }
        showPageCenterDialog(dialog, 0.9f)
    }

    fun refreshAiProfile() {
        tvAiNameProvider().text = Prefs.getAiChatName(context).ifEmpty { "小计" }
        updateConversationSubtitle()
        val avatarPath = Prefs.getAiChatAvatarPath(context)
        if (avatarPath.isNotBlank()) {
            GlideLocalFiles.load(
                target = ivAiAvatarProvider(),
                file = File(avatarPath),
                placeholderRes = R.drawable.ic_ai_default_avatar,
                circleCrop = true,
                overrideSize = 128
            )
        } else {
            ivAiAvatarProvider().setImageResource(R.drawable.ic_ai_default_avatar)
        }
    }

    fun applyBackground() {
        val path = Prefs.getAiChatBgPath(context)
        if (path.isBlank()) {
            Glide.with(context).clear(ivChatBgProvider())
            ivChatBgProvider().visibility = android.view.View.INVISIBLE
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Prefs.setAiChatBgPath(context, "")
            Glide.with(context).clear(ivChatBgProvider())
            ivChatBgProvider().visibility = android.view.View.INVISIBLE
            return
        }
        ivChatBgProvider().visibility = android.view.View.VISIBLE
        Glide.with(context).clear(ivChatBgProvider())
        GlideLocalFiles.load(
            target = ivChatBgProvider(),
            file = file,
            diskCacheStrategy = DiskCacheStrategy.NONE,
            skipMemoryCache = true
        )
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == reqCropAiAvatar || requestCode == reqCropUserAvatar || requestCode == reqCropBg) {
                val error = data?.let { UCrop.getError(it) }
                if (error != null) {
                    val label = if (requestCode == reqCropBg) "背景裁剪失败" else "头像裁剪失败"
                    Utils.toast(context, "$label: ${error.message ?: "未知错误"}")
                }
            }
            return requestCode in setOf(
                reqPickImage, reqPickBg, reqCropBg, reqPickAiAvatar, reqPickUserAvatar, reqCropAiAvatar, reqCropUserAvatar
            )
        }

        when (requestCode) {
            reqPickImage -> {
                val uri = data?.data ?: return true
                handlePickedImage(uri)
                return true
            }
            reqPickBg -> {
                val uri = data?.data ?: return true
                showPickedImagePreview(
                    sourceUri = uri,
                    title = "背景预览",
                    hint = "确认后进入裁剪（按设备比例）"
                ) { confirmedUri ->
                    startBackgroundCrop(confirmedUri)
                }
                return true
            }
            reqCropBg -> {
                val uri = data?.let { UCrop.getOutput(it) } ?: return true
                saveAndApplyBackground(uri)
                return true
            }
            reqPickAiAvatar -> {
                val uri = data?.data ?: return true
                showPickedImagePreview(
                    sourceUri = uri,
                    title = "AI 头像预览",
                    hint = "确认后进入 1:1 裁剪"
                ) { confirmedUri ->
                    startAvatarCrop(confirmedUri, isAiAvatar = true)
                }
                return true
            }
            reqPickUserAvatar -> {
                val uri = data?.data ?: return true
                showPickedImagePreview(
                    sourceUri = uri,
                    title = "用户头像预览",
                    hint = "确认后进入 1:1 裁剪"
                ) { confirmedUri ->
                    startAvatarCrop(confirmedUri, isAiAvatar = false)
                }
                return true
            }
            reqCropAiAvatar -> {
                val uri = data?.let { UCrop.getOutput(it) } ?: return true
                saveAiAvatar(uri)
                return true
            }
            reqCropUserAvatar -> {
                val uri = data?.let { UCrop.getOutput(it) } ?: return true
                saveUserAvatar(uri)
                return true
            }
        }
        return false
    }

    private fun startAvatarCrop(sourceUri: Uri, isAiAvatar: Boolean) {
        val destFile = File(
            context.cacheDir,
            "avatar_crop/${if (isAiAvatar) "ai" else "user"}_${System.currentTimeMillis()}.jpg"
        ).also { it.parentFile?.mkdirs() }
        val destUri = Uri.fromFile(destFile)
        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(92)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(false)
            setShowCropGrid(true)
            setShowCropFrame(true)
            setToolbarTitle(if (isAiAvatar) "裁剪 AI 头像" else "裁剪用户头像")
            setToolbarColor(Color.parseColor("#1A73E8"))
            setStatusBarColor(Color.parseColor("#1A73E8"))
            setToolbarWidgetColor(Color.WHITE)
            setDimmedLayerColor(Color.parseColor("#AA000000").toInt())
        }
        val intent = UCrop.of(sourceUri, destUri)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(1080, 1080)
            .withOptions(options)
            .getIntent(context)
        startForResultNoAnim(intent, if (isAiAvatar) reqCropAiAvatar else reqCropUserAvatar)
    }

    private fun startBackgroundCrop(sourceUri: Uri) {
        val destFile = File(
            context.cacheDir,
            "bg_crop/bg_${System.currentTimeMillis()}.jpg"
        ).also { it.parentFile?.mkdirs() }
        val destUri = Uri.fromFile(destFile)
        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(92)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(false)
            setShowCropGrid(true)
            setShowCropFrame(true)
            setToolbarTitle("裁剪聊天背景")
            setToolbarColor(Color.parseColor("#1A73E8"))
            setStatusBarColor(Color.parseColor("#1A73E8"))
            setToolbarWidgetColor(Color.WHITE)
            setDimmedLayerColor(Color.parseColor("#AA000000").toInt())
        }
        val dm = context.resources.displayMetrics
        val targetW = dm.widthPixels.coerceAtLeast(1).toFloat()
        val targetH = dm.heightPixels.coerceAtLeast(1).toFloat()
        val intent = UCrop.of(sourceUri, destUri)
            .withAspectRatio(targetW, targetH)
            .withMaxResultSize(2160, 2160)
            .withOptions(options)
            .getIntent(context)
        startForResultNoAnim(intent, reqCropBg)
    }

    private fun startForResultNoAnim(intent: Intent, requestCode: Int) {
        val options = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        context.startActivityForResult(intent, requestCode, options)
    }

    private fun openImagePicker(requestCode: Int, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        context.startActivityForResult(Intent.createChooser(intent, chooserTitle), requestCode)
    }

    private fun showPickedImagePreview(
        sourceUri: Uri,
        title: String,
        hint: String,
        onConfirm: (Uri) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_chat_image_preview, null)
        val tvTitle = view.findViewById<TextView>(R.id.tv_pick_preview_title)
        val ivPreview = view.findViewById<ImageView>(R.id.iv_pick_preview_image)
        val tvHint = view.findViewById<TextView>(R.id.tv_pick_preview_hint)
        val btnCancel = view.findViewById<TextView>(R.id.btn_pick_preview_cancel)
        val btnOk = view.findViewById<TextView>(R.id.btn_pick_preview_ok)

        tvTitle.text = title
        tvHint.text = hint
        Glide.with(context)
            .load(sourceUri)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .into(ivPreview)

        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
            .setView(view)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnOk.setOnClickListener {
            dialog.dismiss()
            onConfirm(sourceUri)
        }
        showPageCenterDialog(dialog, 0.9f)
    }

    private fun saveAndApplyBackground(uri: Uri) {
        runCatching {
            val bgDir = File(context.filesDir, "chat_bg").also { it.mkdirs() }
            val oldPath = Prefs.getAiChatBgPath(context)
            val destFile = File(bgDir, "chat_bg_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setAiChatBgPath(context, destFile.absolutePath)
            if (oldPath.isNotBlank() && oldPath != destFile.absolutePath) {
                val oldFile = File(oldPath)
                if (oldFile.exists() && oldFile.parentFile?.absolutePath == bgDir.absolutePath) {
                    oldFile.delete()
                }
            }
            applyBackground()
            Utils.toast(context, "背景已更新")
        }.onFailure {
            Utils.toast(context, "背景更新失败: ${it.message ?: "未知错误"}")
        }
    }

    private fun saveAiAvatar(uri: Uri) {
        runCatching {
            val destFile = File(context.filesDir, "chat_ai_avatar.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setAiChatAvatarPath(context, destFile.absolutePath)
            pendingEditAiAvatarView?.let { iv ->
                GlideLocalFiles.load(
                    target = iv,
                    file = destFile,
                    placeholderRes = R.drawable.ic_ai_default_avatar,
                    circleCrop = true,
                    overrideSize = 128
                )
            }
            refreshAiProfile()
            Utils.toast(context, "AI 头像已更新")
        }.onFailure {
            Utils.toast(context, "AI 头像更新失败: ${it.message ?: "未知错误"}")
        }
    }

    private fun saveUserAvatar(uri: Uri) {
        runCatching {
            val destFile = File(context.filesDir, "chat_user_avatar.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setUserChatAvatarPath(context, destFile.absolutePath)
            adapterProvider().notifyDataSetChanged()
            Utils.toast(context, "用户头像已更新")
        }.onFailure {
            Utils.toast(context, "用户头像更新失败: ${it.message ?: "未知错误"}")
        }
    }

    private fun handlePickedImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val (storedUri, base64, mime) = withContext(Dispatchers.IO) {
                    val sourceMime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val stableUri = copyPickedImageToStorage(uri, sourceMime)
                    val stableFile = File(stableUri.path ?: "")
                    if (stableFile.length() > maxAiImageBytes) {
                        compressImageInPlace(stableFile)
                    }
                    if (stableFile.length() > maxAiImageBytes) {
                        throw IOException("图片过大，请裁剪或压缩后再试")
                    }
                    val stream = context.contentResolver.openInputStream(stableUri)
                        ?: return@withContext Triple(Uri.EMPTY, "", sourceMime)
                    val bytes = stream.readBytes()
                    stream.close()
                    Triple(
                        stableUri,
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                        sourceMime
                    )
                }
                if (base64.isBlank()) return@launch
                appendUserMessage("", msgTypeUserImage, storedUri.toString())

                val text = "[MULTIMODAL_IMAGE]$base64|$mime"
                callAiAccounting(text, false)
            } catch (e: Exception) {
                appendAiTextMessage("图片处理失败，请稍后重试或换一张更清晰的图片。", false)
            }
        }
    }

    private fun compressImageInPlace(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return
        FileOutputStream(file, false).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
        bitmap.recycle()
    }

    private fun copyPickedImageToStorage(sourceUri: Uri, sourceMime: String): Uri {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(sourceMime)
            ?.lowercase(Locale.getDefault())
            ?.ifBlank { null }
            ?: "jpg"
        val imageDir = File(context.filesDir, "chat_images").also { it.mkdirs() }
        val outFile = File(imageDir, "chat_img_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(sourceUri)?.use { ins ->
            FileOutputStream(outFile).use { outs -> ins.copyTo(outs) }
        } ?: throw IOException("无法读取图片")
        return Uri.fromFile(outFile)
    }
}
