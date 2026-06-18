package com.taostudio.tapaccounting.chat.agent

data class AgentValidationResult(
    val valid: Boolean,
    val errorMessage: String? = null,
    val errorType: AgentErrorType? = null,
    val missingParams: List<String> = emptyList()
) {
    companion object {
        fun success(): AgentValidationResult = AgentValidationResult(valid = true)

        fun invalidParams(message: String, missingParams: List<String> = emptyList()): AgentValidationResult =
            AgentValidationResult(
                valid = false,
                errorMessage = message,
                errorType = AgentErrorType.INVALID_PARAMS,
                missingParams = missingParams
            )

        fun notFound(message: String): AgentValidationResult =
            AgentValidationResult(
                valid = false,
                errorMessage = message,
                errorType = AgentErrorType.NOT_FOUND
            )

        fun ambiguous(message: String): AgentValidationResult =
            AgentValidationResult(
                valid = false,
                errorMessage = message,
                errorType = AgentErrorType.AMBIGUOUS
            )

        fun permissionRequired(message: String): AgentValidationResult =
            AgentValidationResult(
                valid = false,
                errorMessage = message,
                errorType = AgentErrorType.PERMISSION_REQUIRED
            )
    }
}
