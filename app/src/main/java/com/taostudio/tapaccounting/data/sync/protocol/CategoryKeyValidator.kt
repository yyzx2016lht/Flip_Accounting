package com.taostudio.tapaccounting.data.sync.protocol

/**
 * Shared categoryKey rules for Bill and Budget operations.
 *
 * Canonical identity is a UUID. The only exception is
 * [BudgetPayload.TOTAL_CATEGORY_KEY] (`__total__`) for the total budget slot.
 * Name-style keys such as `food` are rejected.
 */
object CategoryKeyValidator {

    fun isValid(categoryKey: String): Boolean {
        if (categoryKey == BudgetPayload.TOTAL_CATEGORY_KEY) return true
        return Operation.UUID_PATTERN.matches(categoryKey)
    }

    /**
     * @return null when valid; otherwise an [OperationValidator.ValidationResult.Invalid].
     */
    fun validate(categoryKey: String, fieldName: String = "categoryKey"): OperationValidator.ValidationResult? {
        if (categoryKey.isBlank()) {
            return OperationValidator.ValidationResult.Invalid("$fieldName is blank")
        }
        if (!isValid(categoryKey)) {
            return OperationValidator.ValidationResult.Invalid(
                "$fieldName must be a UUID or '${BudgetPayload.TOTAL_CATEGORY_KEY}', got: $categoryKey"
            )
        }
        return null
    }
}
