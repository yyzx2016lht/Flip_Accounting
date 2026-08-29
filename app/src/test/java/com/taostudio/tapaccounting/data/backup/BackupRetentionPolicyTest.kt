package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class BackupRetentionPolicyTest {
    private data class Item(val id: String, val createdAt: Instant)

    @Test
    fun `keeps newest daily weekly and monthly generations`() {
        val items = (0 until 80).map { day ->
            Item("day-$day", Instant.parse("2026-08-29T12:00:00Z").minusSeconds(day * 86_400L))
        }
        val decision = BackupRetentionPolicy(zoneId = ZoneOffset.UTC).decide(
            items = items,
            createdAt = Item::createdAt,
            stableId = Item::id
        )

        assertTrue(decision.keep.any { it.id == "day-0" })
        assertEquals(items.toSet(), (decision.keep + decision.delete).toSet())
        assertTrue(decision.keep.size >= 7)
        assertTrue(decision.delete.isNotEmpty())
    }

    @Test
    fun `never deletes the final valid backup even with zero quotas`() {
        val only = Item("only", Instant.parse("2026-08-29T12:00:00Z"))
        val decision = BackupRetentionPolicy(
            daily = 0,
            weekly = 0,
            monthly = 0,
            zoneId = ZoneOffset.UTC
        ).decide(listOf(only), Item::createdAt, Item::id)

        assertEquals(listOf(only), decision.keep)
        assertTrue(decision.delete.isEmpty())
    }

    @Test
    fun `keeps only newest backup inside the same bucket`() {
        val newest = Item("newest", Instant.parse("2026-08-29T12:00:00Z"))
        val older = Item("older", Instant.parse("2026-08-29T10:00:00Z"))
        val decision = BackupRetentionPolicy(
            daily = 1,
            weekly = 0,
            monthly = 0,
            zoneId = ZoneOffset.UTC
        ).decide(listOf(older, newest), Item::createdAt, Item::id)

        assertEquals(listOf(newest), decision.keep)
        assertEquals(listOf(older), decision.delete)
    }

    @Test
    fun `keeps configured recent generations even inside the same day`() {
        val items = (0 until 12).map { offset ->
            Item("item-$offset", Instant.parse("2026-08-29T12:00:00Z").minusSeconds(offset * 60L))
        }
        val decision = BackupRetentionPolicy(
            recent = 10,
            daily = 0,
            weekly = 0,
            monthly = 0,
            zoneId = ZoneOffset.UTC
        ).decide(items, Item::createdAt, Item::id)

        assertEquals(items.take(10), decision.keep)
        assertEquals(items.drop(10), decision.delete)
    }

    @Test
    fun `honours each daily weekly and monthly quota`() {
        fun retained(
            items: List<Item>,
            daily: Int,
            weekly: Int,
            monthly: Int
        ): Int = BackupRetentionPolicy(
            daily = daily,
            weekly = weekly,
            monthly = monthly,
            zoneId = ZoneOffset.UTC
        ).decide(items, Item::createdAt, Item::id).keep.size

        val dailyItems = (0 until 10).map { offset ->
            Item("d$offset", Instant.parse("2026-08-29T12:00:00Z").minusSeconds(offset * 86_400L))
        }
        val weeklyItems = (0 until 10).map { offset ->
            Item(
                "w$offset",
                LocalDate.parse("2026-08-24").minusWeeks(offset.toLong())
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
            )
        }
        val monthlyItems = (0 until 10).map { offset ->
            Item(
                "m$offset",
                LocalDate.parse("2026-08-01").minusMonths(offset.toLong())
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
            )
        }

        assertEquals(7, retained(dailyItems, daily = 7, weekly = 0, monthly = 0))
        assertEquals(4, retained(weeklyItems, daily = 0, weekly = 4, monthly = 0))
        assertEquals(6, retained(monthlyItems, daily = 0, weekly = 0, monthly = 6))
    }
}
