package com.taostudio.tapaccounting.chat.agent.skill

import com.taostudio.tapaccounting.chat.agent.AgentSessionContext

interface AgentSkill {
    val id: String
    val displayName: String
    val description: String
    val toolIds: Set<String>
    val routingExamples: List<String>

    fun buildInstructions(context: AgentSessionContext): String = ""
}
