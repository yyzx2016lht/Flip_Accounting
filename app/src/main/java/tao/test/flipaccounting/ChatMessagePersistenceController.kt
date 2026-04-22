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

    fun appendAiTextMessage(text: String, isLoading: Boolean): Int {
        val idx = if (isUiAlive()) {
            val item = ChatDisplayItem(
                msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                content = text,
                timestamp = System.currentTimeMillis(),
                isLoading = isLoading
            )
            displayMessages.add(item)
            val insertedIndex = displayMessages.lastIndex
            adapterProvider().notifyItemInserted(insertedIndex)
            scrollToBottom()
            insertedIndex
        } else {
            -1
        }

        if (!isLoading && text.isNotBlank()) {
            aiWorkScope.launch(Dispatchers.IO) {
                db.chatMessageDao().insert(
                    ChatMessage(
                        msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                        content = text,
                        modelName = Prefs.getAiChatModel(context),
                        bookName = getCurrentBookName(),
                        conversationId = getCurrentConversationId()
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
        return idx
    }

    suspend fun persistAiTextMessage(text: String) {
        withContext(Dispatchers.IO) {
            db.chatMessageDao().insert(
                ChatMessage(
                    msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                    content = text,
                    modelName = Prefs.getAiChatModel(context),
                    bookName = getCurrentBookName(),
                    conversationId = getCurrentConversationId()
                )
            )
        }
    }

    fun removeLoadingMessage(idx: Int) {
        if (!isUiAlive()) return
        if (idx in displayMessages.indices && displayMessages[idx].isLoading) {
            displayMessages.removeAt(idx)
            adapterProvider().notifyItemRemoved(idx)
            scrollToBottom()
        }
    }

    fun updateLoadingMessage(idx: Int, text: String) {
        if (!isUiAlive()) return
        if (idx !in displayMessages.indices) return
        val current = displayMessages[idx]
        if (!current.isLoading) return
        displayMessages[idx] = current.copy(content = text)
        adapterProvider().notifyItemChanged(idx)
        ensureLastMessageVisible()
    }

    fun finalizeLoadingMessage(idx: Int, text: String) {
        if (!isUiAlive()) {
            if (text.isNotBlank()) {
                aiWorkScope.launch(Dispatchers.IO) {
                    db.chatMessageDao().insert(
                        ChatMessage(
                            msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                            content = text,
                            modelName = Prefs.getAiChatModel(context),
                            bookName = getCurrentBookName(),
                            conversationId = getCurrentConversationId()
                        )
                    )
                }
            }
            return
        }
        if (idx !in displayMessages.indices) return
        val current = displayMessages[idx]
        if (!current.isLoading) return
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
                        bookName = getCurrentBookName(),
                        conversationId = getCurrentConversationId()
                    )
                )
            }
        }
    }
}
