package com.taostudio.tapaccounting.chat.agent

object AgentToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.id] = tool
    }

    fun findById(id: String): AgentTool? = tools[id]

    fun getByCategory(category: String): List<AgentTool> =
        tools.values.filter { it.category == category }

    fun getAll(): List<AgentTool> = tools.values.toList()

    fun getToolDescriptions(): String {
        val sb = StringBuilder()
        val grouped = tools.values.groupBy { it.category }
        for ((category, categoryTools) in grouped) {
            sb.appendLine("## $category")
            for (tool in categoryTools) {
                sb.appendLine("- ${tool.id}: ${tool.description}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun getToolJson(): String {
        val sb = StringBuilder("[")
        val allTools = tools.values.toList()
        for ((index, tool) in allTools.withIndex()) {
            sb.append("{")
            sb.append("\"id\":\"${tool.id}\",")
            sb.append("\"category\":\"${tool.category}\",")
            sb.append("\"risk\":\"${tool.risk}\",")
            sb.append("\"description\":\"${tool.description}\",")
            sb.append("\"parameters\":${tool.parameterSchema}")
            sb.append("}")
            if (index < allTools.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }
}
