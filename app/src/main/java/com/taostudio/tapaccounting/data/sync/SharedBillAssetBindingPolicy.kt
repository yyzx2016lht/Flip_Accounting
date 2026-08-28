package com.taostudio.tapaccounting.data.sync

import com.taostudio.tapaccounting.data.local.entity.Bill

/** Keeps asset data private without erasing the local owner's existing binding. */
internal object SharedBillAssetBindingPolicy {
    fun merge(existing: Bill?, incoming: Bill, ownedByLocalMember: Boolean): Bill {
        return if (ownedByLocalMember && existing != null) {
            incoming.copy(
                accountId = existing.accountId,
                toAccountId = existing.toAccountId,
                accountName = existing.accountName,
                toAccountName = existing.toAccountName,
                accountBalanceAfter = existing.accountBalanceAfter,
                toAccountBalanceAfter = existing.toAccountBalanceAfter
            )
        } else {
            incoming.copy(
                accountId = null,
                toAccountId = null,
                accountName = "",
                toAccountName = "",
                accountBalanceAfter = null,
                toAccountBalanceAfter = null
            )
        }
    }
}
