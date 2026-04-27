package tao.test.flipaccounting

import android.content.Context
import com.google.gson.Gson
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.AiRule as DbAiRule

internal fun buildAccountingSystemPrompt(
    ctx: Context,
    promptContext: AIAccountingPromptContext,
    isMultiMode: Boolean,
    matchedPromptRules: List<DbAiRule>,
    localPrefill: AILocalRulePrefill?
): String {
    var prompt = if (isMultiMode) Prefs.getMultiBillPrompt(ctx) else Prefs.getAiPrompt(ctx)
    if (prompt.isEmpty()) {
        prompt = if (isMultiMode) AIService.getDefaultMultiBillPrompt(ctx) else AIService.getDefaultSingleBillPrompt(ctx)
    }
    prompt = adaptPromptForCategoryDepth(
        prompt = prompt,
        hasSecondLevel = hasSecondLevelCategories(promptContext.expenseCats, promptContext.incomeCats)
    )

    prompt += AIPrompts.buildTypeRule(promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildExampleAntiLeakRule()
    prompt += AIPrompts.buildRemarksRichnessRule()
    prompt += AIPrompts.buildIncomeCategoryHardRule()
    prompt += AIPrompts.buildBookFieldRule(promptContext.availableBooks)
    prompt += AIPrompts.buildRepaymentRule(creditCardNames(promptContext), promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildAssetCurrencyRule(assetCurrencyHints(promptContext), promptContext.assetFeatureEnabled)

    if (isMultiMode) {
        prompt += AIPrompts.buildReceiptSemanticRule()
        val isFastMode = Prefs.isMultiBillFastMode(ctx)
        prompt += if (isFastMode) {
            AIPrompts.buildMultiFastModeRule(promptContext.expenseLeafCats, promptContext.incomeLeafCats)
        } else {
            AIPrompts.buildMultiStageOneRule(promptContext.expenseLeafCats, promptContext.incomeLeafCats)
        }
        if (!isFastMode) {
            prompt += AIPrompts.buildMultiTwoStageRule()
        }
    }

    if (matchedPromptRules.isNotEmpty()) {
        prompt += buildPromptCorrectionBlock(matchedPromptRules, includeCategory = !isMultiMode)
    }
    if (!isMultiMode && localPrefill != null) {
        prompt += AIPrompts.buildLocalRulePrefillHint()
    }
    prompt += AIPrompts.buildOutputJsonRuleWithTargetFields()

    val promptExpenseCats = if (!isMultiMode && !localPrefill?.category.isNullOrBlank()) emptyList() else promptContext.expenseCats
    val promptIncomeCats = if (!isMultiMode && !localPrefill?.category.isNullOrBlank()) emptyList() else promptContext.incomeCats
    val promptAssets = if (!isMultiMode && (!localPrefill?.assetName.isNullOrBlank() || !localPrefill?.toAssetName.isNullOrBlank())) {
        emptyList()
    } else {
        promptContext.assetInfoList
    }

    return renderPromptTemplate(
        prompt = prompt,
        promptContext = promptContext,
        assets = promptAssets,
        expenseCats = promptExpenseCats,
        incomeCats = promptIncomeCats
    )
}

internal fun buildAudioAccountingSystemPrompt(
    ctx: Context,
    promptContext: AIAccountingPromptContext,
    isMultiMode: Boolean
): String {
    var prompt = if (isMultiMode) Prefs.getMultiBillPrompt(ctx) else Prefs.getAiPrompt(ctx)
    if (prompt.isEmpty()) {
        prompt = if (isMultiMode) AIService.getDefaultMultiBillPrompt(ctx) else AIService.getDefaultSingleBillPrompt(ctx)
    }
    prompt = adaptPromptForCategoryDepth(
        prompt = prompt,
        hasSecondLevel = hasSecondLevelCategories(promptContext.expenseCats, promptContext.incomeCats)
    )

    prompt += AIPrompts.buildTypeRule(promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildVoiceInputRule()
    prompt += AIPrompts.buildExampleAntiLeakRule()
    prompt += AIPrompts.buildRemarksRichnessRule()
    prompt += AIPrompts.buildIncomeCategoryHardRule()
    prompt += AIPrompts.buildBookFieldRule(promptContext.availableBooks)
    prompt += AIPrompts.buildRepaymentRule(creditCardNames(promptContext), promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildAssetCurrencyRule(assetCurrencyHints(promptContext), promptContext.assetFeatureEnabled)

    if (isMultiMode) {
        prompt += AIPrompts.buildReceiptSemanticRule()
        val isFastMode = Prefs.isMultiBillFastMode(ctx)
        prompt += if (isFastMode) {
            AIPrompts.buildMultiFastModeRule(promptContext.expenseLeafCats, promptContext.incomeLeafCats)
        } else {
            AIPrompts.buildMultiStageOneRule(promptContext.expenseLeafCats, promptContext.incomeLeafCats) +
                AIPrompts.buildMultiTwoStageRule()
        }
    }
    prompt += AIPrompts.buildOutputJsonRuleWithBookField()

    return renderPromptTemplate(
        prompt = prompt,
        promptContext = promptContext,
        assets = promptContext.assetInfoList,
        expenseCats = promptContext.expenseCats,
        incomeCats = promptContext.incomeCats
    )
}

internal fun buildScreenAccountingSystemPrompt(
    ctx: Context,
    promptContext: AIAccountingPromptContext,
    isMultiMode: Boolean
): String {
    var prompt = Prefs.getScreenAccountingPrompt(ctx).ifBlank { AIService.SCREEN_ACCOUNTING_PROMPT_DEFAULT }
    prompt = adaptPromptForCategoryDepth(
        prompt = prompt,
        hasSecondLevel = hasSecondLevelCategories(promptContext.expenseCats, promptContext.incomeCats)
    )
    prompt += AIPrompts.buildRemarksRichnessRule()
    prompt += AIPrompts.buildIncomeCategoryHardRule()
    prompt += AIPrompts.buildTypeRule(promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildRepaymentRule(creditCardNames(promptContext), promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildAssetCurrencyRule(assetCurrencyHints(promptContext), promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildScreenModeRule(isMultiMode, promptContext.expenseLeafCats, promptContext.incomeLeafCats)
    prompt += AIPrompts.buildScreenUnifiedOutputRule()

    return renderPromptTemplate(
        prompt = prompt,
        promptContext = promptContext,
        assets = promptContext.assetInfoList,
        expenseCats = promptContext.expenseCats,
        incomeCats = promptContext.incomeCats
    )
}

internal fun buildPromptCorrectionBlock(matchedRules: List<DbAiRule>, includeCategory: Boolean = true): String {
    if (matchedRules.isEmpty()) return ""
    return buildString {
        appendLine("\n【本地记账习惯修正规则（高优先）】以下规则来自用户自定义，命中后优先遵守：")
        matchedRules.forEachIndexed { index, rule ->
            append("${index + 1}. 关键词：${rule.keyword}")
            rule.targetType?.let { append("；type=$it") }
            if (includeCategory) rule.targetCategory?.takeIf { it.isNotBlank() }?.let { append("；category_name=$it") }
            rule.targetAccount1?.takeIf { it.isNotBlank() }?.let { append("；asset_name=$it") }
            rule.targetAccount2?.takeIf { it.isNotBlank() }?.let { append("；to_asset_name=$it") }
            appendLine()
        }
        appendLine("命中规则时：优先按规则纠正类型、分类、账户；若规则未覆盖的字段拿不准，可留空，不要猜。")
    }
}

internal fun buildCategoryHierarchyHint(candidates: List<String>): String {
    val parents = candidates.filterNot { it.contains("/::/") }
    val children = candidates.filter { it.contains("/::/") }
    return buildString {
        if (parents.isNotEmpty()) append("一级分类：${parents.joinToString("、")}。")
        if (children.isNotEmpty()) append("可选二级分类：${children.joinToString("、")}。")
    }
}

internal fun hasSecondLevelCategories(
    expenseCats: List<String>,
    incomeCats: List<String>
): Boolean {
    return expenseCats.any { it.contains("/::/") } || incomeCats.any { it.contains("/::/") }
}

internal fun adaptPromptForCategoryDepth(prompt: String, hasSecondLevel: Boolean): String {
    val removableKeywords = listOf(
        "优先命中更细的子分类",
        "子分类格式固定为 一级/::/二级",
        "子分类格式必须为 一级/::/二级",
        "子分类格式必须输出 一级/::/二级",
        "一级/::/二级"
    )
    val normalized = prompt
        .lineSequence()
        .filterNot { line -> removableKeywords.any { key -> line.contains(key) } }
        .joinToString("\n")
        .trim()
    val rule = if (hasSecondLevel) {
        "\n【分类层级约束】当前分类库包含二级分类：优先命中更细的子分类；命中子分类时 category_name 必须输出“一级/::/二级”。\n"
    } else {
        "\n【分类层级约束】当前分类库没有二级分类，category_name 只能输出一级分类名；禁止输出“一级/::/二级”格式。\n"
    }
    return normalized + rule
}

private fun creditCardNames(promptContext: AIAccountingPromptContext): List<String> =
    promptContext.dbAssets
        .filter { it.assetCategory == Asset.CATEGORY_CREDIT_CARD }
        .map { it.name }

private fun assetCurrencyHints(promptContext: AIAccountingPromptContext): List<String> =
    promptContext.dbAssets
        .filter { it.currency.isNotEmpty() && it.currency != "CNY" }
        .map { "\"${it.name}\"(${it.currency})" }

private fun renderPromptTemplate(
    prompt: String,
    promptContext: AIAccountingPromptContext,
    assets: List<Map<String, String>>,
    expenseCats: List<String>,
    incomeCats: List<String>
): String {
    return prompt
        .replace("{{TIME}}", promptContext.currentTimeStr)
        .replace("{{ASSETS}}", Gson().toJson(assets))
        .replace("{{EXPENSE_CATS}}", Gson().toJson(expenseCats))
        .replace("{{INCOME_CATS}}", Gson().toJson(incomeCats))
        .replace("{{CURRENCIES}}", Gson().toJson(promptContext.currencies))
        .replace("{{DEMO_ASSET}}", promptContext.demoAsset)
        .replace("{{DEMO_EXPENSE_CAT}}", promptContext.demoExpenseCat)
        .replace("{{DEMO_INCOME_CAT}}", promptContext.demoIncomeCat)
}
