package com.taostudio.tapaccounting.chat.agent

import com.taostudio.tapaccounting.chat.agent.skill.AgentSkill
import com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRegistry
import org.json.JSONArray
import org.json.JSONObject

object AgentPromptBuilder {

    fun buildSystemPrompt(context: AgentSessionContext): String {
        val allSkills = AgentSkillRegistry.getAll()
        val generalSkill = AgentSkillRegistry.findById("general")
        val selectedSkills = if (generalSkill != null) listOf(generalSkill) else emptyList()
        val selectedToolIds = selectedSkills.flatMap { it.toolIds }.distinct()
        val selectedTools = selectedToolIds.mapNotNull { AgentToolRegistry.findById(it) }
        return buildSystemPrompt(context, selectedSkills, selectedTools)
    }

    fun buildSystemPrompt(
        context: AgentSessionContext,
        selectedSkills: List<AgentSkill>,
        tools: List<AgentTool>,
        conversationState: AgentConversationState? = null
    ): String {
        val assetList = context.queryContext.assets.joinToString("\n") { "  - ${it.name} (ID:${it.id}, 币种:${it.currency})" }
        val categoryList = context.queryContext.categories.joinToString("\n") { "  - ${it.name} (ID:${it.id})" }
        val bookList = context.queryContext.availableBooks.joinToString("、")

        val skillInstructions = selectedSkills
            .filter { it.buildInstructions(context).isNotBlank() }
            .joinToString("\n\n") { "### ${it.displayName}\n${it.buildInstructions(context)}" }

        val toolJsonArray = buildToolJsonArray(tools)

        val recentContext = buildRecentContext(conversationState)

        return """
你是一个通用 AI 助手，同时具备记账和财务管理工具。用户可以和你自由对话、询问图片内容，也可以要求你记账或操作应用。

## 输出规则
你必须输出一个JSON对象，格式如下：
{"tool":"工具ID","params":{...}}

或者多步调用格式：
{"calls":[{"tool":"工具ID","params":{...}},...],"response_goal":"目标"}

不要输出任何其他内容，只输出JSON。

## 当前用户信息
账本: ${context.bookName}
可用账本: $bookList

## 用户的资产账户
$assetList

## 用户的记账分类
$categoryList

$recentContext

## Skill 说明
$skillInstructions

## 可用工具
$toolJsonArray

## 重要提示
1. 资产名称要和上面列出的资产账户名称匹配，比如用户说"微信"就找资产列表中的"微信"
2. 分类名称要和上面列出的分类名称匹配
3. 金额、余额等数字必须来自工具结果，不要编造
4. 只输出JSON，不要输出其他内容
5. 如果用户信息不明确，使用 agent.clarify 追问
6. 如果是纯聊天，使用 chat.reply 回复
7. 用户说"上一笔""刚才那笔"时，优先使用 recentBillIds 中的账单 ID
8. 删除或修改账单时，如果目标不明确，请先追问确认
9. 图片不等于账单。用户询问图片内容、人物、物品、场景或文字时，使用 chat.reply 正常回答
10. 只有用户明确表达记账意图，或图片明确属于小票、支付记录、订单、账单凭证且上下文是在提交记账时，才使用 bill.create_from_text
11. 从图片创建账单时，把图片中识别到的商户、金额、时间、支付方式等整理进 bill.create_from_text 的 text 参数
12. 无法判断用户是否想记账时先使用 agent.clarify，不要擅自创建账单
""".trimIndent()
    }

    private fun buildRecentContext(conversationState: AgentConversationState?): String {
        if (conversationState == null) return ""

        val parts = mutableListOf<String>()

        if (conversationState.recentBillIds.isNotEmpty()) {
            val billIdsStr = conversationState.recentBillIds.take(5).joinToString(", ")
            parts.add("最近操作的账单 ID: $billIdsStr")
        }

        if (conversationState.recentAssetIds.isNotEmpty()) {
            val assetIdsStr = conversationState.recentAssetIds.take(5).joinToString(", ")
            parts.add("最近查询的资产 ID: $assetIdsStr")
        }

        if (conversationState.recentBookNames.isNotEmpty()) {
            val bookNamesStr = conversationState.recentBookNames.take(3).joinToString("、")
            parts.add("最近使用的账本: $bookNamesStr")
        }

        if (parts.isEmpty()) return ""

        return "## 会话上下文\n${parts.joinToString("\n")}"
    }

    private fun buildToolJsonArray(tools: List<AgentTool>): String {
        if (tools.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        for ((index, tool) in tools.withIndex()) {
            sb.append("{")
            sb.append("\"id\":\"${tool.id}\",")
            sb.append("\"category\":\"${tool.category}\",")
            sb.append("\"risk\":\"${tool.risk}\",")
            sb.append("\"description\":\"${tool.description}\",")
            sb.append("\"parameters\":${tool.parameterSchema}")
            sb.append("}")
            if (index < tools.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    fun buildToolJson(): String {
        return AgentToolRegistry.getToolJson()
    }

    fun buildUserPrompt(userText: String, context: AgentSessionContext): String {
        return userText
    }
}
