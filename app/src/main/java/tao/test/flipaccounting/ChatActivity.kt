package tao.test.flipaccounting

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioFormat
import android.media.AudioRecord
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
import androidx.core.view.WindowCompat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.ui.common.StatusBarStyle
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
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

class ChatActivity : AppCompatActivity() {
    private val maxVoiceRecordBytes = 8L * 1024L * 1024L
    private val maxVoiceRecordDurationSec = 180
    private enum class RuleSaveOutcome { SAVED, OVERWRITTEN, CANCELED }
    private data class PendingBillSelection(
        val token: String,
        val continuation: CancellableContinuation<Bill?>
    )

    private data class PendingBillConfirmation(
        val token: String,
        val continuation: CancellableContinuation<Boolean>
    )

    companion object {
        const val MSG_TYPE_USER_TEXT = 0
        const val MSG_TYPE_USER_IMAGE = 1
        const val MSG_TYPE_USER_VOICE = 2
        const val MSG_TYPE_AI_TEXT = 3
        const val MSG_TYPE_AI_BILL = 4
        const val BILL_INTERACTION_NONE = 0
        const val BILL_INTERACTION_SELECT_TARGET = 1
        const val BILL_INTERACTION_CONFIRM_MODIFICATION = 2
        const val BILL_INTERACTIVE_ACTION_PRIMARY = 1
        const val BILL_INTERACTIVE_ACTION_SECONDARY = 2

        const val EXTRA_SOURCE_BOOK = "extra_source_book"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        private const val EXTRA_SCROLL_TO_MSG_ID = "scroll_to_msg_id"

        private const val REQ_PICK_IMAGE = 101
        private const val REQ_PICK_BG = 102
        private const val REQ_PICK_AI_AVATAR = 103
        private const val REQ_PICK_USER_AVATAR = 104
        private const val REQ_CROP_AI_AVATAR = 105
        private const val REQ_CROP_USER_AVATAR = 106
        private const val REQ_CROP_BG = 107
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
    private lateinit var layoutVoiceRecordOverlay: View
    private lateinit var ivVoiceRecordState: ImageView
    private lateinit var tvVoiceRecordTitle: TextView
    private lateinit var tvVoiceRecordSubtitle: TextView
    private lateinit var tvVoiceRecordTimer: TextView
    private lateinit var layoutVoiceSelectionBar: LinearLayout
    private lateinit var tvVoiceSelectionCount: TextView
    private lateinit var btnVoiceSelectionCancel: TextView
    private lateinit var btnVoiceSelectionDelete: TextView
    private lateinit var layoutChatInputRow: View

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val aiScopeJob = SupervisorJob()
    private val aiWorkScope = CoroutineScope(aiScopeJob + Dispatchers.Main.immediate)
    private val messagePipeline by lazy {
        ChatMessagePipeline(
            context = this,
            aiWorkScope = aiWorkScope,
            getInputText = { etInput.text?.toString().orEmpty() },
            clearInput = { etInput.setText("") },
            updateInputActionUi = ::updateInputActionUi,
            appendUserMessage = { text, type -> appendUserMessage(text, type) },
            consumePendingHabitSuggestionReply = ::consumePendingHabitSuggestionReply,
            appendAiTextMessage = { text, loading, bookName, conversationId ->
                appendAiTextMessage(text, loading, bookName, conversationId)
            },
            removeLoadingMessage = ::removeLoadingMessage,
            updateLoadingMessage = ::updateLoadingMessage,
            finalizeLoadingMessage = ::finalizeLoadingMessage,
            buildAnalysisInput = ::buildAnalysisInput,
            processBillResult = ::processBillResult,
            processBillModifyResult = { json, text, oldBill -> billCorrectionService.processBillModifyResult(json, text, oldBill) },
            buildBillSummary = ::buildBillSummary,
            transcribeVoiceToTextWithFallback = ::transcribeVoiceToTextWithFallback,
            chooseModifyTargetBill = ::chooseModifyTargetBill,
            persistAiTextMessage = ::persistAiTextMessage,
            db = db,
            getCurrentBookName = { currentBookName },
            getCurrentConversationId = { currentConversationId }
        )
    }
    private val billCorrectionService by lazy {
        ChatBillCorrectionService(
            context = this,
            db = db,
            displayMessages = displayMessages,
            adapterProvider = { adapter },
            appendAiTextMessage = { text, loading, bookName, conversationId ->
                appendAiTextMessage(text, loading, bookName, conversationId)
            },
            scrollToBottom = ::scrollToBottom,
            refreshSessionRows = ::refreshSessionRows,
            getCurrentBookName = { currentBookName },
            setCurrentBookName = { currentBookName = it },
            getCurrentConversationId = { currentConversationId },
            parseTimeToMillis = ::parseTimeToMillis,
            buildBillMessageContent = ::buildBillMessageContent,
            confirmBillModifyPreview = ::confirmBillModifyPreviewInChat
        )
    }
    private val voiceController: ChatVoiceController by lazy {
        ChatVoiceController(
            context = this,
            db = db,
            lifecycleScope = lifecycleScope,
            displayMessages = displayMessages,
            adapterProvider = { adapter },
            layoutVoiceSelectionBarProvider = { layoutVoiceSelectionBar },
            tvVoiceSelectionCountProvider = { tvVoiceSelectionCount },
            pendingTranscriptRevealAnimations = pendingTranscriptRevealAnimations,
            visibleTranscriptPaths = visibleTranscriptPaths,
            transcribingPaths = transcribingPaths,
            transcribeVoiceToTextWithFallback = ::transcribeVoiceToTextWithFallback,
            scrollToBottom = ::scrollToBottom,
            showCustomConfirmDialog = { title, message, confirmText, isDanger, onConfirm ->
                uiHelperController.showCustomConfirmDialog(title, message, confirmText, isDanger, onConfirm)
            },
            findDependentAssistantMessageIds = ::findDependentAssistantMessageIds,
            refreshSessionRows = { refreshSessionRows() }
        )
    }
    private val adapter: ChatAdapter by lazy {
        ChatAdapter(
            context = this,
            displayMessages = displayMessages,
            db = db,
            lifecycleScope = lifecycleScope,
            isMessageSelected = { voiceController.isItemSelected(it) },
            pendingVoiceBubbleAnimations = pendingVoiceBubbleAnimations,
            pendingTranscriptRevealAnimations = pendingTranscriptRevealAnimations,
            visibleTranscriptPaths = visibleTranscriptPaths,
            transcribingPaths = transcribingPaths,
            isVoiceSelectionMode = { voiceController.isVoiceSelectionMode() },
            currentPlayingPath = { voiceController.currentPlayingPath() },
            isMediaPlaying = { voiceController.isMediaPlaying() },
            onToggleVoiceSelection = ::toggleVoiceSelection,
            onPlayVoiceMessage = ::playVoiceMessage,
            onShowVoiceMessageMenu = ::showVoiceMessageMenu,
            onShowTranscriptMenu = ::showTranscriptMenu,
            onShowTextMessageMenu = ::showTextMessageMenu,
            parseVoicePayload = ::parseVoicePayload,
            copyToClipboard = { label, text, toast -> uiHelperController.copyToClipboard(label, text, toast) },
            loadUserAvatar = { iv -> uiHelperController.loadUserAvatar(iv) },
            loadAiAvatar = { iv -> uiHelperController.loadAiAvatar(iv) },
            formatChatMessageTime = { ms -> uiHelperController.formatChatMessageTime(ms) },
            shouldShowTimestamp = { position, timestamp -> uiHelperController.shouldShowTimestamp(position, timestamp) },
            formatTime = { ms -> uiHelperController.formatTime(ms) },
            showSoftKeyboard = { view -> uiHelperController.showSoftKeyboard(view) },
            hideSoftKeyboard = { view -> uiHelperController.hideSoftKeyboard(view) },
            getInlineAmountEditingBillId = { inlineAmountEditingBillId },
            setInlineAmountEditingBillId = { inlineAmountEditingBillId = it },
            onMaybeShowRuleDialogForChatBillCategoryEdit = ::maybeShowRuleDialogForChatBillCategoryEdit,
            showCustomConfirmDialog = { title, message, confirmText, isDanger, onConfirm ->
                uiHelperController.showCustomConfirmDialog(title, message, confirmText, isDanger, onConfirm)
            },
            onInteractiveBillAction = ::onInteractiveBillAction,
            onInterruptAiLoading = ::interruptAiResponse
        )
    }
    private val sessionAdapter by lazy {
        SessionListAdapter(
            onClick = { row ->
                messagePipeline.cancelCurrentRequest(showInterruptedMessage = false)
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
        DrawerSearchResultAdapter(
            onClick = { msg ->
                messagePipeline.cancelCurrentRequest(showInterruptedMessage = false)
                pendingScrollToMessageId = msg.id
                currentBookName = msg.bookName.ifBlank { currentBookName }
                currentConversationId = msg.conversationId.ifBlank { currentConversationId }
                drawerSessions.closeDrawer(GravityCompat.END)
                loadHistoryMessages()
            },
            aiNameProvider = { Prefs.getAiChatName(this) },
            parseVoicePayload = ::parseVoicePayload,
            parseBillsFromMessageContent = ::parseBillsFromMessageContent
        )
    }
    private val sessionController: ChatSessionController by lazy {
        ChatSessionController(
            context = this,
            db = db,
            lifecycleScope = lifecycleScope,
            drawerSessions = drawerSessions,
            drawerContainer = drawerContainer,
            etSessionSearch = etSessionSearch,
            btnNewSession = btnNewSession,
            btnReplyStyle = btnReplyStyle,
            btnChangeChatBg = btnChangeChatBg,
            btnClearCurrentSession = btnClearCurrentSession,
            rvSessionList = rvSessionList,
            sessionAdapter = sessionAdapter,
            searchResultAdapter = searchResultAdapter,
            allSessionRows = allSessionRows,
            displayMessages = displayMessages,
            adapterProvider = { adapter },
            getCurrentBookName = { currentBookName },
            setCurrentBookName = { currentBookName = it },
            getCurrentConversationId = { currentConversationId },
            setCurrentConversationId = { currentConversationId = it },
            newConversationId = ::newConversationId,
            loadHistoryMessages = ::loadHistoryMessages,
            parseVoicePayload = ::parseVoicePayload,
            parseBillIds = ::parseBillIds,
            parseDeprecatedBillIdsFromContent = ::parseDeprecatedBillIdsFromContent,
            parseBillsFromMessageContent = ::parseBillsFromMessageContent,
            isDeprecatedBillMessage = ::isDeprecatedBillMessage,
            showPageCenterDialog = { dialog, widthRatio -> uiHelperController.showPageCenterDialog(dialog, widthRatio) },
            showCustomConfirmDialog = { title, message, confirmText, isDanger, onConfirm ->
                uiHelperController.showCustomConfirmDialog(title, message, confirmText, isDanger, onConfirm)
            },
            onPickBgImage = { mediaController.pickBgImage() },
            onShowReplyStyleDialog = { panelController.showReplyStyleDialog() },
            onConversationSubtitleChanged = ::updateConversationSubtitle,
            cancelCurrentRequest = { messagePipeline.cancelCurrentRequest(showInterruptedMessage = false) }
        )
    }
    private val mediaController: ChatMediaController by lazy {
        ChatMediaController(
            context = this,
            lifecycleScope = lifecycleScope,
            tvAiNameProvider = { tvAiName },
            ivAiAvatarProvider = { ivAiAvatar },
            ivChatBgProvider = { ivChatBg },
            adapterProvider = { adapter },
            ensureAiImageFeatureEnabled = ::ensureAiImageFeatureEnabled,
            showPageCenterDialog = { dialog, widthRatio -> uiHelperController.showPageCenterDialog(dialog, widthRatio) },
            updateConversationSubtitle = ::updateConversationSubtitle,
            appendUserMessage = ::appendUserMessage,
            callAiAccounting = { text, appendUserBubble -> callAiAccounting(text, appendUserBubble = appendUserBubble) },
            appendAiTextMessage = { text, loading -> appendAiTextMessage(text, loading) },
            reqPickImage = REQ_PICK_IMAGE,
            reqPickBg = REQ_PICK_BG,
            reqCropBg = REQ_CROP_BG,
            reqPickAiAvatar = REQ_PICK_AI_AVATAR,
            reqPickUserAvatar = REQ_PICK_USER_AVATAR,
            reqCropAiAvatar = REQ_CROP_AI_AVATAR,
            reqCropUserAvatar = REQ_CROP_USER_AVATAR,
            msgTypeUserImage = MSG_TYPE_USER_IMAGE
        )
    }
    private val panelController: ChatPanelController by lazy {
        ChatPanelController(
            context = this,
            onConversationSubtitleChanged = ::updateConversationSubtitle,
            refreshVoiceSupportHint = ::refreshVoiceSupportHint,
            showPageBottomDialog = { dialog -> uiHelperController.showPageBottomDialog(dialog) }
        )
    }
    private val messageMenuController: ChatMessageMenuController by lazy {
        ChatMessageMenuController(
            context = this,
            parseVoicePayload = ::parseVoicePayload,
            hideVoiceTranscript = ::hideVoiceTranscript,
            transcribeVoiceMessage = ::transcribeVoiceMessage,
            isVoiceTranscriptVisible = ::isVoiceTranscriptVisible,
            copyToClipboard = { label, text, toast -> uiHelperController.copyToClipboard(label, text, toast) },
            enterVoiceSelectionMode = ::enterVoiceSelectionMode,
            requestDeleteFromLongPressMenu = ::requestDeleteFromLongPressMenu,
            isVoiceMode = { isVoiceMode },
            setVoiceMode = { isVoiceMode = it },
            updateVoiceModeUi = ::updateVoiceModeUi,
            etInputProvider = { etInput },
            showSoftKeyboard = { view -> uiHelperController.showSoftKeyboard(view) },
            updateInputActionUi = ::updateInputActionUi
        )
    }
    private val uiHelperController: ChatUiHelperController by lazy {
        ChatUiHelperController(
            context = this,
            displayMessagesProvider = { displayMessages }
        )
    }
    private val audioRecordController: ChatAudioRecordController by lazy {
        ChatAudioRecordController(
            context = this,
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            audioFormat = audioFormat,
            audioBufferSizeProvider = { audioBufferSize },
            btnVoiceHoldProvider = { btnVoiceHold },
            getAudioRecord = { audioRecord },
            setAudioRecord = { audioRecord = it },
            getAudioFile = { audioFile },
            setAudioFile = { audioFile = it },
            getRecordingThread = { recordingThread },
            setRecordingThread = { recordingThread = it },
            isRecording = { isRecording },
            setIsRecording = { isRecording = it },
            getRecordingStartAt = { recordingStartAt },
            setRecordingStartAt = { recordingStartAt = it },
            startRecordingButtonPulse = ::startRecordingButtonPulse,
            stopRecordingButtonPulse = ::stopRecordingButtonPulse,
            showVoiceRecordOverlay = ::showVoiceRecordOverlay,
            hideVoiceRecordOverlay = ::hideVoiceRecordOverlay,
            clearPendingLongPress = ::clearPendingLongPress
        )
    }
    private val historyController: ChatHistoryController by lazy {
        ChatHistoryController(
            context = this,
            db = db,
            lifecycleScope = lifecycleScope,
            displayMessages = displayMessages,
            adapterProvider = { adapter },
            rvMessagesProvider = { rvMessages },
            drawerSessionsProvider = { drawerSessions },
            etSessionSearchProvider = { etSessionSearch },
            rvSessionListProvider = { rvSessionList },
            sessionAdapterProvider = { sessionAdapter },
            allSessionRowsProvider = { allSessionRows },
            getCurrentBookName = { currentBookName },
            getCurrentConversationId = { currentConversationId },
            getPendingScrollToMessageId = { pendingScrollToMessageId },
            setPendingScrollToMessageId = { pendingScrollToMessageId = it },
            parseVoicePayload = ::parseVoicePayload,
            parseVoicePayloadStrict = ::parseVoicePayloadStrict,
            parseBillIds = ::parseBillIds,
            isDeprecatedBillMessage = ::isDeprecatedBillMessage,
            parseBillsFromMessageContent = ::parseBillsFromMessageContent,
            parseDeprecatedBillIdsFromContent = ::parseDeprecatedBillIdsFromContent,
            parseEditedBillIdsFromContent = ::parseEditedBillIdsFromContent,
            parseSnapshotOnlyFromContent = ::parseSnapshotOnlyFromContent,
            mergeChatBillSnapshots = ::mergeChatBillSnapshots,
            markBillIdsAsDeprecated = ::markBillIdsAsDeprecated,
            updateConversationSubtitle = ::updateConversationSubtitle,
            scrollToBottom = ::scrollToBottom,
            refreshSessionRows = ::refreshSessionRows
        )
    }
    private val messagePersistenceController: ChatMessagePersistenceController by lazy {
        ChatMessagePersistenceController(
            context = this,
            db = db,
            lifecycleScope = lifecycleScope,
            aiWorkScope = aiWorkScope,
            displayMessages = displayMessages,
            pendingVoiceBubbleAnimations = pendingVoiceBubbleAnimations,
            adapterProvider = { adapter },
            rvSessionListProvider = { rvSessionList },
            drawerSessionsProvider = { drawerSessions },
            etSessionSearchProvider = { etSessionSearch },
            sessionAdapterProvider = { sessionAdapter },
            allSessionRowsProvider = { allSessionRows },
            getCurrentBookName = { currentBookName },
            getCurrentConversationId = { currentConversationId },
            buildVoicePayload = ::buildVoicePayload,
            scrollToBottom = ::scrollToBottom,
            ensureLastMessageVisible = { ensureLastMessageVisible() },
            refreshSessionRows = ::refreshSessionRows
        )
    }
    private val voiceInputController: ChatVoiceInputController by lazy {
        ChatVoiceInputController(
            context = this,
            etInputProvider = { etInput },
            btnSendProvider = { btnSend },
            btnMoreInputProvider = { btnMoreInput },
            btnVoiceToggleProvider = { btnVoiceToggle },
            btnVoiceHoldProvider = { btnVoiceHold },
            layoutVoiceRecordOverlayProvider = { layoutVoiceRecordOverlay },
            ivVoiceRecordStateProvider = { ivVoiceRecordState },
            tvVoiceRecordTitleProvider = { tvVoiceRecordTitle },
            tvVoiceRecordSubtitleProvider = { tvVoiceRecordSubtitle },
            tvVoiceRecordTimerProvider = { tvVoiceRecordTimer },
            isVoiceMode = { isVoiceMode },
            setVoiceMode = { isVoiceMode = it },
            isRecording = { isRecording },
            setIsRecording = { isRecording = it },
            isWannaCancel = { isWannaCancel },
            setIsWannaCancel = { isWannaCancel = it },
            setIsFingerDown = { isFingerDown = it },
            setLongPressTriggered = { longPressTriggered = it },
            getRecordingStartAt = { recordingStartAt },
            ensureAiVoiceFeatureEnabled = ::ensureAiVoiceFeatureEnabled,
            ensureRecordPermission = ::ensureRecordPermission,
            clearPendingLongPress = ::clearPendingLongPress,
            startVoiceRecording = ::startVoiceRecording,
            stopVoiceRecording = ::stopVoiceRecording,
            onVoiceRecorded = ::onVoiceRecorded,
            isInlineAmountEditing = ::isInlineAmountEditing,
            ensureLastMessageVisible = { ensureLastMessageVisible() },
            refreshVoiceSupportHint = ::refreshVoiceSupportHint
        )
    }
    private val displayMessages = mutableListOf<ChatDisplayItem>()
    private val allSessionRows = mutableListOf<ChatSessionRow>()

    private var currentBookName: String = BookAccountManager.DEFAULT_BOOK
    private var currentConversationId: String = ""
    private var pendingScrollToMessageId: Long = -1L
    private val deprecatedBillMessageIds = mutableSetOf<Long>()
    private var pendingHabitSuggestion: HabitRuleSuggestion? = null
    private var isVoiceMode = false
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
    private var audioSupportProbeJob: Job? = null
    private val pendingVoiceBubbleAnimations = mutableSetOf<String>()
    private val pendingTranscriptRevealAnimations = mutableSetOf<String>()
    private val visibleTranscriptPaths = mutableSetOf<String>()
    private val transcribingPaths = mutableSetOf<String>()
    private var pendingBillSelection: PendingBillSelection? = null
    private var pendingBillConfirmation: PendingBillConfirmation? = null
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

        StatusBarStyle.applyByColor(window, Color.parseColor("#F7F7F7"))

        currentBookName = resolveEntryBookName(intent)
        pendingScrollToMessageId = intent?.getLongExtra(EXTRA_SCROLL_TO_MSG_ID, -1L) ?: -1L

        bindViews()
        setupToolbar()
        setupRecyclerView()
        setupSessionDrawer()
        setupInput()
        setupKeyboardInsets()
        setupFallbackVoiceUi()
        mediaController.refreshAiProfile()
        mediaController.applyBackground()

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
        layoutVoiceRecordOverlay = findViewById(R.id.layout_voice_record_overlay)
        ivVoiceRecordState = findViewById(R.id.iv_voice_record_state)
        tvVoiceRecordTitle = findViewById(R.id.tv_voice_record_title)
        tvVoiceRecordSubtitle = findViewById(R.id.tv_voice_record_subtitle)
        tvVoiceRecordTimer = findViewById(R.id.tv_voice_record_timer)
        layoutVoiceSelectionBar = findViewById(R.id.layout_voice_selection_bar)
        tvVoiceSelectionCount = findViewById(R.id.tv_voice_selection_count)
        btnVoiceSelectionCancel = findViewById(R.id.btn_voice_selection_cancel)
        btnVoiceSelectionDelete = findViewById(R.id.btn_voice_selection_delete)
    }

    private fun setupToolbar() {
        findViewById<ImageView>(R.id.btn_chat_back).setOnClickListener { finish() }
        btnMore.setOnClickListener { showSessionPanel() }
        btnSwitchModel.setOnClickListener { panelController.showModelSwitchDialog() }
        ivAiAvatar.setOnClickListener { mediaController.showEditAiProfileDialog() }
        findViewById<View>(R.id.layout_ai_name_click).setOnClickListener { mediaController.showEditAiProfileDialog() }
    }

    private fun setupSessionDrawer() {
        sessionController.setupSessionDrawer()
    }

    private fun setupRecyclerView() {
        rvMessages.layoutManager = LinearLayoutManager(this)
        rvMessages.adapter = adapter
        rvMessages.itemAnimator = null
        rvMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && !isInlineAmountEditing() && !voiceController.isVoiceSelectionMode()) {
                ensureLastMessageVisible()
            }
        }
    }

    private fun setupInput() {
        btnSend.setOnClickListener { sendText() }
        btnMoreInput.setOnClickListener { mediaController.pickImage() }
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
        voiceInputController.toggleVoiceMode()
    }

    private fun updateVoiceModeUi() {
        voiceInputController.updateVoiceModeUi()
    }

    private fun updateInputActionUi() {
        voiceInputController.updateInputActionUi()
    }

    private fun startRecordingButtonPulse() {
        voiceInputController.startRecordingButtonPulse()
    }

    private fun stopRecordingButtonPulse() {
        voiceInputController.stopRecordingButtonPulse()
    }

    private fun showVoiceRecordOverlay(isCancelState: Boolean) {
        voiceInputController.showVoiceRecordOverlay(isCancelState)
    }

    private fun hideVoiceRecordOverlay() {
        voiceInputController.hideVoiceRecordOverlay()
    }

    private fun handleVoiceButtonTouch(event: MotionEvent): Boolean {
        return voiceInputController.handleVoiceButtonTouch(event)
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
        uiHelperController.showCustomConfirmDialog(
            title = "请先开启语音记账",
            message = "你在 AI 对话里发送语音前，需要先到设置中心开启“语音记账”功能。",
            confirmText = "去开启",
            onConfirm = {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_TAB_INDEX, 3)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        )
        return false
    }

    private fun ensureAiImageFeatureEnabled(): Boolean {
        if (Prefs.isShowAiImage(this)) return true
        uiHelperController.showCustomConfirmDialog(
            title = "请先开启图片记账",
            message = "你在 AI 对话里发送图片前，需要先到设置中心开启“图片记账”功能。",
            confirmText = "去开启",
            onConfirm = {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_TAB_INDEX, 3)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        )
        return false
    }

    private fun clearPendingLongPress() {
        pendingLongPressRunnable?.let { voiceHandler.removeCallbacks(it) }
        pendingLongPressRunnable = null
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
        sessionController.startNewConversation()
    }

    private fun updateConversationSubtitle() {
        val modelName = Prefs.getAiChatModel(this).ifEmpty { "未选择模型" }
        tvAiModel.text = modelName
    }

    private fun showSessionPanel() {
        sessionController.showSessionPanel()
    }

    private fun showDeleteSessionDialog(row: ChatSessionRow) {
        sessionController.showDeleteSessionDialog(row)
    }

    private suspend fun onSessionDeleted(row: ChatSessionRow) {
        sessionController.onSessionDeleted(row)
    }

    private fun confirmClearHistory() {
        sessionController.confirmClearHistory()
    }

    private suspend fun refreshSessionRows() {
        sessionController.refreshSessionRows()
    }

    private fun showRenameSessionDialog(row: ChatSessionRow) {
        sessionController.showRenameSessionDialog(row)
    }

    private fun renameSessionInline(row: ChatSessionRow, newTitle: String) {
        sessionController.renameSessionInline(row, newTitle)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (mediaController.handleActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startVoiceRecording(): Boolean {
        return audioRecordController.startVoiceRecording()
    }

    private fun stopVoiceRecording(onFileReady: (File?, Int) -> Unit) {
        audioRecordController.stopVoiceRecording(onFileReady)
    }

    private fun onVoiceRecorded(tempFile: File, durationSec: Int) {
        lifecycleScope.launch {
            val copiedFile = withContext(Dispatchers.IO) { copyVoiceFileToStorage(tempFile) }
            if (durationSec > maxVoiceRecordDurationSec || copiedFile.length() > maxVoiceRecordBytes) {
                runCatching { copiedFile.delete() }
                appendAiTextMessage("语音超出限制（最长 3 分钟、8MB），请缩短后重试。", isLoading = false)
                return@launch
            }
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
        return audioRecordController.copyVoiceFileToStorage(tempFile)
    }

    private fun buildVoicePayload(audioPath: String, durationSec: Int, transcript: String): String {
        return voiceController.buildVoicePayload(audioPath, durationSec, transcript)
    }

    private fun parseVoicePayload(content: String): VoicePayload {
        return voiceController.parseVoicePayload(content)
    }

    private fun parseVoicePayloadStrict(content: String): VoicePayload? {
        return voiceController.parseVoicePayloadStrict(content)
    }

    private fun playVoiceMessage(item: ChatDisplayItem) {
        voiceController.playVoiceMessage(item)
    }

    private fun stopVoicePlayback() {
        voiceController.stopVoicePlayback()
    }

    private fun showVoiceMessageMenu(anchor: View, item: ChatDisplayItem) {
        messageMenuController.showVoiceMessageMenu(anchor, item)
    }

    private fun showTranscriptMenu(anchor: View, item: ChatDisplayItem) {
        messageMenuController.showTranscriptMenu(anchor, item)
    }

    private fun hideVoiceTranscript(item: ChatDisplayItem) {
        voiceController.hideVoiceTranscript(item)
    }

    private fun isVoiceTranscriptVisible(item: ChatDisplayItem): Boolean {
        return voiceController.isTranscriptVisible(item)
    }

    private fun showTextMessageMenu(anchor: View, item: ChatDisplayItem) {
        messageMenuController.showTextMessageMenu(anchor, item)
    }

    private fun transcribeVoiceMessage(item: ChatDisplayItem, showResult: Boolean, force: Boolean = false) {
        voiceController.transcribeVoiceMessage(item, showResult, force)
    }

    private fun updateVoiceTranscriptByPath(audioPath: String, transcript: String, revealTranscript: Boolean) {
        voiceController.updateVoiceTranscriptByPath(audioPath, transcript, revealTranscript)
    }

    private fun enterVoiceSelectionMode(firstItem: ChatDisplayItem? = null) {
        voiceController.enterVoiceSelectionMode(firstItem)
    }

    private fun exitVoiceSelectionMode() {
        voiceController.exitVoiceSelectionMode()
    }

    private fun toggleVoiceSelection(item: ChatDisplayItem) {
        voiceController.toggleVoiceSelection(item)
    }

    private fun deleteSelectedVoiceMessages() {
        voiceController.deleteSelectedVoiceMessages()
    }

    private fun deleteVoiceMessages(items: List<ChatDisplayItem>) {
        voiceController.deleteVoiceMessages(items)
    }

    private fun requestDeleteFromLongPressMenu(item: ChatDisplayItem) {
        uiHelperController.showCustomConfirmDialog(
            "确认删除",
            "删除后不可恢复，是否继续？\n删除聊天记录不会删除附带账单。",
            "继续删除",
            true
        ) {
            deleteVoiceMessages(listOf(item))
        }
    }

    private suspend fun findDependentAssistantMessageIds(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<Long>()
        val userTypes = listOf(MSG_TYPE_USER_TEXT, MSG_TYPE_USER_IMAGE, MSG_TYPE_USER_VOICE)
        val assistantTypes = listOf(MSG_TYPE_AI_TEXT, MSG_TYPE_AI_BILL)
        for (id in ids.distinct()) {
            val start = db.chatMessageDao().getById(id) ?: continue
            if (!isUserMessageType(start.msgType)) continue
            val nextUser = db.chatMessageDao().findNextUserMessage(
                bookName = start.bookName,
                conversationId = start.conversationId,
                timestamp = start.timestamp,
                id = start.id,
                userTypes = userTypes
            )
            result += db.chatMessageDao().findAssistantMessageIdsBetween(
                bookName = start.bookName,
                conversationId = start.conversationId,
                startTimestamp = start.timestamp,
                startId = start.id,
                endTimestamp = nextUser?.timestamp ?: -1L,
                endId = nextUser?.id ?: -1L,
                assistantTypes = assistantTypes
            )
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
        messagePipeline.sendText()
    }

    private fun callAiAccounting(
        userText: String,
        appendUserBubble: Boolean = true,
        bookkeepingMode: tao.test.flipaccounting.chat.ai.AiBookkeepingMode = tao.test.flipaccounting.chat.ai.AiBookkeepingMode.UNSPECIFIED,
        forceTextReply: Boolean = false,
        loadingIdxOverride: String? = null,
        loadingBootstrapText: String = ""
    ) {
        messagePipeline.callAiAccounting(
            userText = userText,
            appendUserBubble = appendUserBubble,
            bookkeepingMode = bookkeepingMode,
            forceTextReply = forceTextReply,
            loadingIdxOverride = loadingIdxOverride,
            loadingBootstrapText = loadingBootstrapText
        )
    }

    private fun callAiAccountingWithVoice(audioFile: File) {
        messagePipeline.callAiAccountingWithVoice(audioFile)
    }

    private fun interruptAiResponse() {
        messagePipeline.cancelCurrentRequest()
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
                    val outcome = saveRuleWithKeywordConflictPrompt(
                        AiRule(
                            keyword = pending.keyword,
                            targetType = pending.targetType,
                            targetCategory = pending.targetCategory,
                            targetAccount1 = pending.targetAccount1,
                            targetAccount2 = pending.targetAccount2,
                            isEnabled = true
                        )
                    )
                    when (outcome) {
                        RuleSaveOutcome.SAVED ->
                            appendAiTextMessage("好呀，已经帮你记成一条记账习惯啦 ${pending.summaryText}", isLoading = false)
                        RuleSaveOutcome.OVERWRITTEN ->
                            appendAiTextMessage("好呀，检测到同关键词规则，我已用这次内容覆盖旧规则 ${pending.summaryText}", isLoading = false)
                        RuleSaveOutcome.CANCELED ->
                            appendAiTextMessage("检测到同关键词旧规则，你取消了覆盖，这次就先不保存啦~", isLoading = false)
                    }
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

    private suspend fun buildAnalysisInput(userText: String): String {
        return userText.removePrefix("[图片OCR文本]: ")
    }

    private fun buildBillSummary(bills: List<Bill>): String {
        return billCorrectionService.buildBillSummary(bills)
    }

    private suspend fun processBillResult(
        result: JSONObject,
        userText: String,
        bookName: String,
        conversationId: String
    ): List<Bill> {
        return billCorrectionService.processBillResult(result, userText, bookName, conversationId)
    }

    private suspend fun chooseModifyTargetBill(userText: String, candidates: List<Bill>): Bill? {
        if (candidates.isEmpty()) return null
        val displayCandidates = candidates.take(3)
        val token = "bill_select_${UUID.randomUUID()}"
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                if (isFinishing || isDestroyed) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                pendingBillSelection?.continuation?.takeIf { it.isActive }?.resume(null)
                pendingBillSelection = PendingBillSelection(token = token, continuation = cont)
                appendAiTextMessage(
                    "我找到了 ${displayCandidates.size} 笔可能匹配的账单，请在下方点“选这笔”确认要修改哪一笔。",
                    isLoading = false
                )
                appendInteractiveBillCard(
                    bills = displayCandidates,
                    hint = "指令：${userText.take(36)}",
                    interactionMode = BILL_INTERACTION_SELECT_TARGET,
                    interactionToken = token
                )
                cont.invokeOnCancellation {
                    if (pendingBillSelection?.token == token) {
                        pendingBillSelection = null
                    }
                    runOnUiThread { removeInteractiveBillCard(token, deletePersisted = true) }
                }
            }
        }
    }

    private suspend fun confirmBillModifyPreviewInChat(
        oldBill: Bill,
        newBill: Bill,
        changes: List<String>
    ): Boolean {
        val token = "bill_confirm_${UUID.randomUUID()}"
        val previewBeforeId = if (oldBill.id > 0L) -oldBill.id else -System.currentTimeMillis()
        val beforePreviewBill = oldBill.copy(id = previewBeforeId)
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                if (isFinishing || isDestroyed) {
                    cont.resume(false)
                    return@suspendCancellableCoroutine
                }
                pendingBillConfirmation?.continuation?.takeIf { it.isActive }?.resume(false)
                pendingBillConfirmation = PendingBillConfirmation(token = token, continuation = cont)
                appendInteractiveBillCard(
                    bills = listOf(beforePreviewBill, newBill),
                    hint = if (changes.isNotEmpty()) {
                        "改前（上） / 改后（下）\n${changes.take(2).joinToString("；")}"
                    } else {
                        "改前（上） / 改后（下）"
                    },
                    interactionMode = BILL_INTERACTION_CONFIRM_MODIFICATION,
                    interactionToken = token
                )
                cont.invokeOnCancellation {
                    if (pendingBillConfirmation?.token == token) {
                        pendingBillConfirmation = null
                    }
                    runOnUiThread { removeInteractiveBillCard(token, deletePersisted = true) }
                }
            }
        }
    }

    private fun formatBillBrief(bill: Bill): String {
        val typeLabel = when (bill.type) {
            Bill.TYPE_INCOME -> "收入"
            Bill.TYPE_TRANSFER -> if (bill.subType == Bill.SUBTYPE_REPAYMENT) "还款" else "转账"
            else -> "支出"
        }
        val amountText = String.format(Locale.getDefault(), "%.2f", bill.amount)
        val category = bill.categoryName.ifBlank { "未分类" }
        val main = bill.remark.ifBlank { category }
        return "$typeLabel $amountText ${bill.currency} · $main"
    }

    private fun onInteractiveBillAction(item: ChatDisplayItem, bill: Bill, action: Int) {
        when (item.billInteractionMode) {
            BILL_INTERACTION_SELECT_TARGET -> {
                val pending = pendingBillSelection
                if (pending == null || pending.token != item.billInteractionToken) return
                removeInteractiveBillCard(item.billInteractionToken, deletePersisted = true)
                pendingBillSelection = null
                when (action) {
                    BILL_INTERACTIVE_ACTION_PRIMARY -> {
                        if (pending.continuation.isActive) pending.continuation.resume(bill)
                    }
                    BILL_INTERACTIVE_ACTION_SECONDARY -> {
                        appendAiTextMessage("已取消修改。", isLoading = false)
                        if (pending.continuation.isActive) pending.continuation.resume(null)
                    }
                }
            }
            BILL_INTERACTION_CONFIRM_MODIFICATION -> {
                val pending = pendingBillConfirmation
                if (pending == null || pending.token != item.billInteractionToken) return
                pendingBillConfirmation = null
                when (action) {
                    BILL_INTERACTIVE_ACTION_PRIMARY -> {
                        if (pending.continuation.isActive) pending.continuation.resume(true)
                    }
                    BILL_INTERACTIVE_ACTION_SECONDARY -> {
                        removeInteractiveBillCard(item.billInteractionToken, deletePersisted = true)
                        appendAiTextMessage("已取消修改。", isLoading = false)
                        if (pending.continuation.isActive) pending.continuation.resume(false)
                    }
                }
            }
        }
    }

    private fun appendInteractiveBillCard(
        bills: List<Bill>,
        hint: String,
        interactionMode: Int,
        interactionToken: String,
        deprecatedBillIds: Set<Long> = emptySet()
    ) {
        if (bills.isEmpty()) return
        val snapshotContent = buildBillMessageContent(
            bills = bills,
            deprecatedBillIds = deprecatedBillIds,
            snapshotOnly = true
        )
        val rowTimestamp = System.currentTimeMillis()
        displayMessages.add(
            ChatDisplayItem(
                msgType = MSG_TYPE_AI_BILL,
                content = snapshotContent,
                bills = bills.toMutableList(),
                timestamp = rowTimestamp,
                isLoading = false,
                deprecatedBillIds = deprecatedBillIds.toMutableSet(),
                billHint = hint,
                billInteractionMode = interactionMode,
                billInteractionToken = interactionToken
            )
        )
        adapter.notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom(force = true)
    }

    private fun finalizeConfirmedPreviewCard(token: String) {
        if (token.isBlank()) return
        val idx = displayMessages.indexOfFirst { item ->
            item.msgType == MSG_TYPE_AI_BILL &&
                item.billInteractionToken == token &&
                item.billInteractionMode == BILL_INTERACTION_CONFIRM_MODIFICATION
        }
        if (idx < 0) return
        val current = displayMessages[idx]
        if (current.bills.isEmpty()) return
        val beforePreviewId = current.bills.first().id
        val finalizedDeprecatedIds = current.deprecatedBillIds.toMutableSet().apply {
            clear()
            if (beforePreviewId != 0L) add(beforePreviewId)
        }
        val updatedContent = buildBillMessageContent(
            bills = current.bills,
            deprecatedBillIds = finalizedDeprecatedIds,
            editedBillIds = current.editedBillIds,
            snapshotOnly = true
        )
        displayMessages[idx] = current.copy(
            content = updatedContent,
            deprecatedBillIds = finalizedDeprecatedIds,
            billHint = "",
            billInteractionMode = BILL_INTERACTION_NONE,
            billInteractionToken = ""
        )
        adapter.notifyItemChanged(idx)
        val msgId = displayMessages[idx].dbId
        if (msgId > 0L) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.chatMessageDao().getById(msgId)?.let { oldMsg ->
                    db.chatMessageDao().update(oldMsg.copy(content = updatedContent))
                }
            }
        }
    }

    private fun removeInteractiveBillCard(token: String, deletePersisted: Boolean = true) {
        if (token.isBlank()) return
        val idx = displayMessages.indexOfFirst { item ->
            item.msgType == MSG_TYPE_AI_BILL && item.billInteractionToken == token
        }
        if (idx < 0) return
        val removedDbId = displayMessages[idx].dbId
        displayMessages.removeAt(idx)
        adapter.notifyItemRemoved(idx)
        if (idx <= displayMessages.lastIndex) {
            adapter.notifyItemRangeChanged(idx, displayMessages.size - idx)
        }
        if (deletePersisted && removedDbId > 0L) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.chatMessageDao().deleteByIds(listOf(removedDbId))
            }
        }
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
                lifecycleScope.launch {
                    when (saveRuleWithKeywordConflictPrompt(newRule)) {
                        RuleSaveOutcome.SAVED -> Utils.toast(this@ChatActivity, "规则创建成功")
                        RuleSaveOutcome.OVERWRITTEN -> Utils.toast(this@ChatActivity, "已覆盖同关键词旧规则")
                        RuleSaveOutcome.CANCELED -> Utils.toast(this@ChatActivity, "已取消规则保存")
                    }
                }
            },
            onDelete = null
        )
    }

    private suspend fun saveRuleWithKeywordConflictPrompt(newRule: AiRule): RuleSaveOutcome =
        withContext(Dispatchers.IO) {
            val dao = db.aiRuleDao()
            val keyword = newRule.keyword.trim()
            val currentEditId = newRule.id.takeIf { it > 0 }
            val conflicts = dao.getRulesByKeyword(keyword)
                .filter { existing -> currentEditId == null || existing.id != currentEditId }

            if (conflicts.isEmpty()) {
                dao.insertRule(newRule.copy(keyword = keyword))
                return@withContext RuleSaveOutcome.SAVED
            }

            val shouldOverwrite = withContext(Dispatchers.Main) {
                promptKeywordOverwrite(keyword = keyword, existingCount = conflicts.size)
            }
            if (!shouldOverwrite) {
                return@withContext RuleSaveOutcome.CANCELED
            }

            val target = conflicts.first()
            dao.insertRule(newRule.copy(id = target.id, keyword = keyword))
            currentEditId?.takeIf { it != target.id }?.let { dao.deleteRuleById(it) }
            conflicts.drop(1).forEach { duplicate ->
                if (duplicate.id != target.id) dao.deleteRuleById(duplicate.id)
            }
            RuleSaveOutcome.OVERWRITTEN
        }

    private suspend fun promptKeywordOverwrite(
        keyword: String,
        existingCount: Int
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        if (isFinishing || isDestroyed) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_FlipAccounting))
            .setTitle("检测到同关键词规则")
            .setMessage("关键词“$keyword”已有 $existingCount 条规则。\n\n继续保存将覆盖旧规则，是否继续？")
            .setPositiveButton("继续并覆盖") { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(true)
            }
            .setNegativeButton("取消保存") { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(false)
            }
            .setOnCancelListener {
                if (cont.isActive) cont.resume(false)
            }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun loadHistoryMessages() {
        historyController.loadHistoryMessages()
    }

    private fun scrollToPendingMessageIfNeeded() {
        historyController.scrollToPendingMessageIfNeeded()
    }

    private fun parseBillIds(json: String): List<Long> {
        return ChatBillMessageParser.parseBillIds(json)
    }

    private fun isDeprecatedBillMessage(billIds: String): Boolean =
        ChatBillMessageParser.isDeprecatedBillMessage(billIds)

    private fun markBillIdsAsDeprecated(billIds: String): String {
        return ChatBillMessageParser.markBillIdsAsDeprecated(billIds)
    }

    private fun parseBillsFromMessageContent(content: String): List<Bill> {
        return ChatBillMessageParser.parseBillsFromMessageContent(
            content = content,
            currentBookName = currentBookName,
            parseTimeToMillis = ::parseTimeToMillis
        )
    }

    private fun parseDeprecatedBillIdsFromContent(content: String): Set<Long> {
        return ChatBillMessageParser.parseDeprecatedBillIdsFromContent(content)
    }

    private fun parseEditedBillIdsFromContent(content: String): Set<Long> {
        return ChatBillMessageParser.parseEditedBillIdsFromContent(content)
    }

    private fun parseSnapshotOnlyFromContent(content: String): Boolean {
        return ChatBillMessageParser.parseSnapshotOnlyFromContent(content)
    }

    private fun mergeChatBillSnapshots(liveBills: List<Bill>, snapshots: List<Bill>): List<Bill> {
        return ChatBillMessageParser.mergeChatBillSnapshots(liveBills, snapshots)
    }

    private fun buildBillMessageContent(
        bills: List<Bill>,
        deprecatedBillIds: Set<Long> = emptySet(),
        editedBillIds: Set<Long> = emptySet(),
        snapshotOnly: Boolean = false
    ): String {
        return ChatBillMessageParser.buildBillMessageContent(
            bills = bills,
            formatTime = { ms -> uiHelperController.formatTime(ms) },
            deprecatedBillIds = deprecatedBillIds,
            editedBillIds = editedBillIds,
            snapshotOnly = snapshotOnly
        )
    }

    private fun appendUserMessage(text: String, type: Int, imageUri: String = "") {
        messagePersistenceController.appendUserMessage(text, type, imageUri)
    }

    private fun appendUserVoiceMessage(audioFile: File, durationSec: Int, transcript: String): ChatDisplayItem {
        return messagePersistenceController.appendUserVoiceMessage(audioFile, durationSec, transcript)
    }

    private fun appendAiTextMessage(
        text: String,
        isLoading: Boolean,
        bookName: String? = null,
        conversationId: String? = null
    ): String {
        return messagePersistenceController.appendAiTextMessage(text, isLoading, bookName, conversationId)
    }

    private suspend fun persistAiTextMessage(text: String, bookName: String, conversationId: String) {
        messagePersistenceController.persistAiTextMessage(text, bookName, conversationId)
    }

    private fun removeLoadingMessage(uiKey: String) {
        messagePersistenceController.removeLoadingMessage(uiKey)
    }

    private fun updateLoadingMessage(uiKey: String, text: String) {
        messagePersistenceController.updateLoadingMessage(uiKey, text)
    }

    private fun finalizeLoadingMessage(uiKey: String, text: String, bookName: String, conversationId: String) {
        messagePersistenceController.finalizeLoadingMessage(uiKey, text, bookName, conversationId)
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

    override fun onDestroy() {
        messagePipeline.cancelCurrentRequest(showInterruptedMessage = false)
        aiScopeJob.cancel()
        pendingBillSelection?.continuation?.takeIf { it.isActive }?.resume(null)
        pendingBillSelection = null
        pendingBillConfirmation?.continuation?.takeIf { it.isActive }?.resume(false)
        pendingBillConfirmation = null
        super.onDestroy()
        clearPendingLongPress()
        stopVoicePlayback()
        if (isRecording) {
            stopVoiceRecording { _, _ -> }
        }
    }

    override fun onBackPressed() {
        if (voiceController.isVoiceSelectionMode()) {
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
    val uiKey: String = UUID.randomUUID().toString(),
    val msgType: Int,
    val content: String = "",
    val imageUri: String = "",
    val voice: VoicePayload? = null,
    val bills: MutableList<Bill> = mutableListOf(),
    val timestamp: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val isDeprecated: Boolean = false,
    val deprecatedBillIds: MutableSet<Long> = mutableSetOf(),
    val editedBillIds: MutableSet<Long> = mutableSetOf(),
    val billHint: String = "",
    val billInteractionMode: Int = ChatActivity.BILL_INTERACTION_NONE,
    val billInteractionToken: String = ""
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
