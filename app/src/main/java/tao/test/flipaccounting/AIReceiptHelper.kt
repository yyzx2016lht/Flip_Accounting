package tao.test.flipaccounting

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.repository.CategoryRepository
import tao.test.flipaccounting.logic.CurrencyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 小票解析辅助对象。
 * 包含：本地 OCR 结构化提取、AI 结构化响应解析、文本清洗、翻译等所有与小票相关的逻辑。
 * 对 AIService 内部可见（同 package），不对外暴露。
 */
internal object AIReceiptHelper {

    // ─────────────────────────────────────────────
    // 内部数据类
    // ─────────────────────────────────────────────

    internal data class ReceiptSummaryItem(
        val name: String,
        val price: Double,
        val currency: String
    )

    private data class ReceiptPriceToken(
        val amount: Double,
        val hasTaxSuffix: Boolean
    )

    private data class PendingReceiptKnownItem(
        val name: String,
        val quantity: Double,
        val unitPrice: Double,
        val grossTotal: Double,
        var discountTotal: Double = 0.0,
        var netTotal: Double? = null
    )

    // ─────────────────────────────────────────────
    // 入口：从 OCR 文本构建小票摘要
    // ─────────────────────────────────────────────

    /**
     * 利用正则规则直接从 OCR 文本提取小票摘要（快速路径）。
     * 返回 null 表示规则不足以置信，应走 AI 模式。
     */
    fun buildReceiptSummaryDirectlyFromOcr(ocrText: String): String? {
        val currency = detectReceiptCurrency(ocrText)
        val knownPatternItems = extractReceiptKnownPatternItems(ocrText, currency)
        if (knownPatternItems.size >= 2) {
            return knownPatternItems.joinToString("\n") { item ->
                formatReceiptSummaryLine(item.name, item.price, item.currency)
            }
        }
        return null
    }

    /**
     * 降级 fallback：启发式提取（精度低于 knownPattern）。
     */
    fun buildReceiptSummaryHeuristicFallback(ocrText: String): String? {
        val currency = detectReceiptCurrency(ocrText)
        val inlineItems = extractReceiptInlineItems(ocrText, currency)
        if (inlineItems.size >= 2) {
            return inlineItems.joinToString("\n") { item ->
                formatReceiptSummaryLine(item.name, item.price, item.currency)
            }
        }

        val names = extractReceiptCandidateNames(ocrText)
        val pricesRaw = extractReceiptFinalPrices(ocrText)
        val prices = if (pricesRaw.size > names.size) pricesRaw.takeLast(names.size) else pricesRaw
        val pairCount = minOf(names.size, prices.size)
        if (pairCount < 2) return null
        if (abs(names.size - prices.size) > 4 && pairCount < 5) return null

        val summaryItems = mutableListOf<ReceiptSummaryItem>()
        for (i in 0 until pairCount) {
            val name = normalizeReceiptName(names[i])
            val price = prices[i]
            if (name.isBlank() || price <= 0.0) continue
            addReceiptItemIfUnique(summaryItems, name, price, currency)
        }
        return if (summaryItems.size >= 2) {
            summaryItems.joinToString("\n") { item ->
                formatReceiptSummaryLine(item.name, item.price, item.currency)
            }
        } else null
    }

    /**
     * 将 AI 返回的结构化 JSON 字符串转换为自然语言摘要行。
     */
    fun buildReceiptSummaryFromStructured(content: String, originalOcrText: String): String {
        return try {
            val cleaned = cleanJsonString(content)
            val root = JSONObject(cleaned)
            val items = when {
                root.has("items") -> root.optJSONArray("items") ?: JSONArray()
                root.has("bills") -> root.optJSONArray("bills") ?: JSONArray()
                else -> JSONArray()
            }
            val fallbackCurrency = root.optString("currency", detectReceiptCurrency(originalOcrText))
                .ifBlank { detectReceiptCurrency(originalOcrText) }

            val summaryItems = mutableListOf<ReceiptSummaryItem>()
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val rawName = listOf("name", "item_name", "title", "product", "remarks", "category_name")
                    .asSequence()
                    .map { key -> obj.optString(key, "") }
                    .firstOrNull { it.isNotBlank() }
                    ?: ""
                val name = normalizeReceiptName(rawName)
                if (name.isBlank() || isNoiseReceiptName(name)) continue

                val price = listOf("price", "amount", "paid", "final_price", "unit_total")
                    .asSequence()
                    .mapNotNull { key ->
                        if (!obj.has(key)) null else parseReceiptPrice(obj.opt(key))
                    }
                    .firstOrNull()
                    ?: parseReceiptPrice(obj.optString("raw_line", ""))
                    ?: continue
                if (price <= 0.0) continue

                val currency = obj.optString("currency", fallbackCurrency).ifBlank { fallbackCurrency }
                addReceiptItemIfUnique(summaryItems, name, price, currency)
            }

            if (summaryItems.isNotEmpty()) {
                summaryItems.joinToString("\n") { item ->
                    formatReceiptSummaryLine(item.name, item.price, item.currency)
                }
            } else {
                sanitizeReceiptSummaryText(content, originalOcrText) ?: content
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sanitizeReceiptSummaryText(content, originalOcrText) ?: content
        }
    }

    /**
     * 对 AI 自由文本输出进行清洗，提取有效摘要行。
     */
    fun sanitizeReceiptSummaryText(content: String, originalOcrText: String): String? {
        val fallbackCurrency = detectReceiptCurrency(originalOcrText)
        val summaryItems = mutableListOf<ReceiptSummaryItem>()
        val totalKeywords = listOf("总计", "合计", "total", "sum", "suma", "razem")
        val lineRegex = Regex("""([0-9]+[.,][0-9]{2})\s*(PLN|EUR|USD|CNY|RMB|zł|ZŁ|zl|ZL)?""")

        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val lower = line.lowercase(Locale.ROOT)
                if (totalKeywords.any { lower.contains(it) }) return@forEach
                if (isNoiseReceiptName(lower)) return@forEach

                val price = parseReceiptPrice(line) ?: return@forEach
                val match = lineRegex.find(line)
                val currency = match?.groupValues?.getOrNull(2)
                    ?.trim()
                    ?.ifBlank { fallbackCurrency }
                    ?: fallbackCurrency

                val spentIdx = line.indexOf("spent", ignoreCase = true)
                val rawName = when {
                    line.contains("花了") -> line.substringBefore("花了")
                    spentIdx >= 0 -> line.substring(0, spentIdx)
                    else -> line.replace(lineRegex, "").trim()
                }
                val name = normalizeReceiptName(
                    rawName.removePrefix("购买").removePrefix("买").trim()
                )
                if (name.isBlank() || isNoiseReceiptName(name)) return@forEach
                addReceiptItemIfUnique(summaryItems, name, price, currency)
            }

        if (summaryItems.isEmpty()) return null
        return summaryItems.joinToString("\n") { item ->
            formatReceiptSummaryLine(item.name, item.price, item.currency)
        }
    }

    // ─────────────────────────────────────────────
    // System prompt 构建
    // ─────────────────────────────────────────────

    suspend fun buildReceiptSystemPrompt(ctx: Context, ocrText: String = ""): String {
        val assets = Prefs.getAssets(ctx).map { it.name }
        val currencies = CurrencyManager.getEnabledCurrencies(ctx)
        val repo = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())
        val expenseCats = mutableListOf<String>()
        withContext(Dispatchers.IO) { repo.getCategoryTree(0) }.forEach { parentNode ->
            if (parentNode.subs.isEmpty()) expenseCats.add(parentNode.name)
            else parentNode.subs.forEach { childNode ->
                expenseCats.add("${parentNode.name}/::/${childNode.name}")
            }
        }
        val incomeCats = mutableListOf<String>()
        withContext(Dispatchers.IO) { repo.getCategoryTree(1) }.forEach { parentNode ->
            if (parentNode.subs.isEmpty()) incomeCats.add(parentNode.name)
            else parentNode.subs.forEach { childNode ->
                incomeCats.add("${parentNode.name}/::/${childNode.name}")
            }
        }
        val now = Date()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val weekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        val currentTimeStr = "${timeFormat.format(now)} (${weekFormat.format(now)})"

        val customPrompt = Prefs.getReceiptBillPrompt(ctx)
        val basePrompt = if (customPrompt.isNotEmpty()) customPrompt else AIPrompts.RECEIPT_BILL_PROMPT

        val hardenedPrompt = basePrompt + """

【补充硬规则】
1. 这是购物小票解析任务，默认语义全部是支出，不是收入。
2. 商品金额必须使用实付金额，不要用原价或推测值。
3. 只提取商品行，不要把日期、NIP、税率、Discount、TOTAL 等信息当作商品。
4. OCR 重复行只保留一次，避免重复计数。
5. 除非 OCR 原文明确存在且可确认，不要计算或猜测总计。
"""

        return hardenedPrompt.replace("{{TIME}}", currentTimeStr)
            .replace("{{ASSETS}}", Gson().toJson(assets))
            .replace("{{EXPENSE_CATS}}", Gson().toJson(expenseCats))
            .replace("{{INCOME_CATS}}", Gson().toJson(incomeCats))
            .replace("{{CURRENCIES}}", Gson().toJson(currencies))
    }

    // ─────────────────────────────────────────────
    // OCR 文本预处理
    // ─────────────────────────────────────────────

    fun preprocessOcrTextForReceipt(ocrText: String): String {
        val noiseKeywords = listOf("non-fiscal receipt", "taxed sale", "ptu", "discount", "nip")
        val seen = HashSet<String>()
        val cleanedLines = mutableListOf<String>()
        ocrText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { raw ->
                val normalized = raw.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                if (noiseKeywords.any { normalized.contains(it) }) return@forEach
                if (!seen.add(normalized)) return@forEach
                cleanedLines.add(raw)
            }
        return if (cleanedLines.isNotEmpty()) cleanedLines.joinToString("\n") else ocrText
    }

    // ─────────────────────────────────────────────
    // 外语检测
    // ─────────────────────────────────────────────

    fun isForeignText(text: String): Boolean {
        if (text.isEmpty()) return false
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val chineseCount = letters.count { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
        val latinCount = letters.count { it.code in 0x0041..0x007A || it.code in 0x00C0..0x024F }
        return latinCount > chineseCount * 2
    }

    // ─────────────────────────────────────────────
    // 已知结构化模式提取（Carrefour / Biedronka / Auchan）
    // ─────────────────────────────────────────────

    private fun extractReceiptKnownPatternItems(ocrText: String, currency: String): List<ReceiptSummaryItem> {
        val qtyRegex = Regex("""([0-9OolI]+(?:[.,][0-9OolI]+)?)\s*[*xX×]""")
        val signedPriceRegex = Regex("""[-+]?[0-9OolI]+[.,][0-9OolI]{2}""")
        val aggregate = LinkedHashMap<String, Triple<String, Double, Double>>()
        var pending: PendingReceiptKnownItem? = null

        fun flushPending() {
            val item = pending ?: return
            var finalTotal = item.netTotal ?: (item.grossTotal - item.discountTotal)
            if (finalTotal <= 0.0) { pending = null; return }
            if (finalTotal > item.grossTotal + 0.1) finalTotal = item.grossTotal
            val key = canonicalReceiptName(item.name)
            val old = aggregate[key]
            if (old == null) {
                aggregate[key] = Triple(item.name, item.quantity, finalTotal)
            } else {
                aggregate[key] = Triple(old.first, old.second + item.quantity, old.third + finalTotal)
            }
            pending = null
        }

        ocrText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { rawLine ->
                val line = rawLine.replace(Regex("\\s+"), " ")
                val lower = normalizeReceiptNameForMatch(line).lowercase(Locale.ROOT)

                // 折扣行
                if (pending != null && (lower.contains("opust") || lower.contains("discount"))) {
                    val discount = signedPriceRegex.findAll(line)
                        .mapNotNull { parseSignedReceiptPriceToken(it.value) }
                        .firstOrNull()
                    if (discount != null) pending!!.discountTotal += abs(discount)
                    return@forEach
                }

                // 折后净额行（单独一行数字）
                if (pending != null) {
                    val hasQty = qtyRegex.containsMatchIn(line)
                    val prices = signedPriceRegex.findAll(line)
                        .mapNotNull { parseSignedReceiptPriceToken(it.value) }
                        .toList()
                    val letters = line.count { it.isLetter() }
                    if (!hasQty && prices.size == 1 && letters <= 1 && prices[0] > 0.0) {
                        pending!!.netTotal = prices[0]
                        return@forEach
                    }
                }

                val qtyMatch = qtyRegex.find(line) ?: return@forEach
                val qty = parseReceiptQuantityToken(qtyMatch.groupValues[1]) ?: return@forEach
                if (qty <= 0.0) return@forEach

                val afterQty = line.substring(qtyMatch.range.last + 1)
                val prices = signedPriceRegex.findAll(afterQty)
                    .mapNotNull { parseSignedReceiptPriceToken(it.value) }
                    .filter { it > 0.0 }
                    .toList()
                if (prices.size < 2) return@forEach

                var namePart = line.substring(0, qtyMatch.range.first).trim()
                namePart = namePart.replace(Regex("""\b[A-Z]\b$"""), "").trim()
                val name = normalizeReceiptName(namePart)
                if (name.isBlank() || isNoiseReceiptName(name)) return@forEach
                if (isReceiptHeaderLine(normalizeReceiptNameForMatch(name))) return@forEach

                flushPending()
                pending = PendingReceiptKnownItem(
                    name = name,
                    quantity = qty,
                    unitPrice = prices.first(),
                    grossTotal = prices.last()
                )
            }

        flushPending()

        return aggregate.values
            .map { (name, qty, total) ->
                val qtyText = formatReceiptQuantity(qty)
                ReceiptSummaryItem("$name x$qtyText", total, currency)
            }
            .filter { it.price > 0.0 }
    }

    // ─────────────────────────────────────────────
    // 行内数量-价格提取（Inline）
    // ─────────────────────────────────────────────

    private fun extractReceiptInlineItems(ocrText: String, currency: String): List<ReceiptSummaryItem> {
        val items = mutableListOf<ReceiptSummaryItem>()
        val qtyRegex = Regex("""\b[0-9OolI]+[.,][0-9OolI]+\s*[xX]\b""")
        val priceTokenRegex = Regex("""[0-9OolI]+[.,][0-9OolI]{2}\s*[A-Z]?""")

        ocrText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { rawLine ->
                val line = rawLine.replace(Regex("\\s+"), " ")
                val normalized = normalizeReceiptNameForMatch(line)
                if (isReceiptHeaderLine(normalized)) return@forEach
                if (!qtyRegex.containsMatchIn(line)) return@forEach

                val priceTokens = priceTokenRegex.findAll(line)
                    .map { normalizeOcrPriceToken(it.value) }
                    .toList()
                if (priceTokens.size < 2) return@forEach

                val finalToken = priceTokens.last()
                val finalPrice = parseReceiptPrice(finalToken.trimEnd { it.isLetter() }) ?: return@forEach
                if (finalPrice <= 0.0) return@forEach

                val qtyMatch = qtyRegex.find(line) ?: return@forEach
                var namePart = line.substring(0, qtyMatch.range.first).trim()
                namePart = namePart.replace(Regex("""\b[A-Z]\b$"""), "").trim()
                val name = normalizeReceiptName(namePart)
                if (name.isBlank() || isNoiseReceiptName(name)) return@forEach
                if (isReceiptHeaderLine(normalizeReceiptNameForMatch(name))) return@forEach

                addReceiptItemIfUnique(items, name, finalPrice, currency)
            }
        return items
    }

    // ─────────────────────────────────────────────
    // 候选商品名 / 价格提取
    // ─────────────────────────────────────────────

    private fun extractReceiptCandidateNames(ocrText: String): List<String> {
        val noiseKeywords = listOf(
            "non-fiscal receipt", "taxed sale", "ptu", "discount", "nip",
            "receipt", "total", "suma", "razem"
        )
        return ocrText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace(Regex("\\s+"), " ") }
            .filter { line ->
                val lower = line.lowercase(Locale.ROOT)
                if (noiseKeywords.any { lower.contains(it) }) return@filter false
                if (looksLikeDateOrTime(lower)) return@filter false
                if (line.length > 42) return@filter false
                if (isReceiptHeaderLine(normalizeReceiptNameForMatch(line))) return@filter false
                if (line.matches(Regex("^[A-Z]$"))) return@filter false
                if (line.matches(Regex("^[\\d\\s.,!/%xX()OolI-]+$"))) return@filter false
                line.count { it.isLetter() } >= 3
            }
            .map { normalizeReceiptName(it) }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun extractReceiptFinalPrices(ocrText: String): List<Double> {
        val tokens = mutableListOf<ReceiptPriceToken>()
        val tokenRegex = Regex("""[0-9OolI]+[.,][0-9OolI]{2}\s*[A-Z]?""")

        ocrText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                tokenRegex.findAll(line).forEach { m ->
                    val normalized = normalizeOcrPriceToken(m.value)
                    val hasTax = normalized.lastOrNull()?.isUpperCase() == true
                    val amountText = normalized.trimEnd { it.isLetter() }
                    val amount = parseReceiptPrice(amountText) ?: return@forEach
                    tokens.add(ReceiptPriceToken(amount = amount, hasTaxSuffix = hasTax))
                }
            }

        if (tokens.isEmpty()) return emptyList()

        val finals = mutableListOf<Double>()
        var i = 0
        while (i < tokens.size) {
            val cur = tokens[i]
            if (cur.hasTaxSuffix && i + 2 < tokens.size) {
                val discount = tokens[i + 1]
                val after = tokens[i + 2]
                if (!discount.hasTaxSuffix && after.hasTaxSuffix) {
                    val expected = cur.amount - discount.amount
                    if (abs(expected - after.amount) <= 0.06) {
                        finals.add(after.amount)
                        i += 3
                        continue
                    }
                }
            }
            if (cur.hasTaxSuffix) finals.add(cur.amount)
            i++
        }

        return if (finals.isNotEmpty()) finals else tokens.map { it.amount }
    }

    // ─────────────────────────────────────────────
    // 格式化 / 翻译
    // ─────────────────────────────────────────────

    fun formatReceiptSummaryLine(name: String, price: Double, currency: String): String {
        val translated = translateReceiptNameToChinese(name)
        val displayName = if (translated != null && !translated.equals(name, ignoreCase = true)) {
            "$translated ($name)"
        } else {
            name
        }
        return "购买$displayName 花了${String.format(Locale.US, "%.2f", price)} $currency"
    }

    private fun translateReceiptNameToChinese(name: String): String? {
        val n = normalizeReceiptNameForMatch(name)
        if (n.isBlank()) return null

        val isWater = n.contains("woda")
        val isStrawberry = n.contains("truskawka")
        val isBalsam = n.contains("balsam")
        val isCereal = n.contains("platki") || n.contains("płatki") || n.contains("flakes")
        val isApple = n.contains("jabl") || n.contains("jabł") || n.contains("apple")
        val isBread = n.contains("chleb")
        val isYogurt = n.contains("jog")
        val isApricot = n.contains("morel")
        val isForestFruit = n.contains("owoclasu") || n.contains("owoc lasu")
        val isFlaki = n.contains("flaki")
        val isMixMeat = n.contains("krzymix") || n.contains("steklop") || n.contains("stek")

        val base = when {
            isWater -> "矿泉水"
            isStrawberry -> "草莓"
            isBalsam -> "洗洁精"
            isBread -> "面包"
            isYogurt && isApricot -> "杏子酸奶"
            isYogurt && isForestFruit -> "森林水果酸奶"
            isYogurt -> "酸奶"
            isFlaki -> "牛肚"
            isMixMeat -> "混合肉类"
            isCereal -> "麦片"
            isApple -> "苹果"
            else -> null
        } ?: return null

        val attrs = mutableListOf<String>()
        if (n.contains("niegaz")) attrs.add("无气")
        if (n.contains("polskie")) attrs.add("波兰")
        if (n.contains("gala")) attrs.add("Gala")
        if (n.contains("luz")) attrs.add("散装")

        return when {
            attrs.isEmpty() -> base
            base == "苹果" -> attrs.joinToString(" ") + " 苹果"
            else -> attrs.joinToString("") + base
        }
    }

    // ─────────────────────────────────────────────
    // 名称规范化 / 去重
    // ─────────────────────────────────────────────

    fun normalizeReceiptName(name: String): String {
        return name
            .replace(Regex("\\s+"), " ")
            .replace(Regex("""\b[AC]\b$"""), "")
            .trim()
    }

    fun normalizeReceiptNameForMatch(name: String): String {
        return name
            .lowercase(Locale.ROOT)
            .replace("ł", "l").replace("ą", "a").replace("ę", "e")
            .replace("ć", "c").replace("ń", "n").replace("ó", "o")
            .replace("ś", "s").replace("ż", "z").replace("ź", "z")
            .replace(Regex("[^\\p{L}\\d]+"), " ")
            .trim()
    }

    fun addReceiptItemIfUnique(
        items: MutableList<ReceiptSummaryItem>,
        name: String,
        price: Double,
        currency: String
    ) {
        val normalizedName = canonicalReceiptName(name)
        val duplicated = items.any { existing ->
            abs(existing.price - price) < 0.01 &&
                existing.currency.equals(currency, ignoreCase = true) &&
                areLikelySameReceiptName(normalizedName, canonicalReceiptName(existing.name))
        }
        if (!duplicated) items.add(ReceiptSummaryItem(name, price, currency))
    }

    private fun canonicalReceiptName(name: String): String {
        return name
            .lowercase(Locale.ROOT)
            .replace("ł", "l").replace("ą", "a").replace("ę", "e")
            .replace("ć", "c").replace("ń", "n").replace("ó", "o")
            .replace("ś", "s").replace("ż", "z").replace("ź", "z")
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("[0-9]+"), "")
            .replace(Regex("[^\\p{L}]"), "")
            .trim()
    }

    private fun areLikelySameReceiptName(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.contains(b) || b.contains(a)) return true
        val maxLen = maxOf(a.length, b.length)
        if (maxLen <= 4) return false
        return levenshteinDistance(a, b) <= 2
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            for (k in prev.indices) prev[k] = curr[k]
        }
        return prev[b.length]
    }

    // ─────────────────────────────────────────────
    // 噪声 / 头部行判断
    // ─────────────────────────────────────────────

    fun isNoiseReceiptName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        if (n.length <= 1) return true
        return listOf("discount", "taxed sale", "ptu", "non-fiscal", "receipt", "nip")
            .any { n.contains(it) }
    }

    private fun isReceiptHeaderLine(normalizedLine: String): Boolean {
        if (normalizedLine.isBlank()) return true
        val s = normalizedLine.lowercase(Locale.ROOT)
        val headerKeywords = listOf(
            "store", "jeronimo", "martins", "polska", "krakow", "kostrzyn",
            "ul ", " ul.", "nip", "non fiscal", "receipt", "taxed sale", "ptu",
            "total", "suma", "razem", "pln"
        )
        if (headerKeywords.any { s.contains(it) }) return true
        if (Regex("""\b\d{2}-\d{3}\b""").containsMatchIn(s)) return true
        if (Regex("""\b\d{3}-\d{2}-\d{2,3}-\d{2,3}\b""").containsMatchIn(s)) return true
        if (looksLikeDateOrTime(s)) return true
        return false
    }

    private fun looksLikeDateOrTime(line: String): Boolean {
        if (Regex("""\b\d{1,2}[:.]\d{2}\b""").containsMatchIn(line)) return true
        if (Regex("""\b\d{4}[-/]\d{1,2}[-/]\d{1,2}\b""").containsMatchIn(line)) return true
        if (Regex("""\b\d{1,2}\s+[a-z]{3,}\s+\d{4}\b""").containsMatchIn(line)) return true
        return false
    }

    // ─────────────────────────────────────────────
    // 货币检测
    // ─────────────────────────────────────────────

    fun detectReceiptCurrency(text: String): String {
        val upper = text.uppercase(Locale.ROOT)
        return when {
            upper.contains("PLN") -> "PLN"
            upper.contains("EUR") || upper.contains("€") -> "EUR"
            upper.contains("USD") || upper.contains("$") -> "USD"
            isForeignText(text) -> "PLN"
            else -> "CNY"
        }
    }

    // ─────────────────────────────────────────────
    // 价格解析 / OCR Token 规范化
    // ─────────────────────────────────────────────

    fun parseReceiptPrice(value: Any?): Double? {
        return when (value) {
            null -> null
            is Number -> value.toDouble().takeIf { it > 0.0 }
            is String -> {
                val matches = Regex("""\d+[.,]\d{2}""").findAll(value).toList()
                if (matches.isEmpty()) null
                else matches.last().value.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
            }
            else -> null
        }
    }

    private fun parseSignedReceiptPriceToken(token: String): Double? {
        val normalized = token
            .replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1')
            .replace(" ", "")
        val negative = normalized.startsWith("-")
        val amountToken = Regex("""\d+[.,]\d{2}""").find(normalized)?.value ?: return null
        val amount = amountToken.replace(',', '.').toDoubleOrNull() ?: return null
        return if (negative) -amount else amount
    }

    private fun parseReceiptQuantityToken(raw: String): Double? {
        val normalized = raw
            .replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1')
            .replace(',', '.').replace(" ", "")
        return normalized.toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    private fun formatReceiptQuantity(qty: Double): String {
        val intVal = qty.roundToInt().toDouble()
        return if (abs(qty - intVal) < 0.0001) {
            intVal.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.3f", qty).trimEnd('0').trimEnd('.')
        }
    }

    private fun normalizeOcrPriceToken(token: String): String {
        return token.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1')
            .replace(" ", "")
    }

    // ─────────────────────────────────────────────
    // JSON 清洗（从 AIService 搬移过来保持独立性）
    // ─────────────────────────────────────────────

    fun cleanJsonString(input: String): String {
        var s = input.trim()
        if (s.startsWith("```json")) s = s.removePrefix("```json")
        if (s.startsWith("```")) s = s.removePrefix("```")
        if (s.endsWith("```")) s = s.removeSuffix("```")
        return s.trim()
    }
}
