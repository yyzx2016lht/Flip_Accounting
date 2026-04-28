package tao.test.flipaccounting.chat.ai

import java.util.Locale

object AiIntentRouter {
    private val queryWords = listOf("搜索", "查询", "查一下", "统计", "多少", "多少钱", "花了多少", "支出", "消费", "合计", "总共")
    private val bookkeepingWords = listOf("记一笔", "记账", "花了", "收入", "报销", "转账", "还款", "买了", "付了", "收了")
    private val generalChatWords = listOf("你好", "谢谢", "你是谁", "聊天", "讲个", "怎么用", "帮助")
    private val highRiskWriteWords = listOf("删除", "删掉", "清空", "覆盖", "批量修改", "全部改", "全改", "撤销所有", "重置")
    private val modifyWords = listOf("改成", "换成", "上一笔", "刚刚那笔", "刚才那笔", "前一笔", "修改为", "修改成", "其实是")
    private val accountWords = listOf(
        "微信", "支付宝", "银行卡", "现金", "信用卡", "花呗", "京东", "美团",
        "visa卡", "visa", "mastercard", "master card", "万事达", "银联"
    )
    private val amountRegex = Regex("""(?<!\d)(\d+(?:\.\d+)?)(?:元|块|块钱|rmb|cny)?""", RegexOption.IGNORE_CASE)

    fun route(text: String): AiRouteResult {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return AiRouteResult(AiIntentType.UNKNOWN, 0.0)
        }
        if (highRiskWriteWords.any { normalized.contains(it) }) {
            return AiRouteResult(AiIntentType.UNKNOWN, 0.92, extractSlots(normalized))
        }

        val slots = extractSlots(normalized)
        val bookkeepingMode = detectBookkeepingMode(normalized)

        if (modifyWords.any { normalized.contains(it) }) {
            return AiRouteResult(AiIntentType.MODIFY_BILL, 0.85, slots)
        }

        val isQuestionLike = queryWords.any { normalized.contains(it) }
        val hasQueryShape = isQuestionLike && (slots.timeRange != null || slots.account != null || slots.category != null)
        if (hasQueryShape) {
            val confidence = listOf(
                0.45,
                if (slots.timeRange != null) 0.25 else 0.0,
                if (slots.account != null) 0.15 else 0.0,
                if (slots.category != null) 0.15 else 0.0
            ).sum().coerceAtMost(0.98)
            return AiRouteResult(AiIntentType.QUERY, confidence, slots)
        }

        val hasAmount = slots.amount != null
        val hasBookkeepingVerb = bookkeepingWords.any { normalized.contains(it) }
        if (hasAmount && hasBookkeepingVerb) {
            return AiRouteResult(AiIntentType.BOOKKEEPING, 0.9, slots, bookkeepingMode)
        }

        if (generalChatWords.any { normalized.contains(it) }) {
            return AiRouteResult(AiIntentType.GENERAL_CHAT, 0.72, slots)
        }

        return AiRouteResult(AiIntentType.BOOKKEEPING, 0.52, slots, bookkeepingMode)
    }

    fun isHighRiskWrite(text: String): Boolean {
        return highRiskWriteWords.any { text.contains(it) }
    }

    private fun extractSlots(text: String): AiIntentSlots {
        val timeRange = AiTimeRangeParser.parse(text)
        val account = accountWords.firstOrNull { text.contains(it) }
        val category = extractCategory(text)
        val amount = amountRegex.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val keyword = extractKeyword(text)
        return AiIntentSlots(
            timeRange = timeRange,
            account = account,
            category = category,
            amount = amount,
            keyword = keyword
        )
    }

    fun detectBookkeepingMode(text: String): AiBookkeepingMode {
        val normalized = text
            .removePrefix("[图片OCR文本]: ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.getDefault())
        if (normalized.isBlank()) return AiBookkeepingMode.UNSPECIFIED

        val explicitMulti = Regex("分别|各[记来]?一笔|再来一笔|还有一笔|一共\\d+笔|两笔|三笔|多笔").containsMatchIn(normalized)
        if (explicitMulti) return AiBookkeepingMode.MULTI

        val explicitSingle = Regex("就这一笔|只记一笔|单笔|一笔就行|这笔就行").containsMatchIn(normalized)
        if (explicitSingle) return AiBookkeepingMode.SINGLE

        val moneyUnitRegex = Regex("\\d+(?:\\.\\d{1,2})?\\s*(元|块钱|块|rmb|cny|pln|usd|eur|€|\\$)")
        val actionAmountRegex = Regex("(花了|花费|支付|付款|收了|收到|转账|还款|充值|提现|赚了|收入|退款到账|报销到账)\\s*\\d+(?:\\.\\d{1,2})?")
        val amountCount = maxOf(
            moneyUnitRegex.findAll(normalized).count(),
            actionAmountRegex.findAll(normalized).count()
        )

        val actionWords = listOf("买", "花", "支付", "付款", "收", "到账", "退款", "转账", "还款", "充值", "提现", "借出", "收回")
        val actionHitCount = actionWords.count { normalized.contains(it) }

        val connectorRegex = Regex("然后|再|又|另外|同时|并且|以及|分别|之后")
        val connectorCount = connectorRegex.findAll(normalized).count().coerceAtMost(3)

        val sentenceLikeCount = normalized
            .split(Regex("[,，。；;、\\n]+"))
            .map { it.trim() }
            .count { seg ->
                seg.isNotBlank() &&
                    (moneyUnitRegex.containsMatchIn(seg) ||
                        actionAmountRegex.containsMatchIn(seg) ||
                        actionWords.any { seg.contains(it) })
            }

        val hasIncome = listOf("收入", "收到", "到账", "退款到账", "报销到账", "工资").any { normalized.contains(it) }
        val hasExpense = listOf("买", "花", "支付", "付款", "消费").any { normalized.contains(it) }
        val hasTransferOrRepay = listOf("转账", "还款", "还卡").any { normalized.contains(it) }

        val clearSingle =
            amountCount <= 1 &&
                actionHitCount <= 1 &&
                connectorCount == 0 &&
                sentenceLikeCount <= 1 &&
                !hasIncome &&
                !hasTransferOrRepay

        if (clearSingle) return AiBookkeepingMode.SINGLE
        return AiBookkeepingMode.MULTI
    }

    private fun extractCategory(text: String): String? {
        val normalized = text.replace("\\s+".toRegex(), "")
        return when {
            listOf("吃的", "吃饭", "吃了", "餐饮", "三餐", "餐食", "饭", "早餐", "午餐", "晚餐", "外卖", "奶茶", "咖啡", "餐厅").any { normalized.contains(it) } -> "餐饮"
            listOf("交通", "打车", "公交", "地铁", "高铁", "机票", "停车").any { normalized.contains(it) } -> "交通"
            listOf("购物", "买东西", "淘宝", "京东", "拼多多").any { normalized.contains(it) } -> "购物"
            listOf("住房", "房租", "水电", "物业").any { normalized.contains(it) } -> "住房"
            listOf("娱乐", "电影", "游戏", "会员").any { normalized.contains(it) } -> "娱乐"
            listOf("医疗", "医院", "药", "看病").any { normalized.contains(it) } -> "医疗"
            else -> null
        }
    }

    private fun extractKeyword(text: String): String? {
        val normalized = text.trim()
        return when {
            normalized.contains("吃的") -> "吃的"
            normalized.contains("餐饮") -> "餐饮"
            else -> null
        }
    }
}
