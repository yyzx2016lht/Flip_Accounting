package tao.test.flipaccounting

import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.ChatMessage
import java.io.File

class ChatMessagePersistenceController(
    private val context: ChatActivity,
    private val db: AppDatabase,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val aiWorkScope: CoroutineScope,
    private val displayMessages: MutableList<ChatDisplayItem>,
    private val pendingVoiceBubbleAnimations: MutableSet<String>,
    private val adapterProvider: () -> RecyclerView.Adapter<*>,
    private val rvSessionListProvider: () -> RecyclerView,
    private val drawerSessionsProvider: () -> DrawerLayout,
    private val etSessionSearchProvider: () -> android.widget.EditText,
    private val sessionAdapterProvider: () -> SessionListAdapter,
    private val allSessionRowsProvider: () -> List<ChatSessionRow>,
    private val getCurrentBookName: () -> String,
    private val getCurrentConversationId: () -> String,
    private val buildVoicePayload: (String, Int, String) -> String,
    private val scrollToBottom: () -> Unit,
    private val ensureLastMessageVisible: () -> Unit,
    private val refreshSessionRows: suspend () -> Unit
) {
    private fun isUiAlive(): Boolean = !(context.isDestroyed || context.isFinishing)

    fun appendUserMessage(text: String, type: Int, imageUri: String = "") {
        val item = ChatDisplayItem(
            msgType = type,
            content = text,
            imageUri = imageUri,
            timestamp = System.currentTimeMillis()
        )
        displayMessages.add(item)
        adapterProvider().notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom()

        lifecycleScope.launch(Dispatchers.IO) {
            val id = db.chatMessageDao().insert(
                ChatMessage(
                    msgType = type,
                    content = text,
                    imageUri = imageUri,
                    timestamp = item.timestamp,
                    bookName = getCurrentBookName(),
                    conversationId = getCurrentConversationId()
                )
            )
            withContext(Dispatchers.Main) {
                val idx = displayMessages.indexOfLast { it.timestamp == item.timestamp && it.msgType == type }
                if (idx >= 0) displayMessages[idx] = displayMessages[idx].copy(dbId = id)
                lifecycleScope.launch {
                    refreshSessionRows()
                    if (drawerSessionsProvider().isDrawerOpen(androidx.core.view.GravityCompat.END) &&
                        etSessionSearchProvider().text?.toString().orEmpty().isBlank()
                    ) {
                        rvSessionListProvider().adapter = sessionAdapterProvider()
                        sessionAdapterProvider().submit(allSessionRowsProvider().toList())
                    }
                }
            }
        }
    }

    fun appendUserVoiceMessage(audioFile: File, durationSec: Int, transcript: String): ChatDisplayItem {
        val payload = buildVoicePayload(audioFile.absolutePath, durationSec, transcript)
        val item = ChatDisplayItem(
            msgType = ChatActivity.MSG_TYPE_USER_VOICE,
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
        adapterProvider().notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom()

        lifecycleScope.launch(Dispatchers.IO) {
            val id = db.chatMessageDao().insert(
                ChatMessage(
                    msgType = ChatActivity.MSG_TYPE_USER_VOICE,
                    content = payload,
                    timestamp = item.timestamp,
                    bookName = getCurrentBookName(),
                    conversationId = getCurrentConversationId()
                )
            )
            withContext(Dispatchers.Main) {
                val idx = displayMessages.indexOfLast {
                    it.timestamp == item.timestamp && it.msgType == ChatActivity.MSG_TYPE_USER_VOICE
                }
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

    fun appendAiTextMessage(
        text: String,
        isLoading: Boolean,
        bookName: String? = null,
        conversationId: String? = null
    ): String {
        val item = ChatDisplayItem(
            msgType = ChatActivity.MSG_TYPE_AI_TEXT,
            content = text,
            timestamp = System.currentTimeMillis(),
            isLoading = isLoading
        )
        if (isUiAlive()) {
            displayMessages.add(item)
            val insertedIndex = displayMessages.lastIndex
            adapterProvider().notifyItemInserted(insertedIndex)
            scrollToBottom()
        }

        if (!isLoading && text.isNotBlank()) {
            val targetBookName = bookName ?: getCurrentBookName()
            val targetConversationId = conversationId ?: getCurrentConversationId()
            aiWorkScope.launch(Dispatchers.IO) {
                db.chatMessageDao().insert(
                    ChatMessage(
                        msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                        content = text,
                        modelName = Prefs.getAiChatModel(context),
                        bookName = targetBookName,
                        conversationId = targetConversationId
                    )
                )
                withContext(Dispatchers.Main) {
                    if (!isUiAlive()) return@withContext
                    refreshSessionRows()
                    if (drawerSessionsProvider().isDrawerOpen(androidx.core.view.GravityCompat.END) &&
                        etSessionSearchProvider().text?.toString().orEmpty().isBlank()
                    ) {
                        rvSessionListProvider().adapter = sessionAdapterProvider()
                        sessionAdapterProvider().submit(allSessionRowsProvider().toList())
                    }
                }
            }
        }
        return item.uiKey
    }

    suspend fun persistAiTextMessage(text: String, bookName: String, conversationId: String) {
        withContext(Dispatchers.IO) {
            db.chatMessageDao().insert(
                ChatMessage(
                    msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                    content = text,
                    modelName = Prefs.getAiChatModel(context),
                    bookName = bookName,
                    conversationId = conversationId
                )
            )
        }
    }

    fun removeLoadingMessage(uiKey: String) {
        if (!isUiAlive()) return
        val idx = displayMessages.indexOfFirst { it.uiKey == uiKey && it.isLoading }
        if (idx >= 0) {
            displayMessages.removeAt(idx)
            adapterProvider().notifyItemRemoved(idx)
            scrollToBottom()
        }
    }

    fun updateLoadingMessage(uiKey: String, text: String) {
        if (!isUiAlive()) return
        val idx = displayMessages.indexOfFirst { it.uiKey == uiKey && it.isLoading }
        if (idx < 0) return
        val current = displayMessages[idx]
        displayMessages[idx] = current.copy(content = text)
        adapterProvider().notifyItemChanged(idx)
        ensureLastMessageVisible()
    }

    fun finalizeLoadingMessage(uiKey: String, text: String, bookName: String, conversationId: String) {
        if (!isUiAlive()) return
        val idx = displayMessages.indexOfFirst { it.uiKey == uiKey && it.isLoading }
        if (idx < 0) return
        val current = displayMessages[idx]
        displayMessages[idx] = current.copy(content = text, isLoading = false)
        adapterProvider().notifyItemChanged(idx)
        scrollToBottom()
        if (text.isNotBlank()) {
            aiWorkScope.launch(Dispatchers.IO) {
                db.chatMessageDao().insert(
                    ChatMessage(
                        msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                        content = text,
                        modelName = Prefs.getAiChatModel(context),
                        bookName = bookName,
                        conversationId = conversationId
                    )
                )
            }
        }
    }

    @Deprecated("Use uiKey-based appendAiTextMessage overload.")
    fun appendAiTextMessageLegacy(text: String, isLoading: Boolean): Int {
        val uiKey = appendAiTextMessage(text, isLoading)
        return displayMessages.indexOfFirst { it.uiKey == uiKey }
    }

    @Deprecated("Use explicit book/conversation persistence.")
    suspend fun persistAiTextMessage(text: String) {
        persistAiTextMessage(text, getCurrentBookName(), getCurrentConversationId())
    }

    @Deprecated("Use uiKey-based removeLoadingMessage.")
    fun removeLoadingMessage(idx: Int) {
        val uiKey = displayMessages.getOrNull(idx)?.uiKey ?: return
        removeLoadingMessage(uiKey)
    }

    @Deprecated("Use uiKey-based updateLoadingMessage.")
    fun updateLoadingMessage(idx: Int, text: String) {
        val uiKey = displayMessages.getOrNull(idx)?.uiKey ?: return
        updateLoadingMessage(uiKey, text)
    }

    @Deprecated("Use uiKey-based finalizeLoadingMessage with explicit context.")
    fun finalizeLoadingMessage(idx: Int, text: String) {
        val uiKey = displayMessages.getOrNull(idx)?.uiKey ?: return
        finalizeLoadingMessage(uiKey, text, getCurrentBookName(), getCurrentConversationId())
    }
}
