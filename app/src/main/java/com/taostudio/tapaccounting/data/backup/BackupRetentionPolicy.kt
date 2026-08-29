package com.taostudio.tapaccounting.data.backup

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields

data class BackupRetentionDecision<T>(
    val keep: List<T>,
    val delete: List<T>
)

/**
 * Grandfather-father-son history policy. It retains a configurable number of recent backups,
 * plus the newest backup in each selected daily/weekly/monthly bucket, and always retains the newest valid
 * backup, even when every configured quota is zero. Callers should supply only
 * backups they have already validated; "last valid backup" is defined by that
 * input set.
 */
class BackupRetentionPolicy(
    private val recent: Int = 0,
    private val daily: Int = 7,
    private val weekly: Int = 4,
    private val monthly: Int = 6,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    init {
        require(recent >= 0) { "recent must not be negative" }
        require(daily >= 0) { "daily must not be negative" }
        require(weekly >= 0) { "weekly must not be negative" }
        require(monthly >= 0) { "monthly must not be negative" }
    }

    fun <T> decide(
        items: Collection<T>,
        createdAt: (T) -> Instant,
        stableId: (T) -> String
    ): BackupRetentionDecision<T> {
        if (items.isEmpty()) return BackupRetentionDecision(emptyList(), emptyList())

        val sorted = items.sortedWith(
            compareByDescending<T> { createdAt(it) }
                .thenByDescending { stableId(it) }
        )
        val retainedIds = linkedSetOf(stableId(sorted.first()))
        sorted.take(recent).mapTo(retainedIds, stableId)

        retainNewestPerBucket(sorted, daily, stableId) {
            createdAt(it).atZone(zoneId).toLocalDate().toString()
        }.forEach(retainedIds::add)

        val weekFields = WeekFields.ISO
        retainNewestPerBucket(sorted, weekly, stableId) {
            val date = createdAt(it).atZone(zoneId).toLocalDate()
            "${date.get(weekFields.weekBasedYear())}-${date.get(weekFields.weekOfWeekBasedYear())}"
        }.forEach(retainedIds::add)

        retainNewestPerBucket(sorted, monthly, stableId) {
            YearMonth.from(createdAt(it).atZone(zoneId)).toString()
        }.forEach(retainedIds::add)

        val keep = sorted.filter { stableId(it) in retainedIds }
        val delete = sorted.filterNot { stableId(it) in retainedIds }
        return BackupRetentionDecision(keep = keep, delete = delete)
    }

    companion object {
        const val RECENT_LITE = 10
        const val RECENT_FULL = 3
        const val RECENT_CUSTOM = 3

        fun forMode(mode: String): BackupRetentionPolicy = BackupRetentionPolicy(
            recent = when (mode.lowercase()) {
                "lite" -> RECENT_LITE
                "full" -> RECENT_FULL
                else -> RECENT_CUSTOM
            }
        )
    }

    private fun <T> retainNewestPerBucket(
        sorted: List<T>,
        limit: Int,
        stableId: (T) -> String,
        bucket: (T) -> String
    ): List<String> {
        if (limit == 0) return emptyList()
        val selected = linkedMapOf<String, String>()
        for (item in sorted) {
            if (selected.size >= limit) break
            selected.putIfAbsent(bucket(item), stableId(item))
        }
        return selected.values.toList()
    }
}
