package com.taostudio.tapaccounting.chat.query

import com.taostudio.tapaccounting.chat.ai.AiTimeRangeParser
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 本地规则解析器 —— 不依赖 LLM，用关键词匹配识别 10 个高频查询模式。
 *
 * 返回 QueryDraft 或 null（无法识别时返回 null）。
 */
object QueryDraftLocalParser {

    /** 写操作关键词 —— 命中时直接拒绝，不生成草稿 */
    private val WRITE_KEYWORDS = listOf(
        "修改", "改成", "改为", "删除", "删掉", "清空", "覆盖",
        "新增", "记一笔", "记账", "批量修改", "全部改", "全改",
        "撤销所有", "重置", "导入", "恢复", "备份"
    )

    /** 查询触发词 —— 用户用了这些词才认为是查询意图 */
    private val QUERY_TRIGGERS = listOf(
        "花了多少", "支出多少", "消费多少", "赚了多少", "收入多少",
        "花了什么", "消费了什么", "买了什么",
        "最近一笔", "上一笔", "前一笔", "刚刚那笔", "刚才那笔",
        "最近", "有没有", "是否有", "有买过", "有记录",
        "分类", "排行", "排名",
        "多少", "多少钱", "花了", "消费", "支出",
        "账单", "查询", "搜", "搜索"
    )

    /**
     * 尝试将用户输入解析为查询草稿。
     *
     * @param userText 用户输入的原始文本
     * @param context 查询上下文（时间、账本、资产、分类等）
     * @return QueryDraft 或 null（不是查询意图 / 写操作 / 无法解析）
     */
    fun parse(userText: String, context: QueryContext): QueryDraft? {
        val text = userText.trim()
        if (text.isBlank()) return null

        // 1. 写操作检测
        if (looksLikeWrite(text)) return null

        // 2. 查询意图检测
        if (!looksLikeQuery(text)) return null

        // 3. 解析时间范围
        val zoneId = ZoneId.of(context.timezoneId)
        val today = Instant.ofEpochMilli(context.nowMillis).atZone(zoneId).toLocalDate()
        val timeRange = AiTimeRangeParser.parse(text, today, zoneId)?.let {
            QueryTimeRange(
                startMillis = it.startMillis,
                endMillis = it.endMillis,
                rangeKey = normalizeRangeKey(it.phrase),
                label = it.phrase
            )
        }

        // 4. 检测账单类型
        val billType = detectBillType(text)

        // 5. 检测账本范围
        val bookScope = detectBookScope(text)

        // 6. 解析资产
        val resolvedAsset = resolveAsset(text, context)

        // 7. 解析分类
        val resolvedCategory = resolveCategory(text, context)

        // 8. 提取关键词
        val keyword = extractKeyword(text, resolvedAsset?.name, resolvedCategory?.name)

        // 9. 判断查询类型
        val queryType = detectQueryType(text)

        // 10. 特殊处理：最近 N 笔
        val recentCount = if (queryType == QueryType.RECENT_BILLS) {
            extractRecentCount(text)
        } else 1

        // 11. 判断聚合方式
        val aggregation = when (queryType) {
            QueryType.AMOUNT_TOTAL -> QueryAggregation.TOTAL
            QueryType.LATEST_BILL -> QueryAggregation.LATEST
            QueryType.RECENT_BILLS -> QueryAggregation.LIST
            QueryType.EXISTS_KEYWORD -> QueryAggregation.EXISTENCE
            QueryType.TOP_CATEGORIES -> QueryAggregation.BY_CATEGORY
            else -> QueryAggregation.TOTAL
        }

        // 12. 构建草稿
        val draft = QueryDraft(
            queryType = queryType,
            keyword = keyword,
            categoryId = resolvedCategory?.id,
            categoryName = resolvedCategory?.name,
            assetId = resolvedAsset?.id,
            assetName = resolvedAsset?.name,
            bookScope = bookScope,
            bookName = if (bookScope == BookScope.CURRENT) context.currentBookName else null,
            billType = billType,
            timeRange = timeRange,
            aggregation = aggregation,
            recentCount = recentCount,
            sourceText = userText,
            confidence = calculateConfidence(timeRange, keyword, resolvedAsset, resolvedCategory, queryType)
        )

        return draft
    }

    /**
     * 检测是否是对当前草稿的修正指令。
     * 例如："不是苹果，是水果" / "查上个月" / "全部账本" / "只看收入"
     */
    fun detectCorrection(text: String): QueryDraftCorrection? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        // "不是X，是Y" 或 "不是X 是Y" —— 关键词修正
        val keywordCorrection = Regex("不是(.+?)[，,]是(.+)")
            .find(trimmed)
            ?.let { QueryDraftCorrection.UpdateKeyword(it.groupValues[2].trim()) }
        if (keywordCorrection != null) return keywordCorrection

        // 执行指令（优先检测，避免被"查X"模式误匹配）
        if (trimmed == "搜索" || trimmed == "搜索账单" || trimmed.contains("搜索一下")) {
            return QueryDraftCorrection.ExecuteSearch
        }
        if (trimmed == "统计" || trimmed == "统计金额" || trimmed.contains("统计一下")) {
            return QueryDraftCorrection.ExecuteStats
        }

        // 账单类型修正（优先检测，避免被"看X"模式误匹配）
        if (trimmed.contains("只看支出") || trimmed.contains("只看消费")) {
            return QueryDraftCorrection.UpdateBillType(QueryBillType.EXPENSE)
        }
        if (trimmed.contains("只看收入")) {
            return QueryDraftCorrection.UpdateBillType(QueryBillType.INCOME)
        }
        if (trimmed.contains("只看转账")) {
            return QueryDraftCorrection.UpdateBillType(QueryBillType.TRANSFER)
        }
        if (trimmed.contains("全部类型") || trimmed.contains("所有类型")) {
            return QueryDraftCorrection.UpdateBillType(QueryBillType.ANY)
        }

        // 账本范围修正
        if (trimmed.contains("全部账本") || trimmed.contains("所有账本") || trimmed.contains("全账本")) {
            return QueryDraftCorrection.UpdateBookScope(BookScope.ALL)
        }
        if (trimmed.contains("当前账本") || trimmed.contains("本账本")) {
            return QueryDraftCorrection.UpdateBookScope(BookScope.CURRENT)
        }

        // 时间修正（优先于"查X"模式，避免"查上个月"被误识别为关键词修正）
        val timePhrases = listOf(
            "上个月", "上月", "本月", "这个月", "当月",
            "上周", "上星期", "本周", "这周", "这个星期",
            "今年", "本年", "昨天", "今天", "今日"
        )
        for (phrase in timePhrases) {
            if (trimmed.contains(phrase)) {
                return QueryDraftCorrection.UpdateTime(phrase)
            }
        }

        // "换成X" / "改成X" / "改为X" / "查X" / "看X" —— 关键词修正（但不含"删除"等写操作）
        val switchKeyword = Regex("(?:换成|改成|改为|看|查)(.{1,10})$")
            .find(trimmed)
            ?.groupValues?.getOrNull(1)?.trim()
        if (switchKeyword != null && switchKeyword.length <= 10 && !looksLikeWrite(trimmed)) {
            return QueryDraftCorrection.UpdateKeyword(switchKeyword)
        }

        return null
    }

    /** 检测是否是写操作 */
    private fun looksLikeWrite(text: String): Boolean {
        return WRITE_KEYWORDS.any { text.contains(it) }
    }

    /** 检测是否像查询意图 */
    private fun looksLikeQuery(text: String): Boolean {
        return QUERY_TRIGGERS.any { text.contains(it) }
    }

    /** 检测查询类型 */
    private fun detectQueryType(text: String): QueryType {
        // 最近一笔
        val latestPhrases = listOf("最近一笔", "上一笔", "前一笔", "刚刚那笔", "刚才那笔", "最后一笔")
        if (latestPhrases.any { text.contains(it) }) return QueryType.LATEST_BILL

        // 最近 N 笔
        val recentPattern = Regex("最近(\\d+)笔")
        if (recentPattern.containsMatchIn(text)) return QueryType.RECENT_BILLS
        if (text.contains("最近") && text.contains("账单") && !text.contains("多少")) {
            return QueryType.RECENT_BILLS
        }

        // 存在性查询
        if (text.contains("有没有") || text.contains("是否有") || text.contains("有买过")) {
            return QueryType.EXISTS_KEYWORD
        }

        // 分类排行
        if (text.contains("分类") && (text.contains("排行") || text.contains("排名") || text.contains("最多"))) {
            return QueryType.TOP_CATEGORIES
        }

        // 默认：金额统计
        return QueryType.AMOUNT_TOTAL
    }

    /** 检测账单类型 */
    private fun detectBillType(text: String): QueryBillType {
        return when {
            text.contains("退款") -> QueryBillType.REFUND
            text.contains("还款") || text.contains("还信用卡") || text.contains("还卡") -> QueryBillType.REPAYMENT
            text.contains("转账") -> QueryBillType.TRANSFER
            text.contains("收入") || text.contains("赚了") || text.contains("收了") -> QueryBillType.INCOME
            text.contains("支出") || text.contains("花了") || text.contains("消费") || text.contains("买") -> QueryBillType.EXPENSE
            else -> QueryBillType.ANY
        }
    }

    /** 检测账本范围 */
    private fun detectBookScope(text: String): BookScope {
        if (text.contains("全部账本") || text.contains("所有账本") || text.contains("全账本")) {
            return BookScope.ALL
        }
        return BookScope.CURRENT
    }

    /** 解析资产 */
    private fun resolveAsset(text: String, context: QueryContext): QueryAssetOption? {
        val candidates = context.assets.filter { asset ->
            val name = normalizeToken(asset.name)
            val input = normalizeToken(text)
            input.contains(name) || name.contains(input)
        }.sortedBy { it.name.length }
        return if (candidates.size == 1) candidates.first() else null
    }

    /** 解析分类 */
    private fun resolveCategory(text: String, context: QueryContext): QueryCategoryOption? {
        val candidates = context.categories.filter { category ->
            val name = normalizeToken(category.name)
            val input = normalizeToken(text)
            input.contains(name) || name.contains(input)
        }.sortedBy { it.name.length }
        return if (candidates.size == 1) candidates.first() else null
    }

    /** 提取关键词 */
    private fun extractKeyword(text: String, assetName: String?, categoryName: String?): String? {
        var candidate = text
        assetName?.let { candidate = candidate.replace(it, "") }
        categoryName?.let { candidate = candidate.replace(it, "") }

        // 模式1："买X" / "查X" / "看X" / "有没有买过X" —— 提取触发词后面的内容
        val triggerWords = listOf("有没有买过", "有没有", "是否有", "买", "查", "看")
        for (trigger in triggerWords) {
            val idx = candidate.indexOf(trigger)
            if (idx >= 0) {
                var after = candidate.substring(idx + trigger.length).trim()
                // 移除常见的后缀词
                after = after
                    .replace(Regex("(花了|支出|消费|多少钱|多少|一下|情况|记录|吗|么|呢|没有|过)+$"), "")
                    .trim()
                if (after.isNotBlank() && after.length <= 18) return after
            }
        }

        // 模式2："X花了多少"（没有触发词的情况）
        val amountPattern = Regex("(?:今天|昨天|上周|本周|本月|上月|上个月|这个月|今年)?([^，。？?]{1,10})(?:花了多少|支出多少|消费多少)")
        val amountKeyword = amountPattern.find(candidate)?.groupValues?.getOrNull(1).orEmpty()
            .replace("的", "")
            .replace("我", "")
            .replace("了", "")
            .trim()
        if (amountKeyword.isNotBlank()) return amountKeyword

        return null
    }

    /** 提取最近 N 笔的数量 */
    private fun extractRecentCount(text: String): Int {
        val match = Regex("最近(\\d+)笔").find(text)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 5
    }

    /** 计算置信度 */
    private fun calculateConfidence(
        timeRange: QueryTimeRange?,
        keyword: String?,
        asset: QueryAssetOption?,
        category: QueryCategoryOption?,
        queryType: QueryType
    ): Double {
        var confidence = 0.6
        if (timeRange != null) confidence += 0.1
        if (keyword != null) confidence += 0.1
        if (asset != null) confidence += 0.05
        if (category != null) confidence += 0.05
        if (queryType == QueryType.LATEST_BILL || queryType == QueryType.EXISTS_KEYWORD) confidence += 0.1
        return confidence.coerceIn(0.0, 1.0)
    }

    private fun normalizeRangeKey(phrase: String): String? = when (phrase) {
        "今天", "今日" -> "TODAY"
        "昨天" -> "YESTERDAY"
        "本周", "这周", "这个星期" -> "THIS_WEEK"
        "上周", "上星期" -> "LAST_WEEK"
        "本月", "这个月", "当月" -> "THIS_MONTH"
        "上个月", "上月" -> "LAST_MONTH"
        "今年", "本年" -> "THIS_YEAR"
        else -> null
    }

    private fun normalizeToken(value: String): String {
        return value.lowercase(Locale.getDefault())
            .replace(Regex("\\s+"), "")
            .replace("卡", "")
    }
}

/** 草稿修正指令 */
sealed class QueryDraftCorrection {
    /** 更新关键词 */
    data class UpdateKeyword(val newKeyword: String) : QueryDraftCorrection()
    /** 更新时间（通过自然语言短语） */
    data class UpdateTime(val phrase: String) : QueryDraftCorrection()
    /** 更新账本范围 */
    data class UpdateBookScope(val scope: BookScope) : QueryDraftCorrection()
    /** 更新账单类型 */
    data class UpdateBillType(val billType: QueryBillType) : QueryDraftCorrection()
    /** 执行统计 */
    data object ExecuteStats : QueryDraftCorrection()
    /** 执行搜索 */
    data object ExecuteSearch : QueryDraftCorrection()
}
