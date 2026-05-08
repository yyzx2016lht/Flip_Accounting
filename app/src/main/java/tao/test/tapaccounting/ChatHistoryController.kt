package tao.test.tapaccounting

import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.tapaccounting.data.local.AppDatabase

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
    private val parseBillsFromMessageContent: (String) -> List<tao.test.tapaccounting.data.local.entity.Bill>,
    private val parseDeprecatedBillIdsFromContent: (String) -> Set<Long>,
    private val parseEditedBillIdsFromContent: (String) -> Set<Long>,
    private val parseSnapshotOnlyFromContent: (String) -> Boolean,
    private val mergeChatBillSnapshots: (
        List<tao.test.tapaccounting.data.local.entity.Bill>,
        List<tao.test.tapaccounting.data.local.entity.Bill>
    ) -> List<tao.test.tapaccounting.data.local.entity.Bill>,
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
            val billMapById = withContext(Dispatchers.IO) {
                val allIds = dbMessages
                    .asSequence()
                    .filter { it.msgType == ChatActivity.MSG_TYPE_AI_BILL }
                    .flatMap { parseBillIds(it.billIds).asSequence() }
                    .filter { it > 0L }
                    .distinct()
                    .toList()
                if (allIds.isEmpty()) emptyMap() else dbBillDao.getBillsByIds(allIds).associateBy { it.id }
            }
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
                            continue
                        }
                        val billIds = parseBillIds(msg.billIds)
                        val deprecated = isDeprecatedBillMessage(msg.billIds)
                        val bills = billIds.mapNotNull { billMapById[it] }
                        val billSnapshots = parseBillsFromMessageContent(msg.content)
                        val deprecatedBillIds = parseDeprecatedBillIdsFromContent(msg.content)
                        val editedBillIds = parseEditedBillIdsFromContent(msg.content)
                        val snapshotOnly = parseSnapshotOnlyFromContent(msg.content)
                        val displayBills = if (bills.isNotEmpty()) {
                            mergeChatBillSnapshots(bills, billSnapshots)
                        } else {
                            billSnapshots
                        }
                        if (displayBills.isEmpty()) {
                            displayMessages.add(
                                ChatDisplayItem(
                                    dbId = msg.id,
                                    msgType = ChatActivity.MSG_TYPE_AI_TEXT,
                                    content = "这条账单消息暂时无法渲染，原始记录已保留。",
                                    timestamp = msg.timestamp
                                )
                            )
                        } else {
                            displayMessages.add(
                                ChatDisplayItem(
                                    dbId = msg.id,
                                    msgType = msg.msgType,
                                    content = msg.content,
                                    bills = displayBills.toMutableList(),
                                    timestamp = msg.timestamp,
                                    isDeprecated = deprecated || (bills.isEmpty() && !snapshotOnly),
                                    deprecatedBillIds = deprecatedBillIds.toMutableSet(),
                                    editedBillIds = editedBillIds.toMutableSet()
                                )
                            )
                        }
                    }
                }
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
