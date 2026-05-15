package com.taostudio.tapaccounting.chat.ai

import java.time.LocalDate
import java.time.ZoneId

data class AiTimeRange(
    val phrase: String,
    val startMillis: Long,
    val endMillis: Long
)

object AiTimeRangeParser {
    fun parse(text: String, today: LocalDate = LocalDate.now(), zoneId: ZoneId = ZoneId.systemDefault()): AiTimeRange? {
        val normalized = text.replace("\\s+".toRegex(), "")
        val range = when {
            normalized.contains("上个月") || normalized.contains("上月") -> {
                val start = today.withDayOfMonth(1).minusMonths(1)
                start to start.plusMonths(1)
            }
            normalized.contains("本月") || normalized.contains("这个月") || normalized.contains("当月") -> {
                val start = today.withDayOfMonth(1)
                start to start.plusMonths(1)
            }
            normalized.contains("上周") || normalized.contains("上星期") -> {
                val start = today.minusWeeks(1).minusDays((today.dayOfWeek.value - 1).toLong())
                start to start.plusWeeks(1)
            }
            normalized.contains("本周") || normalized.contains("这周") || normalized.contains("这个星期") -> {
                val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
                start to start.plusWeeks(1)
            }
            normalized.contains("今年") || normalized.contains("本年") -> {
                val start = today.withDayOfYear(1)
                start to start.plusYears(1)
            }
            normalized.contains("昨天") -> {
                val start = today.minusDays(1)
                start to start.plusDays(1)
            }
            normalized.contains("今天") || normalized.contains("今日") -> {
                today to today.plusDays(1)
            }
            else -> return null
        }

        val startMillis = range.first.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = range.second.atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
        return AiTimeRange(
            phrase = detectPhrase(normalized),
            startMillis = startMillis,
            endMillis = endMillis
        )
    }

    private fun detectPhrase(text: String): String = when {
        text.contains("上个月") -> "上个月"
        text.contains("上月") -> "上月"
        text.contains("本月") -> "本月"
        text.contains("这个月") -> "这个月"
        text.contains("当月") -> "当月"
        text.contains("上周") -> "上周"
        text.contains("上星期") -> "上星期"
        text.contains("本周") -> "本周"
        text.contains("这周") -> "这周"
        text.contains("这个星期") -> "这个星期"
        text.contains("今年") -> "今年"
        text.contains("本年") -> "本年"
        text.contains("昨天") -> "昨天"
        text.contains("今天") -> "今天"
        text.contains("今日") -> "今日"
        else -> ""
    }
}

