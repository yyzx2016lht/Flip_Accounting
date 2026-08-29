package com.taostudio.tapaccounting.data.sync.protocol

import com.google.gson.JsonObject

data class ManifestMember(
    val memberId: String,
    val displayName: String,
    val joinOrder: Int,
    val invitedAt: Long? = null,
    val joinedAt: Long? = null
)

data class Manifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val sharedBookId: String,
    val name: String,
    val createdAt: Long,
    val members: List<ManifestMember>
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_NAME_LENGTH = 100
        const val MAX_MEMBER_DISPLAY_NAME_LENGTH = 40
        const val MIN_MEMBER_COUNT = 1
        const val MAX_MEMBER_COUNT = 5
    }
}

data class Operation(
    val operationId: String,
    val type: String,
    val entityType: String,
    val entityId: String,
    val revision: Long,
    val deviceId: String,
    val memberId: String,
    val timestamp: Long,
    val payload: JsonObject? = null
) {
    companion object {
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    }
}

data class BudgetPayload(val categoryKey: String) {
    companion object { const val TOTAL_CATEGORY_KEY = "__total__" }
}

object OperationValidator {
    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }
}
