package com.taostudio.tapaccounting.data.backup

/** Stable module ids used by every manual/automatic and local/cloud backup entry point. */
object BackupModuleId {
    const val ASSETS = "assets"
    const val CATEGORIES = "categories"
    const val BILLS = "bills"
    const val DELETED_BILLS = "deleted_bills"
    const val INVESTMENT_LOTS = "investment_lots"
    const val INVESTMENT_DRAFTS = "investment_lot_drafts"
    const val RULES = "rules"
    const val CHAT_MESSAGES = "chat_messages"
    const val BUDGETS = "budgets"
    const val RECURRING_PATTERNS = "recurring_patterns"
    const val BOOKS = "books"
    const val SHARED_LEDGERS = "shared_ledgers"
    const val SHARED_MEMBERS = "shared_members"
    const val SYNC_QUEUE = "sync_queue"
    const val SYNC_OPERATIONS = "sync_operations"
    const val SHARED_SECRETS = "shared_recovery_secrets"

    val coreFinancial = linkedSetOf(
        ASSETS,
        CATEGORIES,
        BILLS,
        DELETED_BILLS,
        INVESTMENT_LOTS,
        RULES,
        BUDGETS,
        RECURRING_PATTERNS,
        BOOKS,
        SHARED_LEDGERS,
        SHARED_MEMBERS,
        SYNC_QUEUE,
        SYNC_OPERATIONS
    )
}

data class BackupContentPolicy(
    val dataModules: Set<String>,
    val settingsModules: Set<String>,
    val includeBanners: Boolean,
    val includeChatMedia: Boolean
)
