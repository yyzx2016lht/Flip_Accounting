package com.taostudio.tapaccounting.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteOperationPlannerTest {
    @Test
    fun `downloads only unseen legacy operations and unprocessed bundles`() {
        val files = listOf(
            "2026-08/device-a/known.json",
            "2026-08/device-a/new.json",
            "batches/2026-08/device-a/done.json.gz",
            "batches/2026-08/device-a/new-batch.json.gz"
        )

        assertEquals(
            listOf(
                "2026-08/device-a/new.json",
                "batches/2026-08/device-a/new-batch.json.gz"
            ),
            RemoteOperationPlanner.pendingFiles(
                remoteFiles = files,
                knownOperationIds = setOf("known"),
                processedBundles = setOf("batches/2026-08/device-a/done.json.gz")
            )
        )
    }

    @Test
    fun `deduplicates repeated directory listings`() {
        assertEquals(
            listOf("2026-08/device-a/new.json"),
            RemoteOperationPlanner.pendingFiles(
                remoteFiles = listOf("2026-08/device-a/new.json", "2026-08/device-a/new.json"),
                knownOperationIds = emptySet(),
                processedBundles = emptySet()
            )
        )
    }
}
