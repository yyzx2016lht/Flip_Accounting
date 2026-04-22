package tao.test.flipaccounting

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.ChatMessage
import java.io.File
import java.util.Locale

class ChatMessagePipeline(
    private val context: ChatActivity,
    private val aiWorkScope: CoroutineScope,
    private val getInputText: () -> String,
    private val clearInput: () -> Unit,
    private val updateInputActionUi: () -> Unit,
    private val appendUserMessage: (String, Int) -> Unit,
    private val consumePendingHabitSuggestionReply: (String) -> Boolean,
    private val appendAiTextMessage: (String, Boolean) -> Int,
    private val removeLoadingMessage: (Int) -> Unit,
    private val updateLoadingMessage: (Int, String) -> Unit,
    private val finalizeLoadingMessage: (Int, String) -> Unit,
    private val buildAnalysisInput: suspend (String) -> String,
    private val decideSingleOrMultiForChat: (String) -> Boolean,
    private val processBillResult: suspend (JSONObject, String) -> List<Bill>,
    private val buildBillSummary: (List<Bill>) -> String,
    private val transcribeVoiceToTextWithFallback: suspend (File) -> String,
    private val persistAiTextMessage: suspend (String) -> Unit
) {
    private fun isUiAlive(): Boolean = !(context.isDestroyed || context.isFinishing)

    private fun runOnUiIfAlive(block: () -> Unit) {
        if (!isUiAlive()) return
        context.runOnUiThread {
            if (!isUiAlive()) return@runOnUiThread
            block()
        }
    }

    fun sendText() {
        val text = getInputText().trim()
        if (text.isEmpty()) {
            Utils.toast(context, "不能发送空内容")
            return
        }
        clearInput()
        updateInputActionUi()
        appendUserMessage(text, ChatActivity.MSG_TYPE_USER_TEXT)
        if (consumePendingHabitSuggestionReply(text)) return
        callAiAccounting(text, appendUserBubble = false)
    }

    fun callAiAccounting(
        userText: String,
        appendUserBubble: Boolean = true,
        forceTextReply: Boolean = false,
        loadingIdxOverride: Int? = null,
        loadingBootstrapText: String = ""
    ) {
        if (appendUserBubble) appendUserMessage(userText, ChatActivity.MSG_TYPE_USER_TEXT)
        val loadingIdx = loadingIdxOverride ?: appendAiTextMessage("正在理解你的消息...", true)
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
        aiWorkScope.launch {
            try {
                val analysisInput = buildAnalysisInput(userText)
                val autoMultiMode = decideSingleOrMultiForChat(userText)
                val result = try {
                    withContext(Dispatchers.IO) {
                        if (userText.startsWith("[MULTIMODAL_IMAGE]")) {
                            val payload = userText.removePrefix("[MULTIMODAL_IMAGE]")
                            val parts = payload.split("|", limit = 2)
                            val base64 = parts.getOrElse(0) { "" }
                            val mime = parts.getOrElse(1) { "image/jpeg" }
                            val visionResult = AIService.analyzeReceiptByImage(context, base64, mime)
                            AIService.analyzeAccounting(context, visionResult) { status ->
                                runOnUiIfAlive { pushLoadingStatus(status) }
                            }
                        } else {
                            AIService.analyzeAccounting(
                                ctx = context,
                                userInput = analysisInput,
                                isMultiModeOverride = autoMultiMode
                            ) { status ->
                                runOnUiIfAlive { pushLoadingStatus(status) }
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
                        appendAiTextMessage(
                            "我这次没能正确解析，但已经收到你的语音转写文本。你可以再说得更具体一点，我继续帮你记账。",
                            false
                        )
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
                            false
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
                }
            } catch (e: Exception) {
                removeLoadingMessage(loadingIdx)
                val msg = mapAiErrorToUserMessage(e)
                appendAiTextMessage(msg, false)
                persistAiTextMessage(msg)
            }
        }
    }

    fun callAiAccountingWithVoice(audioFile: File) {
        val loadingIdx = appendAiTextMessage("正在理解你的消息...", true)
        var loadingStage = 1
        fun pushLoadingStatus(raw: String) {
            val (stage, text) = mapProgressToNaturalStatus(raw)
            loadingStage = maxOf(loadingStage, stage)
            val stableText = when (loadingStage) {
                1 -> "正在理解你的消息..."
                2 -> "正在生成回复..."
                else -> text
            }
            updateLoadingMessage(loadingIdx, stableText)
        }
        aiWorkScope.launch {
            try {
                val voiceUserText = "[语音输入]"
                val autoMultiMode = withContext(Dispatchers.IO) {
                    val transcript = transcribeVoiceToTextWithFallback(audioFile)
                    decideSingleOrMultiForChat(transcript)
                }
                val result = try {
                    withContext(Dispatchers.IO) {
                        AIService.analyzeAccountingByAudio(
                            ctx = context,
                            audioFile = audioFile,
                            isMultiModeOverride = autoMultiMode
                        ) { status ->
                            runOnUiIfAlive { pushLoadingStatus(status) }
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
                        appendAiTextMessage("我收到这段语音了，但这次没能正确解析。你可以再说得更具体一点。", false)
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
                        appendAiTextMessage(hint.ifBlank { "我暂时没识别到明确账单，你可以补充金额、分类或账户。"}, false)
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
                appendAiTextMessage(msg, false)
                persistAiTextMessage(msg)
            }
        }
    }

    private suspend fun appendAssistantCompanionReply(
        userText: String,
        billSummary: String,
        extractorReplyHint: String
    ): Boolean {
        if (Prefs.getAiChatReplyStyle(context) == "off") return false
        val editingIdx = appendAiTextMessage("", true)
        val streamed = StringBuilder()
        val streamOk = try {
            withContext(Dispatchers.IO) {
                AIService.streamAccountingAssistantReply(
                    ctx = context,
                    userInput = userText,
                    billSummary = billSummary,
                    extractorReplyHint = extractorReplyHint
                ) { delta ->
                    if (delta.isNotBlank()) {
                        streamed.append(delta)
                        runOnUiIfAlive {
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
                    ctx = context,
                    userInput = userText,
                    billSummary = billSummary,
                    extractorReplyHint = extractorReplyHint
                )
            }.trim()
        } catch (_: Exception) {
            ""
        }
        removeLoadingMessage(editingIdx)
        val sanitized = sanitizeAssistantReply(reply)
        if (sanitized.isNotBlank()) {
            appendAiTextMessage(sanitized, false)
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
            normalized.contains("http 500") || normalized.contains("500 internal") -> "网络不佳，请重试"
            normalized.contains("timeout") || normalized.contains("timed out") -> "网络不佳，请重试"
            normalized.contains("unable to resolve host") || normalized.contains("failed to connect") -> "网络不佳，请重试"
            raw.isBlank() -> "分析失败，请稍后重试"
            else -> "分析失败: $raw"
        }
    }

    private fun shouldFallbackToAssistant(error: Exception): Boolean {
        val msg = error.message.orEmpty()
        if (msg.contains("API Key")) return false
        if (msg.contains("配置")) return false
        return error is IllegalArgumentException || msg.contains("JSON", ignoreCase = true)
    }

    private fun mapProgressToNaturalStatus(raw: String): Pair<Int, String> {
        val text = raw.trim()
        if (text.isBlank()) return 1 to "正在理解你的消息..."
        val lower = text.lowercase(Locale.getDefault())

        if (text.contains("智能分类中") || text.contains("智能分析中") || text.contains("匹配中")) {
            return 3 to text
        }

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
}
