package com.taostudio.tapaccounting.chat.agent

import com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRegistry
import org.json.JSONObject

object AgentPromptBuilder {

    fun buildSystemPrompt(
        context: AgentSessionContext,
        selectedSkillIds: List<String> = listOf("general"),
        allowedTools: List<AgentTool> = emptyList()
    ): String {
        val assetList = context.queryContext.assets.joinToString("\n") { "  - ${it.name} (ID:${it.id}, 币种:${it.currency})" }
        val categoryList = context.queryContext.categories.joinToString("\n") { "  - ${it.name} (ID:${it.id})" }
        val bookList = context.queryContext.availableBooks.joinToString("、")

        // Build skill-specific instructions
        val skillInstructions = selectedSkillIds.mapNotNull { id ->
            val skill = AgentSkillRegistry.findById(id)
            val instr = skill?.buildInstructions(context)
            if (instr.isNullOrBlank()) null else "### ${skill.displayName}\n$instr"
        }.joinToString("\n\n")

        // Build tool descriptions from actual tool parameter schemas
        val toolDescriptions = buildToolDescriptions(allowedTools)

        return """
你是一个记账助手。用户会用自然语言和你对话，你需要选择合适的工具来完成任务。

## 输出规则
你必须输出一个JSON对象，格式如下：
单步操作: {"tool":"工具ID","params":{...}}
多步操作(最多5步): {"calls":[{"tool":"工具1","params":{...}},{"tool":"工具2","params":{...}}],"response_goal":"目标"}

不要输出任何其他内容，只输出JSON。

## 当前用户信息
账本: ${context.bookName}
可用账本: $bookList

## 用户的资产账户
$assetList

## 用户的记账分类
$categoryList

${if (skillInstructions.isNotBlank()) "## 当前模式说明\n$skillInstructions\n" else ""}
## 可用工具
$toolDescriptions

## 重要规则
1. 资产名称要和上面列出的资产账户名称精确匹配
2. 分类名称要和上面列出的分类名称精确匹配
3. 金额、余额等数字必须来自工具结果，不要编造
4. 只输出JSON，不要输出其他内容
5. 多步操作最多5步
6. 如果用户说"刚才那笔"、"上一笔"等，先搜索最近账单获取ID，再操作
7. 如果用户请求的是软件操作，但当前可用工具无法可靠完成，调用 agent.unsupported，不要假装已经执行
8. 普通知识问答、编程和闲聊仍使用 chat.reply，不要误判为软件功能未实现
""".trimIndent()
    }

    private fun buildToolDescriptions(tools: List<AgentTool>): String {
        if (tools.isEmpty()) return "(无可用工具)"

        val sb = StringBuilder()
        val grouped = tools.groupBy { it.category }
        for ((category, categoryTools) in grouped) {
            sb.appendLine("### $category")
            for (tool in categoryTools) {
                sb.appendLine("- ${tool.id}: ${tool.description}")
                val schema = tool.parameterSchema
                val props = schema.optJSONObject("properties")
                if (props != null && props.length() > 0) {
                    val paramDescs = mutableListOf<String>()
                    for (key in props.keys()) {
                        val prop = props.optJSONObject(key)
                        val type = prop?.optString("type", "string") ?: "string"
                        val desc = prop?.optString("description", "") ?: ""
                        paramDescs.add("$key($type): $desc")
                    }
                    sb.appendLine("  参数: ${paramDescs.joinToString("; ")}")
                }
                val required = schema.optJSONArray("required")
                if (required != null && required.length() > 0) {
                    val reqList = (0 until required.length()).map { required.getString(it) }
                    sb.appendLine("  必填: ${reqList.joinToString(", ")}")
                }
            }
            sb.appendLine()
        }
        return sb.toString().trim()
    }

    fun buildToolJson(): String {
        return AgentToolRegistry.getToolJson()
    }

    fun buildUserPrompt(userText: String, context: AgentSessionContext): String {
        return userText
    }
}
