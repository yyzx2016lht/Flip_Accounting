package com.taostudio.tapaccounting.chat.query

import org.json.JSONObject
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.chat.ai.AiTimeRangeParser
import com.taostudio.tapaccounting.cleanJsonString
import com.taostudio.tapaccounting.extractFirstJsonObjectText
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class QueryPlanner(
    private val modelPlanProvider: (suspend (String, QueryContext) -> String?)? = null
) {
    suspend fun plan(userText: String, context: QueryContext): QueryAction {
        if (looksLikeWrite(userText)) {
            return QueryAction(QueryIntent.UNSUPPORTED)
        }

        val localFallback = planByLocalFallback(userText, context)
        if (shouldBypassModel(userText, localFallback)) {
            return validateOrClarify(localFallback, userText, context)
        }
        val modelAction = runCatching {
            modelPlanProvider?.invoke(userText, context)?.let { parseModelAction(it, context, userText) }
        }.getOrNull()
        if (modelAction != null) {
            val validatedModel = validateOrClarify(modelAction, userText, context)
            val preferLocal = shouldPreferLocal(localFallback, validatedModel)
            return if (preferLocal) validateOrClarify(localFallback, userText, context) else validatedModel
        }
        return validateOrClarify(localFallback, userText, context)
    }

    private fun parseModelAction(rawContent: String, context: QueryContext, userText: String): QueryAction? {
        val cleaned = extractFirstJsonObjectText(cleanJsonString(rawContent)) ?: cleanJsonString(rawContent)
        val root = runCatching { JSONObject(cleaned) }.getOrNull() ?: return null
        val intent = root.optString("intent", "UNSUPPORTED")
            .trim()
            .uppercase(Locale.ROOT)
            .let { raw -> runCatching { QueryIntent.valueOf(raw) }.getOrDefault(QueryIntent.UNSUPPORTED) }
        val slotsObj = root.optJSONObject("slots")
        val rangeObj = slotsObj?.optJSONObject("timeRange")
        val timeRange = QueryTimeRange(
            startMillis = rangeObj?.optLong("startMillis").takeIf { it != null && it >= 0L },
            endMillis = rangeObj?.optLong("endMillis").takeIf { it != null && it >= 0L },
            rangeKey = slotsObj?.optString("rangeKey", "").orEmpty().trim().ifBlank { null },
            label = rangeObj?.optString("label", "").orEmpty().trim().ifBlank { null }
        )
        val accountName = sanitizeAccountName(
            slotsObj?.optString("accountName", "").orEmpty().trim().ifBlank { null }
        )
        val categoryName = slotsObj?.optString("categoryName", "").orEmpty().trim().ifBlank { null }
        val assetIdRaw = slotsObj?.optLong("assetId")?.takeIf { it > 0L }
        val categoryIdRaw = slotsObj?.optLong("categoryId")?.takeIf { it > 0L }
        val bookName = slotsObj?.optString("bookName", "").orEmpty().trim().ifBlank { null }
        val keyword = sanitizeKeyword(
            slotsObj?.optString("keyword", "").orEmpty().trim().ifBlank { null }
        )
        val billType = slotsObj?.optString("billType", "").orEmpty().trim()
            .uppercase(Locale.ROOT)
            .let { runCatching { QueryBillType.valueOf(it) }.getOrDefault(QueryBillType.ANY) }
        val aggregation = slotsObj?.optString("aggregation", "").orEmpty().trim()
            .uppercase(Locale.ROOT)
            .let { runCatching { QueryAggregation.valueOf(it) }.getOrDefault(QueryAggregation.TOTAL) }
        val shouldNavigate = slotsObj?.optBoolean("shouldNavigate", false) ?: false
        val confidence = slotsObj?.optDouble("confidence", root.optDouble("confidence", 0.0)) ?: 0.0
        val clarifyQuestion = slotsObj?.optString("clarifyQuestion", "").orEmpty().trim().ifBlank { null }
        val resolvedAsset = assetIdRaw?.let { id -> context.assets.firstOrNull { it.id == id } }
            ?: resolveAsset(accountName, userText, context)
        val resolvedCategory = categoryIdRaw?.let { id -> context.categories.firstOrNull { it.id == id } }
            ?: resolveCategory(categoryName, userText, context)
        val resolvedBook = resolveBook(bookName, context)
        return QueryAction(
            intent = intent,
            slots = QuerySlots(
                timeRange = resolveTimeRange(timeRange, userText, context),
                accountName = resolvedAsset?.name ?: accountName,
                assetId = resolvedAsset?.id ?: assetIdRaw,
                categoryName = resolvedCategory?.name ?: categoryName,
                categoryId = resolvedCategory?.id ?: categoryIdRaw,
                keyword = keyword,
                billType = billType,
                aggregation = aggregation,
                bookName = resolvedBook,
                shouldNavigate = shouldNavigate,
                confidence = confidence.coerceIn(0.0, 1.0),
                clarifyQuestion = clarifyQuestion
            )
        )
    }

    private fun planByLocalFallback(userText: String, context: QueryContext): QueryAction {
        val text = userText.trim()
        val normalized = text.lowercase(Locale.getDefault())
        val hasOpenIntent = listOf("打开", "跳转", "去", "进入").any { text.contains(it) }
        val asksLatest = listOf("最近一笔", "上一笔", "前一笔", "刚刚那笔", "刚才那笔").any { text.contains(it) }
        val asksExistence = text.contains("有") && (text.contains("吗") || text.contains("没有") || text.contains("是否"))
        val asksCategoryStats = listOf("分类", "花了多少", "消费多少", "支出多少").any { text.contains(it) }
        val resolvedAsset = resolveAsset(null, text, context)
        val resolvedCategory = resolveCategory(null, text, context)
        val resolvedBook = resolveBook(null, context)
        val range = resolveTimeRange(null, text, context)
        val extractedKeyword = extractKeyword(text, resolvedAsset?.name, resolvedCategory?.name)
        val billType = detectBillType(text)

        if (hasOpenIntent && text.contains("统计")) {
            return QueryAction(
                intent = if (resolvedAsset != null) QueryIntent.OPEN_ASSET_STATS_PAGE else QueryIntent.OPEN_STATS_PAGE,
                slots = QuerySlots(
                    timeRange = range,
                    accountName = resolvedAsset?.name,
                    assetId = resolvedAsset?.id,
                    billType = billType,
                    aggregation = QueryAggregation.TOTAL,
                    shouldNavigate = true,
                    bookName = resolvedBook,
                    confidence = 0.8
                )
            )
        }

        if (asksLatest) {
            return QueryAction(
                intent = QueryIntent.QUERY_BILLS,
                slots = QuerySlots(
                    aggregation = QueryAggregation.LATEST,
                    billType = billType,
                    accountName = resolvedAsset?.name,
                    assetId = resolvedAsset?.id,
                    categoryName = resolvedCategory?.name,
                    categoryId = resolvedCategory?.id,
                    bookName = resolvedBook,
                    confidence = 0.84
                )
            )
        }

        val existenceByKeyword = extractedKeyword != null &&
            (text.contains("吗") || text.contains("有没有") || text.contains("是否"))
        val queryIntent = when {
            asksExistence || existenceByKeyword -> QueryIntent.QUERY_EXISTENCE
            resolvedAsset != null -> QueryIntent.QUERY_ASSET_STATS
            resolvedCategory != null || asksCategoryStats -> QueryIntent.QUERY_CATEGORY_STATS
            else -> QueryIntent.QUERY_BILLS
        }
        val aggregation = when (queryIntent) {
            QueryIntent.QUERY_EXISTENCE -> QueryAggregation.EXISTENCE
            QueryIntent.QUERY_CATEGORY_STATS -> QueryAggregation.BY_CATEGORY
            QueryIntent.QUERY_ASSET_STATS -> QueryAggregation.BY_CATEGORY
            else -> QueryAggregation.TOTAL
        }
        val keywordForSlots = when {
            queryIntent == QueryIntent.QUERY_EXISTENCE -> extractedKeyword
            resolvedAsset == null && resolvedCategory == null && extractedKeyword != null -> extractedKeyword
            else -> null
        }
        val shouldNavigate = resolvedAsset != null || (hasOpenIntent && text.contains("统计"))
        return QueryAction(
            intent = queryIntent,
            slots = QuerySlots(
                timeRange = range,
                accountName = resolvedAsset?.name,
                assetId = resolvedAsset?.id,
                categoryName = resolvedCategory?.name,
                categoryId = resolvedCategory?.id,
                keyword = keywordForSlots,
                billType = billType,
                aggregation = aggregation,
                bookName = resolvedBook,
                shouldNavigate = shouldNavigate,
                confidence = 0.72
            )
        )
    }

    private fun validateOrClarify(action: QueryAction, userText: String, context: QueryContext): QueryAction {
        if (action.intent == QueryIntent.CLARIFY || action.intent == QueryIntent.UNSUPPORTED) return action
        if (resolveAsset(null, userText, context) == null) {
            val candidates = findAssetCandidates(userText, context)
            if (candidates.size > 1) {
                val names = candidates.take(3).joinToString("、") { it.name }
                return QueryAction(
                    QueryIntent.CLARIFY,
                    action.slots.copy(
                        clarifyQuestion = "我找到多个可能的资产：$names。你想查哪一个？"
                    )
                )
            }
        }
        if (action.slots.aggregation != QueryAggregation.LATEST && !action.slots.timeRange.isUsableRange()) {
            return QueryAction(
                QueryIntent.CLARIFY,
                action.slots.copy(
                    clarifyQuestion = "你想查哪个时间范围？例如“本月”“上周”或“昨天”。"
                )
            )
        }
        return action
    }

    private fun resolveTimeRange(
        preferred: QueryTimeRange?,
        userText: String,
        context: QueryContext
    ): QueryTimeRange? {
        if (preferred != null && preferred.isValid()) return preferred
        val zoneId = ZoneId.of(context.timezoneId)
        val today = Instant.ofEpochMilli(context.nowMillis).atZone(zoneId).toLocalDate()
        val parsed = AiTimeRangeParser.parse(userText, today, zoneId)
        return parsed?.let {
            QueryTimeRange(
                startMillis = it.startMillis,
                endMillis = it.endMillis,
                rangeKey = normalizeRangeKey(it.phrase),
                label = it.phrase
            )
        }
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

    private fun resolveAsset(
        preferredName: String?,
        userText: String,
        context: QueryContext
    ): QueryAssetOption? {
        preferredName?.let { exact ->
            findAssetCandidates(exact, context).firstOrNull()?.let { return it }
        }
        val candidates = findAssetCandidates(userText, context)
        return if (candidates.size == 1) candidates.first() else null
    }

    private fun findAssetCandidates(input: String, context: QueryContext): List<QueryAssetOption> {
        val normalizedInput = normalizeToken(input)
        if (normalizedInput.isBlank()) return emptyList()
        return context.assets.filter { asset ->
            val name = normalizeToken(asset.name)
            normalizedInput.contains(name) || name.contains(normalizedInput) ||
                sharedKeyword(name, normalizedInput)
        }.sortedBy { it.name.length }
    }

    private fun resolveCategory(
        preferredName: String?,
        userText: String,
        context: QueryContext
    ): QueryCategoryOption? {
        preferredName?.let { exact ->
            findCategoryCandidates(exact, context).firstOrNull()?.let { return it }
        }
        val candidates = findCategoryCandidates(userText, context)
        return if (candidates.size == 1) candidates.first() else null
    }

    private fun findCategoryCandidates(input: String, context: QueryContext): List<QueryCategoryOption> {
        val normalizedInput = normalizeToken(input)
        if (normalizedInput.isBlank()) return emptyList()
        return context.categories.filter { category ->
            val name = normalizeToken(category.name)
            normalizedInput.contains(name) || name.contains(normalizedInput)
        }.sortedBy { it.name.length }
    }

    private fun resolveBook(preferredBook: String?, context: QueryContext): String {
        preferredBook?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            val normalized = BookAccountManager.normalizeBookName(raw)
            if (context.availableBooks.contains(normalized)) return normalized
        }
        return context.currentBookName
    }

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

    private fun extractKeyword(text: String, assetName: String?, categoryName: String?): String? {
        var candidate = text
        assetName?.let { candidate = candidate.replace(it, "") }
        categoryName?.let { candidate = candidate.replace(it, "") }
        val regex = Regex("(买|查|看|有没有|有|是否有)([^，。？?]{1,18})(吗|么|呢|没有|记录|消费|支出)?")
        val matched = regex.find(candidate)?.groupValues?.getOrNull(2)?.trim().orEmpty()
        if (matched.isNotBlank()) {
            return matched
                .replace("一下", "")
                .replace("情况", "")
                .replace("支出", "")
                .replace("消费", "")
                .replace(Regex("[吗么呢]+$"), "")
                .trim()
                .ifBlank { null }
        }
        val amountQueryPattern = Regex("(今天|昨天|上周|本周|本月|上月|上个月|这个月|今年)?([^，。？?]{1,10})(花了多少|支出多少|消费多少)")
        val amountKeyword = amountQueryPattern.find(candidate)?.groupValues?.getOrNull(2).orEmpty()
            .replace("的", "")
            .replace("我", "")
            .replace("了", "")
            .trim()
        if (amountKeyword.isNotBlank()) return amountKeyword
        return null
    }

    private fun looksLikeWrite(text: String): Boolean {
        return listOf("修改", "改成", "改为", "删除", "删掉", "清空", "覆盖",
            "新增", "记一笔", "记账", "批量修改", "全部改", "全改", "撤销所有", "重置")
            .any { text.contains(it) }
    }

    private fun normalizeToken(value: String): String {
        return value.lowercase(Locale.getDefault())
            .replace(Regex("\\s+"), "")
            .replace("卡", "")
    }

    private fun sharedKeyword(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return a.length >= 3 && b.contains(a.take(3))
    }

    private fun sanitizeKeyword(keyword: String?): String? {
        if (keyword.isNullOrBlank()) return null
        return keyword
            .replace("支出", "")
            .replace("消费", "")
            .replace("情况", "")
            .replace(Regex("[吗么呢?？]+$"), "")
            .trim()
            .ifBlank { null }
    }

    private fun sanitizeAccountName(accountName: String?): String? {
        if (accountName.isNullOrBlank()) return null
        return accountName
            .replace("支出", "")
            .replace("账单", "")
            .replace("情况", "")
            .replace("统计", "")
            .trim()
            .ifBlank { null }
    }

    private fun shouldPreferLocal(localAction: QueryAction, modelAction: QueryAction): Boolean {
        if (modelAction.intent == QueryIntent.UNSUPPORTED && localAction.intent != QueryIntent.UNSUPPORTED) return true
        if (modelAction.intent == QueryIntent.CLARIFY && localAction.intent != QueryIntent.CLARIFY) return true
        if (localAction.intent == QueryIntent.OPEN_STATS_PAGE || localAction.intent == QueryIntent.OPEN_ASSET_STATS_PAGE) {
            if (modelAction.intent != localAction.intent) return true
        }
        if (localAction.intent == QueryIntent.QUERY_EXISTENCE && modelAction.intent != QueryIntent.QUERY_EXISTENCE) return true
        if (modelAction.intent == QueryIntent.QUERY_BILLS && localAction.intent == QueryIntent.QUERY_ASSET_STATS) {
            if (modelAction.slots.assetId == null && localAction.slots.assetId != null) return true
        }
        if (modelAction.slots.assetId == null && localAction.slots.assetId != null) return true
        if (modelAction.slots.categoryId == null && localAction.slots.categoryId != null) return true
        if (modelAction.slots.keyword.isNullOrBlank() && !localAction.slots.keyword.isNullOrBlank()) return true
        if (modelAction.slots.timeRange == null && localAction.slots.timeRange != null) return true
        if (modelAction.slots.confidence < 0.5 && localAction.slots.confidence >= 0.65) return true
        return false
    }

    private fun shouldBypassModel(userText: String, localAction: QueryAction): Boolean {
        val text = userText.trim()
        if (text.isBlank()) return true
        if (localAction.intent == QueryIntent.UNSUPPORTED || localAction.intent == QueryIntent.CLARIFY) return false

        val hasExplicitTime = localAction.slots.timeRange != null ||
            listOf("今天", "昨天", "本周", "上周", "本月", "上月", "这个月", "上个月", "今年").any { text.contains(it) }
        val asksLatest = localAction.slots.aggregation == QueryAggregation.LATEST
        val hasEntity = localAction.slots.assetId != null ||
            !localAction.slots.accountName.isNullOrBlank() ||
            localAction.slots.categoryId != null ||
            !localAction.slots.categoryName.isNullOrBlank() ||
            !localAction.slots.keyword.isNullOrBlank()
        val isOpenStats = localAction.intent == QueryIntent.OPEN_STATS_PAGE ||
            localAction.intent == QueryIntent.OPEN_ASSET_STATS_PAGE
        val isExistence = localAction.intent == QueryIntent.QUERY_EXISTENCE
        val hasDeterministicShape = asksLatest || (hasExplicitTime && hasEntity) || isOpenStats || isExistence
        return hasDeterministicShape
    }
}

private fun QueryTimeRange?.isUsableRange(): Boolean {
    return this != null && this.startMillis != null && this.endMillis != null && this.startMillis <= this.endMillis
}

