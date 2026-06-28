# 图片记账草稿确认流程优化方案

## 1. 背景与问题

### 当前流程

图片记账有两条路径，由「图片记账补充输入」开关控制：

**Direct 路径**（开关关闭）：
```
图片 → analyzeScreenAccountingByImage → JSON → 直接入库
一次 AI 调用，无确认弹窗
```

**Draft-confirm 路径**（开关开启，当前用户使用）：
```
图片 → analyzeReceiptByImage → 纯文本 → 弹窗展示
       ↓ 用户点「确认入账」
       analyzeAccounting(编辑后文本) → JSON → 入库（第二次 AI 调用）
       ↓ 用户点「稍后再确认」
       存纯文本到 draft → Inbox → analyzeAccounting → JSON → 入库（第二次 AI 调用）
```

### 问题

1. **AI 被调两次** — 第一次返回纯文本，第二次把文本重新解析为 JSON，浪费 token / 时间 / 网络
2. **Inbox 体验差** — 存的是纯文本，显示用 regex 估算金额不准，确认时才调 AI 解析
3. **首页卡片不刷新** — `InvalidationTracker` 只监听 `bills` 表不监听 `accounting_drafts` 表

---

## 2. 需求

### 核心需求

图片记账（Draft-confirm 路径）改为**一次 AI 调用同时拿到结构化 JSON 和展示文本**：

- AI 返回结构化 JSON
- 本地从 JSON 格式化出展示文本
- 弹窗展示文本，后台持有 JSON
- **用户没改文本** → 直接用 JSON 入库或存 draft，不需要第二次 AI 调用
- **用户改了文本** → 调第二次 AI（`analyzeAccounting`），用编辑后的文本重新解析
- **用户点「稍后再确认」** → 直接存 JSON 到 draft，不调 AI

### 展示文本格式

不带分类，不带分类，不带分类（重要）：

```
烤肉串花了 4.59 PLN，用 Visa 支付
散装番茄花了 0.52 PLN，用 Visa 支付
```

格式规则：
- 单笔：`{remark}花了 {amount} {currency}，用 {account} 支付`
- 多笔：每笔一行
- 无备注时：`消费 {amount} {currency}，用 {account} 支付`
- 无账户时：`{remark}花了 {amount} {currency}`
- 转账：`从 {from} 转到 {to} {amount} {currency}`
- 收入：`收到 {amount} {currency}，{remark}`

### Inbox 需求

- 每笔账单单独一条 draft（多笔不合并）
- 展示为账单卡片（分类名 / 金额 / 账户 / 时间）
- 单击可编辑（金额 / 分类 / 账户 / 备注）
- 底部栏动态文案：选 1 条「确认这笔」，选 N 条「确认 N 笔」
- 确认时直接用已有的 JSON，不再调 AI（除非是旧的自然语言草稿）

### 首页刷新需求

- 从 Inbox 返回后，「待确认账单」卡片自动刷新
- 确认/删除草稿后，卡片数量实时更新

---

## 3. 具体改动

### 3.1 ChatMessagePipeline.kt — 草稿确认路径改造

**当前代码（以多图为例，单图同理）：**

```kotlin
// Draft-confirm path
val visionResult = AIService.analyzeReceiptByImages(...)  // 返回纯文本
val draftForConfirm = mergeSupplementWithSummary(visionResult, supplement)
val confirmedDraft = confirmVisualAccountingDraft(draftForConfirm, ...)  // 用户编辑
if (confirmedDraft == null) { /* 取消 */ }
val accountingInput = buildAccountingInputFromImageDraft(confirmedDraft, supplement)
val result = AIService.analyzeAccounting(accountingInput, ...)  // 第二次 AI 调用
```

**改为：**

```kotlin
// Draft-confirm path（一次调用）
val accountingResult = AIService.analyzeScreenAccountingByImages(...)  // 返回 JSON
if (!canWriteForRequest(requestContext)) return@launch

// 本地格式化展示文本（不带分类）
val displayText = formatDraftDisplayText(accountingResult)
val draftForConfirm = mergeSupplementWithSummary(displayText, supplement)

// 弹窗核对
val confirmedDraft = confirmVisualAccountingDraft(draftForConfirm, ...)
if (!canWriteForRequest(requestContext)) return@launch

if (confirmedDraft == null) {
    // 取消
    return@launch
}

// 判断用户是否改了文本
val originalText = draftForConfirm.trim()
val editedText = confirmedDraft.trim()

if (editedText == originalText) {
    // 用户没改 → 直接用第一次的 JSON，不再调 AI
    result = accountingResult
} else {
    // 用户改了 → 调第二次 AI 重新解析
    val accountingInput = buildAccountingInputFromImageDraft(editedText, supplement)
    result = AIService.analyzeAccounting(accountingInput, ...)
}
```

**新增辅助方法 `formatDraftDisplayText(result: JSONObject): String`：**

```kotlin
private fun formatDraftDisplayText(result: JSONObject): String {
    val bills = when {
        result.has("bills") -> {
            val arr = result.getJSONArray("bills")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }
        result.has("amount") -> listOf(result)
        else -> return ""
    }
    return bills.joinToString("\n") { bill ->
        val remark = bill.optString("remarks", bill.optString("remark", ""))
        val amount = bill.optDouble("amount", 0.0)
        val currency = bill.optString("currency", "CNY")
        val account = bill.optString("asset_name", bill.optString("accountName", ""))
        val type = bill.optInt("type", 0)
        val amountStr = String.format("%.2f", amount)

        when (type) {
            1 -> { // 收入
                if (remark.isNotBlank()) "收到 $amountStr $currency，$remark"
                else "收到 $amountStr $currency"
            }
            2, 3 -> { // 转账/还款
                val toAccount = bill.optString("to_asset_name", bill.optString("toAccountName", ""))
                if (account.isNotBlank() && toAccount.isNotBlank()) {
                    "从 $account 转到 $toAccount $amountStr $currency"
                } else {
                    "转账 $amountStr $currency"
                }
            }
            else -> { // 支出
                val payPhrase = if (account.isNotBlank()) "，用 $account 支付" else ""
                if (remark.isNotBlank()) "${remark}花了 $amountStr $currency$payPhrase"
                else "消费 $amountStr $currency$payPhrase"
            }
        }
    }
}
```

### 3.2 ChatActivity.kt — 「稍后再确认」直接存 JSON

**当前代码：**

```kotlin
btnLater?.setOnClickListener {
    // 调 AI 解析 → 存 draft（拆分为每笔一条）
}
```

**改为：**

```kotlin
btnLater?.setOnClickListener {
    // 直接用已有的 JSON 存 draft，不再调 AI
    lifecycleScope.launch {
        try {
            val repo = AccountingDraftRepository(db.accountingDraftDao())
            if (aiResult != null && aiResult.has("bills")) {
                val billsArr = aiResult.getJSONArray("bills")
                for (i in 0 until billsArr.length()) {
                    val billJson = billsArr.getJSONObject(i).toString()
                    val summary = buildSingleBillSummary(billsArr.getJSONObject(i))
                    repo.saveDraft(
                        source = DraftSource.CHAT_IMAGE,
                        sourceMessageId = null,
                        bookName = bookName,
                        payloadJson = billJson,
                        naturalSummary = summary
                    )
                }
            } else if (aiResult != null && aiResult.has("amount")) {
                repo.saveDraft(
                    source = DraftSource.CHAT_IMAGE,
                    sourceMessageId = null,
                    bookName = bookName,
                    payloadJson = aiResult.toString(),
                    naturalSummary = buildSingleBillSummary(aiResult)
                )
            } else {
                // 兜底：存自然语言
                repo.saveDraft(
                    source = DraftSource.CHAT_IMAGE,
                    sourceMessageId = null,
                    bookName = bookName,
                    payloadJson = AccountingDraftPayload.wrapNaturalLanguage(initialDraft),
                    naturalSummary = initialDraft.take(120)
                )
            }
            Utils.toast(this@ChatActivity, getString(R.string.draft_saved_toast))
        } catch (e: Exception) {
            Utils.toast(this@ChatActivity, "保存失败")
        }
    }
    finish(null)
}
```

**关键变化**：`aiResult`（JSON）需要从 Pipeline 传递到 `confirmVisualAccountingDraftInChat` 的回调中。

### 3.3 confirmVisualAccountingDraftInChat — 返回值携带 JSON

**当前签名：**
```kotlin
private suspend fun confirmVisualAccountingDraftInChat(
    summary: String, bookName: String, conversationId: String
): String?
```

**需要改为（或新增重载）：**
```kotlin
// 返回值从 String? 改为 Pair<String?, JSONObject?>?
// 或者用一个 data class
data class DraftConfirmResult(
    val editedText: String?,      // 用户编辑后的文本（null = 取消）
    val originalJson: JSONObject? // AI 原始 JSON（用于直接存 draft）
)
```

这样 Pipeline 可以把 JSON 传进来，`btnLater` 直接用它存 draft。

### 3.4 AccountingDraftInboxActivity.kt — Inbox 确认流程

**已实现（不需要再改）：**
- `saveDraftAsBill` — 已支持结构化 JSON 直接解析 + 自然语言兜底调 AI
- `showEditDraftDialog` — 单击编辑弹窗
- `showBatchConfirmDialog` / `showConfirmPreviewDialog` — 确认预览
- 底部栏动态文案（`onSelectionChanged`）

**需要微调：**
- `saveDraftAsBill` 中，对于新保存的结构化 JSON draft，直接用 `parseBillFromJson` 解析（不调 AI）
- 对于旧的 `natural_language` draft，仍走 `AIService.analyzeAccounting` 兜底

### 3.5 HomeRefreshController.kt — 首页卡片刷新

**已实现（不需要再改）：**

```kotlin
// InvalidationTracker 加入 accounting_drafts 表
val observer = object : InvalidationTracker.Observer("bills", "accounting_drafts") { ... }
```

### 3.6 DraftAdapter.kt + item_accounting_draft.xml — Inbox 卡片展示

**已实现（不需要再改）：**
- 布局：分类名 / 账户+来源+时间 / 备注 / 金额+币种
- 适配器：从结构化 JSON 解析字段展示

### 3.7 dialog_draft_edit.xml — 编辑弹窗

**已实现（不需要再改）。**

---

## 4. 数据流对比

### 改之前（两次 AI 调用）

```
用户发图片
  → AI #1: analyzeReceiptByImage → "烤肉串4.59PLN，散装番茄0.52PLN"
  → 弹窗展示文本
  → 用户点「确认入账」
    → AI #2: analyzeAccounting("烤肉串4.59PLN...") → JSON
    → 入库
  → 用户点「稍后再确认」
    → 存纯文本到 draft
    → [以后] Inbox 确认 → AI #2: analyzeAccounting → JSON → 入库
```

### 改之后（一次 AI 调用）

```
用户发图片
  → AI #1: analyzeScreenAccountingByImage → JSON
  → 本地格式化 → "烤肉串花了 4.59 PLN，用 Visa 支付\n散装番茄花了 0.52 PLN，用 Visa 支付"
  → 弹窗展示文本
  → 用户点「确认入账」（没改文本）
    → 直接用 JSON 入库 ✅ 不调 AI
  → 用户点「确认入账」（改了文本）
    → AI #2: analyzeAccounting(编辑后文本) → 新 JSON → 入库
  → 用户点「稍后再确认」
    → 直接存 JSON 到 draft ✅ 不调 AI
    → [以后] Inbox 确认 → 直接用 JSON 入库 ✅ 不调 AI
```

---

## 5. 边界情况

| 场景 | 处理方式 |
|------|---------|
| AI #1 返回 null（失败） | 弹 Toast 提示，不弹窗 |
| AI #1 返回 JSON 但无 bills/amount | 弹 Toast 提示识别失败 |
| 用户清空文本再点确认 | 视为取消（文本为空 = 取消） |
| 用户只改了几个字 | `editedText != originalText` → 走第二次 AI |
| 用户改完后第二次 AI 失败 | 弹 Toast 提示，不入库 |
| 旧的 natural_language 草稿在 Inbox | `saveDraftAsBill` 检测到 → 调 AI 解析（兜底） |
| 悬浮窗模式图片记账 | 同样改用一次调用（Pipeline 统一处理） |
| `isImageAccountingNaturalLanguage` 开关 | 本次改动不涉及此开关，它控制的是其他逻辑 |

---

## 6. 需要改动的文件清单

| 文件 | 改动内容 | 改动量 |
|------|---------|-------|
| `ChatMessagePipeline.kt` | 草稿确认路径改调 `analyzeScreenAccountingByImage`；新增 `formatDraftDisplayText`；判断文本是否被编辑 | 中 |
| `ChatActivity.kt` | `confirmVisualAccountingDraftInChat` 返回值携带 JSON；`btnLater` 直接存 JSON；`buildSingleBillSummary` 已有 | 中 |
| `HomeRefreshController.kt` | InvalidationTracker 加 `"accounting_drafts"` | 已完成 ✅ |
| `AccountingDraftInboxActivity.kt` | 确认流程 + 编辑弹窗 + 动态底部栏 | 已完成 ✅ |
| `DraftAdapter.kt` | 账单卡片展示 | 已完成 ✅ |
| `item_accounting_draft.xml` | 卡片布局 | 已完成 ✅ |
| `dialog_draft_edit.xml` | 编辑弹窗布局 | 已完成 ✅ |
| `dialog_draft_preview.xml` | 确认预览弹窗布局 | 已完成 ✅ |
| `AccountingDraftRepository.kt` | 新增 `update()` 方法 | 已完成 ✅ |

---

## 7. 验证方案

1. `./gradlew assembleDebug` 编译通过
2. 开启「图片记账补充输入」→ 发小票图片 → 弹窗展示格式化文本（不带分类）
3. 不改文本 → 点「确认入账」→ 账单入库 → 日志里只有一次 AI 调用
4. 改文本 → 点「确认入账」→ 账单入库 → 日志里有两次 AI 调用
5. 点「稍后再确认」→ 首页「待确认账单」自动出现 → Inbox 展示账单卡片 → 确认入库
6. 关闭「图片记账补充输入」→ 发图片 → 直接入库（Direct 路径不受影响）
