package com.taostudio.tapaccounting.chat.agent

enum class AgentErrorType {
    INVALID_PARAMS,
    NOT_FOUND,
    AMBIGUOUS,
    CONFIRMATION_REQUIRED,
    PERMISSION_REQUIRED,
    NETWORK_ERROR,
    TOOL_EXECUTION_ERROR,
    MODEL_PROTOCOL_ERROR
}
