package com.taostudio.tapaccounting.data.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedSyncGateTest {
    @Test
    fun `serializes sync work from different callers`() = runBlocking {
        val gate = SharedSyncGate()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        (1..3).map {
            async {
                gate.run {
                    val current = active.incrementAndGet()
                    maxActive.updateAndGet { previous -> maxOf(previous, current) }
                    delay(20)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maxActive.get())
    }
}
