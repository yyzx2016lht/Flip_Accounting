package com.taostudio.tapaccounting.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedSyncPolicyTest {
    @Test
    fun `background sync skips a recently completed ledger with no pending upload`() {
        assertFalse(
            SharedSyncPolicy.shouldRunBackgroundSync(
                pendingUploadCount = 0,
                lastSyncTime = 950_000L,
                now = 1_000_000L
            )
        )
    }

    @Test
    fun `pending upload bypasses background quiet period`() {
        assertTrue(
            SharedSyncPolicy.backgroundMode(
                pendingUploadCount = 1,
                lastSyncTime = 999_999L,
                now = 1_000_000L
            ) == SharedSyncPolicy.BackgroundMode.UPLOAD_ONLY
        )
    }

    @Test
    fun `stale ledger is polled for remote changes`() {
        assertTrue(
            SharedSyncPolicy.backgroundMode(
                pendingUploadCount = 0,
                lastSyncTime = 0L,
                now = SharedSyncPolicy.BACKGROUND_QUIET_PERIOD_MS
            ) == SharedSyncPolicy.BackgroundMode.FULL
        )
    }

    @Test
    fun `pending upload also performs full poll when ledger is stale`() {
        assertTrue(
            SharedSyncPolicy.backgroundMode(
                pendingUploadCount = 2,
                lastSyncTime = 1L,
                now = SharedSyncPolicy.BACKGROUND_QUIET_PERIOD_MS + 1L
            ) == SharedSyncPolicy.BackgroundMode.FULL
        )
    }

    @Test
    fun `explicit recipient refresh bypasses quiet period`() {
        assertTrue(
            SharedSyncPolicy.backgroundMode(
                pendingUploadCount = 0,
                lastSyncTime = 999_999L,
                now = 1_000_000L,
                forceFull = true
            ) == SharedSyncPolicy.BackgroundMode.FULL
        )
    }

    @Test
    fun `ledger with a previous sync error bypasses quiet period`() {
        assertTrue(
            SharedSyncPolicy.backgroundMode(
                pendingUploadCount = 0,
                lastSyncTime = 999_999L,
                now = 1_000_000L,
                hasLastError = true
            ) == SharedSyncPolicy.BackgroundMode.FULL
        )
    }

    @Test
    fun `503 uses a short cooldown instead of fifteen minutes`() {
        assertTrue(
            SharedSyncPolicy.cooldownMillis(503, retryAfterMillis = null) ==
                SharedSyncPolicy.SERVER_BUSY_COOLDOWN_MS
        )
    }

    @Test
    fun `server retry-after takes precedence but is capped`() {
        assertTrue(SharedSyncPolicy.cooldownMillis(429, retryAfterMillis = 90_000L) == 90_000L)
        assertTrue(SharedSyncPolicy.cooldownMillis(429, retryAfterMillis = 30 * 60_000L) == 5 * 60_000L)
    }

    @Test
    fun `worker retries a failed remote pull even with no pending uploads`() {
        assertTrue(
            SharedSyncPolicy.shouldRetryWorker(
                failedLedgerCount = 1,
                pendingUploadCount = 0
            )
        )
    }

    @Test
    fun `worker completes only when all ledgers succeeded and upload queue is empty`() {
        assertFalse(
            SharedSyncPolicy.shouldRetryWorker(
                failedLedgerCount = 0,
                pendingUploadCount = 0
            )
        )
        assertTrue(
            SharedSyncPolicy.shouldRetryWorker(
                failedLedgerCount = 0,
                pendingUploadCount = 1
            )
        )
    }
}
