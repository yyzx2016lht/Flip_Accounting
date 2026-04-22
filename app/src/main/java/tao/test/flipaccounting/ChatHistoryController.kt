package tao.test.flipaccounting

import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.data.local.AppDatabase

class ChatHistoryController(
    private val context: ChatActivity,
    private val db: AppDatabase,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val displayMessages: MutableList<ChatDisplayItem>,
    private val adapterProvider: () -> RecyclerView.Adapter<*>,
    private val rvMessagesProvider: () -> RecyclerView,
    private val drawerSessionsProvider: () -> DrawerLayout,
    private val etSessionSearchProvider: () -> android.widget.EditText,
    private val rvSessionListProvider: () -> RecyclerView,
    private val sessionAdapterProvider: () -> SessionListAdapter,
    private val allSessionRowsProvider: () -> List<ChatSessionRow>,
    private val getCurrentBookName: () -> String,
    private val getCurrentConversationId: () -> String,
    private val getPendingScrollToMessageId: () -> Long,
    private val setPendingScrollToMessageId: (Long) -> Unit,
    private val parseVoicePayload: (String) -> VoicePayload,
    private val parseVoicePayloadStrict: (String) -> VoicePayload?,
    private val parseBillIds: (String) -> List<Long>,
    private val isDeprecatedBillMessage: (String) -> Boolean,
    private val parseBillsFromMessageContent: (String) -> List<tao.test.flipaccounting.data.local.entity.Bill>,
    private val parseDeprecatedBillIdsFromContent: (String) -> Set<Long>,
    private val mergeChatBillSnapshots: (
        List<tao.test.flipaccounting.data.local.entity.Bill>,
        List<tao.test.flipaccounting.data.local.entity.Bill>
    ) -> List<tao.test.flipaccounting.data.local.entity.Bill>,
    private val markBillIdsAsDeprecated: (String) -> String,
    private val updateConversationSubtitle: () -> Unit,
    private val scrollToBottom: () -> Unit,
    private val refreshSessionRows: suspend () -> Unit
) {
    fun loadHistoryMessages() {
        lifecycleScope.launch {
            val dbMessages = withContext(Dispatchers.IO) {
                db.chatMessageDao().getAllByBookAndConversation(getCurrentBookName(), getCurrentConversationId())
            }
            val dbBillDao = db.billDao()
            val orphanIds = mutableListOf<Long>()
            displayMessages.clear()
            var hasRenderableUserAnchor = false

            for (msg in dbMessages) {
                when (msg.msgType) {
                    ChatActivity.MSG_TYPE_USER_TEXT -> {
                        val recoveredVoice = parseVoicePayloadStrict(msg.content)
                        if (recoveredVoice != null) {
                            hasRenderableUserAnchor = true
                            displayMessages.add(
                                ChatDisplayItem(
                                    dbId = msg.id,
                                    msgType = ChatActivity.MSG_TYPE_USER_VOICE,
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
                    ChatActivity.MSG_TYPE_USER_VOICE -> {
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
                    ChatActivity.MSG_TYPE_USER_IMAGE -> {
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
                    ChatActivity.MSG_TYPE_AI_TEXT -> {
                        val recoveredVoice = parseVoicePayloadStrict(msg.content)
                        if (recoveredVoice != null) {
                            hasRenderableUserAnchor = true
                            displayMessages.add(
                                ChatDisplayItem(
                                    dbId = msg.id,
                                    msgType = ChatActivity.MSG_TYPE_USER_VOICE,
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
                    ChatActivity.MSG_TYPE_AI_BILL -> {
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
                                        db.chatMessageDao().update(
                                            oldMsg.copy(billIds = markBillIdsAsDeprecated(oldMsg.billIds))
                                        )
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

            adapterProvider().notifyDataSetChanged()
            updateConversationSubtitle()
            scrollToBottom()
            scrollToPendingMessageIfNeeded()
            refreshSessionRows()
            if (drawerSessionsProvider().isDrawerOpen(GravityCompat.END) &&
                etSessionSearchProvider().text?.toString().orEmpty().isBlank()
            ) {
                rvSessionListProvider().adapter = sessionAdapterProvider()
                sessionAdapterProvider().submit(allSessionRowsProvider().toList())
            }
        }
    }

    fun scrollToPendingMessageIfNeeded() {
        val pendingId = getPendingScrollToMessageId()
        if (pendingId <= 0L) return
        val idx = displayMessages.indexOfFirst { it.dbId == pendingId }
        if (idx >= 0) rvMessagesProvider().scrollToPosition(idx)
        setPendingScrollToMessageId(-1L)
    }
}
