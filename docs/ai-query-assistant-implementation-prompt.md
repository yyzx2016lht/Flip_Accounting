# AI 查询助手实现提示词

> 目标读者：负责实现功能的 AI 编程代理或开发者
> 项目：敲敲记账 / TapAccounting / FlipAccounting-AI
> 需求类型：功能优化，聊天内 AI 查询
> 核心原则：AI 只生成可确认的查询草稿，本地代码执行查询和计算结果

---

## 1. 背景与目标

当前项目是一款 AI 驱动的 Android 记账应用。应用已经支持聊天记账、图片/语音/截屏记账、账单搜索、统计、资产、账本、多币种等能力。

项目中曾经存在一套完整的 Agent 系统，位于：

- `docs/archived-agent-code/agent-docs/`
- `docs/archived-agent-code/agent-src/`
- `docs/archived-agent-code/agent-test/`

这套系统包含 `AgentTool`、`AgentSkill`、`ChatAgentOrchestrator` 以及大量 Tool，例如账单创建、删除、修改、资产管理、统计查询、备份、设置等。该系统已经废弃，不应直接恢复。

本次要实现的是一个收窄后的 AI 查询助手，不是完整 Agent。

目标是让用户在聊天界面用自然语言发起查询，例如：

- “这个月买苹果花了多少钱？”
- “上个月餐饮支出多少？”
- “昨天花了什么？”
- “最近一笔账单是什么？”
- “这个月支出最多的分类是什么？”
- “微信这个月花了多少钱？”

系统应该让 AI 理解用户想查什么，但不要让 AI 直接查账、算钱或编造事实。正确流程是：

```text
用户自然语言
  -> AI/本地规则解析成查询草稿
  -> 聊天界面展示可编辑查询卡片
  -> 用户确认、修改或继续对话修正
  -> 本地 Room/DAO 执行查询
  -> 本地模板生成结果
  -> 可选跳转到账单搜索页、统计页或日历页
```

核心产品形态：

**AI 查询不是黑盒回答，而是生成一个可见、可编辑、可确认、可执行的查询草稿。**

---

## 2. 强制边界

### 2.1 必须做

实现聊天内查询能力，支持：

- 查询草稿卡片
- 关键词确认与编辑
- 日期范围确认与编辑
- 账单类型确认与编辑
- 账本范围确认与编辑
- 资产/分类条件确认与编辑
- 本地执行搜索或统计
- 多轮对话修正查询草稿
- 查询结果卡片
- 必要时跳转现有页面

### 2.2 不要做

不要恢复完整 Agent 系统。

不要实现以下写操作：

- 新增账单
- 修改账单
- 删除账单
- 批量删除
- 移动账单到账本
- 修改资产
- 删除资产
- 修改分类
- 删除分类
- 修改设置
- 导入/恢复备份
- 配置 API Key 或云备份

聊天内查询助手只能做：

- READ：只读查询
- NAV：跳转到现有页面，或者打开筛选后的结果页

不得做 WRITE / DESTRUCTIVE / SENSITIVE / SYSTEM 操作。

### 2.3 AI 的职责边界

AI 只允许做：

- 判断用户是不是查询意图
- 判断查询类型
- 提取查询参数
- 在用户修正时更新查询草稿
- 可选：对本地查询结果做非事实性解释，但不能生成金额、笔数、分类排行等事实数据

AI 不允许做：

- 直接回答金额
- 直接回答笔数
- 直接回答排行榜
- 直接决定修改数据库
- 直接执行 SQL
- 编造没有来自本地查询的数据

金额、笔数、账单列表、分类排行、资产统计必须由本地代码计算。

---

## 3. 用户体验需求

### 3.1 基本交互

用户在聊天界面输入：

```text
这个月买苹果花了多少钱？
```

系统不要直接回答：

```text
你这个月苹果花了 123 元。
```

而应该先展示查询草稿卡片：

```text
查询账单

关键词：苹果
时间：2026-06-01 至 2026-06-23（本月）
类型：支出
范围：当前账本

[改关键词] [改日期] [搜索账单] [统计金额]
```

用户可以：

- 点击“统计金额”：本地计算总金额和笔数，在聊天中展示结果
- 点击“搜索账单”：打开或展示账单列表
- 点击“改关键词”：弹出输入框，默认值为“苹果”
- 点击“改日期”：弹出日期范围选择器
- 继续发消息：“不是苹果，是水果”，系统更新草稿关键词为“水果”
- 继续发消息：“查全部账本”，系统更新范围为全部账本
- 继续发消息：“不是这个月，是上个月”，系统更新日期范围为上个月

### 3.2 查询草稿卡片

查询草稿卡片必须展示所有会影响结果的条件。

建议字段：

```text
查询账单

查询方式：统计金额 / 搜索账单 / 分类排行 / 最近账单 / 趋势
关键词：苹果
分类：未指定 / 餐饮 - 水果
资产：未指定 / 微信 / 招商信用卡
时间：2026-06-01 至 2026-06-23（本月）
类型：支出 / 收入 / 转账 / 还款 / 退款 / 全部
账本：当前账本 / 全部账本 / 指定账本

[编辑条件] [搜索账单] [统计金额] [取消]
```

不是所有字段都必须每次显示完整值，但必须保证用户能看见并修改关键条件。

最低要求：

- 关键词
- 时间范围
- 账单类型
- 账本范围
- 执行动作

### 3.3 日期编辑

日期必须可见且可编辑。

用户问：

```text
上个月苹果花了多少钱？
```

卡片应显示：

```text
时间：2026-05-01 至 2026-05-31（上个月）
```

用户点击“改日期”后：

- 打开日期范围选择器
- 默认选中当前草稿的开始日期和结束日期
- 用户确认后更新草稿卡片

自然语言日期存在歧义时，必须让用户确认。

例如：

- “最近一个月”：过去 30 天
- “这个月”：自然月从 1 号到今天
- “上个月”：上个自然月
- “上周”：按照应用设置的每周起始日
- “春节期间”：如果本地解析不了，要求用户手动选日期

### 3.4 关键词编辑

用户问：

```text
这个月买苹果花了多少钱？
```

AI 可以先提取关键词“苹果”。

卡片里应有：

```text
关键词：苹果
```

如果用户认为系统理解错了，可以：

- 点击关键词字段，弹出输入框
- 直接在聊天里说：“不是苹果，是水果”

更新后卡片变为：

```text
关键词：水果
```

### 3.5 分类与关键词的关系

“苹果”可能是：

- 备注关键词
- 商品名
- 分类“水果”
- Apple 产品

所以不要强行把“苹果”映射成分类。

默认行为：

- 如果命中了明确分类，例如“餐饮”“交通”“水果”，可以填入分类字段
- 如果不确定，优先放进关键词字段
- 关键词匹配应搜索备注、分类名、账户名、目标账户名、账本名等已有可搜索字段

用户可以手动把关键词改成分类查询。

建议 UI：

```text
关键词：苹果
分类：未指定

[把关键词作为分类] [选择分类]
```

如果实现复杂，第一版可以只做关键词，不做分类选择器，但后续要支持分类。

### 3.6 搜索账单与统计金额必须分开

“搜索账单”回答的是：

```text
有哪些账单匹配这些条件？
```

“统计金额”回答的是：

```text
这些账单一共多少钱？
```

用户说“多少钱”“花了多少”“支出多少”时，默认执行意图是统计金额。

但草稿卡片仍然应该提供“搜索账单”按钮，方便用户核对明细。

### 3.7 多轮修正

系统必须支持对上一个查询草稿进行多轮修正。

例子：

```text
用户：这个月买苹果花了多少钱？
系统：展示查询草稿，关键词=苹果，时间=本月，类型=支出

用户：不是苹果，是水果
系统：更新草稿，关键词=水果

用户：查上个月
系统：更新草稿，时间=上个月

用户：全部账本
系统：更新草稿，账本=全部账本

用户：搜索
系统：执行搜索账单
```

多轮修正只应该影响当前未执行或最近一次查询草稿。不要影响普通记账流程。

---

## 4. 三阶段实现模式

本功能分三次实现，必须按顺序推进。不要一次性恢复复杂 Agent。

### 4.1 第一阶段：本地规则 + 查询草稿 MVP

目标：

不用依赖 LLM，先用本地规则识别 10 个高频查询，生成查询草稿卡片。

支持查询：

1. 本月花了多少
2. 今天花了多少
3. 昨天花了多少
4. 本周花了多少
5. 上个月花了多少
6. 某关键词花了多少，例如“苹果花了多少”
7. 某分类花了多少，例如“餐饮花了多少”
8. 最近一笔是什么
9. 最近 N 笔账单
10. 有没有买过某关键词

第一阶段必须实现：

- 本地时间解析：今天、昨天、本周、上周、本月、上个月、今年
- 关键词提取
- 账单类型识别：支出、收入、转账、还款、退款、全部
- 查询草稿数据结构
- 查询草稿卡片 UI
- 点击“统计金额”执行本地统计
- 点击“搜索账单”执行本地搜索或跳转搜索页
- 点击“改日期”打开日期范围选择器
- 点击“改关键词”打开输入框

第一阶段不需要：

- LLM 兜底
- 复杂分类 disambiguation
- 趋势图
- 环比同比
- 多模型配置

验收标准：

- 输入“这个月买苹果花了多少钱”，展示草稿，关键词=苹果，时间=本月，类型=支出
- 点击统计金额后，金额和笔数来自本地查询
- 点击搜索账单后，展示或跳转到匹配账单列表
- 用户可以手动修改关键词和日期
- 输入“删除上一笔”不会进入查询助手，也不会执行删除

### 4.2 第二阶段：AI 参数提取 + 多轮修正

目标：

在第一阶段稳定后，引入 AI 作为参数提取器，处理本地规则无法覆盖的自然语言。

AI 输出必须是固定 JSON，不允许自由回答。

建议 Schema：

```json
{
  "intent": "QUERY_DRAFT",
  "queryType": "AMOUNT_TOTAL",
  "slots": {
    "keyword": "苹果",
    "categoryName": null,
    "assetName": null,
    "bookName": null,
    "bookScope": "CURRENT",
    "billType": "EXPENSE",
    "timeRange": {
      "startMillis": 1780264800000,
      "endMillis": 1782251999999,
      "label": "本月"
    },
    "aggregation": "TOTAL"
  },
  "confidence": 0.82,
  "clarifyQuestion": null
}
```

允许的 `queryType`：

- `AMOUNT_TOTAL`：统计金额
- `BILL_LIST`：搜索/列出账单
- `LATEST_BILL`：最近一笔
- `RECENT_BILLS`：最近 N 笔
- `EXISTS_KEYWORD`：是否存在某关键词
- `TOP_CATEGORIES`：分类排行
- `PERIOD_COMPARE`：时间段对比
- `BOOK_SUMMARY`：账本概览
- `ASSET_SUMMARY`：资产相关统计

允许的 `billType`：

- `EXPENSE`
- `INCOME`
- `TRANSFER`
- `REPAYMENT`
- `REFUND`
- `ANY`

允许的 `bookScope`：

- `CURRENT`
- `ALL`
- `SPECIFIC`

第二阶段必须实现：

- 本地规则优先，AI 兜底
- AI 输出 JSON 解析和校验
- AI 输出字段白名单校验
- AI 无效输出时回退本地规则或要求用户澄清
- 多轮修正当前查询草稿
- “不是 X，是 Y”修正关键词
- “不是这个月，是上个月”修正时间
- “查全部账本”修正账本范围
- “只看收入/只看支出”修正账单类型

验收标准：

- AI 不能直接生成金额回复
- AI 输出 JSON 解析失败时，不能崩溃
- AI 输出未知 queryType 时，返回澄清或不支持
- 用户连续修正三次后，草稿状态正确
- 修正后点击统计，使用最新草稿条件查询

### 4.3 第三阶段：结果卡片 + 深度查询体验

目标：

把查询结果做成更好的产品体验，而不是只输出一句文本。

第三阶段支持：

- 查询结果卡片
- 明细预览
- Top 分类
- 时间段对比
- 趋势摘要
- 结果页跳转
- 查询历史
- 常用问题建议

结果卡片示例：

```text
统计结果

条件：
关键词：苹果
时间：2026-06-01 至 2026-06-23
类型：支出
账本：当前账本

结果：
共 5 笔，合计 ¥86.50

最大一笔：
06-12 超市苹果 ¥28.90

[查看明细] [改条件] [再问一句]
```

第三阶段必须实现：

- 查询结果中展示本次查询条件
- 展示金额、笔数、最多 3 条代表账单
- 点击“查看明细”进入完整列表
- 支持“和上个月比呢”这类基于上一次查询的追问
- 支持“换成餐饮看看”这类条件替换
- 支持“按分类排行”改变 aggregation

验收标准：

- 用户能从结果卡片清楚知道系统查了什么
- 用户能返回修改条件
- 用户能进入明细页核对
- 结果中的金额、笔数、账单列表全部来自本地查询
- AI 润色不得改变事实数字

---

## 5. 推荐数据结构

### 5.1 QueryDraft

新增或复用现有查询模型，建议至少包含：

```kotlin
data class QueryDraft(
    val id: String,
    val queryType: QueryType,
    val keyword: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val assetId: Long? = null,
    val assetName: String? = null,
    val bookScope: BookScope = BookScope.CURRENT,
    val bookName: String? = null,
    val billType: QueryBillType = QueryBillType.EXPENSE,
    val timeRange: QueryTimeRange? = null,
    val aggregation: QueryAggregation = QueryAggregation.TOTAL,
    val sourceText: String,
    val confidence: Double = 0.0,
    val createdAt: Long,
    val updatedAt: Long
)
```

### 5.2 QueryType

```kotlin
enum class QueryType {
    AMOUNT_TOTAL,
    BILL_LIST,
    LATEST_BILL,
    RECENT_BILLS,
    EXISTS_KEYWORD,
    TOP_CATEGORIES,
    PERIOD_COMPARE,
    BOOK_SUMMARY,
    ASSET_SUMMARY
}
```

### 5.3 BookScope

```kotlin
enum class BookScope {
    CURRENT,
    ALL,
    SPECIFIC
}
```

### 5.4 QueryResult

```kotlin
data class QueryResult(
    val draft: QueryDraft,
    val totalAmount: Double? = null,
    val billCount: Int = 0,
    val billsPreview: List<BillPreview> = emptyList(),
    val topCategories: List<CategoryAmount> = emptyList(),
    val compare: CompareResult? = null,
    val generatedAt: Long
)
```

---

## 6. 推荐实现位置

当前项目已有：

- `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryModels.kt`
- `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryPlanner.kt`
- `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryExecutor.kt`
- `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryContextBuilder.kt`
- `app/src/main/java/com/taostudio/tapaccounting/chat/query/RoomQueryBillSource.kt`
- `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryNavigator.kt`

优先复用这些文件，不要新建完整 Agent 框架。

建议新增或改造：

- `QueryDraft` / `QueryDraftState`
- `QueryDraftRenderer` 或聊天消息类型渲染逻辑
- `QueryDraftController`
- `QueryDraftActionHandler`
- `QueryResultRenderer`
- 必要的 DAO 查询方法

如果现有 `QueryAction` 和 `QuerySlots` 足够，可以在其上扩展，不必重复建模。

---

## 7. 查询执行规则

### 7.1 金额统计

统计金额时：

- 支出默认只统计 `Bill.TYPE_EXPENSE`
- 排除退款或按现有业务规则处理退款
- 尊重 `excludeFromStats`
- 尊重账本范围
- 尊重时间范围
- 尊重关键词、分类、资产筛选
- 多币种时第一版可按账单原金额分别展示，或按项目已有折算逻辑展示 CNY 合计；必须明确口径

### 7.2 关键词匹配

关键词应至少匹配：

- `remark`
- `categoryName`
- `accountName`
- `toAccountName`
- `bookName`
- `currency`

不要只匹配备注。

### 7.3 日期范围

所有日期范围必须转成 `[startMillis, endMillis]`。

自然语言时间解析优先使用项目已有：

- `AiTimeRangeParser`

如果无法解析：

- 展示草稿但标记时间未指定
- 要求用户选择日期
- 不要默认查全部历史，除非用户明确说“所有时间”

### 7.4 账本范围

默认查当前账本。

用户明确说：

- “全部账本”
- “所有账本”
- “全账本”

则查询所有账本。

用户提到具体账本名时，查指定账本。

### 7.5 不支持写操作

如果用户说：

- “删除上一笔”
- “把苹果那笔改成水果”
- “帮我记一笔”
- “清空本月账单”

查询助手应返回：

```text
这个请求涉及新增、修改或删除账单。我现在只能帮你查询和打开相关页面。
```

不得生成写操作草稿。

---

## 8. AI 提示词要求

如果实现第二阶段 AI 解析，请使用类似下面的系统提示词。

```text
你是 TapAccounting 的查询参数提取器。

你的任务不是回答用户问题，而是把用户的自然语言转换为一个固定 JSON 查询草稿。

你不能生成金额、笔数、分类排行、账单列表等事实结果。
所有事实结果都由 App 本地数据库查询得到。

只允许输出 JSON，不要输出 Markdown、解释、代码块或额外文本。

如果用户请求新增、修改、删除、导入、恢复、设置、授权等写操作或高风险操作，输出：
{"intent":"UNSUPPORTED","reason":"WRITE_OR_UNSAFE_OPERATION"}

如果用户是在修正上一条查询草稿，输出需要更新的字段。

允许的 queryType:
- AMOUNT_TOTAL
- BILL_LIST
- LATEST_BILL
- RECENT_BILLS
- EXISTS_KEYWORD
- TOP_CATEGORIES
- PERIOD_COMPARE
- BOOK_SUMMARY
- ASSET_SUMMARY

允许的 billType:
- EXPENSE
- INCOME
- TRANSFER
- REPAYMENT
- REFUND
- ANY

允许的 bookScope:
- CURRENT
- ALL
- SPECIFIC

输出格式：
{
  "intent": "QUERY_DRAFT" | "UPDATE_DRAFT" | "CLARIFY" | "UNSUPPORTED",
  "queryType": "AMOUNT_TOTAL",
  "slots": {
    "keyword": null,
    "categoryName": null,
    "assetName": null,
    "bookName": null,
    "bookScope": "CURRENT",
    "billType": "EXPENSE",
    "timeRange": {
      "startMillis": null,
      "endMillis": null,
      "label": null
    },
    "aggregation": "TOTAL"
  },
  "confidence": 0.0,
  "clarifyQuestion": null
}
```

App 侧必须校验 AI 输出，不得信任原始 JSON。

---

## 9. UI 验收场景

### 场景 1：关键词金额查询

输入：

```text
这个月买苹果花了多少钱？
```

期望：

- 展示查询草稿
- 关键词=苹果
- 时间=本月
- 类型=支出
- 默认动作=统计金额
- 用户可点击统计金额
- 结果金额来自本地查询

### 场景 2：用户改关键词

前置：已有草稿，关键词=苹果。

输入：

```text
不是苹果，是水果
```

期望：

- 不执行查询
- 更新当前草稿
- 关键词=水果
- 时间等其他条件保持不变

### 场景 3：用户改日期

前置：已有草稿，时间=本月。

输入：

```text
查上个月
```

期望：

- 更新当前草稿时间为上个月自然月
- 其他条件保持不变

### 场景 4：用户点击日期

前置：已有草稿。

操作：

- 点击“改日期”
- 选择新的开始日期和结束日期

期望：

- 草稿卡片日期更新
- 点击统计金额后使用新日期

### 场景 5：搜索和统计分离

输入：

```text
这个月苹果账单
```

期望：

- 默认动作可以是搜索账单
- 仍提供统计金额按钮
- 搜索账单展示明细
- 统计金额展示合计和笔数

### 场景 6：写操作拒绝

输入：

```text
删除这个月苹果的账单
```

期望：

- 不生成查询草稿
- 不执行删除
- 明确回复只能查询，不能删除

### 场景 7：最近一笔

输入：

```text
最近一笔是什么？
```

期望：

- 可直接执行查询或展示简单确认卡片
- 返回最近一笔账单，包含日期、类型、金额、备注/分类
- 结果来自本地数据库

### 场景 8：分类排行

输入：

```text
这个月支出最多的分类是什么？
```

期望：

- 展示或执行 Top 分类查询
- 返回前 3-5 个分类及金额
- 金额来自本地统计

### 场景 9：账本范围

输入：

```text
全部账本这个月苹果花了多少？
```

期望：

- 账本范围=全部账本
- 草稿卡片明确展示“全部账本”
- 执行时跨账本查询

### 场景 10：无法理解

输入：

```text
看看那个东西
```

期望：

- 不要乱查
- 追问用户：
  “你想查哪个关键词、分类或时间范围？”

---

## 10. 测试要求

必须补充单元测试或可验证测试用例。

至少覆盖：

- 本月时间解析
- 上个月时间解析
- 今天/昨天解析
- 关键词提取
- 分类名匹配
- 资产名匹配
- 全部账本识别
- 写操作拒绝
- AI JSON 解析失败回退
- 多轮修正关键词
- 多轮修正日期
- 点击统计后查询条件正确
- 点击搜索后查询条件正确
- `excludeFromStats` 不计入统计
- 退款/还款/转账类型过滤正确

---

## 11. 最终验收要求

功能完成后，必须满足以下验收标准：

1. 聊天内可以把自然语言查询转成查询草稿卡片。
2. 查询草稿卡片展示关键词、日期范围、类型、账本范围等关键条件。
3. 用户可以手动修改关键词。
4. 用户可以通过日期选择器修改日期范围。
5. 用户可以通过继续聊天修正关键词、日期、账本、类型。
6. 点击“统计金额”后，本地查询并返回金额和笔数。
7. 点击“搜索账单”后，本地查询并展示或跳转明细列表。
8. AI 不直接生成金额、笔数、排行榜等事实结果。
9. 所有事实结果来自本地数据库查询。
10. 写操作请求被拒绝，不会创建、修改或删除任何数据。
11. 不恢复 `docs/archived-agent-code/` 中的完整 Agent 框架。
12. 不引入 WRITE / DESTRUCTIVE 工具。
13. 查询功能不破坏现有 AI 记账流程。
14. 查询失败或 AI 输出异常时，应用不崩溃。
15. 查询结果中必须展示本次查询条件，方便用户核对。

---

## 12. 实现优先级建议

优先做：

1. 查询草稿数据结构
2. 本地规则识别
3. 查询草稿卡片 UI
4. 手动编辑关键词和日期
5. 本地统计金额
6. 本地搜索明细
7. 多轮修正
8. AI 参数提取兜底
9. 查询结果卡片
10. 高级查询，例如环比、Top 分类、账本概览

不要优先做：

- 完整 Agent
- 工具调用框架
- 写操作
- 复杂权限/设置控制
- 自动执行高风险动作

---

## 13. 一句话总结

要实现的不是“AI 替用户查完并回答”，而是：

**AI 帮用户生成一个可确认、可编辑的查询草稿；用户确认后，App 用本地数据库执行查询，并用可核对的结果卡片展示事实。**
