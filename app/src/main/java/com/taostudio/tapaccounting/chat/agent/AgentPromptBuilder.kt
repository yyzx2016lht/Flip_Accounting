package com.taostudio.tapaccounting.chat.agent

import org.json.JSONObject

object AgentPromptBuilder {

    fun buildSystemPrompt(context: AgentSessionContext): String {
        return """
你是一个记账助手，帮助用户通过自然语言完成记账和查询操作。

## 当前上下文
- 账本: ${context.bookName}
- 会话ID: ${context.conversationId}

## 可用工具
${AgentToolRegistry.getToolDescriptions()}

## 输出规则
你必须输出一个JSON对象，包含以下字段：
- tool: 工具ID（字符串）
- params: 工具参数（对象）
- assistant_hint: 你的思考过程（字符串，可选）

## 特殊工具
- chat.reply: 纯聊天回复，params.message 为回复内容
- agent.clarify: 追问用户，params.question 为问题内容
- agent.preview: 展示待确认操作预览

## 重要规则
1. 金额、余额、笔数等数字必须来自工具结果，不要编造
2. 不要在聊天中回显完整API Key或密码
3. 删除等危险操作必须先用agent.preview预览
4. 简单记账（单笔）可以免确认直接执行
""".trimIndent()
    }

    fun buildToolJson(): String {
        return AgentToolRegistry.getToolJson()
    }

    fun buildUserPrompt(userText: String, context: AgentSessionContext): String {
        val sb = StringBuilder()
        sb.appendLine("用户消息: $userText")
        sb.appendLine()
        sb.appendLine("上下文信息:")
        sb.appendLine("- 当前账本: ${context.bookName}")
        if (context.queryContext.assets.isNotEmpty()) {
            sb.appendLine("- 资产列表: ${context.queryContext.assets.joinToString(", ") { it.name }}")
        }
        if (context.queryContext.categories.isNotEmpty()) {
            sb.appendLine("- 分类列表: ${context.queryContext.categories.joinToString(", ") { it.name }}")
        }
        return sb.toString()
    }
}
