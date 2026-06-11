package com.taostudio.tapaccounting.chat.agent

import org.json.JSONObject

sealed class AgentEffect {
    data class ProcessAccountingResult(
        val result: JSONObject,
        val sourceText: String
    ) : AgentEffect()
}
