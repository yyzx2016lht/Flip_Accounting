package tao.test.flipaccounting

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.ChatMessage
import tao.test.flipaccounting.data.local.entity.AiRule
import tao.test.flipaccounting.data.repository.CategoryRepository
import tao.test.flipaccounting.logic.BillAssetImpactService
import tao.test.flipaccounting.logic.BillMutationService
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.logic.RuleDialogHelper
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs

class ChatActivity : AppCompatActivity() {

    companion object {
        const val MSG_TYPE_USER_TEXT = 0
        const val MSG_TYPE_USER_IMAGE = 1
        const val MSG_TYPE_USER_VOICE = 2
        const val MSG_TYPE_AI_TEXT = 3
        const val MSG_TYPE_AI_BILL = 4

        const val EXTRA_SOURCE_BOOK = "extra_source_book"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        private const val EXTRA_SCROLL_TO_MSG_ID = "scroll_to_msg_id"

        private const val REQ_PICK_IMAGE = 101
        private const val REQ_PICK_BG = 102
        private const val REQ_PICK_AI_AVATAR = 103
        private const val REQ_PICK_USER_AVATAR = 104
        private const val REQ_CROP_AI_AVATAR = 105
        private const val REQ_CROP_USER_AVATAR = 106
        private const val DEPRECATED_BILL_IDS_PREFIX = "__deprecated__:"
        private const val VOICE_PAYLOAD_V2_PREFIX = "__voice_v2__:"
    }

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: android.widget.EditText
    private lateinit var btnSend: ImageView
    private lateinit var btnMore: ImageView
    private lateinit var btnMoreInput: ImageView
    private lateinit var btnVoiceToggle: ImageView
    private lateinit var btnVoiceHold: com.google.android.material.button.MaterialButton
    private lateinit var tvAiName: TextView
    private lateinit var tvAiModel: TextView
    private lateinit var ivAiAvatar: ImageView
    private lateinit var ivChatBg: ImageView
    private lateinit var btnSwitchModel: TextView
    private lateinit var chatRoot: View
    private lateinit var bottomBar: View
    private lateinit var drawerSessions: DrawerLayout
    private lateinit var drawerContainer: View
    private lateinit var etSessionSearch: android.widget.EditText
    private lateinit var btnNewSession: TextView
    private lateinit var btnReplyStyle: TextView
    private lateinit var btnChangeChatBg: TextView
    private lateinit var btnClearCurrentSession: TextView
    private lateinit var rvSessionList: RecyclerView
    private lateinit var tvVoiceModelHint: TextView
    private lateinit var tvVoiceRecordOverlay: TextView
    private lateinit var layoutVoiceSelectionBar: LinearLayout
    private lateinit var tvVoiceSelectionCount: TextView
    private lateinit var btnVoiceSelectionCancel: TextView
    private lateinit var btnVoiceSelectionDelete: TextView
    private lateinit var layoutChatInputRow: View

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val adapter by lazy { ChatAdapter() }
    private val sessionAdapter by lazy {
        SessionListAdapter(
            onClick = { row ->
                currentBookName = row.bookName
                currentConversationId = row.conversationId
                drawerSessions.closeDrawer(GravityCompat.END)
                loadHistoryMessages()
            },
            onRename = { row, newTitle -> renameSessionInline(row, newTitle) },
            onDelete = { row -> showDeleteSessionDialog(row) }
        )
    }
    private val searchResultAdapter by lazy {
        DrawerSearchResultAdapter { msg ->
            pendingScrollToMessageId = msg.id
            currentBookName = msg.bookName.ifBlank { currentBookName }
            currentConversationId = msg.conversationId.ifBlank { currentConversationId }
            drawerSessions.closeDrawer(GravityCompat.END)
            loadHistoryMessages()
        }
    }
    private val displayMessages = mutableListOf<ChatDisplayItem>()
    private val allSessionRows = mutableListOf<ChatSessionRow>()
    private var drawerSearchJob: Job? = null

    private var currentBookName: String = BookAccountManager.DEFAULT_BOOK
    private var currentConversationId: String = ""
    private var pendingScrollToMessageId: Long = -1L
    private var pendingEditAiAvatarView: ImageView? = null
    private val deprecatedBillMessageIds = mutableSetOf<Long>()
    private var pendingHabitSuggestion: HabitRuleSuggestion? = null
    private var isVoiceMode = false
    private var isVoiceSelectionMode = false
    private val selectedVoiceMessageIds = mutableSetOf<Long>()
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private var isWannaCancel = false
    private var isFingerDown = false
    private var longPressTriggered = false
    private var pendingLongPressRunnable: Runnable? = null
    private val voiceHandler = Handler(Looper.getMainLooper())
    private var recordingStartAt = 0L
    private var currentPlayingPath: String? = null
    private var pausedVoicePath: String? = null
    private var pausedVoicePositionMs: Int = 0
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFocusGranted = false
    private var audioSupportProbeJob: Job? = null
    private val pendingVoiceBubbleAnimations = mutableSetOf<String>()
    private val pendingTranscriptRevealAnimations = mutableSetOf<String>()
    private var inlineAmountEditingBillId: Long? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioBufferSize by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(sampleRate * 2)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        currentBookName = resolveEntryBookName(intent)
        pendingScrollToMessageId = intent?.getLongExtra(EXTRA_SCROLL_TO_MSG_ID, -1L) ?: -1L

        bindViews()
        setupToolbar()
        setupRecyclerView()
        setupSessionDrawer()
        setupInput()
        setupKeyboardInsets()
        setupFallbackVoiceUi()
        refreshAiProfile()
        applyBackground()

        lifecycleScope.launch {
            bootstrapConversationState()
            loadHistoryMessages()
        }
    }

    private fun bindViews() {
        rvMessages = findViewById(R.id.rv_chat_messages)
        etInput = findViewById(R.id.et_chat_input)
        btnSend = findViewById(R.id.btn_chat_send)
        btnMore = findViewById(R.id.btn_chat_more)
        btnMoreInput = findViewById(R.id.btn_chat_more_input)
        btnVoiceToggle = findViewById(R.id.btn_voice_toggle)
        btnVoiceHold = findViewById(R.id.btn_voice_hold)
        tvAiName = findViewById(R.id.tv_ai_name)
        tvAiModel = findViewById(R.id.tv_ai_model)
        ivAiAvatar = findViewById(R.id.iv_ai_avatar)
        ivChatBg = findViewById(R.id.iv_chat_bg)
        btnSwitchModel = findViewById(R.id.btn_switch_model)
        chatRoot = findViewById(R.id.chat_root)
        bottomBar = findViewById(R.id.layout_chat_bottom)
        drawerSessions = findViewById(R.id.drawer_chat_sessions)
        drawerContainer = findViewById(R.id.layout_session_drawer_container)
        etSessionSearch = findViewById(R.id.et_session_search)
        btnNewSession = findViewById(R.id.btn_new_session)
        btnReplyStyle = findViewById(R.id.btn_reply_style)
        btnChangeChatBg = findViewById(R.id.btn_change_chat_bg)
        btnClearCurrentSession = findViewById(R.id.btn_clear_current_session)
        rvSessionList = findViewById(R.id.rv_session_list)
        tvVoiceModelHint = findViewById(R.id.tv_voice_model_hint)
        layoutChatInputRow = findViewById(R.id.layout_chat_input_row)
        tvVoiceRecordOverlay = findViewById(R.id.tv_voice_record_overlay)
        layoutVoiceSelectionBar = findViewById(R.id.layout_voice_selection_bar)
        tvVoiceSelectionCount = findViewById(R.id.tv_voice_selection_count)
        btnVoiceSelectionCancel = findViewById(R.id.btn_voice_selection_cancel)
        btnVoiceSelectionDelete = findViewById(R.id.btn_voice_selection_delete)
    }

    private fun setupToolbar() {
        findViewById<ImageView>(R.id.btn_chat_back).setOnClickListener { finish() }
        btnMore.setOnClickListener { showSessionPanel() }
        btnSwitchModel.setOnClickListener { showModelSwitchDialog() }
        ivAiAvatar.setOnClickListener { showEditAiProfileDialog() }
        findViewById<View>(R.id.layout_ai_name_click).setOnClickListener { showEditAiProfileDialog() }
    }

    private fun setupSessionDrawer() {
        drawerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
            width = (resources.displayMetrics.widthPixels * 0.76f).toInt()
        }
        rvSessionList.layoutManager = LinearLayoutManager(this)
        rvSessionList.adapter = sessionAdapter

        etSessionSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                drawerSearchJob?.cancel()
                val keyword = s?.toString().orEmpty().trim()
                if (keyword.isBlank()) {
                    lifecycleScope.launch {
                        refreshSessionRows()
                        rvSessionList.adapter = sessionAdapter
                        sessionAdapter.submit(allSessionRows.toList())
                    }
                    return
                }
                drawerSearchJob = lifecycleScope.launch {
                    delay(250)
                    val results = withContext(Dispatchers.IO) {
                        db.chatMessageDao().searchByBook(currentBookName, "%$keyword%")
                    }
                    rvSessionList.adapter = searchResultAdapter
                    searchResultAdapter.submit(results)
                }
            }
        })

        btnNewSession.setOnClickListener {
            startNewConversation()
            drawerSessions.closeDrawer(GravityCompat.END)
        }
        btnReplyStyle.setOnClickListener { showReplyStyleDialog() }
        btnChangeChatBg.setOnClickListener {
            drawerSessions.closeDrawer(GravityCompat.END)
            pickBgImage()
        }
        btnClearCurrentSession.setOnClickListener {
            drawerSessions.closeDrawer(GravityCompat.END)
            confirmClearHistory()
        }
    }

    private fun setupRecyclerView() {
        rvMessages.layoutManager = LinearLayoutManager(this)
        rvMessages.adapter = adapter
        rvMessages.itemAnimator = null
        rvMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && !isInlineAmountEditing()) ensureLastMessageVisible()
        }
    }

    private fun setupInput() {
        btnSend.setOnClickListener { sendText() }
        btnMoreInput.setOnClickListener { pickImage() }
        etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) ensureLastMessageVisible()
        }
        etInput.setOnClickListener { ensureLastMessageVisible() }
        etInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                updateInputActionUi()
            }
        })
        updateInputActionUi()
        btnVoiceSelectionCancel.setOnClickListener { exitVoiceSelectionMode() }
        btnVoiceSelectionDelete.setOnClickListener { deleteSelectedVoiceMessages() }
    }

    private fun setupFallbackVoiceUi() {
        btnVoiceToggle.setOnClickListener { toggleVoiceMode() }
        btnVoiceHold.setOnTouchListener { _, event -> handleVoiceButtonTouch(event) }
        updateVoiceModeUi()
        refreshVoiceSupportHint()
    }

    private fun toggleVoiceMode() {
        if (!isVoiceMode && !ensureAiVoiceFeatureEnabled()) return
        isVoiceMode = !isVoiceMode
        updateVoiceModeUi()
        if (isVoiceMode) {
            etInput.clearFocus()
        }
        refreshVoiceSupportHint()
    }

    private fun updateVoiceModeUi() {
        etInput.visibility = if (isVoiceMode) View.GONE else View.VISIBLE
        btnVoiceHold.visibility = if (isVoiceMode) View.VISIBLE else View.GONE
        btnVoiceToggle.setImageResource(if (isVoiceMode) android.R.drawable.ic_menu_edit else R.drawable.ic_mic)
        btnVoiceToggle.setColorFilter(if (isVoiceMode) Color.parseColor("#5E5E5E") else Color.parseColor("#5E5E5E"))
        btnVoiceHold.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F2F3F5"))
        btnVoiceHold.text = "按住说话，松开发送"
        updateInputActionUi()
    }

    private fun updateInputActionUi() {
        val hasText = etInput.text?.toString()?.trim()?.isNotEmpty() == true
        if (isVoiceMode) {
            btnSend.visibility = View.GONE
            btnMoreInput.visibility = View.VISIBLE
            btnSend.alpha = 0.4f
            return
        }
        btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
        btnMoreInput.visibility = if (hasText) View.GONE else View.VISIBLE
        btnSend.alpha = if (hasText) 1f else 0.4f
    }

    private fun startRecordingButtonPulse() {
        btnVoiceHold.animate().cancel()
        btnVoiceHold.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.92f).setDuration(180).withEndAction {
            if (isRecording && !isWannaCancel) {
                btnVoiceHold.animate().scaleX(1.02f).scaleY(1.02f).alpha(1f).setDuration(180).withEndAction {
                    if (isRecording && !isWannaCancel) startRecordingButtonPulse()
                }.start()
            }
        }.start()
    }

    private fun stopRecordingButtonPulse() {
        btnVoiceHold.animate().cancel()
        btnVoiceHold.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
    }

    private fun showVoiceRecordOverlay(isCancelState: Boolean) {
        tvVoiceRecordOverlay.text = if (isCancelState) "松开取消" else "松开发送，上滑取消"
        tvVoiceRecordOverlay.setBackgroundResource(R.drawable.bg_msg_popup_menu)
        tvVoiceRecordOverlay.animate().cancel()
        if (tvVoiceRecordOverlay.visibility != View.VISIBLE) {
            tvVoiceRecordOverlay.alpha = 0f
            tvVoiceRecordOverlay.translationY = 12f
            tvVoiceRecordOverlay.visibility = View.VISIBLE
        }
        tvVoiceRecordOverlay.animate().alpha(1f).translationY(0f).setDuration(140).start()
    }

    private fun hideVoiceRecordOverlay() {
        if (tvVoiceRecordOverlay.visibility != View.VISIBLE) return
        tvVoiceRecordOverlay.animate().cancel()
        tvVoiceRecordOverlay.animate().alpha(0f).translationY(12f).setDuration(120).withEndAction {
            tvVoiceRecordOverlay.visibility = View.GONE
        }.start()
    }

    private fun handleVoiceButtonTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!ensureAiVoiceFeatureEnabled()) return true
                if (!ensureRecordPermission()) return true
                clearPendingLongPress()
                LocalAsrService.resetStreamingBuffer()
                isFingerDown = true
                isWannaCancel = false
                btnVoiceHold.animate().scaleX(1.03f).scaleY(1.03f).setDuration(80).start()
                longPressTriggered = true
                Utils.vibrate(this, 10)
                val started = startVoiceRecording()
                if (!started) {
                    isRecording = false
                    hideVoiceRecordOverlay()
                    Utils.toast(this, "录音启动失败")
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRecording) {
                    val shouldCancel = event.y < -150f
                    if (shouldCancel != isWannaCancel) {
                        isWannaCancel = shouldCancel
                        Utils.vibrate(this, if (shouldCancel) 30 else 10)
                        btnVoiceHold.text = if (shouldCancel) "松开取消" else "松开发送"
                        showVoiceRecordOverlay(shouldCancel)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                clearPendingLongPress()
                isFingerDown = false
                btnVoiceHold.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                if (!isRecording) return true
                if (isRecording) {
                    if (isWannaCancel) {
                        stopVoiceRecording { file, _ ->
                            file?.delete()
                            LocalAsrService.resetStreamingBuffer()
                            runOnUiThread { btnVoiceHold.text = "按住说话，松开发送" }
                        }
                        Utils.toast(this, "已取消")
                    } else {
                        val holdDurationMs = (System.currentTimeMillis() - recordingStartAt).coerceAtLeast(0L)
                        stopVoiceRecording { file, durationSec ->
                            runOnUiThread { btnVoiceHold.text = "按住说话，松开发送" }
                            if (holdDurationMs < 450L) {
                                file?.delete()
                                Utils.toast(this, "按住稍久一点再说话")
                                return@stopVoiceRecording
                            }
                            if (file == null) {
                                runOnUiThread { Utils.toast(this, "未检测到清晰语音") }
                                return@stopVoiceRecording
                            }
                            onVoiceRecorded(file, durationSec)
                        }
                    }
                }
                hideVoiceRecordOverlay()
                longPressTriggered = false
                return true
            }
        }
        return false
    }

    private fun ensureRecordPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
            Utils.toast(this, "需要麦克风权限才能录音")
        }
        return granted
    }

    private fun ensureAiVoiceFeatureEnabled(): Boolean {
        if (Prefs.isShowAiVoice(this)) return true
        AlertDialog.Builder(this)
            .setTitle("请先开启语音记账")
            .setMessage("你在 AI 对话里发送语音前，需要先到设置中心开启“语音记账”功能。")
            .setPositiveButton("去开启") { _, _ ->
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_TAB_INDEX, 3)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            .setNegativeButton("取消", null)
            .show()
        return false
    }

    private fun ensureAiImageFeatureEnabled(): Boolean {
        if (Prefs.isShowAiImage(this)) return true
        AlertDialog.Builder(this)
            .setTitle("请先开启图片记账")
            .setMessage("你在 AI 对话里发送图片前，需要先到设置中心开启“图片记账”功能。")
            .setPositiveButton("去开启") { _, _ ->
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_TAB_INDEX, 3)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            .setNegativeButton("取消", null)
            .show()
        return false
    }

    private fun clearPendingLongPress() {
        pendingLongPressRunnable?.let { voiceHandler.removeCallbacks(it) }
        pendingLongPressRunnable = null
    }

    private fun showEditAiProfileDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_ai_profile, null)
        val ivAvatar = view.findViewById<ImageView>(R.id.iv_ai_profile_avatar)
        val etName = view.findViewById<android.widget.EditText>(R.id.et_ai_profile_name)

        val currentName = Prefs.getAiChatName(this).ifBlank { "小计" }
        etName.setText(currentName)
        etName.setSelection(currentName.length)

        val avatarPath = Prefs.getAiChatAvatarPath(this)
        if (avatarPath.isNotBlank()) {
            Glide.with(this)
                .load(Uri.fromFile(File(avatarPath)))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .circleCrop()
                .placeholder(R.drawable.ic_ai_default_avatar)
                .into(ivAvatar)
        } else {
            ivAvatar.setImageResource(R.drawable.ic_ai_default_avatar)
        }

        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_FlipAccounting))
            .setTitle("编辑 AI 资料")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                Prefs.setAiChatName(this, etName.text?.toString()?.trim().orEmpty().ifBlank { "小计" })
                refreshAiProfile()
            }
            .create()

        ivAvatar.setOnClickListener {
            pendingEditAiAvatarView = ivAvatar
            startActivityForResult(Intent(Intent.ACTION_PICK).apply { type = "image/*" }, REQ_PICK_AI_AVATAR)
        }

        dialog.show()
    }

    private fun setupKeyboardInsets() {
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        val initialBottomPadding = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(chatRoot) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = ime.bottom > 0
            val extra = if (imeVisible) 0 else nav.bottom
            bottomBar.setPadding(
                bottomBar.paddingLeft,
                bottomBar.paddingTop,
                bottomBar.paddingRight,
                initialBottomPadding + extra
            )
            if (imeVisible && !isInlineAmountEditing()) ensureLastMessageVisible()
            insets
        }
    }

    private suspend fun bootstrapConversationState() {
        currentBookName = BookAccountManager.normalizeBookName(currentBookName)
        db.chatMessageDao().migrateLegacyBookAndConversation(currentBookName, "legacy")

        val fromIntentConversation = intent?.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty().trim()
        if (fromIntentConversation.isNotEmpty()) {
            currentConversationId = fromIntentConversation
            return
        }

        if (pendingScrollToMessageId > 0L) {
            val msg = db.chatMessageDao().getById(pendingScrollToMessageId)
            if (msg != null) {
                if (msg.bookName.isNotBlank()) currentBookName = msg.bookName
                if (msg.conversationId.isNotBlank()) {
                    currentConversationId = msg.conversationId
                    return
                }
            }
        }

        val latest = db.chatMessageDao().getLatestConversationIdByBook(currentBookName).orEmpty()
        currentConversationId = if (latest.isNotBlank()) latest else newConversationId()
    }

    private fun newConversationId(): String =
        "conv_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

    private fun startNewConversation() {
        currentConversationId = newConversationId()
        displayMessages.clear()
        adapter.notifyDataSetChanged()
        updateConversationSubtitle()
        lifecycleScope.launch { refreshSessionRows() }
        Utils.toast(this, "已新建对话")
    }

    private fun updateConversationSubtitle() {
        val modelName = Prefs.getAiChatModel(this).ifEmpty { "未选择模型" }
        tvAiModel.text = modelName
    }

    private fun showSessionPanel() {
        lifecycleScope.launch {
            refreshSessionRows()
            rvSessionList.adapter = sessionAdapter
            sessionAdapter.submit(allSessionRows.toList())
            if (!drawerSessions.isDrawerOpen(GravityCompat.END)) {
                drawerSessions.openDrawer(GravityCompat.END)
            }
        }
    }

    private fun showDeleteSessionDialog(row: ChatSessionRow) {
        AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("请选择删除方式。你可以只删除这条历史会话，也可以连同该会话生成的账单一起删除。")
            .setNegativeButton("取消", null)
            .setNeutralButton("保留账单") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.chatMessageDao().deleteByBookAndConversation(row.bookName, row.conversationId)
                    }
                    onSessionDeleted(row)
                }
            }
            .setPositiveButton("删除账单") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val sessionBills = loadSessionActiveBills(row.bookName, row.conversationId)
                        sessionBills.forEach { bill ->
                            tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
                        }
                        db.chatMessageDao().deleteByBookAndConversation(row.bookName, row.conversationId)
                    }
                    onSessionDeleted(row)
                }
            }
            .show()
    }

    private suspend fun onSessionDeleted(row: ChatSessionRow) {
        if (currentBookName == row.bookName && currentConversationId == row.conversationId) {
            switchToLatestConversationOrNew(row.bookName)
            loadHistoryMessages()
        } else {
            refreshSessionRows()
            rvSessionList.adapter = sessionAdapter
            sessionAdapter.submit(allSessionRows.toList())
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_FlipAccounting))
            .setTitle("清空聊天记录")
            .setMessage("该操作只会清空当前对话的聊天内容，不会删除对应账单。如果需要删除账单，请使用历史会话里的“删除会话”。")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val voiceFiles = displayMessages
                        .mapNotNull { it.voice?.audioPath?.takeIf { path -> path.isNotBlank() } }
                        .distinct()
                    withContext(Dispatchers.IO) {
                        db.chatMessageDao().deleteByBookAndConversation(currentBookName, currentConversationId)
                        voiceFiles.forEach { path -> runCatching { File(path).delete() } }
                    }
                    displayMessages.clear()
                    adapter.notifyDataSetChanged()
                    refreshSessionRows()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private suspend fun refreshSessionRows() {
        val msgs = withContext(Dispatchers.IO) {
            val currentBookMsgs = db.chatMessageDao().getAllByBook(currentBookName)
            if (currentBookMsgs.any { it.conversationId.isNotBlank() }) currentBookMsgs else db.chatMessageDao().getAll()
        }
        val grouped = msgs
            .groupBy { (it.bookName.ifBlank { BookAccountManager.DEFAULT_BOOK }) to it.conversationId }
            .filterKeys { it.second.isNotBlank() }
            .toMutableMap()
        val orderByFirstSeen = mutableMapOf<Pair<String, String>, Int>()
        grouped.entries
            .groupBy { it.key.first }
            .forEach { (_, entriesInBook) ->
                entriesInBook
                    .sortedBy { entry -> entry.value.minOfOrNull { it.timestamp } ?: Long.MAX_VALUE }
                    .forEachIndexed { index, entry ->
                        orderByFirstSeen[entry.key] = index + 1
                    }
            }

        allSessionRows.clear()
        val rows = mutableListOf<ChatSessionRow>()
        grouped.forEach { (key, list) ->
            val rowBookName = key.first
            val convId = key.second
            val latest = list.maxByOrNull { it.timestamp }
            val latestBillMsg = list
                .filter { it.msgType == MSG_TYPE_AI_BILL && !isDeprecatedBillMessage(it.billIds) }
                .maxByOrNull { it.timestamp }
            val preview = runCatching {
                buildSessionPreview(latestBillMsg, latest)
            }.getOrElse {
                buildSessionPreviewFallback(latestBillMsg, latest)
            }
            val bookLabel = BookAccountManager.normalizeBookName(rowBookName).ifBlank {
                BookAccountManager.DEFAULT_BOOK
            }
            val defaultTitle = "$bookLabel · 会话 ${orderByFirstSeen[key] ?: 1}"
            val savedTitle = Prefs.getAiChatSessionTitle(this, rowBookName, convId).trim()
            val oldAutoTitle = buildSessionAutoTitle(list, preview)
            val finalTitle = if (
                savedTitle.isBlank() ||
                isLegacySessionAutoTitle(savedTitle) ||
                savedTitle == oldAutoTitle
            ) {
                defaultTitle
            } else {
                savedTitle
            }
            rows += ChatSessionRow(
                bookName = rowBookName,
                conversationId = convId,
                title = finalTitle,
                preview = preview,
                displayTime = latest?.timestamp?.let {
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                } ?: "刚刚创建",
                timestamp = latest?.timestamp ?: System.currentTimeMillis(),
                isCurrent = rowBookName == currentBookName && convId == currentConversationId
            )
        }
        allSessionRows.addAll(rows.sortedByDescending { it.timestamp })
    }

    private fun buildSessionAutoTitle(messages: List<ChatMessage>, preview: String): String {
        val latestUserText = messages
            .asReversed()
            .firstOrNull { it.msgType == MSG_TYPE_USER_TEXT }
            ?.content
            .orEmpty()
            .trim()
        if (latestUserText.isNotBlank()) {
            val clean = latestUserText
                .replace(Regex("\\s+"), " ")
                .replace("。", "")
                .replace("，", " ")
                .replace(",", " ")
                .trim()
            return clean.take(16).ifBlank { "新的会话" }
        }

        val latestVoiceText = messages
            .asReversed()
            .firstOrNull { it.msgType == MSG_TYPE_USER_VOICE }
            ?.let { parseVoicePayload(it.content).transcript.trim() }
            .orEmpty()
        if (latestVoiceText.isNotBlank()) {
            return latestVoiceText.take(16)
        }

        if (preview.isNotBlank()) {
            return preview
                .removePrefix("最后一笔账单：（")
                .removePrefix("最近消息：")
                .removeSuffix("）")
                .trim()
                .take(16)
                .ifBlank { "新的会话" }
        }
        return "新的会话"
    }

    private fun isLegacySessionAutoTitle(title: String): Boolean {
        val t = title.trim()
        if (t == "AI对话") return true
        if (t == "新的会话") return true
        if (Regex("^记账会话\\s*\\d+$").matches(t)) return true
        return Regex("^AI对话\\d+$").matches(t)
    }

    private suspend fun switchToLatestConversationOrNew(bookName: String) {
        currentBookName = bookName
        val latest = withContext(Dispatchers.IO) {
            db.chatMessageDao().getLatestConversationIdByBook(bookName).orEmpty()
        }
        currentConversationId = if (latest.isNotBlank()) latest else newConversationId()
    }

    private suspend fun loadSessionActiveBills(bookName: String, conversationId: String): List<Bill> {
        val messages = db.chatMessageDao().getAllByBookAndConversation(bookName, conversationId)
        val ids = linkedSetOf<Long>()
        messages.filter { it.msgType == MSG_TYPE_AI_BILL }.forEach { msg ->
            parseBillIds(msg.billIds).filterTo(ids) { it > 0L }
            parseDeprecatedBillIdsFromContent(msg.content).forEach { if (it > 0L) ids.add(it) }
            parseBillsFromMessageContent(msg.content).mapTo(ids) { it.id }.filter { it > 0L }
        }
        return ids.mapNotNull { db.billDao().getBillById(it) }
    }

    private suspend fun buildSessionPreview(latestBillMsg: ChatMessage?, latestMsg: ChatMessage?): String {
        if (latestBillMsg == null) return "最后一笔账单：（暂无）"
        val latestBill = withContext(Dispatchers.IO) {
            parseBillIds(latestBillMsg.billIds)
                .mapNotNull { db.billDao().getBillById(it) }
                .lastOrNull()
        } ?: parseBillsFromMessageContent(latestBillMsg.content).lastOrNull()
        val remark = latestBill?.remark?.trim().orEmpty()
        val displayRemark = when {
            remark.isNotBlank() -> remark
            latestBill != null && latestBill.categoryName.isNotBlank() -> latestBill.categoryName
            else -> "暂无"
        }
        return "最后一笔账单：（$displayRemark）"
    }

    private fun buildSessionPreviewFallback(latestBillMsg: ChatMessage?, latestMsg: ChatMessage?): String {
        val latestBill = latestBillMsg?.let { parseBillsFromMessageContent(it.content).lastOrNull() }
        val remark = latestBill?.remark?.trim().orEmpty()
        if (remark.isNotBlank()) return "最后一笔账单：（$remark）"
        val fallbackText = latestMsg?.content?.trim().orEmpty()
        return if (fallbackText.isNotBlank()) {
            "最近消息：${fallbackText.take(18)}"
        } else {
            "最后一笔账单：（暂无）"
        }
    }

    private fun showRenameSessionDialog(row: ChatSessionRow) {
        val input = android.widget.EditText(this).apply {
            setText(row.title)
            setSelection(text?.length ?: 0)
            hint = "输入会话名称"
            setPadding(40, 28, 40, 28)
        }
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_FlipAccounting))
            .setTitle("重命名对话")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                Prefs.setAiChatSessionTitle(this, row.bookName, row.conversationId, input.text?.toString().orEmpty())
                lifecycleScope.launch {
                    refreshSessionRows()
                    if (etSessionSearch.text?.toString().orEmpty().isBlank()) {
                        rvSessionList.adapter = sessionAdapter
                        sessionAdapter.submit(allSessionRows.toList())
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renameSessionInline(row: ChatSessionRow, newTitle: String) {
        val value = newTitle.trim()
        if (value.isBlank() || value == row.title) return
        Prefs.setAiChatSessionTitle(this, row.bookName, row.conversationId, value)
        lifecycleScope.launch {
            refreshSessionRows()
            if (etSessionSearch.text?.toString().orEmpty().isBlank()) {
                rvSessionList.adapter = sessionAdapter
                sessionAdapter.submit(allSessionRows.toList())
            }
        }
    }

    private fun showReplyStyleDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_reply_style, null)
        val optionContainer = view.findViewById<LinearLayout>(R.id.layout_style_options)
        val btnCancel = view.findViewById<TextView>(R.id.btn_style_cancel)
        val current = Prefs.getAiChatReplyStyle(this)
        val options = listOf(
            Triple("cute", "可爱俏皮", "更活一点，允许少量颜文字和小俏皮话"),
            Triple("gentle", "温柔陪伴", "更轻一点，像在旁边慢慢接住你"),
            Triple("concise", "简洁克制", "只说重点，语气干净，不拖泥带水"),
            Triple("playful", "活泼碎碎念", "更有聊天感，适合想要热闹一点的反馈"),
            Triple("custom", "自定义", "按你自己的提示词来定义语气、人设和长度"),
            Triple("off", "关闭", "只保留账单卡片，不再补自然回复")
        )

        lateinit var dialog: AlertDialog
        options.forEach { (value, title, desc) ->
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_reply_style_option, optionContainer, false)
            val titleView = itemView.findViewById<TextView>(R.id.tv_style_title)
            val descView = itemView.findViewById<TextView>(R.id.tv_style_desc)
            val stateView = itemView.findViewById<TextView>(R.id.tv_style_state)
            titleView.text = title
            descView.text = desc
            val selected = current == value
            itemView.background = getDrawable(
                if (selected) R.drawable.bg_reply_style_option_selected else R.drawable.bg_dialog_action_item
            )
            stateView.visibility = if (selected) View.VISIBLE else View.GONE
            itemView.setOnClickListener {
                if (value == "custom") {
                    dialog.dismiss()
                    showCustomReplyStyleDialog()
                } else {
                    Prefs.setAiChatReplyStyle(this, value)
                    Utils.toast(this, if (value == "off") "已关闭自然回复" else "已切换为$title")
                    dialog.dismiss()
                }
            }
            optionContainer.addView(itemView)
        }

        dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_FlipAccounting))
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener { styleChatPanelWindow(dialog) }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showCustomReplyStyleDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_reply_style_custom, null)
        val input = view.findViewById<android.widget.EditText>(R.id.et_custom_reply_style)
        val btnSave = view.findViewById<TextView>(R.id.btn_custom_save)
        val btnTurnOff = view.findViewById<TextView>(R.id.btn_custom_turn_off)
        val btnCancel = view.findViewById<TextView>(R.id.btn_custom_cancel)
        input.setText(Prefs.getAiChatReplyStyleCustomPrompt(this))
        input.setSelection(input.text?.length ?: 0)

        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_FlipAccounting))
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener { styleChatPanelWindow(dialog) }

        btnSave.setOnClickListener {
            val prompt = input.text?.toString().orEmpty().trim()
            Prefs.setAiChatReplyStyleCustomPrompt(this, prompt)
            Prefs.setAiChatReplyStyle(this, "custom")
            Utils.toast(this, "已切换为自定义风格")
            dialog.dismiss()
        }
        btnTurnOff.setOnClickListener {
            Prefs.setAiChatReplyStyle(this, "off")
            Utils.toast(this, "已关闭自然回复")
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun styleChatPanelWindow(dialog: AlertDialog) {
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setGravity(android.view.Gravity.BOTTOM)
            val margin = (12 * resources.displayMetrics.density).toInt()
            win.setLayout(resources.displayMetrics.widthPixels - margin * 2, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showModelSwitchDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_model_picker, null)
        val etSearch = view.findViewById<android.widget.EditText>(R.id.et_model_search)
        val rv = view.findViewById<RecyclerView>(R.id.rv_model_list)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_model_empty)
        val btnCancel = view.findViewById<TextView>(R.id.btn_model_cancel)
        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_FlipAccounting))
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val allModels = Prefs.getAiModelsCache(this).ifEmpty {
            listOf(Prefs.getAiModel(this), Prefs.getAiSingleModel(this), Prefs.getAiMultiModel(this)).distinct()
        }
        val modelAdapter = ModelOptionAdapter(
            current = Prefs.getAiChatModel(this),
            onSelect = { model ->
                Prefs.setAiChatModel(this, model)
                updateConversationSubtitle()
                refreshVoiceSupportHint()
                dialog.dismiss()
            }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = modelAdapter

        fun filter(kw: String) {
            val list = if (kw.isBlank()) allModels else allModels.filter { it.contains(kw, ignoreCase = true) }
            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            modelAdapter.submit(list)
        }
        filter("")

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                filter(s?.toString().orEmpty().trim())
            }
        })
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun pickImage() {
        if (!ensureAiImageFeatureEnabled()) return
        startActivityForResult(Intent(Intent.ACTION_PICK).apply { type = "image/*" }, REQ_PICK_IMAGE)
    }

    private fun pickBgImage() {
        val options = arrayOf("恢复默认背景", "选择图片")
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_FlipAccounting))
            .setTitle("设置聊天背景")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        Prefs.setAiChatBgPath(this, "")
                        Glide.with(this).clear(ivChatBg)
                        ivChatBg.visibility = View.INVISIBLE
                    }
                    1 -> startActivityForResult(Intent(Intent.ACTION_PICK).apply { type = "image/*" }, REQ_PICK_BG)
                }
            }
            .show()
    }

    private fun refreshAiProfile() {
        tvAiName.text = Prefs.getAiChatName(this).ifEmpty { "小计" }
        updateConversationSubtitle()
        val avatarPath = Prefs.getAiChatAvatarPath(this)
        if (avatarPath.isNotBlank()) {
            GlideLocalFiles.load(
                target = ivAiAvatar,
                file = File(avatarPath),
                placeholderRes = R.drawable.ic_ai_default_avatar,
                circleCrop = true
            )
        } else {
            ivAiAvatar.setImageResource(R.drawable.ic_ai_default_avatar)
        }
    }

    private fun applyBackground() {
        val path = Prefs.getAiChatBgPath(this)
        if (path.isBlank()) {
            Glide.with(this).clear(ivChatBg)
            ivChatBg.visibility = View.INVISIBLE
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Prefs.setAiChatBgPath(this, "")
            Glide.with(this).clear(ivChatBg)
            ivChatBg.visibility = View.INVISIBLE
            return
        }
        ivChatBg.visibility = View.VISIBLE
        Glide.with(this).clear(ivChatBg)
        GlideLocalFiles.load(
            target = ivChatBg,
            file = file,
            diskCacheStrategy = DiskCacheStrategy.NONE,
            skipMemoryCache = true
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == REQ_CROP_AI_AVATAR || requestCode == REQ_CROP_USER_AVATAR) {
                val error = data?.let { UCrop.getError(it) }
                if (error != null) Utils.toast(this, "头像裁剪失败: ${error.message ?: "未知错误"}")
            }
            return
        }
        when (requestCode) {
            REQ_PICK_IMAGE -> {
                val uri = data?.data ?: return
                handlePickedImage(uri)
            }
            REQ_PICK_BG -> {
                val uri = data?.data ?: return
                saveAndApplyBackground(uri)
            }
            REQ_PICK_AI_AVATAR -> {
                val uri = data?.data ?: return
                startAvatarCrop(uri, isAiAvatar = true)
            }
            REQ_PICK_USER_AVATAR -> {
                val uri = data?.data ?: return
                startAvatarCrop(uri, isAiAvatar = false)
            }
            REQ_CROP_AI_AVATAR -> {
                val uri = data?.let { UCrop.getOutput(it) } ?: return
                saveAiAvatar(uri)
            }
            REQ_CROP_USER_AVATAR -> {
                val uri = data?.let { UCrop.getOutput(it) } ?: return
                saveUserAvatar(uri)
            }
        }
    }

    private fun startAvatarCrop(sourceUri: Uri, isAiAvatar: Boolean) {
        val destFile = File(
            cacheDir,
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
            .getIntent(this)
        startActivityForResult(intent, if (isAiAvatar) REQ_CROP_AI_AVATAR else REQ_CROP_USER_AVATAR)
    }

    private fun saveAndApplyBackground(uri: Uri) {
        runCatching {
            val bgDir = File(filesDir, "chat_bg").also { it.mkdirs() }
            val oldPath = Prefs.getAiChatBgPath(this)
            val destFile = File(bgDir, "chat_bg_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setAiChatBgPath(this, destFile.absolutePath)
            if (oldPath.isNotBlank() && oldPath != destFile.absolutePath) {
                val oldFile = File(oldPath)
                if (oldFile.exists() && oldFile.parentFile?.absolutePath == bgDir.absolutePath) {
                    oldFile.delete()
                }
            }
            applyBackground()
            Utils.toast(this, "背景已更新")
        }.onFailure {
            Utils.toast(this, "背景更新失败: ${it.message ?: "未知错误"}")
        }
    }

    private fun saveAiAvatar(uri: Uri) {
        runCatching {
            val destFile = File(filesDir, "chat_ai_avatar.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setAiChatAvatarPath(this, destFile.absolutePath)
            pendingEditAiAvatarView?.let { iv ->
                GlideLocalFiles.load(
                    target = iv,
                    file = destFile,
                    placeholderRes = R.drawable.ic_ai_default_avatar,
                    circleCrop = true
                )
            }
            refreshAiProfile()
            Utils.toast(this, "AI 头像已更新")
        }.onFailure {
            Utils.toast(this, "AI 头像更新失败: ${it.message ?: "未知错误"}")
        }
    }

    private fun saveUserAvatar(uri: Uri) {
        runCatching {
            val destFile = File(filesDir, "chat_user_avatar.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setUserChatAvatarPath(this, destFile.absolutePath)
            adapter.notifyDataSetChanged()
            Utils.toast(this, "用户头像已更新")
        }.onFailure {
            Utils.toast(this, "用户头像更新失败: ${it.message ?: "未知错误"}")
        }
    }

    private fun handlePickedImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val (storedUri, base64, mime) = withContext(Dispatchers.IO) {
                    val sourceMime = contentResolver.getType(uri) ?: "image/jpeg"
                    val stableUri = copyPickedImageToStorage(uri, sourceMime)
                    val stream = contentResolver.openInputStream(stableUri) ?: return@withContext Triple(Uri.EMPTY, "", sourceMime)
                    val bytes = stream.readBytes()
                    stream.close()
                    Triple(
                        stableUri,
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                        sourceMime
                    )
                }
                if (base64.isBlank()) return@launch
                appendUserMessage("", MSG_TYPE_USER_IMAGE, imageUri = storedUri.toString())

                val text = if (Prefs.getOcrMode(this@ChatActivity) == Prefs.OCR_MODE_LOCAL) {
                    val ocr = withContext(Dispatchers.IO) {
                        try { ReceiptOcrHelper.runOcrOnly(this@ChatActivity, storedUri) } catch (_: Exception) { "" }
                    }
                    if (ocr.isBlank()) "[MULTIMODAL_IMAGE]$base64|$mime" else "[图片OCR文本]: $ocr"
                } else {
                    "[MULTIMODAL_IMAGE]$base64|$mime"
                }
                callAiAccounting(text, appendUserBubble = false)
            } catch (e: Exception) {
                appendAiTextMessage("图片处理失败: ${e.message}", isLoading = false)
            }
        }
    }

    private fun copyPickedImageToStorage(sourceUri: Uri, sourceMime: String): Uri {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(sourceMime)
            ?.lowercase(Locale.getDefault())
            ?.ifBlank { null }
            ?: "jpg"
        val imageDir = File(filesDir, "chat_images").also { it.mkdirs() }
        val outFile = File(imageDir, "chat_img_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext")
        contentResolver.openInputStream(sourceUri)?.use { ins ->
            FileOutputStream(outFile).use { outs -> ins.copyTo(outs) }
        } ?: throw IOException("无法读取图片")
        return Uri.fromFile(outFile)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startVoiceRecording(): Boolean {
        if (isRecording) return true
        val tempFile = File(cacheDir, "chat_voice_input.wav")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            audioBufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        return try {
            record.startRecording()
            audioRecord = record
            audioFile = tempFile
            isRecording = true
            recordingStartAt = System.currentTimeMillis()
            btnVoiceHold.text = "松开发送"
            btnVoiceHold.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E6E6E6"))
            startRecordingButtonPulse()
            showVoiceRecordOverlay(false)
            recordingThread = Thread {
                writeAudioDataToFile(tempFile)
            }
            recordingThread?.start()
            true
        } catch (_: Exception) {
            isRecording = false
            try { record.release() } catch (_: Exception) {}
            false
        }
    }

    private fun writeAudioDataToFile(file: File) {
        val data = ByteArray(audioBufferSize)
        try {
            FileOutputStream(file).use { os ->
                os.write(ByteArray(44), 0, 44)
                var totalAudioLen = 0L
                while (isRecording) {
                    val read = audioRecord?.read(data, 0, data.size) ?: AudioRecord.ERROR_INVALID_OPERATION
                    when {
                        read > 0 -> {
                            os.write(data, 0, read)
                            totalAudioLen += read
                        }
                        read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION -> break
                    }
                }
                updateWavHeader(file, totalAudioLen)
            }
        } catch (_: IOException) {
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
        header[20] = 1
        header[22] = channels.toByte()
        header[24] = (longSampleRate and 0xffL).toByte()
        header[25] = (longSampleRate shr 8 and 0xffL).toByte()
        header[26] = (longSampleRate shr 16 and 0xffL).toByte()
        header[27] = (longSampleRate shr 24 and 0xffL).toByte()
        header[28] = (byteRate and 0xffL).toByte()
        header[29] = (byteRate shr 8 and 0xffL).toByte()
        header[30] = (byteRate shr 16 and 0xffL).toByte()
        header[31] = (byteRate shr 24 and 0xffL).toByte()
        header[32] = (1 * 16 / 8).toByte()
        header[34] = 16
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = (totalAudioLen shr 8 and 0xffL).toByte()
        header[42] = (totalAudioLen shr 16 and 0xffL).toByte()
        header[43] = (totalAudioLen shr 24 and 0xffL).toByte()
        runCatching {
            java.io.RandomAccessFile(file, "rw").use {
                it.seek(0)
                it.write(header)
            }
        }
    }

    private fun stopVoiceRecording(onFileReady: (File?, Int) -> Unit) {
        clearPendingLongPress()
        if (!isRecording) {
            onFileReady(null, 0)
            return
        }
        isRecording = false
        stopRecordingButtonPulse()
        btnVoiceHold.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F2F3F5"))
        hideVoiceRecordOverlay()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { recordingThread?.join(1200) } catch (_: Exception) {}
        recordingThread = null
        val durationMs = (System.currentTimeMillis() - recordingStartAt).coerceAtLeast(400L)
        val durationSec = (durationMs / 1000.0).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(1)
        val readyFile = audioFile?.takeIf { it.exists() && it.length() > 44L }
        onFileReady(readyFile, durationSec)
    }

    private fun onVoiceRecorded(tempFile: File, durationSec: Int) {
        lifecycleScope.launch {
            val copiedFile = withContext(Dispatchers.IO) { copyVoiceFileToStorage(tempFile) }
            val added = appendUserVoiceMessage(copiedFile, durationSec, "")
            val loadingIdx = appendAiTextMessage("正在听写语音...", isLoading = true)
            val transcript = withContext(Dispatchers.IO) {
                transcribeVoiceToTextWithFallback(copiedFile)
            }.trim()
            if (transcript.isBlank()) {
                removeLoadingMessage(loadingIdx)
                appendAiTextMessage("这段语音没有识别清楚，你可以再说一遍，我会继续按“语音转文字”方式发送。", isLoading = false)
                return@launch
            }
            callAiAccounting(
                userText = transcript,
                appendUserBubble = false,
                forceTextReply = true,
                loadingIdxOverride = loadingIdx,
                loadingBootstrapText = "正在理解你的消息..."
            )
        }
    }

    private fun currentChatModelSupportsDirectAudioInput(): Boolean {
        val model = Prefs.getAiChatModel(this).ifBlank { Prefs.getAiSingleModel(this) }
        return Prefs.getAiChatModelAudioSupport(this, model) == true
    }

    private suspend fun transcribeVoiceToTextWithFallback(audioFile: File): String {
        fun normalize(raw: String?): String {
            val text = raw.orEmpty().trim()
            return if (
                text.isBlank() ||
                text == "WHISPER_NOT_SETUP" ||
                text == "MODEL_DOWNLOADING"
            ) "" else text
        }
        val asrMode = Prefs.getAsrMode(this)
        return if (asrMode == Prefs.ASR_MODE_WHISPER) {
            normalize(LocalAsrService.speechToText(this@ChatActivity, audioFile))
        } else {
            normalize(AIService.speechToText(this@ChatActivity, audioFile))
        }
    }

    private fun copyVoiceFileToStorage(tempFile: File): File {
        val voiceDir = File(filesDir, "chat_voice").also { it.mkdirs() }
        val dest = File(voiceDir, "voice_${System.currentTimeMillis()}.wav")
        tempFile.inputStream().use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        return dest
    }

    private fun buildVoicePayload(audioPath: String, durationSec: Int, transcript: String): String {
        val json = JSONObject().apply {
            put("audioPath", audioPath)
            put("durationSec", durationSec)
            put("transcript", transcript)
        }.toString()
        return VOICE_PAYLOAD_V2_PREFIX + json
    }

    private fun parseVoicePayload(content: String): VoicePayload {
        val normalized = content.removePrefix(VOICE_PAYLOAD_V2_PREFIX).trim()
        return try {
            val obj = JSONObject(normalized)
            VoicePayload(
                audioPath = obj.optString("audioPath"),
                durationSec = obj.optInt("durationSec", 1).coerceAtLeast(1),
                transcript = obj.optString("transcript")
            )
        } catch (_: Exception) {
            parseLooseVoicePayload(normalized) ?: VoicePayload(transcript = content)
        }
    }

    private fun parseVoicePayloadStrict(content: String): VoicePayload? {
        val normalized = content.removePrefix(VOICE_PAYLOAD_V2_PREFIX).trim()
        return try {
            val obj = JSONObject(normalized)
            val audioPath = obj.optString("audioPath").trim()
            val durationSec = obj.optInt("durationSec", -1)
            if (audioPath.isBlank() || durationSec <= 0) return null
            VoicePayload(
                audioPath = audioPath,
                durationSec = durationSec,
                transcript = obj.optString("transcript").trim()
            )
        } catch (_: Exception) {
            parseLooseVoicePayload(normalized)
        }
    }

    private fun parseLooseVoicePayload(raw: String): VoicePayload? {
        val text = raw.trim()
        if (!text.contains("audioPath", ignoreCase = true)) return null
        val audioPath = Regex("['\\\"]audioPath['\\\"]\\s*:\\s*['\\\"]([^'\\\"]+)['\\\"]")
            .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val duration = Regex("['\\\"]durationSec['\\\"]\\s*:\\s*(\\d+)")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        val transcript = Regex("['\\\"]transcript['\\\"]\\s*:\\s*['\\\"]([^'\\\"]*)['\\\"]")
            .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (audioPath.isBlank() || duration <= 0) return null
        return VoicePayload(
            audioPath = audioPath,
            durationSec = duration.coerceAtLeast(1),
            transcript = transcript
        )
    }

    private fun playVoiceMessage(item: ChatDisplayItem) {
        val voice = item.voice ?: parseVoicePayload(item.content)
        val path = voice.audioPath.takeIf { it.isNotBlank() } ?: run {
            Utils.toast(this, "未找到语音文件")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Utils.toast(this, "语音文件已不存在")
            return
        }
        if (currentPlayingPath == path) {
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
                adapter.notifyDataSetChanged()
                return
            }
            stopVoicePlayback()
            adapter.notifyDataSetChanged()
        }
        stopVoicePlayback()
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        audioManager?.mode = AudioManager.MODE_NORMAL
        audioManager?.isSpeakerphoneOn = true
        currentAudioFocusGranted =
            audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(path)
            setOnCompletionListener {
                stopVoicePlayback()
                adapter.notifyDataSetChanged()
            }
            prepare()
            start()
        }
        currentPlayingPath = path
        pausedVoicePath = null
        pausedVoicePositionMs = 0
        adapter.notifyDataSetChanged()
    }

    private fun stopVoicePlayback() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        if (currentAudioFocusGranted) {
            runCatching { audioManager?.abandonAudioFocus(null) }
        }
        audioManager?.mode = AudioManager.MODE_NORMAL
        audioManager?.isSpeakerphoneOn = false
        currentAudioFocusGranted = false
        currentPlayingPath = null
        pausedVoicePath = null
        pausedVoicePositionMs = 0
    }

    private fun showVoiceMessageMenu(anchor: View, item: ChatDisplayItem) {
        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_msg_menu, null)
        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true

        popupView.findViewById<View>(R.id.menu_item_copy).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_edit_resend).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_transcribe).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_retranscribe).visibility = View.VISIBLE
        val voice = item.voice ?: parseVoicePayload(item.content)
        val hasTranscript = voice.transcript.trim().isNotBlank()
        popupView.findViewById<View>(R.id.menu_item_copy_transcript).visibility =
            if (hasTranscript) View.VISIBLE else View.GONE
        val tvTranscribe = popupView.findViewById<TextView>(R.id.tv_menu_transcribe)
        val ivTranscribe = popupView.findViewById<ImageView>(R.id.iv_menu_transcribe)
        tvTranscribe.text = if (hasTranscript) "隐藏转写" else "转文字"
        ivTranscribe.setImageResource(if (hasTranscript) R.drawable.ic_delete else R.drawable.ic_mic)
        ivTranscribe.setColorFilter(if (hasTranscript) Color.parseColor("#FFB4B4") else Color.WHITE)

        popupView.findViewById<View>(R.id.menu_item_transcribe).setOnClickListener {
            popup.dismiss()
            if (hasTranscript) {
                hideVoiceTranscript(item)
            } else {
                transcribeVoiceMessage(item, showResult = true)
            }
        }
        popupView.findViewById<View>(R.id.menu_item_retranscribe).setOnClickListener {
            popup.dismiss()
            transcribeVoiceMessage(item, showResult = true, force = true)
        }
        popupView.findViewById<View>(R.id.menu_item_copy_transcript).setOnClickListener {
            popup.dismiss()
            val transcript = voice.transcript.trim()
            if (transcript.isNotBlank()) {
                copyToClipboard("voice_transcript", transcript, "已复制转写文本")
            }
        }
        popupView.findViewById<View>(R.id.menu_item_multiselect).setOnClickListener {
            popup.dismiss()
            enterVoiceSelectionMode(item)
        }
        popupView.findViewById<View>(R.id.menu_item_delete).setOnClickListener {
            popup.dismiss()
            deleteVoiceMessages(listOf(item.dbId))
        }
        popup.showAsDropDown(anchor, -40, -anchor.height - 16)
    }

    private fun hideVoiceTranscript(item: ChatDisplayItem) {
        val voice = item.voice ?: parseVoicePayload(item.content)
        if (voice.transcript.isBlank()) return
        updateVoiceMessageContent(item, voice.copy(transcript = ""))
        Utils.toast(this, "已隐藏转写")
    }

    private fun showTextMessageMenu(anchor: View, item: ChatDisplayItem) {
        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_msg_menu, null)
        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true

        popupView.findViewById<View>(R.id.menu_item_copy).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_edit_resend).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_transcribe).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_retranscribe).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_copy_transcript).visibility = View.GONE

        popupView.findViewById<View>(R.id.menu_item_copy).setOnClickListener {
            popup.dismiss()
            val text = item.content.trim()
            if (text.isBlank()) return@setOnClickListener
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("chat_message", text))
            Utils.toast(this, "已复制")
        }
        popupView.findViewById<View>(R.id.menu_item_edit_resend).setOnClickListener {
            popup.dismiss()
            val text = item.content.trim()
            if (text.isBlank()) return@setOnClickListener
            if (isVoiceMode) {
                isVoiceMode = false
                updateVoiceModeUi()
            }
            etInput.setText(text)
            etInput.setSelection(text.length)
            etInput.requestFocus()
            showSoftKeyboard(etInput)
            updateInputActionUi()
        }
        popupView.findViewById<View>(R.id.menu_item_multiselect).setOnClickListener {
            popup.dismiss()
            enterVoiceSelectionMode(item)
        }
        popupView.findViewById<View>(R.id.menu_item_delete).setOnClickListener {
            popup.dismiss()
            deleteVoiceMessages(listOf(item.dbId))
        }
        popup.showAsDropDown(anchor, -40, -anchor.height - 16)
    }

    private fun transcribeVoiceMessage(item: ChatDisplayItem, showResult: Boolean, force: Boolean = false) {
        val voice = item.voice ?: parseVoicePayload(item.content)
        val path = voice.audioPath.takeIf { it.isNotBlank() } ?: run {
            Utils.toast(this, "未找到语音文件")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Utils.toast(this, "语音文件已不存在")
            return
        }
        lifecycleScope.launch {
            if (!force && voice.transcript.trim().isNotBlank()) {
                if (showResult) {
                    pendingTranscriptRevealAnimations += voice.audioPath
                    val idx = findVoiceItemIndex(item.dbId, voice.audioPath)
                    if (idx >= 0) adapter.notifyItemChanged(idx)
                    scrollToBottom()
                }
                return@launch
            }
            val text = withContext(Dispatchers.IO) {
                transcribeVoiceToTextWithFallback(file)
            }.orEmpty().trim()
            if (text.isBlank()) {
                Utils.toast(this@ChatActivity, "转文字失败，请稍后重试")
                return@launch
            }
            val updatedVoice = voice.copy(transcript = text)
            if (showResult) {
                pendingTranscriptRevealAnimations += updatedVoice.audioPath
            }
            updateVoiceMessageContent(item, updatedVoice)
            if (showResult) {
                scrollToBottom()
            }
        }
    }

    private fun updateVoiceTranscriptByPath(audioPath: String, transcript: String, revealTranscript: Boolean) {
        val idx = findVoiceItemIndex(targetDbId = 0L, targetAudioPath = audioPath)
        if (idx < 0) return
        val item = displayMessages[idx]
        val voice = item.voice ?: parseVoicePayload(item.content)
        if (voice.transcript == transcript) return
        if (revealTranscript) pendingTranscriptRevealAnimations += audioPath
        updateVoiceMessageContent(item, voice.copy(transcript = transcript))
    }

    private fun updateVoiceMessageContent(item: ChatDisplayItem, voice: VoicePayload) {
        val idx = findVoiceItemIndex(
            targetDbId = item.dbId,
            targetAudioPath = voice.audioPath.ifBlank { item.voice?.audioPath.orEmpty() }
        )
        if (idx >= 0) {
            val dbId = displayMessages[idx].dbId
            displayMessages[idx] = displayMessages[idx].copy(
                dbId = dbId,
                content = buildVoicePayload(voice.audioPath, voice.durationSec, voice.transcript),
                voice = voice
            )
            adapter.notifyItemChanged(idx)
        }
        val persistedId = if (idx >= 0) displayMessages[idx].dbId else item.dbId
        if (persistedId > 0L) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.chatMessageDao().getById(persistedId)?.let { msg ->
                    db.chatMessageDao().update(
                        msg.copy(content = buildVoicePayload(voice.audioPath, voice.durationSec, voice.transcript))
                    )
                }
            }
        }
    }

    private fun findVoiceItemIndex(targetDbId: Long, targetAudioPath: String): Int {
        val normalizedPath = targetAudioPath.trim()
        if (targetDbId > 0L) {
            val byId = displayMessages.indexOfFirst { it.msgType == MSG_TYPE_USER_VOICE && it.dbId == targetDbId }
            if (byId >= 0) return byId
        }
        if (normalizedPath.isBlank()) return -1
        return displayMessages.indexOfLast {
            if (it.msgType != MSG_TYPE_USER_VOICE) return@indexOfLast false
            val voice = it.voice ?: parseVoicePayload(it.content)
            voice.audioPath.trim() == normalizedPath
        }
    }

    private fun enterVoiceSelectionMode(firstItem: ChatDisplayItem? = null) {
        isVoiceSelectionMode = true
        firstItem?.dbId?.takeIf { it > 0L }?.let { selectedVoiceMessageIds.add(it) }
        updateVoiceSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun exitVoiceSelectionMode() {
        isVoiceSelectionMode = false
        selectedVoiceMessageIds.clear()
        updateVoiceSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun toggleVoiceSelection(item: ChatDisplayItem) {
        val id = item.dbId.takeIf { it > 0L } ?: return
        if (selectedVoiceMessageIds.contains(id)) selectedVoiceMessageIds.remove(id) else selectedVoiceMessageIds.add(id)
        if (selectedVoiceMessageIds.isEmpty()) {
            exitVoiceSelectionMode()
        } else {
            updateVoiceSelectionUi()
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateVoiceSelectionUi() {
        layoutVoiceSelectionBar.visibility = if (isVoiceSelectionMode) View.VISIBLE else View.GONE
        tvVoiceSelectionCount.text = "已选择 ${selectedVoiceMessageIds.size} 条消息"
    }

    private fun deleteSelectedVoiceMessages() {
        if (selectedVoiceMessageIds.isEmpty()) {
            exitVoiceSelectionMode()
            return
        }
        deleteVoiceMessages(selectedVoiceMessageIds.toList())
    }

    private fun deleteVoiceMessages(ids: List<Long>) {
        if (ids.isEmpty()) return
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_FlipAccounting))
            .setTitle("删除消息")
            .setMessage("确定删除选中的消息吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val extraAssistantMessageIds = findDependentAssistantMessageIds(ids)
                    val allIds = (ids + extraAssistantMessageIds).distinct()
                    val files = displayMessages
                        .filter { allIds.contains(it.dbId) }
                        .mapNotNull { it.voice?.audioPath?.takeIf { path -> path.isNotBlank() } }
                    withContext(Dispatchers.IO) {
                        db.chatMessageDao().deleteByIds(allIds)
                        files.forEach { runCatching { File(it).delete() } }
                    }
                    displayMessages.removeAll { allIds.contains(it.dbId) }
                    adapter.notifyDataSetChanged()
                    exitVoiceSelectionMode()
                    refreshSessionRows()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun findDependentAssistantMessageIds(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val targetIds = ids.toSet()
        val result = mutableListOf<Long>()
        displayMessages.forEachIndexed { index, item ->
            if (!targetIds.contains(item.dbId) || !isUserMessageType(item.msgType)) return@forEachIndexed
            var cursor = index + 1
            while (cursor in displayMessages.indices) {
                val next = displayMessages[cursor]
                if (isUserMessageType(next.msgType)) break
                if ((next.msgType == MSG_TYPE_AI_BILL || next.msgType == MSG_TYPE_AI_TEXT) && next.dbId > 0L) {
                    result += next.dbId
                }
                cursor++
            }
        }
        return result.distinct()
    }

    private fun isUserMessageType(msgType: Int): Boolean =
        msgType == MSG_TYPE_USER_TEXT || msgType == MSG_TYPE_USER_IMAGE || msgType == MSG_TYPE_USER_VOICE

    private fun ensureModelAudioSupportProbed() {
        val model = Prefs.getAiChatModel(this).ifBlank { Prefs.getAiSingleModel(this) }
        if (Prefs.getAiChatModelAudioSupport(this, model) != null || audioSupportProbeJob?.isActive == true) {
            refreshVoiceSupportHint()
            return
        }
        audioSupportProbeJob = lifecycleScope.launch {
            val support = withContext(Dispatchers.IO) {
                AIService.probeDirectAudioInputSupport(this@ChatActivity)
            }
            Prefs.setAiChatModelAudioSupport(this@ChatActivity, model, support)
            refreshVoiceSupportHint()
        }
    }

    private fun refreshVoiceSupportHint() {
        tvVoiceModelHint.visibility = View.GONE
        val lp = layoutChatInputRow.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin != 0) {
            lp.topMargin = 0
            layoutChatInputRow.layoutParams = lp
        }
    }

    private fun sendText() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) {
            Utils.toast(this, "不能发送空内容")
            return
        }
        etInput.setText("")
        updateInputActionUi()
        appendUserMessage(text, MSG_TYPE_USER_TEXT)
        if (consumePendingHabitSuggestionReply(text)) return
        callAiAccounting(text, appendUserBubble = false)
    }

    private fun callAiAccounting(
        userText: String,
        appendUserBubble: Boolean = true,
        forceTextReply: Boolean = false,
        loadingIdxOverride: Int? = null,
        loadingBootstrapText: String = ""
    ) {
        if (appendUserBubble) appendUserMessage(userText, MSG_TYPE_USER_TEXT)
        val loadingIdx = loadingIdxOverride ?: appendAiTextMessage("正在理解你的消息...", isLoading = true)
        var loadingStage = 1
        fun pushLoadingStatus(raw: String) {
            val (stage, text) = mapProgressToNaturalStatus(raw)
            val nextStage = maxOf(loadingStage, stage)
            loadingStage = nextStage
            val stableText = when (nextStage) {
                1 -> "正在理解你的消息..."
                2 -> "正在生成回复..."
                else -> text
            }
            updateLoadingMessage(loadingIdx, stableText)
        }
        if (loadingIdxOverride != null && loadingBootstrapText.isNotBlank()) {
            updateLoadingMessage(loadingIdx, loadingBootstrapText)
        }
        lifecycleScope.launch {
            try {
                val analysisInput = buildAnalysisInput(userText)
                val result = try {
                    withContext(Dispatchers.IO) {
                        if (userText.startsWith("[MULTIMODAL_IMAGE]")) {
                            val payload = userText.removePrefix("[MULTIMODAL_IMAGE]")
                            val parts = payload.split("|", limit = 2)
                            val base64 = parts.getOrElse(0) { "" }
                            val mime = parts.getOrElse(1) { "image/jpeg" }
                            val visionResult = AIService.analyzeReceiptByImage(this@ChatActivity, base64, mime)
                            AIService.analyzeAccounting(this@ChatActivity, visionResult) { status ->
                                runOnUiThread { pushLoadingStatus(status) }
                            }
                        } else {
                            AIService.analyzeAccounting(this@ChatActivity, analysisInput) { status ->
                                runOnUiThread { pushLoadingStatus(status) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!shouldFallbackToAssistant(e)) throw e
                    null
                }

                removeLoadingMessage(loadingIdx)
                if (result == null) {
                    val replied = appendAssistantCompanionReply(userText, billSummary = "", extractorReplyHint = "")
                    if (forceTextReply && !replied) {
                        appendAiTextMessage("我这次没能正确解析，但已经收到你的语音转写文本。你可以再说得更具体一点，我继续帮你记账。", isLoading = false)
                    }
                    return@launch
                }
                if (result.optBoolean("no_bill", false)) {
                    val hint = result.optString("reply", "").trim()
                    val replied = appendAssistantCompanionReply(
                        userText = userText,
                        billSummary = "",
                        extractorReplyHint = hint
                    )
                    if (forceTextReply && !replied) {
                        appendAiTextMessage(
                            hint.ifBlank { "我暂时没识别到明确账单，你可以补充金额、分类或账户，我继续帮你完成。"},
                            isLoading = false
                        )
                    }
                    return@launch
                }
                val savedBills = processBillResult(result, userText)
                if (savedBills.isNotEmpty()) {
                    appendAssistantCompanionReply(
                        userText = userText,
                        billSummary = buildBillSummary(savedBills),
                        extractorReplyHint = ""
                    )
                    maybePromptToSaveHabit(userText, savedBills)
                }
            } catch (e: Exception) {
                removeLoadingMessage(loadingIdx)
                val msg = mapAiErrorToUserMessage(e)
                appendAiTextMessage(msg, isLoading = false)
                withContext(Dispatchers.IO) {
                    db.chatMessageDao().insert(
                        ChatMessage(
                            msgType = MSG_TYPE_AI_TEXT,
                            content = msg,
                            modelName = Prefs.getAiChatModel(this@ChatActivity),
                            bookName = currentBookName,
                            conversationId = currentConversationId
                        )
                    )
                }
            }
        }
    }

    private fun callAiAccountingWithVoice(audioFile: File) {
        val loadingIdx = appendAiTextMessage("正在理解你的消息...", isLoading = true)
        var loadingStage = 1
        fun pushLoadingStatus(raw: String) {
            val (stage, _) = mapProgressToNaturalStatus(raw)
            loadingStage = maxOf(loadingStage, stage)
            val stableText = if (loadingStage >= 2) "正在生成回复..." else "正在理解你的消息..."
            updateLoadingMessage(loadingIdx, stableText)
        }
        lifecycleScope.launch {
            try {
                val voiceUserText = "[语音输入]"
                val result = try {
                    withContext(Dispatchers.IO) {
                        AIService.analyzeAccountingByAudio(this@ChatActivity, audioFile) { status ->
                            runOnUiThread { pushLoadingStatus(status) }
                        }
                    }
                } catch (e: Exception) {
                    if (!shouldFallbackToAssistant(e)) throw e
                    null
                }

                removeLoadingMessage(loadingIdx)
                if (result == null) {
                    val replied = appendAssistantCompanionReply(voiceUserText, billSummary = "", extractorReplyHint = "")
                    if (!replied) {
                        appendAiTextMessage("我收到这段语音了，但这次没能正确解析。你可以再说得更具体一点。", isLoading = false)
                    }
                    return@launch
                }
                if (result.optBoolean("no_bill", false)) {
                    val hint = result.optString("reply", "").trim()
                    val replied = appendAssistantCompanionReply(
                        userText = voiceUserText,
                        billSummary = "",
                        extractorReplyHint = hint
                    )
                    if (!replied) {
                        appendAiTextMessage(hint.ifBlank { "我暂时没识别到明确账单，你可以补充金额、分类或账户。"}, isLoading = false)
                    }
                    return@launch
                }
                val savedBills = processBillResult(result, voiceUserText)
                if (savedBills.isNotEmpty()) {
                    appendAssistantCompanionReply(
                        userText = voiceUserText,
                        billSummary = buildBillSummary(savedBills),
                        extractorReplyHint = ""
                    )
                }
            } catch (e: Exception) {
                removeLoadingMessage(loadingIdx)
                val msg = mapAiErrorToUserMessage(e)
                appendAiTextMessage(msg, isLoading = false)
                withContext(Dispatchers.IO) {
                    db.chatMessageDao().insert(
                        ChatMessage(
                            msgType = MSG_TYPE_AI_TEXT,
                            content = msg,
                            modelName = Prefs.getAiChatModel(this@ChatActivity),
                            bookName = currentBookName,
                            conversationId = currentConversationId
                        )
                    )
                }
            }
        }
    }

    private suspend fun appendAssistantCompanionReply(
        userText: String,
        billSummary: String,
        extractorReplyHint: String
    ): Boolean {
        if (Prefs.getAiChatReplyStyle(this) == "off") return false
        val editingIdx = appendAiTextMessage("", isLoading = true)
        val streamed = StringBuilder()
        val streamOk = try {
            withContext(Dispatchers.IO) {
                AIService.streamAccountingAssistantReply(
                    ctx = this@ChatActivity,
                    userInput = userText,
                    billSummary = billSummary,
                    extractorReplyHint = extractorReplyHint,
                    isCorrection = isBillCorrectionRequest(userText)
                ) { delta ->
                    if (delta.isNotBlank()) {
                        streamed.append(delta)
                        runOnUiThread {
                            updateLoadingMessage(editingIdx, streamed.toString())
                        }
                    }
                }
            }
        } catch (_: Exception) {
            false
        }

        if (streamOk && streamed.isNotBlank()) {
            finalizeLoadingMessage(editingIdx, sanitizeAssistantReply(streamed.toString().trim()))
            return true
        }

        val reply = try {
            withContext(Dispatchers.IO) {
                AIService.generateAccountingAssistantReply(
                    ctx = this@ChatActivity,
                    userInput = userText,
                    billSummary = billSummary,
                    extractorReplyHint = extractorReplyHint,
                    isCorrection = isBillCorrectionRequest(userText)
                )
            }.trim()
        } catch (e: Exception) {
            ""
        }
        removeLoadingMessage(editingIdx)
        val sanitized = sanitizeAssistantReply(reply)
        if (sanitized.isNotBlank()) {
            appendAiTextMessage(sanitized, isLoading = false)
            return true
        }
        return false
    }

    private fun sanitizeAssistantReply(reply: String): String {
        var text = reply.trim()
        if (text.equals("BILL_SAVED", ignoreCase = true) || text.equals("NO_BILL", ignoreCase = true)) {
            return ""
        }
        text = text.replace(Regex("^\\s*(BILL_SAVED|NO_BILL|SCENE)\\s*[:：-]?\\s*", RegexOption.IGNORE_CASE), "")
        return text.trim()
    }

    private fun mapAiErrorToUserMessage(error: Exception): String {
        val raw = error.message.orEmpty()
        val normalized = raw.lowercase(Locale.getDefault())
        return when {
            normalized.contains("http 500") || normalized.contains("500 internal") ->
                "网络不佳，请重试"
            normalized.contains("timeout") || normalized.contains("timed out") ->
                "网络不佳，请重试"
            normalized.contains("unable to resolve host") || normalized.contains("failed to connect") ->
                "网络不佳，请重试"
            raw.isBlank() ->
                "分析失败，请稍后重试"
            else ->
                "分析失败: $raw"
        }
    }

    private fun shouldFallbackToAssistant(error: Exception): Boolean {
        val msg = error.message.orEmpty()
        if (msg.contains("API Key")) return false
        if (msg.contains("配置")) return false
        return error is IllegalArgumentException || msg.contains("JSON", ignoreCase = true)
    }

    private fun ensureLastMessageVisible(force: Boolean = false) {
        if (!force && isInlineAmountEditing()) return
        if (displayMessages.isEmpty()) return
        rvMessages.post {
            rvMessages.scrollToPosition(displayMessages.lastIndex)
        }
    }

    private fun consumePendingHabitSuggestionReply(text: String): Boolean {
        val pending = pendingHabitSuggestion ?: return false
        val normalized = text.trim().lowercase(Locale.getDefault())
        val acceptWords = listOf("是", "好", "好的", "行", "可以", "记住", "加入", "添加", "那就记入记账界面")
        val rejectWords = listOf("不", "不用", "不要", "算了", "否", "不需要")

        return when {
            acceptWords.any { normalized.contains(it) } -> {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.aiRuleDao().insertRule(
                            AiRule(
                                keyword = pending.keyword,
                                targetType = pending.targetType,
                                targetCategory = pending.targetCategory,
                                targetAccount1 = pending.targetAccount1,
                                targetAccount2 = pending.targetAccount2,
                                isEnabled = true
                            )
                        )
                    }
                    appendAiTextMessage("好呀，已经帮你记成一条记账习惯啦 ${pending.summaryText}", isLoading = false)
                }
                pendingHabitSuggestion = null
                true
            }
            rejectWords.any { normalized.contains(it) } -> {
                pendingHabitSuggestion = null
                appendAiTextMessage("好哒，那这次我先不记进习惯里~", isLoading = false)
                true
            }
            else -> false
        }
    }

    private fun maybePromptToSaveHabit(userText: String, savedBills: List<Bill>) {
        if (!Prefs.isAiPromptCorrectionEnabled(this)) return
        if (!isBillCorrectionRequest(userText)) return
        val bill = savedBills.firstOrNull() ?: return
        val keyword = bill.remark.ifBlank { bill.categoryName.substringAfterLast("·").ifBlank { bill.categoryName } }
        pendingHabitSuggestion = HabitRuleSuggestion(
            keyword = keyword,
            targetType = when {
                bill.type == Bill.TYPE_TRANSFER && bill.subType == Bill.SUBTYPE_REPAYMENT -> 3
                else -> bill.type
            },
            targetCategory = bill.categoryName,
            targetAccount1 = bill.accountName.ifBlank { null },
            targetAccount2 = bill.toAccountName.ifBlank { null }
        )
        appendAiTextMessage(
            "这次纠错我可以顺手帮你记成记账习惯哦~\n关键词：${pendingHabitSuggestion?.keyword}\n分类：${pendingHabitSuggestion?.targetCategory ?: "未设置"}${pendingHabitSuggestion?.targetAccount1?.let { "\n账户：$it" } ?: ""}\n回复“是”我就帮你加进去。",
            isLoading = false
        )
    }

    private suspend fun buildAnalysisInput(userText: String): String {
        val normalized = userText.removePrefix("[图片OCR文本]: ")
        if (!isBillCorrectionRequest(normalized)) return normalized

        val lastBill = findLatestActiveBill() ?: return normalized

        return buildString {
            appendLine("这是一次修改上一笔账单的请求。请不要新增无关账单，只返回修改后的完整账单 JSON。")
            appendLine("本次允许修改的字段包括：分类(category_name)、资产/账户(asset_name)、转入账户(to_asset_name)、备注(remarks)、金额(amount)、时间(time)、类型(type)。")
            appendLine("请忽略系统提示词里的示例日期、示例金额、示例商家名，它们只是格式演示，绝不能被当成当前用户账单内容。")
            appendLine("如果用户只说“资产改为/账户改为/备注改为/分类改为”，就只修改对应字段。")
            appendLine("如果用户没有明确指定资产，就允许 asset_name 为空，不要为了凑字段强行猜一个资产。")
            appendLine("上一笔账单如下：")
            appendLine("金额=${String.format(Locale.getDefault(), "%.2f", lastBill.amount)}")
            appendLine("类型=${lastBill.type}")
            appendLine("分类=${lastBill.categoryName}")
            appendLine("账户=${lastBill.accountName}")
            appendLine("转入账户=${lastBill.toAccountName}")
            appendLine("备注=${lastBill.remark}")
            appendLine("时间=${formatTime(lastBill.time)}")
            appendLine("用户这次的话：$normalized")
            append("如果用户只修改了其中一项，其余字段必须沿用上一笔账单，尤其不要丢失原有金额、账户、备注。")
        }
    }

    private fun buildBillSummary(bills: List<Bill>): String {
        return bills.joinToString("；") { bill ->
            val typeLabel = when (bill.type) {
                1 -> "收入"
                2 -> if (bill.subType == Bill.SUBTYPE_REPAYMENT) "还款" else "转账"
                else -> "支出"
            }
            "$typeLabel ${String.format(Locale.getDefault(), "%.2f", bill.amount)}元，分类${bill.categoryName}，账户${bill.accountName.ifBlank { "未指定" }}${bill.remark.takeIf { it.isNotBlank() }?.let { "，备注$it" } ?: ""}"
        }
    }

    private suspend fun processBillResult(result: JSONObject, userText: String): List<Bill> {
        val rawBills = mutableListOf<JSONObject>()
        when {
            result.has("bills") -> {
                val arr = result.getJSONArray("bills")
                for (i in 0 until arr.length()) rawBills.add(arr.getJSONObject(i))
            }
            result.has("amount") -> rawBills.add(result)
            else -> {
                appendAiTextMessage("AI 返回了无法识别的格式。", isLoading = false)
                return emptyList()
            }
        }

        if (rawBills.isEmpty()) {
            appendAiTextMessage("未解析到账单信息。", isLoading = false)
            return emptyList()
        }

        val correctionTarget = if (isBillCorrectionRequest(userText)) findLatestActiveBillTarget() else null
        val correctionBaseBill = correctionTarget?.bill
        if (correctionBaseBill != null && rawBills.size == 1) {
            mergeCorrectionFields(rawBills[0], correctionBaseBill)
        }

        val savedBills = mutableListOf<Bill>()
        val savedBillIds = mutableListOf<Long>()
        val activeBookName = BookAccountManager.normalizeBookName(
            currentBookName.ifBlank { BookAccountManager.getSelectedBook(this@ChatActivity) }
        )
        currentBookName = activeBookName

        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@ChatActivity)
            for (billJson in rawBills) {
                val timeLong = parseTimeToMillis(billJson.optString("time", ""))
                val rawType = billJson.optInt("type", 0)
                val type = when (rawType) { 0, 1, 2, 3 -> rawType; else -> 0 }
                val finalType = if (type == 3) 2 else type
                val subType = if (type == 3) Bill.SUBTYPE_REPAYMENT else Bill.SUBTYPE_NORMAL

                val categoryName = billJson.optString("category_name", "其它").replace("/::/", " > ")
                val assetName = billJson.optString("asset_name", "")
                val toAssetName = billJson.optString("to_asset_name", "")
                val amount = billJson.optDouble("amount", 0.0)
                val remark = billJson.optString("remarks", billJson.optString("remark", ""))
                val currency = billJson.optString("currency", "CNY")
                val fee = billJson.optDouble("fee", 0.0).coerceAtLeast(0.0)

                val categoryEntity = CategoryRepository(db.categoryDao()).findCategoryByDisplayName(
                    if (finalType == Bill.TYPE_INCOME) 1 else 0,
                    categoryName
                )
                val assetEntity = if (assetName.isNotEmpty()) db.assetDao().getAssetByName(assetName) else null
                val toAssetEntity = if (toAssetName.isNotEmpty()) db.assetDao().getAssetByName(toAssetName) else null
                val exchangeRate = when {
                    finalType == Bill.TYPE_TRANSFER && toAssetEntity != null && amount > 0.0 ->
                        BillAssetImpactService.estimateExchangeRateToTarget(amount, currency, toAssetEntity.currency)
                    currency.equals("CNY", ignoreCase = true) -> 1.0
                    else -> BillAssetImpactService.estimateExchangeRateToCny(currency)
                }

                val bill = Bill(
                    type = finalType,
                    subType = subType,
                    amount = amount,
                    currency = currency,
                    exchangeRate = exchangeRate,
                    fee = fee,
                    categoryId = categoryEntity?.id,
                    accountId = assetEntity?.id,
                    toAccountId = toAssetEntity?.id,
                    categoryName = categoryName,
                    accountName = assetName,
                    toAccountName = toAssetName,
                    time = timeLong,
                    remark = remark,
                    bookName = activeBookName
                )
                val savedBill = if (correctionBaseBill != null && rawBills.size == 1) {
                    BillMutationService.replaceBill(db, correctionBaseBill, bill)
                } else {
                    BillMutationService.insertBillAndApplyImpact(db, bill)
                }
                savedBillIds.add(savedBill.id)
                savedBills.add(savedBill)
            }
        }

        val billIdsJson = JSONArray(savedBillIds.map { it.toString() }).toString()
        val billsJsonArr = buildBillMessageContent(savedBills)
        val msgId = withContext(Dispatchers.IO) {
            db.chatMessageDao().insert(
                ChatMessage(
                    msgType = MSG_TYPE_AI_BILL,
                    content = billsJsonArr,
                    billIds = billIdsJson,
                    modelName = Prefs.getAiChatModel(this@ChatActivity),
                    bookName = activeBookName,
                    conversationId = currentConversationId
                )
            )
        }

        if (correctionTarget != null && correctionBaseBill != null && savedBills.size == 1) {
            val oldIdx = correctionTarget.messageIndex
            if (oldIdx in displayMessages.indices) {
                val old = displayMessages[oldIdx]
                val updatedBills = old.bills.toMutableList()
                if (correctionTarget.billIndex in updatedBills.indices) {
                    updatedBills[correctionTarget.billIndex] = correctionBaseBill
                }
                val updatedDeprecatedIds = old.deprecatedBillIds.toMutableSet().apply {
                    if (correctionBaseBill.id > 0L) add(correctionBaseBill.id)
                }
                val hasActiveBills = updatedBills.any { it.id !in updatedDeprecatedIds }
                if (!hasActiveBills && old.dbId > 0L) {
                    deprecatedBillMessageIds.add(old.dbId)
                }
                displayMessages[oldIdx] = old.copy(
                    bills = updatedBills,
                    isDeprecated = !hasActiveBills,
                    deprecatedBillIds = updatedDeprecatedIds
                )
                withContext(Dispatchers.IO) {
                    if (old.dbId > 0L) {
                        db.chatMessageDao().getById(old.dbId)?.let { oldMsg ->
                            db.chatMessageDao().update(
                                oldMsg.copy(
                                    billIds = if (hasActiveBills) oldMsg.billIds else markBillIdsAsDeprecated(oldMsg.billIds),
                                    content = buildBillMessageContent(updatedBills, updatedDeprecatedIds)
                                )
                            )
                        }
                    }
                }
                adapter.notifyItemChanged(oldIdx)
            }
        }

        displayMessages.add(
            ChatDisplayItem(
                dbId = msgId,
                msgType = MSG_TYPE_AI_BILL,
                content = billsJsonArr,
                bills = savedBills.toMutableList(),
                timestamp = System.currentTimeMillis(),
                isLoading = false
            )
        )
        adapter.notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom()
        refreshSessionRows()
        return savedBills
    }

    private data class ActiveBillTarget(
        val messageIndex: Int,
        val billIndex: Int,
        val bill: Bill
    )

    private fun findLatestActiveBillTarget(): ActiveBillTarget? {
        for (messageIndex in displayMessages.indices.reversed()) {
            val item = displayMessages[messageIndex]
            if (item.msgType != MSG_TYPE_AI_BILL || item.isDeprecated || item.bills.isEmpty()) continue
            for (billIndex in item.bills.indices.reversed()) {
                val bill = item.bills[billIndex]
                if (bill.id in item.deprecatedBillIds) continue
                return ActiveBillTarget(messageIndex, billIndex, bill)
            }
        }
        return null
    }

    private fun findLatestActiveBill(): Bill? = findLatestActiveBillTarget()?.bill

    private fun mergeCorrectionFields(billJson: JSONObject, baseBill: Bill) {
        if (!billJson.has("amount") || billJson.optDouble("amount", 0.0) <= 0.0) {
            billJson.put("amount", baseBill.amount)
        }

        val normalizedType = billJson.optInt("type", -1)
        if (normalizedType !in 0..3) {
            billJson.put(
                "type",
                if (baseBill.type == Bill.TYPE_TRANSFER && baseBill.subType == Bill.SUBTYPE_REPAYMENT) 3 else baseBill.type
            )
        }

        if (billJson.optString("category_name").isBlank()) {
            billJson.put("category_name", baseBill.categoryName)
        }
        if (billJson.optString("asset_name").isBlank()) {
            billJson.put("asset_name", baseBill.accountName)
        }
        if (billJson.optString("to_asset_name").isBlank()) {
            billJson.put("to_asset_name", baseBill.toAccountName)
        }

        val newRemark = billJson.optString("remarks", billJson.optString("remark", ""))
        if (newRemark.isBlank()) {
            billJson.put("remarks", baseBill.remark)
        }

        if (billJson.optString("time").isBlank()) {
            billJson.put(
                "time",
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(baseBill.time))
            )
        }

        if (billJson.optString("currency").isBlank()) {
            billJson.put("currency", baseBill.currency)
        }
    }

    private fun isBillCorrectionRequest(text: String): Boolean {
        val t = text.lowercase(Locale.getDefault())
        val refLast = listOf("刚刚", "上一笔", "那笔", "刚才", "这笔").any { t.contains(it) }
        val editAction = listOf("改为", "改成", "修改", "改下", "改一下", "改到").any { t.contains(it) }
        return refLast && editAction
    }

    private fun parseTimeToMillis(timeStr: String): Long {
        if (timeStr.isBlank()) return System.currentTimeMillis()
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(timeStr)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun maybeShowRuleDialogForChatBillCategoryEdit(
        item: ChatDisplayItem,
        originalBill: Bill,
        updatedBill: Bill
    ) {
        if (originalBill.categoryName == updatedBill.categoryName) return
        val referenceText = updatedBill.remark.ifBlank { originalBill.remark }.trim()
        if (referenceText.isBlank()) return

        Utils.toast(this, "检测到 AI 识别与最终结果不一致，可添加本地规则自动纠正")

        RuleDialogHelper.showDialog(
            ctx = this,
            rule = null,
            referenceText = referenceText,
            defaultType = updatedBill.type,
            defaultCat = updatedBill.categoryName,
            defaultAcc1 = updatedBill.accountName.ifBlank { null },
            defaultAcc2 = updatedBill.toAccountName.ifBlank { null },
            isOverlay = false,
            onSave = { newRule ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.aiRuleDao().insertRule(newRule)
                    withContext(Dispatchers.Main) {
                        Utils.toast(this@ChatActivity, "规则创建成功")
                    }
                }
            },
            onDelete = null
        )
    }

    private fun loadHistoryMessages() {
        lifecycleScope.launch {
            val dbMessages = withContext(Dispatchers.IO) {
                db.chatMessageDao().getAllByBookAndConversation(currentBookName, currentConversationId)
            }
            val dbBillDao = db.billDao()
            val orphanIds = mutableListOf<Long>()
            displayMessages.clear()
            var hasRenderableUserAnchor = false

            for (msg in dbMessages) {
                when (msg.msgType) {
                    MSG_TYPE_USER_TEXT -> {
                        val recoveredVoice = parseVoicePayloadStrict(msg.content)
                        if (recoveredVoice != null) {
                            hasRenderableUserAnchor = true
                            displayMessages.add(
                                ChatDisplayItem(
                                    dbId = msg.id,
                                    msgType = MSG_TYPE_USER_VOICE,
                                    content = msg.content,
                                    timestamp = msg.timestamp,
                                    voice = recoveredVoice
                                )
                            )
                            continue
                        }
                        hasRenderableUserAnchor = true
                        displayMessages.add(
                            ChatDisplayItem(
                                dbId = msg.id,
                                msgType = msg.msgType,
                                content = msg.content,
                                timestamp = msg.timestamp
                            )
                        )
                    }
                    MSG_TYPE_USER_VOICE -> {
                        hasRenderableUserAnchor = true
                        displayMessages.add(
                            ChatDisplayItem(
                                dbId = msg.id,
                                msgType = msg.msgType,
                                content = msg.content,
                                timestamp = msg.timestamp,
                                voice = parseVoicePayload(msg.content)
                            )
                        )
                    }
                    MSG_TYPE_USER_IMAGE -> {
                        hasRenderableUserAnchor = true
                        displayMessages.add(
                            ChatDisplayItem(
                                dbId = msg.id,
                                msgType = msg.msgType,
                                content = msg.content,
                                imageUri = msg.imageUri,
                                timestamp = msg.timestamp
                            )
                        )
                    }
                    MSG_TYPE_AI_TEXT -> {
                        val recoveredVoice = parseVoicePayloadStrict(msg.content)
                        if (recoveredVoice != null) {
                            hasRenderableUserAnchor = true
                            displayMessages.add(
                                ChatDisplayItem(
                                    dbId = msg.id,
                                    msgType = MSG_TYPE_USER_VOICE,
                                    content = msg.content,
                                    timestamp = msg.timestamp,
                                    voice = recoveredVoice
                                )
                            )
                            continue
                        }
                        if (!hasRenderableUserAnchor) {
                            orphanIds.add(msg.id)
                            continue
                        }
                        displayMessages.add(
                            ChatDisplayItem(
                                dbId = msg.id,
                                msgType = msg.msgType,
                                content = msg.content,
                                timestamp = msg.timestamp
                            )
                        )
                    }
                    MSG_TYPE_AI_BILL -> {
                        if (!hasRenderableUserAnchor) {
                            orphanIds.add(msg.id)
                            continue
                        }
                        val billIds = parseBillIds(msg.billIds)
                        val deprecated = isDeprecatedBillMessage(msg.billIds)
                        val bills = withContext(Dispatchers.IO) { billIds.mapNotNull { dbBillDao.getBillById(it) } }
                        val billSnapshots = parseBillsFromMessageContent(msg.content)
                        val deprecatedBillIds = parseDeprecatedBillIdsFromContent(msg.content)
                        val displayBills = if (bills.isNotEmpty()) {
                            mergeChatBillSnapshots(bills, billSnapshots)
                        } else {
                            billSnapshots
                        }
                        if (displayBills.isEmpty()) {
                            orphanIds.add(msg.id)
                        } else {
                            displayMessages.add(
                                ChatDisplayItem(
                                    dbId = msg.id,
                                    msgType = msg.msgType,
                                    content = msg.content,
                                    bills = displayBills.toMutableList(),
                                    timestamp = msg.timestamp,
                                    isDeprecated = deprecated || bills.isEmpty(),
                                    deprecatedBillIds = deprecatedBillIds.toMutableSet()
                                )
                            )
                            if (!deprecated && bills.isEmpty() && msg.id > 0L) {
                                withContext(Dispatchers.IO) {
                                    db.chatMessageDao().getById(msg.id)?.let { oldMsg ->
                                        db.chatMessageDao().update(oldMsg.copy(billIds = markBillIdsAsDeprecated(oldMsg.billIds)))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (orphanIds.isNotEmpty()) {
                withContext(Dispatchers.IO) { db.chatMessageDao().deleteByIds(orphanIds) }
            }

            adapter.notifyDataSetChanged()
            updateConversationSubtitle()
            scrollToBottom()
            scrollToPendingMessageIfNeeded()
            refreshSessionRows()
            if (::drawerSessions.isInitialized && drawerSessions.isDrawerOpen(GravityCompat.END) &&
                etSessionSearch.text?.toString().orEmpty().isBlank()
            ) {
                rvSessionList.adapter = sessionAdapter
                sessionAdapter.submit(allSessionRows.toList())
            }
        }
    }

    private fun scrollToPendingMessageIfNeeded() {
        if (pendingScrollToMessageId <= 0L) return
        val idx = displayMessages.indexOfFirst { it.dbId == pendingScrollToMessageId }
        if (idx >= 0) rvMessages.scrollToPosition(idx)
        pendingScrollToMessageId = -1L
    }

    private fun parseBillIds(json: String): List<Long> {
        if (json.isBlank()) return emptyList()
        val cleanJson = json.removePrefix(DEPRECATED_BILL_IDS_PREFIX)
        return try {
            val arr = JSONArray(cleanJson)
            (0 until arr.length()).map { arr.getString(it).toLong() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isDeprecatedBillMessage(billIds: String): Boolean =
        billIds.startsWith(DEPRECATED_BILL_IDS_PREFIX)

    private fun markBillIdsAsDeprecated(billIds: String): String {
        if (billIds.startsWith(DEPRECATED_BILL_IDS_PREFIX)) return billIds
        return DEPRECATED_BILL_IDS_PREFIX + billIds
    }

    private fun parseBillsFromMessageContent(content: String): List<Bill> {
        if (content.isBlank()) return emptyList()
        return try {
            val root = JSONObject(content)
            val arr = root.optJSONArray("bills") ?: JSONArray()
            parseBillArray(arr)
        } catch (_: Exception) {
            try {
                val arr = JSONArray(content)
                parseBillArray(arr)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun parseBillArray(arr: JSONArray): List<Bill> =
        (0 until arr.length()).mapNotNull { index ->
            val billJson = arr.optJSONObject(index) ?: return@mapNotNull null
            val rawType = billJson.optInt("type", 0)
            val rawSubType = billJson.optInt("subType", Bill.SUBTYPE_NORMAL)
            val finalType = if (rawType == 3) 2 else rawType
            val subType = if (rawType == 3) Bill.SUBTYPE_REPAYMENT else rawSubType
            Bill(
                id = billJson.optLong("id", 0L),
                type = finalType,
                subType = subType,
                amount = billJson.optDouble("amount", 0.0),
                originalAmount = billJson.optDouble("originalAmount", billJson.optDouble("amount", 0.0)),
                currency = billJson.optString("currency", "CNY"),
                exchangeRate = billJson.optDouble("exchangeRate", 1.0),
                categoryName = billJson.optString("category_name", "其它").replace("/::/", " > "),
                accountName = billJson.optString("asset_name", ""),
                toAccountName = billJson.optString("to_asset_name", ""),
                time = parseTimeToMillis(billJson.optString("time", "")),
                remark = billJson.optString("remarks", billJson.optString("remark", "")),
                bookName = currentBookName,
                relatedBillId = billJson.optLong("relatedBillId", 0L).takeIf { it > 0L }
            )
        }

    private fun parseDeprecatedBillIdsFromContent(content: String): Set<Long> {
        if (content.isBlank()) return emptySet()
        return try {
            val root = JSONObject(content)
            val arr = root.optJSONArray("deprecatedBillIds") ?: return emptySet()
            buildSet {
                for (i in 0 until arr.length()) {
                    val id = arr.optLong(i, 0L)
                    if (id > 0L) add(id)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun mergeChatBillSnapshots(liveBills: List<Bill>, snapshots: List<Bill>): List<Bill> {
        if (snapshots.isEmpty()) return liveBills
        val liveById = liveBills.filter { it.id > 0L }.associateBy { it.id }
        val merged = mutableListOf<Bill>()
        snapshots.forEach { snapshot ->
            val live = if (snapshot.id > 0L) liveById[snapshot.id] else null
            merged += live ?: snapshot
        }
        liveBills.forEach { live ->
            if (live.id <= 0L || snapshots.none { it.id == live.id }) {
                merged += live
            }
        }
        return merged
    }

    private fun buildBillMessageContent(
        bills: List<Bill>,
        deprecatedBillIds: Set<Long> = emptySet()
    ): String {
        val arr = JSONArray()
        bills.forEach { bill ->
            arr.put(JSONObject().apply {
                put("id", bill.id)
                put("amount", bill.amount)
                put("type", if (bill.subType == Bill.SUBTYPE_REPAYMENT) 3 else bill.type)
                put("subType", bill.subType)
                put("originalAmount", bill.originalAmount)
                put("asset_name", bill.accountName)
                put("category_name", bill.categoryName.replace(" > ", "/::/"))
                put("time", formatTime(bill.time))
                put("remarks", bill.remark)
                put("currency", bill.currency)
                put("exchangeRate", bill.exchangeRate)
                put("to_asset_name", bill.toAccountName)
                put("fee", bill.fee)
                if (bill.relatedBillId != null) {
                    put("relatedBillId", bill.relatedBillId)
                }
            })
        }
        return JSONObject().apply {
            put("bills", arr)
            put("deprecatedBillIds", JSONArray(deprecatedBillIds.toList()))
        }.toString()
    }

    private fun appendUserMessage(text: String, type: Int, imageUri: String = "") {
        val item = ChatDisplayItem(
            msgType = type,
            content = text,
            imageUri = imageUri,
            timestamp = System.currentTimeMillis()
        )
        displayMessages.add(item)
        adapter.notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom()

        lifecycleScope.launch(Dispatchers.IO) {
            val id = db.chatMessageDao().insert(
                ChatMessage(
                    msgType = type,
                    content = text,
                    imageUri = imageUri,
                    timestamp = item.timestamp,
                    bookName = currentBookName,
                    conversationId = currentConversationId
                )
            )
            withContext(Dispatchers.Main) {
                val idx = displayMessages.indexOfLast { it.timestamp == item.timestamp && it.msgType == type }
                if (idx >= 0) displayMessages[idx] = displayMessages[idx].copy(dbId = id)
                lifecycleScope.launch {
                    refreshSessionRows()
                    if (::drawerSessions.isInitialized && drawerSessions.isDrawerOpen(GravityCompat.END) &&
                        etSessionSearch.text?.toString().orEmpty().isBlank()
                    ) {
                        rvSessionList.adapter = sessionAdapter
                        sessionAdapter.submit(allSessionRows.toList())
                    }
                }
            }
        }
    }

    private fun appendUserVoiceMessage(audioFile: File, durationSec: Int, transcript: String): ChatDisplayItem {
        val payload = buildVoicePayload(audioFile.absolutePath, durationSec, transcript)
        val item = ChatDisplayItem(
            msgType = MSG_TYPE_USER_VOICE,
            content = payload,
            timestamp = System.currentTimeMillis(),
            voice = VoicePayload(
                audioPath = audioFile.absolutePath,
                durationSec = durationSec,
                transcript = transcript
            )
        )
        pendingVoiceBubbleAnimations += audioFile.absolutePath
        displayMessages.add(item)
        adapter.notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom()

        lifecycleScope.launch(Dispatchers.IO) {
            val id = db.chatMessageDao().insert(
                ChatMessage(
                    msgType = MSG_TYPE_USER_VOICE,
                    content = payload,
                    timestamp = item.timestamp,
                    bookName = currentBookName,
                    conversationId = currentConversationId
                )
            )
            withContext(Dispatchers.Main) {
                val idx = displayMessages.indexOfLast { it.timestamp == item.timestamp && it.msgType == MSG_TYPE_USER_VOICE }
                if (idx >= 0) {
                    displayMessages[idx] = displayMessages[idx].copy(dbId = id)
                    val latestPayload = displayMessages[idx].content
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.chatMessageDao().getById(id)?.let { stored ->
                            if (stored.content != latestPayload) {
                                db.chatMessageDao().update(stored.copy(content = latestPayload))
                            }
                        }
                    }
                }
                refreshSessionRows()
            }
        }
        return item
    }

    private fun appendAiTextMessage(text: String, isLoading: Boolean): Int {
        val item = ChatDisplayItem(
            msgType = MSG_TYPE_AI_TEXT,
            content = text,
            timestamp = System.currentTimeMillis(),
            isLoading = isLoading
        )
        displayMessages.add(item)
        val idx = displayMessages.lastIndex
        adapter.notifyItemInserted(idx)
        scrollToBottom()

        if (!isLoading && text.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.chatMessageDao().insert(
                    ChatMessage(
                        msgType = MSG_TYPE_AI_TEXT,
                        content = text,
                        modelName = Prefs.getAiChatModel(this@ChatActivity),
                        bookName = currentBookName,
                        conversationId = currentConversationId
                    )
                )
                withContext(Dispatchers.Main) {
                    refreshSessionRows()
                    if (::drawerSessions.isInitialized && drawerSessions.isDrawerOpen(GravityCompat.END) &&
                        etSessionSearch.text?.toString().orEmpty().isBlank()
                    ) {
                        rvSessionList.adapter = sessionAdapter
                        sessionAdapter.submit(allSessionRows.toList())
                    }
                }
            }
        }
        return idx
    }

    private fun removeLoadingMessage(idx: Int) {
        if (idx in displayMessages.indices && displayMessages[idx].isLoading) {
            displayMessages.removeAt(idx)
            adapter.notifyItemRemoved(idx)
            scrollToBottom()
        }
    }

    private fun mapProgressToNaturalStatus(raw: String): Pair<Int, String> {
        val text = raw.trim()
        if (text.isBlank()) return 1 to "正在理解你的消息..."
        val lower = text.lowercase(Locale.getDefault())
        return when {
            lower.contains("reply") ||
                lower.contains("respond") ||
                lower.contains("output") ||
                lower.contains("generate") ||
                lower.contains("生成") ||
                lower.contains("回复") -> 2 to "正在生成回复..."

            lower.contains("upload") ||
                lower.contains("audio") ||
                lower.contains("image") ||
                lower.contains("ocr") ||
                lower.contains("parse") ||
                lower.contains("extract") ||
                lower.contains("analy") ||
                lower.contains("thinking") ||
                lower.contains("理解") ||
                lower.contains("分析") -> 1 to "正在理解你的消息..."

            else -> 1 to "正在理解你的消息..."
        }
    }

    private fun updateLoadingMessage(idx: Int, text: String) {
        if (idx !in displayMessages.indices) return
        val current = displayMessages[idx]
        if (!current.isLoading) return
        displayMessages[idx] = current.copy(content = text)
        adapter.notifyItemChanged(idx)
        ensureLastMessageVisible()
    }

    private fun finalizeLoadingMessage(idx: Int, text: String) {
        if (idx !in displayMessages.indices) return
        val current = displayMessages[idx]
        if (!current.isLoading) return
        displayMessages[idx] = current.copy(content = text, isLoading = false)
        adapter.notifyItemChanged(idx)
        scrollToBottom()
        if (text.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.chatMessageDao().insert(
                    ChatMessage(
                        msgType = MSG_TYPE_AI_TEXT,
                        content = text,
                        modelName = Prefs.getAiChatModel(this@ChatActivity),
                        bookName = currentBookName,
                        conversationId = currentConversationId
                    )
                )
            }
        }
    }

    private fun scrollToBottom(force: Boolean = false) {
        if (!force && isInlineAmountEditing()) return
        if (displayMessages.isNotEmpty()) rvMessages.scrollToPosition(displayMessages.lastIndex)
    }

    private fun isInlineAmountEditing(): Boolean = inlineAmountEditingBillId != null

    private fun resolveEntryBookName(intent: Intent?): String {
        val fromIntent = intent?.getStringExtra(EXTRA_SOURCE_BOOK).orEmpty().trim()
        if (fromIntent.isNotEmpty()) return BookAccountManager.normalizeBookName(fromIntent)
        return BookAccountManager.getSelectedBook(this)
    }

    inner class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = displayMessages.size

        override fun getItemViewType(position: Int): Int = displayMessages[position].msgType

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                MSG_TYPE_USER_TEXT, MSG_TYPE_USER_IMAGE, MSG_TYPE_USER_VOICE ->
                    UserVH(inflater.inflate(R.layout.item_chat_user, parent, false))
                MSG_TYPE_AI_BILL ->
                    AiBillVH(inflater.inflate(R.layout.item_chat_bill, parent, false))
                else ->
                    AiTextVH(inflater.inflate(R.layout.item_chat_ai, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = displayMessages[position]
            when (holder) {
                is UserVH -> holder.bind(item)
                is AiTextVH -> holder.bind(item)
                is AiBillVH -> holder.bind(item)
            }
        }
    }

    inner class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvText: TextView = v.findViewById(R.id.tv_user_text)
        private val tvTime: TextView = v.findViewById(R.id.tv_user_time)
        private val ivImage: ImageView = v.findViewById(R.id.iv_user_image)
        private val layoutVoice: LinearLayout = v.findViewById(R.id.layout_user_voice)
        private val tvVoice: TextView = v.findViewById(R.id.tv_voice_text)
        private val ivVoicePlay: ImageView = v.findViewById(R.id.iv_voice_play)
        private val layoutVoiceWave: LinearLayout = v.findViewById(R.id.layout_voice_wave)
        private val layoutVoiceTranscript: LinearLayout = v.findViewById(R.id.layout_voice_transcript)
        private val tvVoiceTranscript: TextView = v.findViewById(R.id.tv_voice_transcript)
        private val ivVoiceTranscriptCopy: ImageView = v.findViewById(R.id.iv_voice_transcript_copy)
        private val waveBars by lazy {
            listOf(
                v.findViewById<View>(R.id.view_voice_bar_1),
                v.findViewById<View>(R.id.view_voice_bar_2),
                v.findViewById<View>(R.id.view_voice_bar_3),
                v.findViewById<View>(R.id.view_voice_bar_4),
                v.findViewById<View>(R.id.view_voice_bar_5)
            )
        }
        private val cbSelect: android.widget.CheckBox = v.findViewById(R.id.cb_user_select)
        private val ivUserAvatar: ImageView = v.findViewById(R.id.iv_user_avatar)

        fun bind(item: ChatDisplayItem) {
            tvTime.text = formatChatMessageTime(item.timestamp)
            tvTime.visibility = if (shouldShowTimestamp(adapterPosition, item.timestamp)) View.VISIBLE else View.GONE
            loadUserAvatar(ivUserAvatar)
            val supportsSelection = item.msgType == MSG_TYPE_USER_TEXT || item.msgType == MSG_TYPE_USER_VOICE
            cbSelect.visibility = if (isVoiceSelectionMode && supportsSelection) View.VISIBLE else View.GONE
            cbSelect.isChecked = selectedVoiceMessageIds.contains(item.dbId)
            when (item.msgType) {
                MSG_TYPE_USER_IMAGE -> {
                    tvText.visibility = View.GONE
                    layoutVoice.visibility = View.GONE
                    layoutVoiceTranscript.visibility = View.GONE
                    ivImage.visibility = View.VISIBLE
                    Glide.with(itemView.context)
                        .load(Uri.parse(item.imageUri))
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .into(ivImage)
                }
                MSG_TYPE_USER_VOICE -> {
                    tvText.visibility = View.GONE
                    ivImage.visibility = View.GONE
                    layoutVoice.visibility = View.VISIBLE
                    val voice = item.voice ?: parseVoicePayload(item.content)
                    tvVoice.text = "${voice.durationSec}''"
                    val width = (44 + voice.durationSec.coerceAtMost(45) * 3.4f) * itemView.resources.displayMetrics.density
                    layoutVoiceWave.layoutParams = layoutVoiceWave.layoutParams.apply {
                        this.width = width.toInt()
                    }
                    val isPlaying = currentPlayingPath == voice.audioPath && (mediaPlayer?.isPlaying == true)
                    ivVoicePlay.setImageResource(
                        if (isPlaying) R.drawable.ic_voice_pause_telegram else R.drawable.ic_voice_play_telegram
                    )
                    bindWaveBars(voice.audioPath, isPlaying)
                    bindVoiceTranscript(voice)
                    maybeAnimateFreshVoiceBubble(voice.audioPath)
                    maybeAnimateTranscriptReveal(voice.audioPath)
                    layoutVoice.alpha = if (isVoiceSelectionMode && selectedVoiceMessageIds.contains(item.dbId)) 0.8f else 1f
                    layoutVoice.setOnClickListener {
                        if (isVoiceSelectionMode) toggleVoiceSelection(item) else playVoiceMessage(item)
                    }
                    layoutVoice.setOnLongClickListener {
                        showVoiceMessageMenu(layoutVoice, item)
                        true
                    }
                }
                else -> {
                    tvText.visibility = View.VISIBLE
                    ivImage.visibility = View.GONE
                    layoutVoice.visibility = View.GONE
                    layoutVoiceTranscript.visibility = View.GONE
                    tvText.text = item.content
                    tvText.alpha = if (isVoiceSelectionMode && selectedVoiceMessageIds.contains(item.dbId)) 0.8f else 1f
                    itemView.setOnClickListener {
                        if (isVoiceSelectionMode) toggleVoiceSelection(item)
                    }
                    itemView.setOnLongClickListener {
                        showTextMessageMenu(tvText, item)
                        true
                    }
                }
            }
            if (item.msgType != MSG_TYPE_USER_VOICE) {
                layoutVoice.setOnClickListener(null)
                layoutVoice.setOnLongClickListener(null)
                layoutVoiceTranscript.setOnLongClickListener(null)
                ivVoiceTranscriptCopy.setOnClickListener(null)
            }
            ivUserAvatar.setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_PICK).apply { type = "image/*" }, REQ_PICK_USER_AVATAR)
            }
            itemView.setOnClickListener {
                if (isVoiceSelectionMode && supportsSelection) toggleVoiceSelection(item)
            }
        }

        private fun bindVoiceTranscript(voice: VoicePayload) {
            val transcript = voice.transcript.trim()
            if (transcript.isBlank()) {
                layoutVoiceTranscript.visibility = View.GONE
                layoutVoiceTranscript.alpha = 1f
                layoutVoiceTranscript.translationY = 0f
                ivVoiceTranscriptCopy.setOnClickListener(null)
                layoutVoiceTranscript.setOnLongClickListener(null)
                return
            }
            layoutVoiceTranscript.visibility = View.VISIBLE
            tvVoiceTranscript.text = transcript
            ivVoiceTranscriptCopy.setOnClickListener {
                copyToClipboard("voice_transcript", transcript, "已复制转写文本")
            }
            layoutVoiceTranscript.setOnLongClickListener {
                copyToClipboard("voice_transcript", transcript, "已复制转写文本")
                true
            }
        }

        private fun bindWaveBars(audioPath: String, isPlaying: Boolean) {
            waveBars.forEachIndexed { index, bar ->
                bar.animate().cancel()
                if (isPlaying) {
                    val minScale = 0.45f + index * 0.08f
                    bar.scaleY = minScale
                    bar.alpha = 0.55f + index * 0.08f
                    animateWaveBar(bar, audioPath, index)
                } else {
                    bar.scaleY = 1f
                    bar.alpha = 0.85f
                }
            }
        }

        private fun maybeAnimateFreshVoiceBubble(audioPath: String) {
            if (!pendingVoiceBubbleAnimations.remove(audioPath)) return
            layoutVoice.animate().cancel()
            layoutVoice.alpha = 0f
            layoutVoice.translationX = 20f
            layoutVoice.scaleX = 0.97f
            layoutVoice.scaleY = 0.97f
            layoutVoice.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .start()
        }

        private fun maybeAnimateTranscriptReveal(audioPath: String) {
            if (layoutVoiceTranscript.visibility != View.VISIBLE) return
            if (!pendingTranscriptRevealAnimations.remove(audioPath)) return
            layoutVoiceTranscript.animate().cancel()
            layoutVoiceTranscript.alpha = 0f
            layoutVoiceTranscript.translationY = 10f
            layoutVoiceTranscript.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200L)
                .start()
        }

        private fun animateWaveBar(bar: View, audioPath: String, index: Int) {
            val targetScale = 1.4f - index * 0.08f
            val duration = 170L + index * 45L
            bar.animate()
                .scaleY(targetScale)
                .alpha(1f)
                .setDuration(duration)
                .withEndAction {
                    if (currentPlayingPath == audioPath) {
                        bar.animate()
                            .scaleY(0.45f + index * 0.08f)
                            .alpha(0.55f + index * 0.08f)
                            .setDuration(duration)
                            .withEndAction {
                                if (currentPlayingPath == audioPath) {
                                    animateWaveBar(bar, audioPath, index)
                                }
                            }
                            .start()
                    }
                }
                .start()
        }
    }

    private fun copyToClipboard(label: String, text: String, toast: String = "已复制") {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        Utils.toast(this, toast)
    }

    inner class ModelOptionAdapter(
        private val current: String,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<ModelOptionAdapter.VH>() {
        private val list = mutableListOf<String>()

        fun submit(data: List<String>) {
            list.clear()
            list.addAll(data)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_option, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(list[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvName: TextView = v.findViewById(R.id.tv_model_name)
            private val tvTag: TextView = v.findViewById(R.id.tv_model_selected_tag)
            fun bind(model: String) {
                tvName.text = model
                tvTag.visibility = if (model == current) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onSelect(model) }
            }
        }
    }

    inner class SessionListAdapter(
        private val onClick: (ChatSessionRow) -> Unit,
        private val onRename: (ChatSessionRow, String) -> Unit,
        private val onDelete: (ChatSessionRow) -> Unit
    ) : RecyclerView.Adapter<SessionListAdapter.VH>() {
        private val list = mutableListOf<ChatSessionRow>()
        private var openedPosition: Int = RecyclerView.NO_POSITION
        private var editingPosition: Int = RecyclerView.NO_POSITION

        fun submit(data: List<ChatSessionRow>) {
            val openedKey = list.getOrNull(openedPosition)?.let { it.bookName to it.conversationId }
            val editingKey = list.getOrNull(editingPosition)?.let { it.bookName to it.conversationId }
            list.clear()
            list.addAll(data)
            openedPosition = list.indexOfFirst {
                (it.bookName to it.conversationId) == openedKey
            }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
            editingPosition = list.indexOfFirst {
                (it.bookName to it.conversationId) == editingKey
            }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
            notifyDataSetChanged()
        }

        fun closeSwipeActions() {
            if (openedPosition == RecyclerView.NO_POSITION) return
            val old = openedPosition
            openedPosition = RecyclerView.NO_POSITION
            notifyItemChanged(old)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_session_drawer, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = list.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(
                item = list[position],
                opened = position == openedPosition,
                editing = position == editingPosition
            )
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val foreground: View = v.findViewById(R.id.layout_session_foreground)
            private val tvTitle: TextView = v.findViewById(R.id.tv_session_title)
            private val etTitle: EditText = v.findViewById(R.id.et_session_title)
            private val tvPreview: TextView = v.findViewById(R.id.tv_session_preview)
            private val tvTime: TextView = v.findViewById(R.id.tv_session_time)
            private val btnRename: ImageView = v.findViewById(R.id.btn_session_rename)
            private val btnDelete: ImageView = v.findViewById(R.id.btn_session_delete)
            private val slop = ViewConfiguration.get(v.context).scaledTouchSlop
            private val actionsWidthPx = 120f * v.resources.displayMetrics.density
            private var downX = 0f
            private var downY = 0f
            private var startTx = 0f
            private var dragging = false

            init {
                foreground.setOnTouchListener { _, ev -> onForegroundTouch(ev) }
                foreground.setOnClickListener {
                    val pos = adapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    if (editingPosition == pos) return@setOnClickListener
                    val item = list.getOrNull(pos) ?: return@setOnClickListener
                    if (openedPosition == pos) {
                        closeSwipeActions()
                        return@setOnClickListener
                    }
                    closeSwipeActions()
                    onClick(item)
                }
                btnRename.setOnClickListener {
                    val pos = adapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    closeSwipeActions()
                    startInlineEdit(pos)
                }
                btnDelete.setOnClickListener {
                    val pos = adapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val item = list.getOrNull(pos) ?: return@setOnClickListener
                    closeSwipeActions()
                    onDelete(item)
                }
                etTitle.setOnEditorActionListener { _, actionId, event ->
                    val imeDone = actionId == EditorInfo.IME_ACTION_DONE
                    val keyDone = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
                    if (imeDone || keyDone) {
                        commitInlineRename()
                        true
                    } else {
                        false
                    }
                }
                etTitle.setOnFocusChangeListener { _, hasFocus ->
                    val pos = adapterPosition
                    if (!hasFocus && pos != RecyclerView.NO_POSITION && editingPosition == pos) {
                        commitInlineRename()
                    }
                }
            }

            private fun onForegroundTouch(ev: MotionEvent): Boolean {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return false
                if (editingPosition == pos) return false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        startTx = foreground.translationX
                        dragging = false
                        if (openedPosition != RecyclerView.NO_POSITION && openedPosition != pos) {
                            val old = openedPosition
                            openedPosition = RecyclerView.NO_POSITION
                            notifyItemChanged(old)
                        }
                        itemView.parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (!dragging) {
                            when {
                                abs(dx) > slop && abs(dx) > abs(dy) -> dragging = true
                                abs(dy) > slop -> {
                                    itemView.parent?.requestDisallowInterceptTouchEvent(false)
                                    return false
                                }
                            }
                        }
                        if (dragging) {
                            val tx = (startTx + dx).coerceIn(-actionsWidthPx, 0f)
                            foreground.translationX = tx
                            return true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        itemView.parent?.requestDisallowInterceptTouchEvent(false)
                        if (dragging) {
                            settleSwipe(pos)
                            dragging = false
                            return true
                        }
                        if (ev.actionMasked == MotionEvent.ACTION_UP) {
                            foreground.performClick()
                            return true
                        }
                    }
                }
                return false
            }

            private fun settleSwipe(pos: Int) {
                val shouldOpen = foreground.translationX < -actionsWidthPx * 0.38f
                val target = if (shouldOpen) -actionsWidthPx else 0f
                if (shouldOpen) {
                    val old = openedPosition
                    openedPosition = pos
                    if (old != RecyclerView.NO_POSITION && old != pos) notifyItemChanged(old)
                } else if (openedPosition == pos) {
                    openedPosition = RecyclerView.NO_POSITION
                }
                foreground.animate().translationX(target).setDuration(180L).start()
            }

            private fun startInlineEdit(pos: Int) {
                val old = editingPosition
                editingPosition = pos
                if (old != RecyclerView.NO_POSITION && old != pos) notifyItemChanged(old)
                notifyItemChanged(pos)
            }

            private fun commitInlineRename() {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val item = list.getOrNull(pos) ?: return
                val newTitle = etTitle.text?.toString().orEmpty().trim()
                editingPosition = RecyclerView.NO_POSITION
                hideKeyboard(etTitle)
                notifyItemChanged(pos)
                if (newTitle.isBlank() || newTitle == item.title) return
                onRename(item, newTitle)
            }

            private fun hideKeyboard(view: View) {
                val imm = view.context.getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            }

            fun bind(item: ChatSessionRow, opened: Boolean, editing: Boolean) {
                tvTitle.text = item.title
                tvPreview.text = item.preview
                tvTime.text = item.displayTime
                foreground.animate().cancel()
                foreground.translationX = if (opened) -actionsWidthPx else 0f
                val bgRes = when {
                    item.isCurrent -> R.drawable.bg_book_item_selected
                    else -> R.drawable.bg_book_item_normal
                }
                foreground.setBackgroundResource(bgRes)
                if (editing) {
                    tvTitle.visibility = View.GONE
                    etTitle.visibility = View.VISIBLE
                    if (etTitle.text?.toString() != item.title) etTitle.setText(item.title)
                    etTitle.post {
                        if (adapterPosition != RecyclerView.NO_POSITION && adapterPosition == editingPosition) {
                            etTitle.requestFocus()
                            etTitle.setSelection(etTitle.text?.length ?: 0)
                            val imm = etTitle.context.getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.showSoftInput(etTitle, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                } else {
                    tvTitle.visibility = View.VISIBLE
                    etTitle.visibility = View.GONE
                }
            }
        }
    }

    inner class DrawerSearchResultAdapter(
        private val onClick: (ChatMessage) -> Unit
    ) : RecyclerView.Adapter<DrawerSearchResultAdapter.VH>() {
        private val list = mutableListOf<ChatMessage>()

        fun submit(data: List<ChatMessage>) {
            list.clear()
            list.addAll(data)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_search_result, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = list.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(list[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvSender: TextView = v.findViewById(R.id.tv_search_sender)
            private val tvTime: TextView = v.findViewById(R.id.tv_search_time)
            private val tvContent: TextView = v.findViewById(R.id.tv_search_content)

            fun bind(msg: ChatMessage) {
                val isUser = msg.msgType in listOf(MSG_TYPE_USER_TEXT, MSG_TYPE_USER_IMAGE, MSG_TYPE_USER_VOICE)
                tvSender.text = if (isUser) "我" else Prefs.getAiChatName(this@ChatActivity)
                tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                tvContent.text = when (msg.msgType) {
                    MSG_TYPE_USER_IMAGE -> "[图片]"
                    MSG_TYPE_USER_VOICE -> {
                        val transcript = parseVoicePayload(msg.content).transcript
                        if (transcript.isNotBlank()) "[语音] $transcript" else "[语音]"
                    }
                    MSG_TYPE_AI_BILL -> {
                        val bill = parseBillsFromMessageContent(msg.content).lastOrNull()
                        val remark = bill?.remark?.takeIf { it.isNotBlank() }
                            ?: bill?.categoryName
                            ?: "账单记录"
                        "账单：$remark"
                    }
                    else -> msg.content.trim().ifBlank { "(空内容)" }.take(100)
                }
                itemView.setOnClickListener { onClick(msg) }
            }
        }
    }

    inner class AiTextVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvText: TextView = v.findViewById(R.id.tv_ai_text)
        private val tvTime: TextView = v.findViewById(R.id.tv_ai_time)
        private val loading: LinearLayout = v.findViewById(R.id.layout_ai_loading)
        private val tvLoading: TextView = v.findViewById(R.id.tv_ai_loading_text)
        private val ivAvatar: ImageView = v.findViewById(R.id.iv_ai_avatar_msg)
        private val cbSelect: android.widget.CheckBox = v.findViewById(R.id.cb_ai_text_select)

        fun bind(item: ChatDisplayItem) {
            tvTime.text = formatChatMessageTime(item.timestamp)
            tvTime.visibility = if (shouldShowTimestamp(adapterPosition, item.timestamp)) View.VISIBLE else View.GONE
            loadAiAvatar(ivAvatar)
            cbSelect.visibility = if (isVoiceSelectionMode && !item.isLoading && item.content.isNotBlank()) View.VISIBLE else View.GONE
            cbSelect.isChecked = selectedVoiceMessageIds.contains(item.dbId)
            if (item.isLoading) {
                loading.visibility = View.VISIBLE
                tvText.visibility = View.GONE
                tvLoading.text = item.content.ifBlank { "分析中..." }
            } else {
                loading.visibility = View.GONE
                tvText.visibility = View.VISIBLE
                tvText.text = item.content
            }
            tvText.alpha = if (isVoiceSelectionMode && selectedVoiceMessageIds.contains(item.dbId)) 0.8f else 1f
            itemView.setOnClickListener {
                if (isVoiceSelectionMode && !item.isLoading && item.content.isNotBlank()) {
                    toggleVoiceSelection(item)
                }
            }
            itemView.setOnLongClickListener {
                if (item.isLoading || item.content.isBlank()) return@setOnLongClickListener false
                showTextMessageMenu(tvText, item)
                true
            }
        }
    }

    inner class AiBillVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvTime: TextView = v.findViewById(R.id.tv_ai_bill_time)
        private val container: LinearLayout = v.findViewById(R.id.container_bills)
        private val ivAvatar: ImageView = v.findViewById(R.id.iv_ai_avatar_bill)

        fun bind(item: ChatDisplayItem) {
            tvTime.text = formatChatMessageTime(item.timestamp)
            tvTime.visibility = if (shouldShowTimestamp(adapterPosition, item.timestamp)) View.VISIBLE else View.GONE
            loadAiAvatar(ivAvatar)
            container.removeAllViews()

            item.bills.forEachIndexed { index, bill ->
                val deprecated = item.isDeprecated || item.deprecatedBillIds.contains(bill.id)
                val card = LayoutInflater.from(itemView.context)
                    .inflate(R.layout.item_chat_bill_card, container, false)
                val tvCat = card.findViewById<TextView>(R.id.tv_chat_bill_category)
                val tvDetail = card.findViewById<TextView>(R.id.tv_chat_bill_detail)
                val tvAmount = card.findViewById<TextView>(R.id.tv_chat_bill_amount)
                val etAmount = card.findViewById<android.widget.EditText>(R.id.et_chat_bill_amount)
                val tvBillTime = card.findViewById<TextView>(R.id.tv_chat_bill_time)
                val ivIcon = card.findViewById<ImageView>(R.id.iv_chat_bill_icon)
                val btnEdit = card.findViewById<TextView>(R.id.btn_chat_bill_edit_category)
                val btnDelete = card.findViewById<TextView>(R.id.btn_chat_bill_delete)
                val isTransfer = bill.type == Bill.TYPE_TRANSFER

                tvCat.text = bill.categoryName
                tvDetail.text = listOf(bill.accountName, bill.remark).filter { it.isNotBlank() }.joinToString(" | ")
                val sign = when (bill.type) {
                    Bill.TYPE_INCOME -> "+"
                    Bill.TYPE_TRANSFER -> ""
                    else -> "-"
                }
                val amountText = String.format(Locale.getDefault(), "%.2f", bill.amount)
                tvAmount.text = "$sign$amountText"
                val amountColor = when (bill.type) {
                    Bill.TYPE_INCOME -> Color.parseColor("#2E7D32")
                    Bill.TYPE_TRANSFER -> Color.parseColor("#7A8598")
                    else -> Color.parseColor("#D32F2F")
                }
                val amountBgRes = when (bill.type) {
                    Bill.TYPE_INCOME -> R.drawable.bg_chat_bill_amount_tag_income
                    Bill.TYPE_TRANSFER -> R.drawable.bg_chat_bill_amount_tag_transfer
                    else -> R.drawable.bg_chat_bill_amount_tag_expense
                }
                val amountBgActiveRes = when (bill.type) {
                    Bill.TYPE_INCOME -> R.drawable.bg_chat_bill_amount_tag_income_active
                    Bill.TYPE_TRANSFER -> R.drawable.bg_chat_bill_amount_tag_transfer_active
                    else -> R.drawable.bg_chat_bill_amount_tag_expense_active
                }
                tvAmount.setTextColor(amountColor)
                etAmount.setTextColor(amountColor)
                tvAmount.setBackgroundResource(amountBgRes)
                etAmount.setBackgroundResource(amountBgActiveRes)
                etAmount.setText(amountText)
                etAmount.setSelection(etAmount.text?.length ?: 0)
                tvAmount.visibility = View.VISIBLE
                etAmount.visibility = View.GONE
                tvBillTime.text = formatTime(bill.time)
                if (deprecated) {
                    val strike = android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    tvCat.paintFlags = tvCat.paintFlags or strike
                    tvDetail.paintFlags = tvDetail.paintFlags or strike
                    tvAmount.paintFlags = tvAmount.paintFlags or strike
                    tvBillTime.paintFlags = tvBillTime.paintFlags or strike
                    card.alpha = 0.55f
                    btnEdit.visibility = View.GONE
                    btnDelete.visibility = View.GONE
                    etAmount.isEnabled = false
                } else {
                    card.alpha = 1f
                    btnEdit.visibility = if (isTransfer) View.GONE else View.VISIBLE
                    btnDelete.visibility = View.VISIBLE
                    etAmount.isEnabled = true
                }
                val iconTint = when (bill.type) {
                    0 -> Color.parseColor("#D32F2F")
                    1 -> Color.parseColor("#2E7D32")
                    else -> Color.parseColor("#7A8598")
                }
                ivIcon.setImageResource(android.R.drawable.ic_menu_info_details)
                ivIcon.setColorFilter(iconTint)
                lifecycleScope.launch {
                    val iconUrl = withContext(Dispatchers.IO) {
                        CategoryIconHelper.findCategoryIcon(this@ChatActivity, bill.categoryName, bill.type)
                    }
                    if (iconUrl.isNotBlank()) {
                        Glide.with(ivIcon.context)
                            .load(iconUrl)
                            .diskCacheStrategy(DiskCacheStrategy.DATA)
                            .into(ivIcon)
                    }
                }

                btnEdit.setOnClickListener {
                    if (deprecated) return@setOnClickListener
                    val pickerType = if (bill.type == 1) 1 else 0
                    OverlayDialogs.showGridCategoryPicker(this@ChatActivity, bill.categoryName, pickerType) { selected ->
                        val originalBill = bill.copy()
                        lifecycleScope.launch {
                            val updated = withContext(Dispatchers.IO) {
                                val categoryEntity = CategoryRepository(db.categoryDao()).findCategoryByDisplayName(
                                    pickerType,
                                    selected
                                )
                                BillMutationService.replaceBill(
                                    db = db,
                                    oldBill = bill,
                                    newBill = bill.copy(categoryName = selected, categoryId = categoryEntity?.id)
                                )
                            }
                            val msgIdx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                            if (msgIdx >= 0) {
                                val rowIdx = displayMessages[msgIdx].bills.indexOfFirst { it.id == bill.id }
                                if (rowIdx >= 0) {
                                    displayMessages[msgIdx].bills[rowIdx] = updated
                                    adapter.notifyItemChanged(msgIdx)
                                }
                            }
                            maybeShowRuleDialogForChatBillCategoryEdit(item, originalBill, updated)
                        }
                    }
                }
                var savingAmount = false
                fun closeInlineAmountEdit(hideKeyboard: Boolean) {
                    if (hideKeyboard) hideSoftKeyboard(etAmount)
                    etAmount.clearFocus()
                    etAmount.visibility = View.GONE
                    tvAmount.visibility = View.VISIBLE
                    tvAmount.setBackgroundResource(amountBgRes)
                    if (inlineAmountEditingBillId == bill.id) {
                        inlineAmountEditingBillId = null
                    }
                }
                fun startInlineAmountEdit() {
                    if (deprecated || savingAmount) return
                    if (inlineAmountEditingBillId != null && inlineAmountEditingBillId != bill.id) {
                        Utils.toast(this@ChatActivity, "请先完成当前金额编辑")
                        return
                    }
                    inlineAmountEditingBillId = bill.id
                    tvAmount.visibility = View.GONE
                    etAmount.visibility = View.VISIBLE
                    tvAmount.setBackgroundResource(amountBgRes)
                    etAmount.setBackgroundResource(amountBgActiveRes)
                    etAmount.setText(String.format(Locale.getDefault(), "%.2f", bill.amount))
                    etAmount.setSelection(etAmount.text?.length ?: 0)
                    etAmount.requestFocus()
                    showSoftKeyboard(etAmount)
                }
                fun commitInlineAmountEdit() {
                    if (deprecated || savingAmount || etAmount.visibility != View.VISIBLE) return
                    val value = etAmount.text?.toString().orEmpty().trim()
                    val editedAmount = value.toDoubleOrNull()
                    if (editedAmount == null || !editedAmount.isFinite() || editedAmount <= 0.0) {
                        Utils.toast(this@ChatActivity, "请输入有效金额")
                        etAmount.requestFocus()
                        return
                    }
                    val changed = kotlin.math.abs(editedAmount - bill.amount) > 0.000001
                    if (!changed) {
                        closeInlineAmountEdit(hideKeyboard = true)
                        return
                    }
                    savingAmount = true
                    lifecycleScope.launch {
                        val updated = withContext(Dispatchers.IO) {
                            val updatedBill = when {
                                bill.subType == Bill.SUBTYPE_REFUND -> bill.copy(amount = editedAmount)
                                bill.type == Bill.TYPE_EXPENSE -> bill.copy(amount = editedAmount, originalAmount = editedAmount)
                                else -> bill.copy(amount = editedAmount)
                            }
                            BillMutationService.replaceBill(
                                db = db,
                                oldBill = bill,
                                newBill = updatedBill
                            )
                        }
                        savingAmount = false
                        closeInlineAmountEdit(hideKeyboard = true)
                        val msgIdx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                        if (msgIdx >= 0) {
                            val rowIdx = displayMessages[msgIdx].bills.indexOfFirst { it.id == bill.id }
                            if (rowIdx >= 0) {
                                displayMessages[msgIdx].bills[rowIdx] = updated
                                adapter.notifyItemChanged(msgIdx)
                            }
                        }
                    }
                }
                tvAmount.setOnClickListener { startInlineAmountEdit() }
                etAmount.setOnEditorActionListener { _, actionId, event ->
                    val imeDone = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    val enterDown = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        event.action == android.view.KeyEvent.ACTION_DOWN
                    if (imeDone || enterDown) {
                        commitInlineAmountEdit()
                        true
                    } else {
                        false
                    }
                }
                etAmount.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && !savingAmount && etAmount.visibility == View.VISIBLE) {
                        closeInlineAmountEdit(hideKeyboard = false)
                    }
                }
                btnDelete.setOnClickListener {
                    if (deprecated) return@setOnClickListener
                    lifecycleScope.launch {
                        val deletedBillSnapshot = bill.copy()
                        withContext(Dispatchers.IO) {
                            if (bill.id > 0L) tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
                        }
                        val msgIdx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                        if (msgIdx >= 0) {
                            if (displayMessages[msgIdx].bills.size <= 1) {
                                displayMessages[msgIdx] = displayMessages[msgIdx].copy(
                                    bills = mutableListOf(deletedBillSnapshot),
                                    isDeprecated = true,
                                    deprecatedBillIds = mutableSetOf(deletedBillSnapshot.id)
                                )
                                val msgId = displayMessages[msgIdx].dbId
                                if (msgId > 0L) {
                                    withContext(Dispatchers.IO) {
                                        db.chatMessageDao().getById(msgId)?.let { oldMsg ->
                                            db.chatMessageDao().update(
                                                oldMsg.copy(
                                                    billIds = markBillIdsAsDeprecated(oldMsg.billIds),
                                                    content = buildBillMessageContent(listOf(deletedBillSnapshot), setOf(deletedBillSnapshot.id))
                                                )
                                            )
                                        }
                                    }
                                }
                                adapter.notifyItemChanged(msgIdx)
                            } else {
                                val currentItem = displayMessages[msgIdx]
                                val updatedBills = currentItem.bills.toMutableList()
                                updatedBills[index] = deletedBillSnapshot
                                val updatedDeprecatedIds = currentItem.deprecatedBillIds.toMutableSet().apply {
                                    add(deletedBillSnapshot.id)
                                }
                                displayMessages[msgIdx] = currentItem.copy(
                                    bills = updatedBills,
                                    deprecatedBillIds = updatedDeprecatedIds
                                )
                                val msgId = currentItem.dbId
                                if (msgId > 0L) {
                                    withContext(Dispatchers.IO) {
                                        db.chatMessageDao().getById(msgId)?.let { oldMsg ->
                                            db.chatMessageDao().update(
                                                oldMsg.copy(
                                                    content = buildBillMessageContent(updatedBills, updatedDeprecatedIds)
                                                )
                                            )
                                        }
                                    }
                                }
                                adapter.notifyItemChanged(msgIdx)
                            }
                        }
                    }
                }

                container.addView(card)
            }
            if (item.isDeprecated) {
                container.alpha = 0.55f
            } else {
                container.alpha = 1f
            }
        }
    }

    private fun loadUserAvatar(iv: ImageView) {
        val path = Prefs.getUserChatAvatarPath(this)
        val file = if (path.isNotBlank()) File(path) else null
        if (file != null && file.exists()) {
            GlideLocalFiles.load(
                target = iv,
                file = file,
                placeholderRes = R.drawable.ic_user_avatar_default,
                circleCrop = true
            )
        } else {
            iv.setImageResource(R.drawable.ic_user_avatar_default)
        }
    }

    private fun loadAiAvatar(iv: ImageView) {
        val path = Prefs.getAiChatAvatarPath(this)
        val file = if (path.isNotBlank()) File(path) else null
        if (file != null && file.exists()) {
            GlideLocalFiles.load(
                target = iv,
                file = file,
                placeholderRes = R.drawable.ic_ai_default_avatar,
                circleCrop = true
            )
        } else {
            iv.setImageResource(R.drawable.ic_ai_default_avatar)
        }
    }

    private fun showSoftKeyboard(view: View) {
        view.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSoftKeyboard(view: View) {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    private fun formatChatMessageTime(ms: Long): String {
        val nowMs = System.currentTimeMillis()
        if (ms > nowMs) return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

        val diffMs = nowMs - ms
        if (diffMs < 60_000L) return "刚刚"
        if (diffMs < 60L * 60L * 1000L) return "${diffMs / 60_000L} 分钟前"

        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val dayDiff = dayDiffFromToday(target)
        return when {
            dayDiff == 0L -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
            dayDiff == 1L -> "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
            dayDiff in 2L..6L -> "${weekdayLabel(target)} ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
            now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) ->
                SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(ms))
            else -> SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(ms))
        }
    }

    private fun shouldShowTimestamp(position: Int, timestamp: Long): Boolean {
        if (position <= 0) return true
        val prev = displayMessages.getOrNull(position - 1)?.timestamp ?: return true
        return (timestamp - prev) >= 10 * 60 * 1000L
    }

    private fun dayDiffFromToday(target: java.util.Calendar): Long {
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val other = target.clone() as java.util.Calendar
        other.set(java.util.Calendar.HOUR_OF_DAY, 0)
        other.set(java.util.Calendar.MINUTE, 0)
        other.set(java.util.Calendar.SECOND, 0)
        other.set(java.util.Calendar.MILLISECOND, 0)
        return (today.timeInMillis - other.timeInMillis) / (24L * 60L * 60L * 1000L)
    }

    private fun weekdayLabel(calendar: java.util.Calendar): String =
        when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "周一"
            java.util.Calendar.TUESDAY -> "周二"
            java.util.Calendar.WEDNESDAY -> "周三"
            java.util.Calendar.THURSDAY -> "周四"
            java.util.Calendar.FRIDAY -> "周五"
            java.util.Calendar.SATURDAY -> "周六"
            else -> "周日"
        }

    override fun onDestroy() {
        super.onDestroy()
        clearPendingLongPress()
        stopVoicePlayback()
        if (isRecording) {
            stopVoiceRecording { _, _ -> }
        }
    }

    override fun onBackPressed() {
        if (isVoiceSelectionMode) {
            exitVoiceSelectionMode()
            return
        }
        if (::drawerSessions.isInitialized && drawerSessions.isDrawerOpen(GravityCompat.END)) {
            drawerSessions.closeDrawer(GravityCompat.END)
            return
        }
        super.onBackPressed()
    }
}

data class ChatDisplayItem(
    val dbId: Long = 0,
    val msgType: Int,
    val content: String = "",
    val imageUri: String = "",
    val voice: VoicePayload? = null,
    val bills: MutableList<Bill> = mutableListOf(),
    val timestamp: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val isDeprecated: Boolean = false,
    val deprecatedBillIds: MutableSet<Long> = mutableSetOf()
)

data class VoicePayload(
    val audioPath: String = "",
    val durationSec: Int = 1,
    val transcript: String = ""
)

data class ChatSessionRow(
    val bookName: String,
    val conversationId: String,
    val title: String,
    val preview: String,
    val displayTime: String,
    val timestamp: Long,
    val isCurrent: Boolean
)

data class HabitRuleSuggestion(
    val keyword: String,
    val targetType: Int?,
    val targetCategory: String?,
    val targetAccount1: String?,
    val targetAccount2: String?
) {
    val summaryText: String
        get() = buildString {
            append("关键词：$keyword")
            targetCategory?.takeIf { it.isNotBlank() }?.let { append("，分类：$it") }
            targetAccount1?.takeIf { it.isNotBlank() }?.let { append("，账户：$it") }
            targetAccount2?.takeIf { it.isNotBlank() }?.let { append("，目标账户：$it") }
        }
}
