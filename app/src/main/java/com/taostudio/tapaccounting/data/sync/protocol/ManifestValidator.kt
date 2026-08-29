package com.taostudio.tapaccounting.data.sync.protocol

/** Shared validation used before manifest encoding and after decoding. */
object ManifestValidator {
    fun validate(manifest: Manifest): ValidationResult {
        if (manifest.schemaVersion != Manifest.CURRENT_SCHEMA_VERSION) {
            return ValidationResult.Invalid("Unsupported manifest schemaVersion: ${manifest.schemaVersion}")
        }
        if (!Operation.UUID_PATTERN.matches(manifest.sharedBookId)) {
            return ValidationResult.Invalid("sharedBookId is not a valid UUID: ${manifest.sharedBookId}")
        }
        if (manifest.name.isBlank()) return ValidationResult.Invalid("Missing name")
        if (manifest.name.length > Manifest.MAX_NAME_LENGTH) {
            return ValidationResult.Invalid("name too long: ${manifest.name.length}")
        }
        if (manifest.createdAt < 0) return ValidationResult.Invalid("createdAt must be >= 0")
        if (manifest.members.size !in Manifest.MIN_MEMBER_COUNT..Manifest.MAX_MEMBER_COUNT) {
            return ValidationResult.Invalid(
                "Expected ${Manifest.MIN_MEMBER_COUNT}..${Manifest.MAX_MEMBER_COUNT} members, got ${manifest.members.size}"
            )
        }
        if (manifest.members.none { it.joinOrder == 1 }) {
            return ValidationResult.Invalid("Missing creator member with joinOrder 1")
        }

        val memberIds = mutableSetOf<String>()
        val joinOrders = mutableSetOf<Int>()
        manifest.members.forEachIndexed { index, member ->
            if (!Operation.UUID_PATTERN.matches(member.memberId)) {
                return ValidationResult.Invalid(
                    "Member $index memberId is not a valid UUID: ${member.memberId}"
                )
            }
            if (!memberIds.add(member.memberId)) {
                return ValidationResult.Invalid("Duplicate memberId: ${member.memberId}")
            }
            if (member.displayName.length > Manifest.MAX_MEMBER_DISPLAY_NAME_LENGTH) {
                return ValidationResult.Invalid("Member $index displayName too long")
            }
            if (member.joinOrder !in 1..Manifest.MAX_MEMBER_COUNT) {
                return ValidationResult.Invalid(
                    "Member $index joinOrder must be 1..${Manifest.MAX_MEMBER_COUNT}, got ${member.joinOrder}"
                )
            }
            if (!joinOrders.add(member.joinOrder)) {
                return ValidationResult.Invalid("Duplicate joinOrder: ${member.joinOrder}")
            }
            if (member.invitedAt != null && member.invitedAt < 0) {
                return ValidationResult.Invalid("Member $index invitedAt must be >= 0")
            }
            if (member.joinedAt != null && member.joinedAt < 0) {
                return ValidationResult.Invalid("Member $index joinedAt must be >= 0")
            }
            if (member.invitedAt != null && member.joinedAt != null && member.joinedAt < member.invitedAt) {
                return ValidationResult.Invalid("Member $index joinedAt cannot be before invitedAt")
            }
        }
        return ValidationResult.Valid
    }

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()

        val isValid: Boolean get() = this is Valid
    }
}
