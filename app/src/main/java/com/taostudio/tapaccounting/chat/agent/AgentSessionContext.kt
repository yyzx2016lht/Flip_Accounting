package com.taostudio.tapaccounting.chat.agent

import com.taostudio.tapaccounting.chat.query.QueryContext

data class AgentSessionContext(
    val bookName: String,
    val conversationId: String,
    val queryContext: QueryContext,
    val permissionState: Map<String, Boolean> = emptyMap()
)
