package com.taostudio.tapaccounting

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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
import android.widget.FrameLayout
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
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.ui.common.StatusBarStyle
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import com.taostudio.tapaccounting.data.local.entity.AiRule
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.logic.BillAssetImpactService
import com.taostudio.tapaccounting.logic.BillMutationService
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.logic.RuleDialogHelper
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
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
    companion object {
        const val MSG_TYPE_USER_TEXT = 0
        const val MSG_TYPE_USER_IMAGE = 1
        const val MSG_TYPE_USER_VOICE = 2
        const val MSG_TYPE_AI_TEXT = 3
        const val MSG_TYPE_AI_BILL = 4
        const val MSG_TYPE_QUERY_DRAFT = 5
        const val MSG_TYPE_QUERY_RESULT = 6
        const val BILL_INTERACTION_NONE = 0
        const val BILL_INTERACTIVE_ACTION_PRIMARY = 1
        const val BILL_INTERACTIVE_ACTION_SECONDARY = 2

        const val EXTRA_SOURCE_BOOK = "extra_source_book"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_MODE = "extra_chat_mode"
        const val MODE_ACCOUNTING = 0
        private const val EXTRA_SCROLL_TO_MSG_ID = "scroll_to_msg_id"

        private const val REQ_PICK_IMAGE = 101
        private const val REQ_PICK_BG = 102
        private const val REQ_PICK_AI_AVATAR = 103
        private const val REQ_PICK_USER_AVATAR = 104
        private const val REQ_CROP_AI_AVATAR = 105
        private const val REQ_CROP_USER_AVATAR = 106
        private const val REQ_CROP_BG = 107
        private const val REQ_IMAGE_PERMISSION = 1002
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
    private lateinit var layoutPendingImages: View
    private lateinit var containerPendingImages: LinearLayout
    private lateinit var tvPendingImageCount: TextView
    private lateinit var btnClearPendingImages: TextView

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val aiScopeJob = SupervisorJob()
    private val aiWorkScope = CoroutineScope(aiScopeJob + Dispatchers.Main.immediate)
    internal val queryDraftManager by lazy {
        com.taostudio.tapaccounting.chat.query.QueryDraftManager(db) { currentBookName }
    }

    /** 构建查询上下文（供 QueryDraftManager 和 QueryPlanner 使用） */
    internal suspend fun buildQueryContext(): com.taostudio.tapaccounting.chat.query.QueryContext {
        return com.taostudio.tapaccounting.chat.query.QueryContextBuilder(db).build(currentBookName)
    }
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
            decideSingleOrMultiForChat = ::decideSingleOrMultiForChat,
            processBillResult = ::processBillResult,
            confirmVisualAccountingDraft = ::confirmVisualAccountingDraftInChat,
            buildBillSummary = ::buildBillSummary,
            transcribeVoiceToTextWithFallback = ::transcribeVoiceToTextWithFallback,
            persistAiTextMessage = ::persistAiTextMessage,
            db = db,
            getCurrentBookName = { currentBookName },
            getCurrentConversationId = { currentConversationId },
            appendQueryDraftMessage = { draft -> appendQueryDraftMessage(draft) },
            appendQueryResultMessage = { result -> appendQueryResultMessage(result) }
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
            buildBillMessageContent = ::buildBillMessageContent
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
            onOpenImagePreview = ::openImagePreview,
            onInterruptAiLoading = ::interruptAiResponse,
            onQueryDraftStats = { item -> onQueryDraftStats(item) },
            onQueryDraftSearch = { item -> onQueryDraftSearch(item) },
            onQueryDraftCancel = { item -> onQueryDraftCancel(item) },
            onQueryResultViewDetails = { item -> onQueryResultViewDetails(item) },
            onQueryResultEditConditions = { item -> onQueryResultEditConditions(item) },
            onQueryDraftEditKeyword = { item -> onQueryDraftEditKeyword(item) },
            onQueryDraftEditDate = { item -> onQueryDraftEditDate(item) },
            onQueryDraftEditBillType = { item -> onQueryDraftEditBillType(item) },
            onQueryDraftEditBookScope = { item -> onQueryDraftEditBookScope(item) }
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
            onImageReady = { uri, base64, mime -> onImageReady(uri, base64, mime) },
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
    private val pendingImages = mutableListOf<PendingImage>()

    private var currentBookName: String = BookAccountManager.DEFAULT_BOOK
    private var currentConversationId: String = ""
    private var chatMode: Int = MODE_ACCOUNTING
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
        chatMode = intent?.getIntExtra(EXTRA_MODE, MODE_ACCOUNTING) ?: MODE_ACCOUNTING
        pendingScrollToMessageId = intent?.getLongExtra(EXTRA_SCROLL_TO_MSG_ID, -1L) ?: -1L

        bindViews()
        setupToolbar()
        setupRecyclerView()
        setupSessionDrawer()
        setupInput()
        setupKeyboardInsets()
        setupFallbackVoiceUi()
        mediaController.refreshAiProfile()
        applyChatMode()
        mediaController.applyBackground()

        lifecycleScope.launch {
            bootstrapConversationState()
            loadHistoryMessages()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::tvAiModel.isInitialized) {
            updateConversationSubtitle()
            ensureModelAudioSupportProbed()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            clearInlineAmountFocusIfTouchOutside(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun clearInlineAmountFocusIfTouchOutside(ev: MotionEvent) {
        inlineAmountEditingBillId ?: return
        val focusedAmountInput = currentFocus as? EditText ?: return
        if (focusedAmountInput.id != R.id.et_chat_bill_amount) return

        val inputBounds = Rect()
        if (focusedAmountInput.getGlobalVisibleRect(inputBounds) &&
            inputBounds.contains(ev.rawX.toInt(), ev.rawY.toInt())
        ) {
            return
        }

        uiHelperController.hideSoftKeyboard(focusedAmountInput)
        focusedAmountInput.clearFocus()
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
        applySessionDrawerAdaptiveWidth()
        etSessionSearch = findViewById(R.id.et_session_search)
        btnNewSession = findViewById(R.id.btn_new_session)
        btnReplyStyle = findViewById(R.id.btn_reply_style)
        btnChangeChatBg = findViewById(R.id.btn_change_chat_bg)
        btnClearCurrentSession = findViewById(R.id.btn_clear_current_session)
        rvSessionList = findViewById(R.id.rv_session_list)
        tvVoiceModelHint = findViewById(R.id.tv_voice_model_hint)
        layoutChatInputRow = findViewById(R.id.layout_chat_input_row)
        layoutPendingImages = findViewById(R.id.layout_pending_images)
        containerPendingImages = findViewById(R.id.container_pending_images)
        tvPendingImageCount = findViewById(R.id.tv_pending_image_count)
        btnClearPendingImages = findViewById(R.id.btn_clear_pending_images)
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

    private fun applySessionDrawerAdaptiveWidth() {
        val density = resources.displayMetrics.density
        val maxWidth = (328f * density).roundToInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val sideGap = (48f * density).roundToInt()
        val targetWidth = minOf(maxWidth, screenWidth - sideGap).coerceAtLeast((272f * density).roundToInt())
        drawerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
            width = targetWidth.coerceAtMost(screenWidth)
        }
    }

    private fun setupToolbar() {
        findViewById<ImageView>(R.id.btn_chat_back).setOnClickListener { finish() }
        btnMore.setOnClickListener { showSessionPanel() }
        btnSwitchModel.setOnClickListener { panelController.showModelSwitchDialog() }
        ivAiAvatar.setOnClickListener { mediaController.showEditAiProfileDialog() }
        findViewById<View>(R.id.layout_ai_name_click).setOnClickListener { mediaController.showEditAiProfileDialog() }
    }

    private fun applyChatMode() {
        btnMoreInput.visibility = View.VISIBLE
        updateModeControls()
    }

    private fun updateModeControls() {
        btnSwitchModel.text = getString(R.string.chat_model_button)
        btnSwitchModel.setTextColor(Color.parseColor("#3390EC"))
        btnSwitchModel.setBackgroundResource(R.drawable.bg_search_box)
        etInput.hint = getString(R.string.accounting_chat_input_hint)
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
        btnMoreInput.setOnClickListener { requestImageAccessAndPick() }
        btnClearPendingImages.setOnClickListener {
            pendingImages.clear()
            updatePendingImagePreview()
        }
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

    private fun requestImageAccessAndPick() {
        if (!ensureAiImageFeatureEnabled()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mediaController.pickImages()
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mediaController.pickImages()
        } else {
            requestPermissions(arrayOf(permission), REQ_IMAGE_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_IMAGE_PERMISSION) {
            if (grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Utils.toast(this, getString(R.string.toast_album_permission))
            }
            mediaController.pickImages()
        }
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
            Utils.toast(this, getString(R.string.mic_permission_required))
        }
        return granted
    }

    private fun ensureAiVoiceFeatureEnabled(): Boolean {
        if (Prefs.isShowAiVoice(this)) return true
        uiHelperController.showCustomConfirmDialog(
            title = getString(R.string.chat_voice_dialog_title),
            message = getString(R.string.chat_voice_dialog_message),
            confirmText = getString(R.string.chat_goto_settings),
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
            title = getString(R.string.chat_image_dialog_title),
            message = getString(R.string.chat_image_dialog_message),
            confirmText = getString(R.string.chat_goto_settings),
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
            // Reject agent-prefixed conversation IDs — create a new accounting conversation.
            if (fromIntentConversation.startsWith("agent_")) {
                currentConversationId = newConversationId()
                return
            }
            currentConversationId = fromIntentConversation
            return
        }

        if (pendingScrollToMessageId > 0L) {
            val msg = db.chatMessageDao().getById(pendingScrollToMessageId)
            if (msg != null) {
                if (msg.bookName.isNotBlank()) currentBookName = msg.bookName
                if (msg.conversationId.isNotBlank()) {
                    // Reject agent-prefixed conversation IDs
                    if (msg.conversationId.startsWith("agent_")) {
                        currentConversationId = newConversationId()
                        return
                    }
                    currentConversationId = msg.conversationId
                    return
                }
            }
        }

        val latest = db.chatMessageDao().getLatestAccountingConversationIdByBook(currentBookName).orEmpty()
        currentConversationId = if (latest.isNotBlank()) latest else newConversationId()
    }

    private fun newConversationId(): String {
        return "conv_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    }

    private fun startNewConversation() {
        sessionController.startNewConversation()
    }

    private fun updateConversationSubtitle() {
        val preset = AiProviderRegistry.resolvePreset(this)
        val effectiveModel = AiModelSlots.resolveChatModel(this)
        tvAiModel.text = if (AiModelSlots.isChatFollowingMainText(this)) {
            getString(R.string.ai_chat_model_subtitle_follow_main_fmt, preset.displayName, effectiveModel)
        } else {
            getString(R.string.ai_chat_model_subtitle_custom_fmt, preset.displayName, effectiveModel)
        }
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
                appendAiTextMessage(getString(R.string.voice_too_long), isLoading = false)
                return@launch
            }
            val loadingIdx = appendAiTextMessage(getString(R.string.transcribing_voice), isLoading = true)
            val transcript = withContext(Dispatchers.IO) {
                transcribeVoiceToTextWithFallback(copiedFile)
            }.trim()
            if (transcript == "API_KEY_NOT_SETUP") {
                removeLoadingMessage(loadingIdx)
                appendAiTextMessage(getString(R.string.api_key_required_cloud_asr), isLoading = false)
                return@launch
            }
            if (transcript == "MODEL_DOWNLOADING") {
                removeLoadingMessage(loadingIdx)
                appendAiTextMessage(getString(R.string.asr_model_downloading), isLoading = false)
                return@launch
            }
            if (transcript == "WHISPER_NOT_SETUP") {
                removeLoadingMessage(loadingIdx)
                appendAiTextMessage(getString(R.string.asr_model_needed_for_local), isLoading = false)
                return@launch
            }
            if (transcript.isBlank()) {
                removeLoadingMessage(loadingIdx)
                appendAiTextMessage(getString(R.string.voice_not_clear), isLoading = false)
                return@launch
            }
            // Save voice message, then process through accounting pipeline
            appendUserVoiceMessage(copiedFile, durationSec, "")
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
        val model = AiModelSlots.resolveChatModel(this)
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
            getString(R.string.confirm_delete),
            "删除后可在回收站恢复，是否继续？\n删除聊天记录不会删除附带账单。",
            getString(R.string.delete),
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
        val model = AiModelSlots.resolveChatModel(this)
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
        val text = etInput.text?.toString().orEmpty().trim()
        val images = pendingImages.toList()

        if (text.isEmpty() && images.isEmpty()) {
            messagePipeline.sendText()  // Pipeline handles the empty-toast
            return
        }

        if (images.isEmpty()) {
            messagePipeline.sendText()
            return
        }

        etInput.setText("")
        pendingImages.clear()
        updatePendingImagePreview()
        dispatchToAccounting(text, images)
    }

    /**
     * Route [text] + [images] to the accounting pipeline.
     */
    private fun dispatchToAccounting(text: String, images: List<PendingImage>) {
        if (images.isNotEmpty()) {
            val useDraft = Prefs.isReceiptImageDraftConfirmEnabled(this)
            val payload = ChatImageComposer.encodeMultiImagePayload(images, text, useDraft)
            images.forEach { img ->
                appendUserMessage("", MSG_TYPE_USER_IMAGE, img.uri?.toString().orEmpty())
            }
            if (text.isNotBlank()) {
                appendUserMessage(text, MSG_TYPE_USER_TEXT)
            }
            messagePipeline.callAiAccounting(payload, appendUserBubble = false)
        } else {
            messagePipeline.callAiAccounting(text)
        }
    }

    private fun onImageReady(uri: Uri, base64: String, mime: String) {
        if (ChatImageComposer.isAtLimit(pendingImages.size)) {
            Utils.toast(this, getString(R.string.toast_max_images, ChatImageComposer.MAX_PENDING_IMAGES))
            return
        }
        pendingImages.add(PendingImage(uri, base64, mime))
        updatePendingImagePreview()
    }

    private fun updatePendingImagePreview() {
        if (!::containerPendingImages.isInitialized || !::layoutPendingImages.isInitialized) return

        containerPendingImages.removeAllViews()

        if (pendingImages.isEmpty()) {
            layoutPendingImages.visibility = View.GONE
            return
        }

        layoutPendingImages.visibility = View.VISIBLE
        tvPendingImageCount.text = getString(R.string.selected_image_count, pendingImages.size)

        val density = resources.displayMetrics.density
        val size = (68 * density).toInt()
        val margin = (4 * density).toInt()
        val removeBtnSize = (22 * density).toInt()

        pendingImages.forEachIndexed { index, img ->
            val frameLayout = FrameLayout(this).apply {
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@ChatActivity,
                    R.drawable.bg_chat_image_thumb
                )
                clipToOutline = true
                setPadding(margin, margin, margin, margin)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, 0, margin, 0)
                }
            }
            val imageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            Glide.with(this)
                .load(img.uri)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .centerCrop()
                .into(imageView)
            frameLayout.addView(imageView)

            val removeBtn = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(removeBtnSize, removeBtnSize).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                }
                text = "×"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chat_attachment_remove)
                contentDescription = "移除第 ${index + 1} 张图片"
                setOnClickListener { removePendingImage(index) }
            }
            frameLayout.addView(removeBtn)
            containerPendingImages.addView(frameLayout)
        }
    }

    private fun removePendingImage(index: Int) {
        if (index in pendingImages.indices) {
            pendingImages.removeAt(index)
            updatePendingImagePreview()
        }
    }

    private fun callAiAccounting(
        userText: String,
        appendUserBubble: Boolean = true,
        forceTextReply: Boolean = false,
        loadingIdxOverride: String? = null,
        loadingBootstrapText: String = ""
    ) {
        messagePipeline.callAiAccounting(
            userText = userText,
            appendUserBubble = appendUserBubble,
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
        return userText.removePrefix("[图片OCR文本]: ").trim()
    }

    private fun buildBillSummary(bills: List<Bill>): String {
        return billCorrectionService.buildBillSummary(bills)
    }

    private fun decideSingleOrMultiForChat(text: String): Boolean {
        val normalized = text
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.getDefault())
        if (normalized.isBlank()) return false

        val explicitMulti = Regex("分别|各[记来]?一笔|再来一笔|还有一笔|一共\\d+笔|两笔|三笔|四笔").containsMatchIn(normalized)
        if (explicitMulti) return true
        val explicitSingle = Regex("就这一笔|只记一笔|单笔|一笔就行|这笔就行").containsMatchIn(normalized)
        if (explicitSingle) return false

        var multiScore = 0
        val moneyUnitRegex = Regex("\\d+(?:\\.\\d{1,2})?\\s*(元|块钱|块|rmb|cny|pln|usd|eur|€|\\$)")
        val actionAmountRegex = Regex("(花了|花费|支付|付款|收了|收到|转账|还款|充值|提现|赚了|收入)\\s*\\d+(?:\\.\\d{1,2})?")
        val amountMatches = actionAmountRegex.findAll(normalized).toList()
        if (amountMatches.size >= 2) multiScore += 2
        val unitMatches = moneyUnitRegex.findAll(normalized).toList()
        if (unitMatches.size >= 2) multiScore++
        val separatorRegex = Regex("[，,;；、然后接着又还]")
        if (separatorRegex.containsMatchIn(normalized) && amountMatches.size >= 2) multiScore++

        return multiScore >= 2
    }

    private suspend fun processBillResult(
        result: JSONObject,
        userText: String,
        bookName: String,
        conversationId: String
    ): List<Bill> {
        return billCorrectionService.processBillResult(result, userText, bookName, conversationId)
    }

    private suspend fun confirmVisualAccountingDraftInChat(
        summary: String,
        bookName: String,
        conversationId: String
    ): String? {
        val initialDraft = summary.trim()
        if (initialDraft.isBlank()) return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                if (isFinishing || isDestroyed) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                val themeContext = ContextThemeWrapper(this@ChatActivity, R.style.Theme_TapAccounting)
                val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_visual_accounting_draft, null)
                val etDraft = view.findViewById<EditText>(R.id.et_visual_draft)
                val btnCancel = view.findViewById<TextView>(R.id.btn_visual_draft_cancel)
                val btnConfirm = view.findViewById<TextView>(R.id.btn_visual_draft_confirm)

                etDraft.setText(initialDraft)
                etDraft.setSelection(initialDraft.length)

                val dialog = AlertDialog.Builder(themeContext)
                    .setView(view)
                    .create()

                var completed = false
                fun finish(value: String?) {
                    if (completed) return
                    completed = true
                    dialog.setOnDismissListener(null)
                    if (cont.isActive) cont.resume(value)
                    if (dialog.isShowing) dialog.dismiss()
                }

                btnCancel.setOnClickListener { finish(null) }
                btnConfirm.setOnClickListener {
                    val edited = etDraft.text?.toString().orEmpty().trim()
                    if (edited.isBlank()) {
                        Utils.toast(this@ChatActivity, getString(R.string.keep_recognizable_bill_content))
                        return@setOnClickListener
                    }
                    finish(edited)
                }
                dialog.setOnCancelListener { finish(null) }
                dialog.setOnDismissListener { finish(null) }
                cont.invokeOnCancellation {
                    runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                    }
                }

                OverlayDialogs.showPageCenterDialog(
                    dialog = dialog,
                    ctx = this@ChatActivity,
                    widthRatio = 0.92f,
                    cancelOnTouchOutside = false,
                    useSolidPanelBackground = false
                )
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                if (!dialog.isShowing) finish(null)
            }
        }
    }

    private fun formatBillBrief(bill: Bill): String {
        val typeLabel = when (bill.type) {
            Bill.TYPE_INCOME -> getString(R.string.income)
            Bill.TYPE_TRANSFER -> if (bill.subType == Bill.SUBTYPE_REPAYMENT) getString(R.string.repayment) else getString(R.string.transfer)
            else -> getString(R.string.expense)
        }
        val amountText = String.format(Locale.getDefault(), "%.2f", bill.amount)
        val category = bill.categoryName.ifBlank { getString(R.string.uncategorized) }
        val main = bill.remark.ifBlank { category }
        return "$typeLabel $amountText ${bill.currency} · $main"
    }

    private fun onInteractiveBillAction(item: ChatDisplayItem, bill: Bill, action: Int) {
        // No interactive bill actions remain after agent mode removal.
    }

    private fun parseTimeToMillis(timeStr: String): Long {
        if (timeStr.isBlank()) return System.currentTimeMillis()
        val locale = Locale.getDefault()
        val fullFormats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
        for (pattern in fullFormats) {
            val parsed = runCatching { SimpleDateFormat(pattern, locale).parse(timeStr)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        val partialFormats = listOf("MM-dd HH:mm", "MM-dd HH:mm:ss")
        for (pattern in partialFormats) {
            val parsedDate = runCatching { SimpleDateFormat(pattern, locale).parse(timeStr) }.getOrNull() ?: continue
            return Calendar.getInstance(locale).apply {
                val currentYear = get(Calendar.YEAR)
                time = parsedDate
                set(Calendar.YEAR, currentYear)
            }.timeInMillis
        }
        return System.currentTimeMillis()
    }

    private fun maybeShowRuleDialogForChatBillCategoryEdit(
        item: ChatDisplayItem,
        originalBill: Bill,
        updatedBill: Bill
    ) {
        if (originalBill.categoryName == updatedBill.categoryName) return
        val referenceText = updatedBill.remark.ifBlank { originalBill.remark }.trim()
        if (referenceText.isBlank()) return

        Utils.toast(this, getString(R.string.ai_mismatch_detected))

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
                        RuleSaveOutcome.SAVED -> Utils.toast(this@ChatActivity, getString(R.string.rule_saved))
                        RuleSaveOutcome.OVERWRITTEN -> Utils.toast(this@ChatActivity, getString(R.string.rule_overwritten))
                        RuleSaveOutcome.CANCELED -> Utils.toast(this@ChatActivity, getString(R.string.rule_save_canceled))
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
        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting))
            .setTitle(getString(R.string.duplicate_rule_dialog_title))
            .setMessage(getString(R.string.duplicate_rule_dialog_message, keyword, existingCount.toString()))
            .setPositiveButton(getString(R.string.continue_and_overwrite)) { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(true)
            }
            .setNegativeButton(getString(R.string.cancel_save)) { d, _ ->
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

    private fun openImagePreview(item: ChatDisplayItem) {
        val imageUris = displayMessages
            .asSequence()
            .filter { it.msgType == MSG_TYPE_USER_IMAGE && it.imageUri.isNotBlank() }
            .map { it.imageUri }
            .toList()
        if (imageUris.isEmpty()) return
        val index = imageUris.indexOf(item.imageUri).coerceAtLeast(0)
        val intent = android.content.Intent(this, ChatImagePreviewActivity::class.java).apply {
            putStringArrayListExtra(ChatImagePreviewActivity.EXTRA_IMAGE_URIS, ArrayList(imageUris))
            putExtra(ChatImagePreviewActivity.EXTRA_INDEX, index)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, 0)
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

    /** 追加查询草稿卡片消息 */
    private fun appendQueryDraftMessage(draft: com.taostudio.tapaccounting.chat.query.QueryDraft): String {
        val uiKey = UUID.randomUUID().toString()
        val item = ChatDisplayItem(
            msgType = MSG_TYPE_QUERY_DRAFT,
            content = queryDraftManager.formatConditionsText(draft),
            timestamp = System.currentTimeMillis(),
            queryDraft = draft
        )
        displayMessages.add(item)
        adapter.notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom()
        return uiKey
    }

    /** 追加查询结果卡片消息 */
    private fun appendQueryResultMessage(result: com.taostudio.tapaccounting.chat.query.QueryResult): String {
        val uiKey = UUID.randomUUID().toString()
        val item = ChatDisplayItem(
            msgType = MSG_TYPE_QUERY_RESULT,
            content = queryDraftManager.formatResultText(result),
            timestamp = System.currentTimeMillis(),
            queryResult = result
        )
        displayMessages.add(item)
        adapter.notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom()
        return uiKey
    }

    /** 用户点击"统计金额" —— 使用该卡片自己的草稿 */
    private fun onQueryDraftStats(item: ChatDisplayItem) {
        val draft = item.queryDraft ?: return
        lifecycleScope.launch {
            val queryContext = withContext(Dispatchers.IO) { buildQueryContext() }
            val result = withContext(Dispatchers.IO) { queryDraftManager.executeStats(draft, queryContext) }
            appendQueryResultMessage(result)
        }
    }

    /** 用户点击"搜索账单" —— 使用该卡片自己的草稿 */
    private fun onQueryDraftSearch(item: ChatDisplayItem) {
        val draft = item.queryDraft ?: return
        lifecycleScope.launch {
            val queryContext = withContext(Dispatchers.IO) { buildQueryContext() }
            val bills = withContext(Dispatchers.IO) { queryDraftManager.executeSearch(draft, queryContext) }
            // executeSearch 已按 draft.billType 过滤，直接对结果求和即可
            val statBills = bills.filterNot { it.excludeFromStats }
            val result = com.taostudio.tapaccounting.chat.query.QueryResult(
                draft = draft,
                billCount = statBills.size,
                totalAmount = statBills.sumOf { it.amount },
                billsPreview = bills.take(3).map {
                    com.taostudio.tapaccounting.chat.query.BillPreview(
                        id = it.id, time = it.time, type = it.type,
                        amount = it.amount, remark = it.remark,
                        categoryName = it.categoryName,
                        accountName = it.accountName, currency = it.currency
                    )
                }
            )
            appendQueryResultMessage(result)
        }
    }

    /** 用户点击"取消" */
    private fun onQueryDraftCancel(item: ChatDisplayItem) {
        queryDraftManager.clearDraft()
        val index = displayMessages.indexOfFirst { it.uiKey == item.uiKey }
        if (index >= 0) {
            displayMessages.removeAt(index)
            adapter.notifyItemRemoved(index)
        }
    }

    /** 用户点击"查看明细" —— 带完整筛选条件 */
    private fun onQueryResultViewDetails(item: ChatDisplayItem) {
        val result = item.queryResult ?: return
        val draft = result.draft
        // 用本地查询获取匹配账单，然后展示在聊天中
        lifecycleScope.launch {
            val queryContext = withContext(Dispatchers.IO) { buildQueryContext() }
            val bills = withContext(Dispatchers.IO) { queryDraftManager.executeSearch(draft, queryContext) }
            if (bills.isNotEmpty()) {
                val summary = buildBillListSummary(bills.take(10), draft)
                appendAiTextMessage(summary, false, currentBookName, currentConversationId)
            } else {
                appendAiTextMessage("没有找到匹配的账单。", false, currentBookName, currentConversationId)
            }
        }
    }

    /** 构建账单列表摘要 */
    private fun buildBillListSummary(bills: List<Bill>, draft: com.taostudio.tapaccounting.chat.query.QueryDraft): String {
        val dateFormat = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
        val sb = StringBuilder()
        val timeLabel = draft.timeRange?.label ?: "全部时间"
        val typeLabel = when (draft.billType) {
            com.taostudio.tapaccounting.chat.query.QueryBillType.EXPENSE -> "支出"
            com.taostudio.tapaccounting.chat.query.QueryBillType.INCOME -> "收入"
            com.taostudio.tapaccounting.chat.query.QueryBillType.TRANSFER -> "转账"
            com.taostudio.tapaccounting.chat.query.QueryBillType.REPAYMENT -> "还款"
            com.taostudio.tapaccounting.chat.query.QueryBillType.REFUND -> "退款"
            com.taostudio.tapaccounting.chat.query.QueryBillType.ANY -> "全部"
        }
        sb.appendLine("📋 ${timeLabel}${typeLabel}账单明细（前${bills.size}条）")
        sb.appendLine()
        for (bill in bills) {
            val date = dateFormat.format(java.util.Date(bill.time))
            val title = bill.remark.ifBlank { bill.categoryName.ifBlank { bill.accountName.ifBlank { "未备注" } } }
            sb.appendLine("$date $title ¥${String.format(java.util.Locale.getDefault(), "%.2f", bill.amount)}")
        }
        return sb.toString().trimEnd()
    }

    /** 用户点击"改条件" —— 重新展示草稿卡片 */
    private fun onQueryResultEditConditions(item: ChatDisplayItem) {
        val result = item.queryResult ?: return
        // 重新设置 currentDraft 为该结果的草稿
        queryDraftManager.updateDraft(result.draft.id, result.draft)
        appendQueryDraftMessage(result.draft)
    }

    // === 卡片手动编辑 ===

    /** 编辑关键词 */
    private fun onQueryDraftEditKeyword(item: ChatDisplayItem) {
        val draft = item.queryDraft ?: return
        val editText = android.widget.EditText(this).apply {
            setText(draft.keyword ?: "")
            hint = getString(R.string.query_edit_keyword_hint)
            setPadding(48, 32, 48, 32)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.query_edit_keyword_title))
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newKeyword = editText.text.toString().trim().ifBlank { null }
                val updated = draft.copy(keyword = newKeyword, updatedAt = System.currentTimeMillis())
                updateQueryDraftItem(item, updated)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 编辑日期范围 */
    private fun onQueryDraftEditDate(item: ChatDisplayItem) {
        val draft = item.queryDraft ?: return
        com.taostudio.tapaccounting.ui.dialog.ElegantDatePickerSheet.showRange(
            context = this,
            initialStartMillis = draft.timeRange?.startMillis,
            initialEndMillis = draft.timeRange?.endMillis
        ) { startMillis, endMillis ->
            val label = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(startMillis)) +
                " 至 " +
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(endMillis))
            val newTimeRange = com.taostudio.tapaccounting.chat.query.QueryTimeRange(
                startMillis = startMillis,
                endMillis = endMillis,
                label = label
            )
            val updated = draft.copy(timeRange = newTimeRange, updatedAt = System.currentTimeMillis())
            updateQueryDraftItem(item, updated)
        }
    }

    /** 编辑账单类型 */
    private fun onQueryDraftEditBillType(item: ChatDisplayItem) {
        val draft = item.queryDraft ?: return
        val types = arrayOf("支出", "收入", "转账", "还款", "退款", "全部")
        val typeValues = arrayOf(
            com.taostudio.tapaccounting.chat.query.QueryBillType.EXPENSE,
            com.taostudio.tapaccounting.chat.query.QueryBillType.INCOME,
            com.taostudio.tapaccounting.chat.query.QueryBillType.TRANSFER,
            com.taostudio.tapaccounting.chat.query.QueryBillType.REPAYMENT,
            com.taostudio.tapaccounting.chat.query.QueryBillType.REFUND,
            com.taostudio.tapaccounting.chat.query.QueryBillType.ANY
        )
        val currentIndex = typeValues.indexOf(draft.billType).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择账单类型")
            .setSingleChoiceItems(types, currentIndex) { dialog, which ->
                val updated = draft.copy(billType = typeValues[which], updatedAt = System.currentTimeMillis())
                updateQueryDraftItem(item, updated)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 编辑账本范围 */
    private fun onQueryDraftEditBookScope(item: ChatDisplayItem) {
        val draft = item.queryDraft ?: return
        val scopes = arrayOf("当前账本", "全部账本")
        val scopeValues = arrayOf(
            com.taostudio.tapaccounting.chat.query.BookScope.CURRENT,
            com.taostudio.tapaccounting.chat.query.BookScope.ALL
        )
        val currentIndex = scopeValues.indexOf(draft.bookScope).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择账本范围")
            .setSingleChoiceItems(scopes, currentIndex) { dialog, which ->
                val updated = draft.copy(
                    bookScope = scopeValues[which],
                    bookName = if (scopeValues[which] == com.taostudio.tapaccounting.chat.query.BookScope.CURRENT) currentBookName else null,
                    updatedAt = System.currentTimeMillis()
                )
                updateQueryDraftItem(item, updated)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 更新查询草稿卡片 */
    private fun updateQueryDraftItem(item: ChatDisplayItem, newDraft: com.taostudio.tapaccounting.chat.query.QueryDraft) {
        val index = displayMessages.indexOfFirst { it.uiKey == item.uiKey }
        if (index < 0) return
        displayMessages[index] = item.copy(
            queryDraft = newDraft,
            content = queryDraftManager.formatConditionsText(newDraft)
        )
        adapter.notifyItemChanged(index)
        // 同步更新 currentDraft
        queryDraftManager.updateDraft(newDraft.id, newDraft)
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
    val billInteractionToken: String = "",
    val queryDraft: com.taostudio.tapaccounting.chat.query.QueryDraft? = null,
    val queryResult: com.taostudio.tapaccounting.chat.query.QueryResult? = null
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
