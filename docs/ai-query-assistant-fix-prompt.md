# AI 查询助手修复提示词

> 目标读者：负责修复当前实现的 AI 编程代理或开发者
> 当前基线：最新提交 `2fdd992 refactor: 查询路由从本地规则优先改为 AI Router 四分类`
> 主需求文档：`docs/ai-query-assistant-implementation-prompt.md`
> 本文档目的：针对当前实现偏差，给出可直接执行的修复要求

---

## 1. 当前状态总结

当前实现已经完成了一部分正确方向：

- 新增了 AI Router 四分类：
  - `ACCOUNTING_CREATE`
  - `ACCOUNTING_QUERY`
  - `GENERAL_CHAT`
  - `UNSUPPORTED_WRITE`
- `ACCOUNTING_QUERY` 后会调用 `AIService.extractQueryDraft()` 生成查询草稿 JSON。
- 没有恢复完整 Agent 系统。
- 没有引入写操作 Tool。

但是仍然存在几个不符合需求的关键问题：

1. 有 active query draft 时，多轮修正仍然在 AI Router 之前走本地规则。
2. 查询草稿卡片仍然不能手动编辑关键词和日期。
3. 统计金额固定只统计支出/退款，没有按草稿的 `billType` 计算。
4. 点击旧草稿卡片时，会执行全局 `currentDraft`，不是该卡片自己的草稿。
5. 查看明细/跳转统计页会丢掉关键词、分类、资产、账单类型等筛选条件。
6. `excludeFromStats` 没有在统计金额时排除。
7. 测试还在覆盖旧本地 parser，没有覆盖真正的 AI Router 链路和查询执行口径。

本次修复目标是解决以上问题。

---

## 2. 最高优先级原则

### 2.1 不要本地规则优先触发查询或修正

正式产品逻辑必须是：

```text
用户输入
  -> AI Router
      -> ACCOUNTING_CREATE：现有记账流程
      -> ACCOUNTING_QUERY：AI Query Extractor 生成/更新查询草稿
      -> GENERAL_CHAT：普通聊天
      -> UNSUPPORTED_WRITE：拒绝写操作
```

不要让本地 `QueryDraftLocalParser` 在 Router 之前根据关键词抢先触发查询或修正。

当前代码中这个逻辑是错误的：

```kotlin
if (queryDraftMgr.hasActiveDraft()) {
    val correction = queryDraftMgr.detectExecutionCommand(userText)
    ...
    queryDraftMgr.applyCorrection(userText, queryContext)
    ...
}

val routerResult = AIService.classifyRouterIntent(...)
```

必须修改。

### 2.2 AI 可以判断和提取参数，但不能生成事实结果

AI 允许：

- 判断是否查询
- 提取查询参数
- 根据用户后续输入更新查询草稿

AI 不允许：

- 直接回答金额
- 直接回答笔数
- 直接生成账单明细
- 修改数据库

金额、笔数、明细、排行必须来自本地数据库查询。

### 2.3 查询卡片必须可编辑

查询草稿不是只读展示。它必须可编辑：

- 点击关键词：弹出输入框，默认当前关键词，保存后更新该草稿
- 点击日期：弹出日期范围选择器，默认当前日期范围，保存后更新该草稿
- 至少支持修改账单类型和账本范围，可以用简单弹窗/菜单实现

---

## 3. 修复任务一：改掉 active draft 的本地规则优先修正

### 3.1 当前问题

当前 `ChatMessagePipeline.kt` 在有 `currentDraft` 时，先走：

- `queryDraftMgr.detectExecutionCommand(userText)`
- `queryDraftMgr.applyCorrection(userText, queryContext)`

这两个都依赖 `QueryDraftLocalParser.detectCorrection()`，属于本地规则优先。

这会导致：

- 用户普通聊天可能被误判成查询修正
- 用户输入“看电影推荐”可能被正则识别成“改关键词为电影推荐”
- 与“不要本地路由”的产品原则冲突

### 3.2 要求的新逻辑

所有纯文本输入都先进入 AI Router。

推荐流程：

```kotlin
val routerResult = AIService.classifyRouterIntent(context, userText)

when (routerResult.intent) {
    "GENERAL_CHAT" -> normalChat()
    "UNSUPPORTED_WRITE" -> rejectWrite()
    "ACCOUNTING_CREATE" -> continueAccountingFlow()
    "ACCOUNTING_QUERY" -> {
        val aiDraftJson = AIService.extractQueryDraft(
            ctx = context,
            userText = userText,
            existingDraft = queryDraftMgr.currentDraft
        )
        if (existingDraft != null && aiDraftJson.intent == "UPDATE_DRAFT") {
            update current draft from AI JSON
        } else if (aiDraftJson.intent == "QUERY_DRAFT") {
            create new draft
        }
    }
}
```

如果不想改 `extractQueryDraft()` 签名，也可以把当前草稿序列化进 prompt 的 userText 中，但更推荐显式加参数：

```kotlin
suspend fun extractQueryDraft(
    ctx: Context,
    userText: String,
    existingDraft: QueryDraft? = null
): JSONObject?
```

### 3.3 Query Extractor 需要支持更新草稿

当存在 active draft 时，Extractor prompt 必须包含当前草稿条件，例如：

```json
{
  "currentDraft": {
    "keyword": "苹果",
    "timeRange": {"label": "本月"},
    "billType": "EXPENSE",
    "bookScope": "CURRENT"
  },
  "userText": "不是苹果，是水果"
}
```

AI 应输出：

```json
{
  "intent": "UPDATE_DRAFT",
  "slots": {
    "keyword": "水果"
  },
  "confidence": 0.9
}
```

App 应把 `UPDATE_DRAFT` 合并到当前草稿，未出现的字段保持原值。

### 3.4 保留本地执行命令可以，但必须非常窄

“统计金额”“搜索账单”“取消”这类按钮已经存在，因此文本命令不是必须支持。

如果保留文本命令，只允许完全匹配：

- `统计`
- `统计金额`
- `搜索`
- `搜索账单`
- `取消`

不要使用包含匹配，例如 `contains("统计一下")`、`contains("搜索一下")`。

并且这类命令只在 active draft 下生效。

---

## 4. 修复任务二：查询草稿卡片必须可手动编辑

### 4.1 当前问题

`ChatAdapters.QueryDraftVH` 目前只绑定展示和三个按钮：

- 统计金额
- 搜索账单
- 取消

关键词行、日期行、类型行、账本行没有点击编辑逻辑。

### 4.2 需要新增回调

在 `ChatAdapter` 构造参数中新增回调：

```kotlin
private val onQueryDraftEditKeyword: (ChatDisplayItem) -> Unit = {},
private val onQueryDraftEditDate: (ChatDisplayItem) -> Unit = {},
private val onQueryDraftEditBillType: (ChatDisplayItem) -> Unit = {},
private val onQueryDraftEditBookScope: (ChatDisplayItem) -> Unit = {}
```

在 `QueryDraftVH.bind()` 中绑定：

```kotlin
rowKeyword.setOnClickListener { onQueryDraftEditKeyword(item) }
rowTime.setOnClickListener { onQueryDraftEditDate(item) }
rowBillType.setOnClickListener { onQueryDraftEditBillType(item) }
rowBook.setOnClickListener { onQueryDraftEditBookScope(item) }
```

如果当前布局没有 `row_bill_type` / `row_book` id，要补上。

### 4.3 ChatActivity 中实现编辑关键词

点击关键词时：

- 弹出输入框
- 默认值为 `item.queryDraft.keyword ?: ""`
- 用户确认后，更新该卡片对应的 `QueryDraft`
- 同步更新 `queryDraftManager.currentDraft`，如果用户编辑的是当前草稿
- 刷新 RecyclerView 对应 item

建议新增方法：

```kotlin
private fun updateQueryDraftItem(item: ChatDisplayItem, newDraft: QueryDraft)
```

不要只更新全局 `currentDraft`，也要更新 `displayMessages` 中这张卡片的 `queryDraft`。

### 4.4 ChatActivity 中实现编辑日期范围

使用已经存在的：

```kotlin
ElegantDatePickerSheet.showRange(...)
```

点击日期时：

- `initialStartMillis = draft.timeRange?.startMillis`
- `initialEndMillis = draft.timeRange?.endMillis`
- 用户确认后生成新的 `QueryTimeRange`
- label 可用：
  - 如果是自定义范围：`yyyy-MM-dd 至 yyyy-MM-dd`
  - 如果保留原 label 且范围没变，可以保留原 label

更新该卡片的 `QueryDraft`。

### 4.5 编辑类型

可以用简单单选弹窗：

- 支出 `EXPENSE`
- 收入 `INCOME`
- 转账 `TRANSFER`
- 还款 `REPAYMENT`
- 退款 `REFUND`
- 全部 `ANY`

确认后更新草稿。

### 4.6 编辑账本范围

第一版至少支持：

- 当前账本 `CURRENT`
- 全部账本 `ALL`

如果支持指定账本 `SPECIFIC`，需要列出 `QueryContext.availableBooks`。

---

## 5. 修复任务三：点击卡片必须使用该卡片自己的草稿

### 5.1 当前问题

`onQueryDraftStats(item)` 和 `onQueryDraftSearch(item)` 只检查 `item.queryDraft != null`，但执行时调用：

```kotlin
queryDraftManager.executeStats(context)
queryDraftManager.executeSearch(context)
```

而 `QueryDraftManager` 内部使用 `currentDraft`。

如果聊天里存在多个草稿卡片，点击旧卡片会执行最新 currentDraft。

### 5.2 要求

新增按指定草稿执行的方法：

```kotlin
suspend fun executeStats(draft: QueryDraft, context: QueryContext): QueryResult
suspend fun executeSearch(draft: QueryDraft, context: QueryContext): List<Bill>
```

旧方法可以保留：

```kotlin
suspend fun executeStats(context: QueryContext): QueryResult? =
    currentDraft?.let { executeStats(it, context) }
```

ChatActivity 必须改为：

```kotlin
val draft = item.queryDraft ?: return
val result = withContext(Dispatchers.IO) {
    queryDraftManager.executeStats(draft, context)
}
```

搜索同理。

### 5.3 QueryResult 必须引用被执行的草稿

结果中的：

```kotlin
QueryResult(draft = draft, ...)
```

必须使用执行时传入的草稿，而不是 `currentDraft!!`。

---

## 6. 修复任务四：统计金额必须按 billType 正确计算

### 6.1 当前问题

当前 `executeStats()` 写死：

```kotlin
val expenseBills = bills.filter {
    (it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND) ||
        it.subType == Bill.SUBTYPE_REFUND
}
val totalAmount = expenseBills.sumOf { it.amount }
```

这会导致：

- 查询收入时结果为 0
- 查询转账时结果为 0
- 查询全部类型时只算支出
- 查询退款口径混乱

### 6.2 要求

`loadAndFilterBills()` 已经按 `draft.billType` 过滤。`executeStats()` 不要再次强行过滤成支出。

建议：

```kotlin
val statBills = bills.filterNot { it.excludeFromStats }
val totalAmount = statBills.sumOf { it.amount }
val billCount = statBills.size
```

如果 `draft.billType == ANY`，可以：

- 第一版直接合计所有匹配账单金额，但 UI 必须标注“全部类型合计”
- 更好：在结果里展示支出/收入/转账分项

最低要求：不要把收入/转账误算为 0。

### 6.3 退款口径

退款应遵循项目现有统计口径。

如果当前项目把退款作为 `subType == Bill.SUBTYPE_REFUND`，请明确处理：

- 查询 `REFUND`：只统计退款账单
- 查询 `EXPENSE`：不应把退款作为普通支出加总
- 查询 `ANY`：可以展示匹配总额，但需避免误导

不要在 `EXPENSE` 统计里把退款金额加到支出金额里，除非项目统计页已经这么做且有明确业务约定。

---

## 7. 修复任务五：统计必须尊重 excludeFromStats

### 7.1 要求

统计金额时必须排除：

```kotlin
bill.excludeFromStats == true
```

推荐做法：

- `executeStats()` 中排除 `excludeFromStats`
- `executeSearch()` 是否排除可以按产品决定：
  - 搜索明细可以展示所有匹配账单
  - 但结果卡片里的统计金额必须只统计未排除账单

### 7.2 测试

必须添加测试：

- 一笔普通支出 100
- 一笔 `excludeFromStats=true` 支出 999
- 统计金额应为 100，不是 1099

---

## 8. 修复任务六：查看明细必须带完整筛选条件

### 8.1 当前问题

`ChatActivity.onQueryResultViewDetails()` 构造了完整 `QuerySlots`，但 `QueryNavigator.openStatsPage()` 发布给统计页的 `StatsExternalQueryFilter` 只包含：

- startMillis
- endMillis
- label
- bookName
- currency

关键词、分类、资产、账单类型都丢了。

### 8.2 要求

二选一：

#### 方案 A：打开专门的账单搜索结果页

优先推荐。

点击“查看明细”时，打开能展示完整 bill list 的页面或 BottomSheet，并传入完整筛选条件：

- keyword
- categoryId / categoryName
- assetId / assetName
- timeRange
- billType
- bookScope / bookName

#### 方案 B：扩展 StatsExternalQueryFilter

如果继续跳转统计页，则扩展：

```kotlin
data class StatsExternalQueryFilter(
    val startMillis: Long?,
    val endMillis: Long?,
    val label: String?,
    val bookName: String?,
    val currency: String?,
    val keyword: String?,
    val categoryId: Long?,
    val categoryName: String?,
    val assetId: Long?,
    val assetName: String?,
    val billType: QueryBillType?
)
```

并确保 Stats 页面实际使用这些筛选条件，而不是只接收字段。

### 8.3 验收

用户查询：

```text
这个月苹果花了多少钱
```

点击“查看明细”后，明细页只能展示匹配“苹果”的账单，不能展示整个本月所有账单。

---

## 9. 修复任务七：更新测试

当前新增测试主要是 `QueryDraftLocalParserTest`，但这个 parser 不应该是正式入口。

必须新增或调整测试覆盖：

### 9.1 Router 行为测试

如果 AIService 不方便直接单测网络调用，至少把 Router JSON 解析逻辑抽成纯函数测试：

- `{"intent":"ACCOUNTING_QUERY"}` -> ACCOUNTING_QUERY
- 未知 intent -> GENERAL_CHAT
- malformed JSON -> fallback 不崩溃

### 9.2 Query Extractor JSON 转 QueryDraft 测试

测试 `QueryDraftManager.createFromAiExtract()`：

- AMOUNT_TOTAL + keyword + timeRange
- INCOME billType
- ALL bookScope
- categoryName 解析到 categoryId
- assetName 解析到 assetId
- invalid queryType 回退或拒绝

### 9.3 执行口径测试

给 `QueryDraftManager` 或可抽出的纯函数补测试：

- `EXPENSE` 只统计支出
- `INCOME` 统计收入，不返回 0
- `TRANSFER` 统计转账，不返回 0
- `REFUND` 只统计退款
- `ANY` 不只统计支出
- `excludeFromStats` 不计入统计金额

### 9.4 卡片执行测试

如果 UI 测试成本太高，至少把执行方法改成可单测：

- `executeStats(draftA)` 和 `executeStats(draftB)` 使用各自 draft
- 不依赖 `currentDraft`

---

## 10. 修复后最终验收清单

修复完成后必须满足：

1. 新建查询草稿只在 AI Router 输出 `ACCOUNTING_QUERY` 后发生。
2. 有 active draft 时，用户新输入仍然先过 AI Router，不走本地规则优先修正。
3. 多轮修正由 AI Query Extractor 输出 `UPDATE_DRAFT`，App 合并更新草稿。
4. 查询草稿卡片可以点击编辑关键词。
5. 查询草稿卡片可以点击编辑日期范围。
6. 查询草稿卡片可以修改账单类型。
7. 查询草稿卡片可以修改账本范围。
8. 点击旧草稿按旧草稿查询，点击新草稿按新草稿查询。
9. 统计金额按 `billType` 正确计算。
10. `excludeFromStats` 不计入统计金额。
11. 点击“查看明细”保留关键词、分类、资产、类型、时间、账本等完整筛选条件。
12. AI 不直接生成金额、笔数或明细。
13. 不恢复完整 Agent。
14. 不新增写操作 Tool。
15. 原有记账流程不被查询功能破坏。

---

## 11. 不要做的事

不要为了快速修复而做以下事情：

- 不要把 `QueryDraftLocalParser` 重新作为正式入口。
- 不要在 Router 前用本地关键词判断是否查询。
- 不要让 AI 直接回答金额。
- 不要把写操作包装成确认卡片。
- 不要恢复 `docs/archived-agent-code/agent-src/` 里的完整 Agent。
- 不要只修 prompt，不修本地执行口径。
- 不要只更新 UI 文案，不实现点击编辑。

---

## 12. 推荐修复顺序

1. 改 `ChatMessagePipeline`：所有纯文本先走 AI Router，移除 active draft 的本地规则优先修正。
2. 改 `AIService.extractQueryDraft()`：支持传入 existingDraft，并让 prompt 支持 `UPDATE_DRAFT`。
3. 改 `QueryDraftManager`：支持 `createFromAiExtract()` 新建草稿和 `updateFromAiExtract()` 合并更新草稿。
4. 改 `QueryDraftManager`：新增 `executeStats(draft, context)` / `executeSearch(draft, context)`。
5. 修统计口径：按 `billType` 统计，排除 `excludeFromStats`。
6. 改 `ChatActivity`：卡片点击执行 item 自己的 draft。
7. 改 `ChatAdapter` + `ChatActivity`：实现关键词、日期、类型、账本范围手动编辑。
8. 改 `QueryNavigator` 或明细入口：查看明细保留完整筛选条件。
9. 补测试。

