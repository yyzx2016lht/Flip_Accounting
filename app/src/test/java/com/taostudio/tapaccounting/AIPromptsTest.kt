package com.taostudio.tapaccounting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIPromptsTest {
    @Test
    fun intentRouterPromptContainsKeyElements() {
        val prompt = AIPrompts.INTENT_ROUTER_PROMPT_DEFAULT

        assertTrue(prompt.contains("BOOKKEEPING"))
        assertTrue(prompt.contains("GENERAL_CHAT"))
        assertTrue(prompt.contains("intent"))
    }

    @Test
    fun categoryRulesRequireCandidateIdsWithoutInventedCategoryExamples() {
        val prompt = AIPrompts.buildCategoryRulesCompact(hasSecondLevel = false)

        assertTrue(prompt.contains("category_id"))
        assertTrue(prompt.contains("禁止自造 id 或分类名"))
        assertFalse(prompt.contains("酒店→住宿"))
        assertFalse(prompt.contains("软件/服务"))
    }

    @Test
    fun accountingDataBlockAddsRequestLocalCategoryIds() {
        val dataBlock = buildDataBlock(promptContext())

        assertTrue(dataBlock.contains("""{"id":"e0","name":"网费"}"""))
        assertTrue(dataBlock.contains("""{"id":"e1","name":"其它"}"""))
        assertTrue(dataBlock.contains("""{"id":"i0","name":"工资"}"""))
    }

    @Test
    fun chatAccountingPromptIncludesCategoryIds() {
        val prompt = buildAccountingUserPrompt(
            userInput = "充话费50",
            promptContext = promptContext(),
            matchedPromptRules = emptyList(),
            assetFeatureEnabled = false,
            isFromChat = true
        )

        assertTrue(prompt.contains("对话记账模式"))
        assertTrue(prompt.contains("""{"id":"e0","name":"网费"}"""))
    }

    @Test
    fun screenAccountingPromptIncludesCategoryIds() {
        val prompt = buildScreenAccountingUserText(
            promptContext = promptContext(),
            taskInstruction = "识别图片中的账单"
        )

        assertTrue(prompt.contains("""{"id":"e0","name":"网费"}"""))
        assertTrue(prompt.contains("识别图片中的账单"))
    }

    private fun promptContext() = AIAccountingPromptContext(
        dbAssets = emptyList(),
        assetInfoList = emptyList(),
        assetNames = emptyList(),
        assetCurrencyMap = emptyMap(),
        expenseCats = listOf("网费", "其它"),
        incomeCats = listOf("工资"),
        currencies = listOf("CNY"),
        currentTimeStr = "2026-07-20 10:55:00",
        assetFeatureEnabled = false,
        availableBooks = emptyList()
    )
}

