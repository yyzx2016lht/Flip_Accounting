package tao.test.flipaccounting

import android.content.Context

internal fun buildAssistantStyleInstruction(
    ctx: Context,
    defaultCustomReplyStyleGuide: String
): String {
    val customPrompt = shortenForModel(
        Prefs.getAiChatReplyStyleCustomPrompt(ctx).trim(),
        800,
        preserveTail = false
    )
    return when (Prefs.getAiChatReplyStyle(ctx)) {
        "gentle" ->
            "回复风格：温柔、轻声、像陪伴一样。请直接对用户说人话，不要输出场景标签、英文状态词或说明文字。"
        "concise" ->
            "回复风格：简洁、克制、少废话，但仍然要像正常聊天回复。请直接对用户说完整的人话，不要只输出 BILL_SAVED、NO_BILL、已记录 这类内部标签。"
        "playful" ->
            "回复风格：活泼、俏皮、可以碎碎念一点。请直接对用户说人话，不要输出场景标签、英文状态词或说明文字。"
        "custom" ->
            if (customPrompt.isNotBlank()) {
                "回复风格（用户自定义，高优先）：$customPrompt\n请直接对用户说自然的人话，不要输出场景标签、英文状态词、JSON 或内部指令。"
            } else {
                defaultCustomReplyStyleGuide
            }
        else ->
            "回复风格：自然、可爱一点、可以带少量颜文字和俏皮话，但不要太吵。请直接对用户说人话，不要输出场景标签、英文状态词或说明文字。"
    }
}

internal fun buildAccountingAssistantUserPrompt(
    userInput: String,
    billSummary: String,
    extractorReplyHint: String,
    styleInstruction: String,
    chatHistoryContext: String = ""
): String {
    val scene = if (billSummary.isBlank()) "NO_BILL" else "BILL_SAVED"
    return buildString {
        appendLine("场景：$scene")
        if (chatHistoryContext.isNotBlank()) {
            appendLine("【相关历史对话记录】")
            appendLine(chatHistoryContext)
            appendLine("【用户最新输入】")
        }
        appendLine("用户原话：$userInput")
        if (billSummary.isNotBlank()) appendLine("账单摘要：$billSummary")
        if (extractorReplyHint.isNotBlank()) appendLine("上游识别备注：$extractorReplyHint")
        appendLine(styleInstruction)
        append("请直接回复用户。")
    }
}
