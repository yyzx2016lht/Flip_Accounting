package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedRecoveryReadinessTest {
    @Test
    fun readiness_isNotPresent_whenThereAreNoSharedLedgers() {
        assertEquals(
            SharedRecoveryReadiness.NOT_PRESENT,
            assessSharedRecoveryReadiness(SharedRestoreData(), emptySet())
        )
    }

    @Test
    fun readiness_isReady_onlyWhenEveryLedgerHasItsLocalMemberAndCredential() {
        val ledger = ledger()
        val complete = SharedRestoreData(
            ledgers = listOf(ledger),
            members = listOf(
                SharedMemberBackup(
                    ledgerUuid = LEDGER_ID,
                    memberId = MEMBER_ID,
                    displayName = "我",
                    joinOrder = 1,
                    isLocal = true
                )
            )
        )

        assertEquals(
            SharedRecoveryReadiness.READY,
            assessSharedRecoveryReadiness(complete, setOf(LEDGER_ID))
        )
        assertEquals(
            SharedRecoveryReadiness.INCOMPLETE,
            assessSharedRecoveryReadiness(complete, emptySet())
        )
        assertEquals(
            SharedRecoveryReadiness.INCOMPLETE,
            assessSharedRecoveryReadiness(complete.copy(members = emptyList()), setOf(LEDGER_ID))
        )
        assertEquals(
            SharedRecoveryReadiness.INCOMPLETE,
            assessSharedRecoveryReadiness(
                complete.copy(members = complete.members.map { it.copy(isLocal = false) }),
                setOf(LEDGER_ID)
            )
        )
    }

    @Test
    fun manifestReadiness_usesTheSealedArchiveInventory() {
        val ready = manifest(
            module(BackupModuleId.SHARED_LEDGERS, 2),
            module(BackupModuleId.SHARED_MEMBERS, 3),
            module(BackupModuleId.SHARED_SECRETS, 2)
        )
        val incomplete = ready.copy(
            modules = ready.modules.map {
                if (it.name == BackupModuleId.SHARED_SECRETS) it.copy(itemCount = 1) else it
            }
        )

        assertEquals(SharedRecoveryReadiness.READY, ready.sharedRecoveryReadiness())
        assertEquals(SharedRecoveryReadiness.INCOMPLETE, incomplete.sharedRecoveryReadiness())
        assertEquals(
            SharedRecoveryReadiness.NOT_PRESENT,
            manifest(module(BackupModuleId.SHARED_LEDGERS, 0)).sharedRecoveryReadiness()
        )
    }

    private fun ledger() = SharedLedgerBackup(
        uuid = LEDGER_ID,
        bookName = "家庭账本",
        name = "家庭共享",
        webdavUrl = "https://dav.jianguoyun.com/dav/",
        webdavUser = "member@example.com",
        remotePath = "TapAccounting/shared/$LEDGER_ID",
        localMemberId = MEMBER_ID,
        createdAt = 1L
    )

    private fun module(name: String, count: Long) = BackupV2Module(
        name = name,
        itemCount = count,
        byteSize = 0,
        sha256 = "0".repeat(64)
    )

    private fun manifest(vararg modules: BackupV2Module) = BackupV2Manifest(
        formatVersion = BackupV2Manifest.FORMAT_VERSION,
        appVersion = "test",
        dbSchemaVersion = 1,
        backupId = "00000000-0000-0000-0000-000000000001",
        createdAt = 1,
        moduleCount = modules.size,
        modules = modules.toList(),
        payloadSize = 0,
        payloadSha256 = "0".repeat(64)
    )

    companion object {
        private const val LEDGER_ID = "00000000-0000-0000-0000-000000000010"
        private const val MEMBER_ID = "00000000-0000-0000-0000-000000000011"
    }
}
