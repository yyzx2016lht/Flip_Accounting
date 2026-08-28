# TapAccounting 功能优化实现提示词（9 项）

> 目标读者：负责实现功能的 AI 编程代理或开发者  
> 项目：敲敲记账 / TapAccounting / FlipAccounting-AI  
> 文档类型：功能优化实现规格  
> 前置阅读：`docs/project-overview.md`  
> 排除项：**AI 查询助手** 已有独立文档 `docs/ai-query-assistant-implementation-prompt.md`，且产品方决定暂缓完善，本文档不包含该项。

---

## 使用说明

1. **一次只实现一个功能**。不要试图在一个 PR 里同时做预算、周期账单、草稿箱。
2. **先读 `docs/project-overview.md` 和本文档对应章节**，再读相关源码，不要凭空新建平行体系。
3. **优先复用现有 Room 实体、DAO、统计逻辑、AI 规则、备份导入链路**。能本地算清的，不要让 LLM 编数字。
4. **每个功能都必须有开关或渐进发布策略**。默认行为不能破坏现有“记账 + 统计 + 资产”主流程。
5. **不要恢复 `docs/archived-agent-code/` 里的完整 Agent 系统**。这些优化都是“产品化已有能力”，不是重做 Agent。
6. 实现完成后，必须补：单元测试（纯逻辑）、至少 3 条手工验收路径、以及“明确不做什么”的说明。

### 推荐实现顺序

| 阶段 | 功能 | 原因 |
|------|------|------|
| 第一阶段 | #2 消费洞察卡片、#5 编辑即学习规则、#9 首页驾驶舱 | 复用现有统计和 AiRule，改动面可控，用户感知强 |
| 第二阶段 | #3 轻量预算、#4 周期账单检测、#8 信用卡账单周期、#6 资产对账 | 都依赖账单时间序列和资产模型，可共享计算层 |
| 第三阶段 | #7 导入迁移引导、#10 AI 记账草稿箱 | 涉及 onboarding 和跨入口状态统一，改动面最大 |

---

## 通用约束（所有 9 项都必须遵守）

### 必须遵守

- 所有金额、笔数、比例、日期范围：**本地 Room/DAO 计算**。
- 所有写入数据库的账单：**继续走现有确认/保存链路**，不要偷偷自动入账。
- 新 UI 必须适配现有浅色卡片风格、中文字符串放 `strings.xml`。
- 新表/新字段必须有 Room migration，不能破坏旧备份恢复。
- 新功能默认应可关闭，或至少不打扰未使用用户。

### 不要做

- 不要引入完整 Agent / Tool Calling 框架。
- 不要让 LLM 直接返回“本月花了多少钱”这类事实，只能返回建议文案或结构化标签。
- 不要做银行/支付宝/微信自动账单抓取（本期全部排除）。
- 不要做多人共享账本。
- 不要做复杂 Excel 公式级预算系统。

### 关键现有代码索引

| 领域 | 主要文件 |
|------|----------|
| 账单 | `data/local/entity/Bill.kt`, `BillDao.kt` |
| 资产 | `data/local/entity/Asset.kt`, `AssetDao.kt`, `AddAssetActivity.kt`, `AssetDetailActivity.kt` |
| 统计 | `ui/main/stats/StatsFragment.kt`, `StatsViewModel.kt` |
| 首页 | `ui/main/home/HomeFragment.kt`, `HomeViewModel.kt`, `HomeChartController.kt` |
| AI 规则 | `data/local/entity/AiRule.kt`, `AiRuleManageActivity.kt`, `AIService.kt`, `AccountingFormController.kt` |
| 余额调整 | `BalanceAdjustmentActivity.kt` |
| 导入 | `BackupActivity.kt`, `CsvManager.kt`, `BackupHomeActivity.kt` |
| 聊天记账 | `ChatMessagePipeline.kt`, `ChatBillCorrectionService.kt`, `AccountingFormController.kt` |
| 服务端参考 | `server/.../StatisticsService.kt`（仅供算法参考，Android 端不要直接依赖 server 模块） |

---

# 功能 #2：AI / 本地消费洞察卡片

## 1. 背景与目标

现状：首页和统计页主要展示数字和图表，用户需要自己解读“这个月花得多不多”“哪类异常”。  
`server/StatisticsService.kt` 里已有 `timeInsight`、`categoryInsight`、`insights` 等算法，但 **Android 端没有消费**。

目标：在首页和/或统计页增加 1~3 张“洞察卡片”，用一句话告诉用户值得注意的消费变化，例如：

- “本月餐饮比上月高 32%”
- “本周有 3 笔金额相近的订阅扣费”
- “昨天有一笔 ¥899 支出，高于你平时同类消费”

产品定位：**反馈工具**，不是聊天，不是查询助手。

## 2. 非目标

- 不做开放式 AI 聊天分析。
- 不做需要用户配置的复杂报表。
- 第一版不做个性化“理财建议”，只做基于账单的客观洞察。

## 3. 方案概述

新增本地洞察引擎 `InsightEngine`（建议包：`insight/` 或 `logic/insight/`）：

```text
BillDao 查询本期/上期/近 N 天数据
  -> InsightRule 本地规则计算候选洞察
  -> 排序 + 去重 + 限额（首页最多 2 张，统计页最多 4 张）
  -> InsightCard UI 渲染
```

第一版洞察类型（至少实现 4 种）：

| insightType | 示例文案 | 计算方式 |
|-------------|----------|----------|
| `MONTH_CATEGORY_DELTA` | 本月餐饮比上月高 32% | 分类支出环比 |
| `LARGE_EXPENSE` | 昨天有一笔 ¥899 的购物支出 | 单笔金额 > 同类 P90 或 > 月均单笔 3 倍 |
| `RECURRING_HINT` | 检测到 3 笔相近金额的周期扣费 | 与 #4 可共享检测器，但这里只做提示，不做管理页 |
| `WEEKEND_SPEND` | 周末支出占本周 68% | 时间分布 |

可选：对文案做 AI 润色，但 **数字必须来自本地结果，LLM 不能改金额和百分比**。

## 4. 数据模型

```kotlin
data class InsightCardModel(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val severity: InsightSeverity, // INFO / WARN / POSITIVE
    val action: InsightAction?,      // 跳转统计页、打开分类、打开日历
    val payload: Map<String, String> = emptyMap(),
    val generatedAt: Long
)
```

不需要持久化表。洞察是派生数据，每次进入页面重新计算，可内存缓存 5 分钟。

## 5. UI/UX

### 首页

- 在 `fragment_home.xml` 月收支摘要下方，插入 `RecyclerView` 或 1~2 个静态 card slot。
- 无洞察时整块隐藏，不留空白。
- 卡片样式参考现有设置页/聊天气泡：白底、圆角、左侧色条区分 severity。

### 统计页

- 在 `StatsFragment` 饼图上方增加“本月洞察”区域。
- 点击卡片可跳到对应筛选后的账单列表或分类详情。

### 开关

- 在 `ProfileFragment` 或 `AiFeatureSettingsActivity` 增加“消费洞察”开关，默认开启。
- Pref 建议：`insight_cards_enabled`，默认 `true`。

## 6. 分阶段实现

### Phase 1（必须）

- 本地规则引擎 + 首页 1 张卡片 + 统计页列表
- 4 种 insightType
- 单元测试覆盖环比、大额、周末占比

### Phase 2（可选）

- 点击卡片跳转过滤页
- 洞察缓存
- AI 文案润色（只润色，不改数字）

## 7. 验收标准

- 新用户无账单时，不显示洞察区。
- 有账单时，至少能正确展示“本月总支出环比上月”。
- 卡片上的金额、百分比与统计页一致。
- 关闭开关后，首页和统计页都不再显示洞察。
- 不增加首页首屏加载超过 200ms（账单 1 万条内）。

## 8. 测试要求

- 纯函数测试：环比、P90、大额判断、周末占比。
- 手工：本月餐饮明显高于上月；昨天单笔大额；关闭开关。

---

# 功能 #3：轻量预算管理

## 1. 背景与目标

现状：项目没有预算实体。只有 `Bill.excludeFromStats` 可排除统计，不是预算。  
目标：做 **按月 + 按分类** 的轻量预算，不碰复杂 envelope budgeting。

用户价值：

- 看到“餐饮本月预算 ¥1800，已用 ¥1260 (70%)”
- 超支时提醒
- 首次设置时可基于过去 3 个月平均值给建议

## 2. 非目标

- 不做按日预算。
- 不做多币种预算换算。
- 不做账本级与分类级嵌套双预算。
- 不做 AI 自动修改预算。

## 3. 数据模型

新增 Room 表 `budgets`：

```kotlin
@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookName: String,              // 空字符串表示全部账本
    val categoryId: Long?,             // null 表示“总支出预算”
    val categoryName: String?,         // 冗余展示
    val yearMonth: String,             // "2026-06"
    val amount: Double,
    val currency: String = "CNY",
    val alertThreshold: Double = 0.8,  // 80% 提醒
    val createdAt: Long,
    val updatedAt: Long
)
```

说明：

- 第一版只支持 **支出预算**。
- 统计口径与 `StatsViewModel` 保持一致：排除 `excludeFromStats = true` 的账单。
- 退款是否冲减预算：第一版 **冲减**，与统计净支出一致。

## 4. 核心逻辑

新增 `BudgetService`：

- `getMonthSpend(bookName, categoryId, yearMonth): Double`
- `getBudgetProgress(budget): BudgetProgress`
- `suggestBudgetFromHistory(categoryId, months = 3): Double?` — 用过去 3 个月同分类月均支出，向上取整到 10 元

```kotlin
data class BudgetProgress(
    val budgetAmount: Double,
    val usedAmount: Double,
    val percent: Double,
    val remaining: Double,
    val status: BudgetStatus // NORMAL / WARNING / EXCEEDED
)
```

## 5. UI/UX

### 入口

1. 统计页顶部增加“本月预算”卡片；无预算时显示“设置预算”。
2. 分类详情/分类长按菜单增加“设置本月预算”。

### 设置页

新增 `BudgetManageActivity` 或 BottomSheet：

- 展示当前月各分类预算列表
- 支持编辑金额、删除预算
- “根据过去 3 个月推荐”按钮

### 首页联动

- 与 #9 驾驶舱共用一张“预算进度”卡（如果 #9 已做，不要重复做两套 UI）。

### 提醒

- 本地通知可选 Phase 2；第一版先在 UI 上标红/橙色即可。

## 6. 分阶段实现

### Phase 1

- 表结构 + DAO + `BudgetService`
- 统计页预算卡
- 分类预算编辑

### Phase 2

- 历史推荐
- 超支通知
- 首页驾驶舱联动

## 7. 验收标准

- 能为“餐饮”设置 6 月预算 ¥2000；当月餐饮支出 ¥1500 时显示 75%。
- 超 100% 显示超支状态。
- 总预算与分类预算可同时存在，互不覆盖。
- 删除预算后 UI 恢复“未设置”。
- 备份/恢复后预算数据保留。

## 8. 测试要求

- 单元测试：progress 计算、退款冲减、excludeFromStats 排除。
- Migration 测试。
- 手工：设置预算 -> 记几笔账 -> 看进度变化。

---

# 功能 #4：周期账单 / 订阅检测

## 1. 背景与目标

现状：零实现。用户经常重复支付房租、会员、云服务、保险，但 App 不会主动发现。  
目标：从已有账单中识别“疑似周期支出”，提示用户确认并可选设置提醒。

这不是自动记账，而是 **发现模式 + 用户确认**。

## 2. 非目标

- 不自动创建未来账单。
- 不自动扣款提醒银行。
- 第一版不做收入周期（工资）管理。

## 3. 数据模型

新增表 `recurring_patterns`：

```kotlin
@Entity(tableName = "recurring_patterns")
data class RecurringPattern(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantKey: String,           // 归一化商户/备注关键词
    val categoryId: Long?,
    val categoryName: String?,
    val accountName: String?,
    val bookName: String,
    val amountApprox: Double,          // 中位数金额
    val amountTolerance: Double,       // 允许波动
    val frequency: RecurringFrequency, // WEEKLY / MONTHLY / YEARLY
    val dayOfMonthHint: Int?,          // 常见扣款日
    val lastSeenAt: Long,
    val nextExpectedAt: Long?,
    val status: RecurringStatus,       // SUGGESTED / CONFIRMED / DISMISSED
    val createdAt: Long,
    val updatedAt: Long
)
```

## 4. 检测算法（第一版必须本地实现）

新增 `RecurringBillDetector`：

输入：近 12 个月支出账单  
规则：

1. 按 `remark/categoryName/accountName` 归一化分组（去空格、小写、去常见后缀）。
2. 每组至少 **3 次** 支出。
3. 金额波动在 ±15% 内。
4. 间隔接近 7±1 天、30±3 天、365±7 天之一。
5. 输出 `confidence` 分数，取前 5 个候选。

触发时机：

- App 冷启动后后台轻量扫描（WorkManager，低优先级）
- 用户保存一笔新支出后，增量检查该商户
- 统计页/洞察页展示“发现 X 个疑似订阅”

## 5. UI/UX

### 发现页

新增 `RecurringBillsActivity`：

- Tab：`待确认` / `已确认` / `已忽略`
- 每条显示：商户名、大约金额、周期、最近一笔、下次预计
- 操作：确认、忽略、查看相关账单

### 提示入口

- 首页/洞察卡： “发现 3 个疑似周期账单，去看看？”
- 记账保存后若命中已有 confirmed pattern，可 toast：“这像是你的 Netflix 月订阅”

### 提醒（Phase 2）

- 对 `CONFIRMED` 模式，在 `nextExpectedAt` 前 1 天发本地通知

## 6. 与 #2 洞察的关系

- #2 的 `RECURRING_HINT` 可以直接调用本检测器。
- 但 #4 负责完整管理和确认，不只是展示一句话。

## 7. 验收标准

- 连续 3 个月同一备注“爱奇艺会员 15 元”能被识别为月度模式。
- 用户忽略后不再反复弹同一条。
- 用户确认后可在列表看到。
- 金额差太大的同类商户不会误合并。
- 检测任务不会阻塞主线程。

## 8. 测试要求

- 构造 3/4/5 笔间隔账单样本做单元测试。
- 边界：只有 2 笔不提示；金额波动过大不提示。
- 手工：确认/忽略/查看账单。

---

# 功能 #5：编辑即学习的 AI 规则（闭环强化）

## 1. 背景与目标

现状：项目已有完整 AiRule 体系，但用户不会主动去 `AiRuleManageActivity` 配规则。  
已有触发点：

- `AccountingFormController.handleRulePromptAfterBookkeeping()` — 表单保存后若用户改了 AI 字段，弹窗问是否记住
- `ChatActivity.maybeShowRuleDialogForChatBillCategoryEdit()` — 聊天改分类后弹窗
- `AIService.applyLocalRuleOverrideOnResult()` — 运行时应用规则

缺口：

- `ChatActivity.pendingHabitSuggestion` / `HabitRuleSuggestion` **已定义但未使用**
- 聊天文字记账路径没有稳定的“编辑后学规则”闭环
- 规则冲突处理仍不完善（见 `docs/audit/11-logic-correctness.md`）

目标：让用户在 **改分类、改账户、改备注关键词** 后，自然地被问一句“以后都这样吗？”，并在后台自动写好 `AiRule`。

## 2. 非目标

- 不让 AI 自动偷偷写规则，必须用户确认。
- 不做复杂正则规则编辑器。
- 不修改现有 `AiRuleManageActivity` 的主流程，只增强触发和推荐。

## 3. 产品流程

```text
AI 产出账单草稿
  -> 用户修改字段（分类/账户/备注）
  -> 系统生成 RuleCreateSuggestion
  -> 弹窗：“以后把「瑞幸」都归到 餐饮-咖啡 吗？”
  -> 用户确认
  -> 写入 AiRule 表
  -> 下次 AIService 自动应用
```

`RuleCreateSuggestion` 建议字段：

```kotlin
data class RuleCreateSuggestion(
    val keyword: String,
    val targetType: String?,
    val targetCategory: String?,
    val targetAccount1: String?,
    val targetAccount2: String?,
    val source: RuleSuggestionSource // FORM / CHAT / IMAGE / VOICE
)
```

## 4. 必须覆盖的入口

| 入口 | 现状 | 要做的 |
|------|------|--------|
| 传统表单 `AccountingFormController` | 已有 | 修复冲突处理；补测试 |
| 聊天改账单分类 | 部分有 | 扩展到账户/备注 |
| 聊天文字记账首次保存前修改 | 无 | 在确认卡片修改后触发 |
| 图片/语音多账单确认 | 无 | 每笔保存后独立触发 |
| Overlay 截屏/悬浮记账 | 无 | 与表单共用 helper |

## 5. 统一组件

新增或整理为 `AiRuleSuggestionCoordinator`：

- `detectSuggestion(before, after): RuleCreateSuggestion?`
- `shouldPrompt(suggestion): Boolean`
- `showPrompt(activity, suggestion, onConfirm)`
- `persistRule(suggestion)`

要求：

- 同一关键词 24h 内不重复弹。
- 若已存在同关键词规则，改为“更新现有规则？”而不是新建冲突规则。
- 受 `Prefs.isAiPromptCorrectionEnabled()` / `enable_local_rule_override` 控制。

## 6. UI/UX

- 继续使用现有 `RuleDialogHelper` 视觉，不要新造一套。
- 文案简短，带明确例子。
- 提供“不再提醒”仅对本条关键词生效，不要全局关闭学习功能。

## 7. 验收标准

- 表单里把“瑞幸咖啡”分类从餐饮改成咖啡子类，保存后弹学习窗；确认后下次“瑞幸”自动归类。
- 聊天里修改账单分类也会弹。
- 已存在同关键词规则时，不再创建重复规则。
- 关闭“本地规则/提示校正”后，不再弹窗，但规则管理页仍可手动编辑。
- `pendingHabitSuggestion` 死代码要么接通，要么删除，不能继续悬空。

## 8. 测试要求

- 单元测试：关键词抽取、冲突检测、24h 去重。
- 手工：表单、聊天、图片三条链路各测一次。

---

# 功能 #6：资产对账助手

## 1. 背景与目标

现状：已有 `BalanceAdjustmentActivity` 支持用户手动输入实际余额并生成差额调整；投资资产有 `InvestmentInterestService.reconcileAssetLotsToBalance()`。  
但没有“对账助手”产品流程：用户不知道差额从哪来，也找不到可能漏记的账单。

目标：用户输入某个资产当前真实余额后，系统：

1. 计算与账本余额差额
2. 展示可能原因（最近未入账账单、转账、利息）
3. 提供建议动作：补记账单 / 直接余额调整 / 查看最近流水

## 2. 非目标

- 不自动修改余额。
- 不连接银行 API。
- 不做 OCR 识别银行截图（可留给以后）。

## 3. 方案概述

新增 `AssetReconciliationService`：

```text
用户选择资产 + 输入实际余额
  -> 读取 Asset.balance
  -> diff = actual - ledger
  -> 若 |diff| < 阈值(如 0.01) => 显示“已对齐”
  -> 否则搜索最近 30 天账单，找可能相关记录
  -> 生成 ReconciliationReport
```

```kotlin
data class ReconciliationReport(
    val assetId: Long,
    val assetName: String,
    val ledgerBalance: Double,
    val actualBalance: Double,
    val diff: Double,
    val likelyCauses: List<ReconciliationCause>,
    val recentUnmatchedBills: List<BillPreview>,
    val suggestedActions: List<ReconciliationAction>
)
```

`likelyCauses` 第一版只做规则，不用 AI：

- 最近有大额转账
- 最近有退款
- 投资资产利息结算
- 最近余额调整记录

## 4. UI/UX

### 入口

1. `AssetDetailActivity` 顶部按钮：“对账”
2. `BalanceAdjustmentActivity` 前置一步：先显示差异分析，再让用户选择“调整余额”或“去补账”

### 页面

新增 `AssetReconcileActivity` 或在现有页面加 BottomSheet：

- 顶部：账本余额 vs 实际余额 vs 差额
- 中部：可能原因列表
- 下部：最近 10 笔相关账单
- 按钮：`补记账单`、`余额调整`、`稍后处理`

## 5. 验收标准

- 微信资产账本 1000，用户输入实际 1200，显示 +200 差额。
- 若最近有一笔 +200 收入未记，出现在“可能相关账单”候选。
- 点击“余额调整”跳到现有 `BalanceAdjustmentActivity`，差额预填。
- 信用卡资产走负债口径，不把它当成正向余额资产处理。

## 6. 测试要求

- 单元测试：diff 计算、信用卡负债符号、候选账单筛选。
- 手工：资金资产、信用卡资产各测一次。

---

# 功能 #7：导入迁移入口强化

## 1. 背景与目标

现状：已有 CSV 导入、完整备份恢复、钱迹/随手记类数据兼容痕迹，但入口深埋在 `BackupHomeActivity` / `BackupActivity`，新用户不知道能迁移。  
目标：把“从其他记账 App 迁移”做成明确 onboarding 能力，而不是隐藏工具。

## 2. 非目标

- 第一版不新增全新格式解析器，优先复用 `CsvManager` 和现有 backup restore。
- 不做云端自动迁移。
- 不做双向同步。

## 3. 方案概述

新增 `ImportOnboardingActivity` 或在 `BackupHomeActivity` 顶部增加引导区：

### 首启引导（可选显示一次）

Pref：`import_onboarding_seen_v1`

步骤：

1. 欢迎页：说明支持 CSV / 备份文件 / 从其他 App 导出后导入
2. 选择来源：随手记 / 钱迹 / 其他 CSV / 本应用备份
3. 导入执行：复用 `BackupActivity.performCsvImport()` 或现有 restore
4. 导入后检查：展示“新建了 X 个资产、Y 个分类、Z 笔账单”
5. 引导处理临时资产：CSV 导入会创建 `type="CSV导入待确认"`、`remark=CSV_TEMP_ASSET_MARKER` 的资产，必须引导用户合并或重命名

## 4. 导入后审查页（关键）

新增 `ImportReviewActivity`：

- 列出本次导入创建的临时资产、未识别分类、重复账单风险
- 用户可批量把临时资产合并到真实资产
- 完成后写 Pref `import_review_completed_v1`

## 5. UI 文案要求

- 明确告诉用户：导入不会覆盖现有数据，除非选择覆盖恢复。
- 给出外部教程占位：“如何在钱迹/随手记导出 CSV”。

## 6. 验收标准

- 新装后能看到迁移入口，不依赖用户自己翻备份页。
- CSV 导入后审查页能列出临时资产。
- 完成审查后，首页能正常看到导入账单。
- 已看过引导的用户不会每次启动都弹。
- 不影响老用户直接进入备份页。

## 7. 测试要求

- 用小样本 CSV 验证导入统计数字。
- 手工：首次引导 -> 导入 -> 审查 -> 合并资产。

---

# 功能 #8：信用卡账单周期管理

## 1. 背景与目标

现状：`Asset` 已有 `assetCategory = CREDIT_CARD`、`creditLimit`、`billingDay` 字段，但：

- `billingDay` 注释写“还款日（保留字段，暂不使用）”
- `AddAssetActivity` 没有让用户配置账单日/还款日/额度
- 没有“本期应还 / 未出账 / 还款倒计时”

产品方诉求：知道怎么做，但希望 **先做小，不做成银行级账单系统**。

## 2. 非目标

- 不做分期、利息、最低还款复杂计算。
- 不做账单 PDF 导入。
- 不做多币种信用卡账单。

## 3. 数据模型调整

建议明确区分两个日期，不要继续混用 `billingDay`：

### 方案 A（推荐）

给 `Asset` 增加字段：

```kotlin
val statementDay: Int = 0,   // 账单日，每月几号出账
val dueDay: Int = 0,           // 还款日，每月几号前应还
val creditLimit: Double = 0.0
```

保留 `billingDay` 仅做迁移兼容，读取时 `dueDay = if (dueDay>0) dueDay else billingDay`。

### 周期定义

以 `statementDay = 10` 为例：

- **已出账周期**：上个账单日次日 00:00 到本次账单日 23:59
- **未出账消费**：本次账单日之后到今天的消费
- **本期待还**：已出账周期内消费 - 退款 - 对该卡的还款转账

## 4. 核心计算

新增 `CreditCardCycleService`：

```kotlin
data class CreditCardCycleSnapshot(
    val assetId: Long,
    val cardName: String,
    val statementDay: Int,
    val dueDay: Int,
    val creditLimit: Double,
    val statementStart: Long,
    val statementEnd: Long,
    val billedSpend: Double,
    val unbilledSpend: Double,
    val paymentsInCycle: Double,
    val amountDue: Double,
    val availableLimit: Double?,
    val daysToDue: Int?
)
```

账单筛选：

- 消费/退款：关联该信用卡账户或转入该卡
- 还款：识别 `TYPE_TRANSFER` + `SUBTYPE_REPAYMENT` 或现有 AI 还款规则（`AIPrompts.buildRepaymentRule()`）

## 5. UI/UX

### 资产编辑

在 `AddAssetActivity` 的信用卡类型下显示：

- 额度
- 账单日（1~31）
- 还款日（1~31）

### 资产详情

`AssetDetailActivity` 顶部卡：

- 本期待还 ¥X
- 未出账 ¥Y
- 距离还款日还有 N 天
- 剩余额度（若设置了 creditLimit）

### 首页/驾驶舱

- 若有信用卡 3 天内到期，展示提醒卡（与 #9 共用）

## 6. 分阶段实现

### Phase 1

- 字段迁移 + 编辑 UI + 详情页快照

### Phase 2

- 还款提醒通知
- 记账时识别“还信用卡”

## 7. 验收标准

- 能为招商信用卡设置账单日 10 号、还款日 28 号、额度 20000。
- 今天 23 号时，能正确区分“已出账”和“未出账”。
- 本期待还不把未出账消费算进去。
- 未设置账单日的信用卡不展示周期卡，只展示负债余额。
- 与 `AssetsFragment` 负债汇总不冲突。

## 8. 测试要求

- 单元测试：周期边界（月初、月末、账单日=1、账单日=31）
- 手工：消费几笔 -> 看本期待还 -> 记一笔还款 -> 金额下降

---

# 功能 #9：首页驾驶舱卡片

## 1. 背景与目标

现状：`HomeFragment` 结构基本固定：banner + 月收支 + 可选趋势图 + 账单列表。  
目标：把首页变成“一眼知道今天该干什么”的驾驶舱，但保持轻量，不做负一屏。

第一版只加 3 类卡：

1. **待处理** — 来自 #10 草稿箱数量 / 待确认导入 / 待确认周期账单
2. **预算进度** — 来自 #3，本月总预算或 Top 1 超支分类
3. **重要提醒** — 信用卡还款、周期账单、异常支出洞察

## 2. 非目标

- 不做用户自定义拖拽排序（Phase 2 以后再说）。
- 不做桌面 Widget（另项）。
- 不把首页做成 8 张卡的大杂烩。

## 3. 技术方案

新增 `HomeDashboardCard` sealed model + `HomeDashboardAdapter`。

```kotlin
sealed class HomeDashboardCard {
    data class PendingActions(val items: List<PendingActionItem>) : HomeDashboardCard()
    data class BudgetProgress(val progress: BudgetProgress) : HomeDashboardCard()
    data class Reminder(val title: String, val body: String, val action: InsightAction?) : HomeDashboardCard()
}
```

在 `HomeFragment` 中：

```text
HomeViewModel.loadDashboardCards()
  -> 聚合各 feature provider
  -> 按优先级最多返回 3 张
  -> 插入到 bill list 上方 header
```

Provider 示例：

- `PendingActionsProvider` — 读草稿箱、导入审查、周期账单待确认
- `BudgetDashboardProvider` — 读 #3
- `ReminderProvider` — 读 #8 + #4 + #2

## 4. UI/UX

- 卡片统一样式，横向或纵向列表均可，但首屏高度不超过屏幕 1/3。
- 无内容不显示。
- 点击卡片进入对应功能页。

### 开关

- `home_dashboard_enabled`，默认 `true`
- 与 `show_home_trend_card` 共存；趋势图仍可在个人中心开关

## 5. 验收标准

- 有待确认草稿时，首页出现“你有 2 笔待确认账单”。
- 有预算且达到 80% 时出现预算卡。
- 信用卡 3 天内还款时出现提醒卡。
- 三类同时存在时，总卡数不超过 3，按优先级裁剪。
- 关闭开关后恢复旧首页布局。

## 6. 测试要求

- Provider 单元测试：优先级、空列表、多来源合并。
- 手工：分别制造三种条件，确认显示和跳转。

---

# 功能 #10：AI 记账草稿箱（待确认账单 Inbox）

## 1. 背景与目标

现状（重要）：

- 产品方已明确要求：**账单必须经用户确认后才正式入账**。这一点与目标一致。
- 但当前实现是分散的：
  - 图片记账有 `requires_review` 和视觉草稿确认
  - Overlay/表单有多账单 `pendingBills` 队列（内存）
  - 聊天文字记账 `ChatBillCorrectionService` 往往 **直接入库**
  - 查询草稿 `QueryDraft` 是另一套体系，不能混用

用户痛点不是“没有确认”，而是：

- 截图/语音/多图识别后，只能当场处理，不能稍后再确认
- 不同入口的“待确认”状态不统一，没有全局 Inbox
- 未确认账单是否影响统计，用户看不清楚

目标：做一个统一的 **AI 记账草稿箱**，把所有“已识别但未正式入账”的账单集中管理。

## 2. 非目标

- 不重做 AI 识别。
- 不改变“确认后才入账”的安全原则。
- 第一版不做跨设备同步草稿。

## 3. 核心原则

1. **未确认草稿不得写入 `bills` 表**。
2. **未确认草稿不得影响资产余额和统计**。
3. 用户可随时回来批量确认、编辑、删除。
4. 聊天页仍可展示草稿卡片，但状态以 Inbox 为准。

## 4. 数据模型

新增表 `accounting_drafts`：

```kotlin
@Entity(tableName = "accounting_drafts")
data class AccountingDraft(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val source: DraftSource,          // CHAT_TEXT / CHAT_IMAGE / VOICE / SCREEN / OVERLAY / CSV_REVIEW
    val sourceMessageId: String?,     // 对应 chat_messages.id，可空
    val bookName: String,
    val payloadJson: String,          // AI 账单 JSON 或 bills 数组
    val naturalSummary: String?,
    val riskFlagsJson: String?,
    val status: DraftStatus,          // PENDING / CONFIRMED / DISCARDED
    val createdAt: Long,
    val updatedAt: Long,
    val confirmedAt: Long? = null
)
```

## 5. 流程改造

### 统一入口

新增 `AccountingDraftRepository`：

- `saveDraft(...)`
- `listPending()`
- `confirmDraft(id)` -> 调用现有 `BillSaveService` / `AccountingFormController` 保存逻辑
- `discardDraft(id)`
- `countPending()`

### 各来源接入

| 来源 | 当前行为 | 目标行为 |
|------|----------|----------|
| 聊天文字记账 | 直接保存 | 默认生成 draft；聊天展示确认卡；用户点确认才入库 |
| 聊天图片记账 | 部分 review | 统一写入 draft |
| Overlay/表单多账单 | 内存 `pendingBills` | 每笔写 draft；表单只是 draft 的一个编辑器 |
| CSV 临时账单 | 直接导入 | 可选：导入先进 draft，再批量确认 |

### 聊天 UI

- 保留现有账单确认卡片，但增加“稍后处理”按钮。
- 点“稍后处理”：草稿保留在 Inbox，聊天里显示“已存入待确认账单”。

### Inbox 页面

新增 `AccountingDraftInboxActivity`：

- 列表：摘要、金额、来源、时间、风险标记
- 详情：复用现有账单编辑 UI
- 批量操作：确认、删除
- 顶部统计：`待确认 X 笔，合计 ¥Y`

### 首页联动

- #9 驾驶舱显示待确认数量

## 6. 与现有“确认后入账”的关系

这不是新原则，而是把已有原则 **产品化**：

- 用户已经逐笔确认 -> 很好，草稿箱只是把“可晚点确认”的入口统一出来
- 不要做成另一套会自动过期的隐形逻辑
- 不要让草稿和已入账账单在 UI 上难以区分

## 7. 分阶段实现

### Phase 1

- 表结构 + Inbox 页面 + 聊天图片/多账单接入
- 聊天文字记账仍暂时直接入库（开关控制）

### Phase 2

- 聊天文字记账也进草稿箱
- 首页驾驶舱联动
- 批量确认

### Phase 3

- CSV 导入先进草稿
- 跨会话恢复、搜索

## 8. 验收标准

- 图片识别 3 笔账单，用户点“稍后处理”，统计页金额不变。
- Inbox 看到 3 笔，确认 1 笔后，统计只增加那 1 笔。
- 删除草稿不会留下幽灵账单。
- Overlay 多账单和聊天草稿共用同一仓库，不能两套状态。
- 与 `QueryDraft` 完全分离，聊天里两种卡片样式不同。

## 9. 测试要求

- 单元测试：confirm/discard 不污染统计。
- 集成测试：保存草稿 -> 确认 -> Bill 表新增。
- 手工：稍后处理、批量确认、删除、重启 App 后草稿仍在。

---

## 附录 A：功能依赖关系

```text
#9 首页驾驶舱
  ├─ depends on #3 预算进度（可选）
  ├─ depends on #8 信用卡提醒（可选）
  ├─ depends on #4 周期账单待确认（可选）
  ├─ depends on #10 草稿箱数量（可选）
  └─ depends on #2 洞察提醒（可选）

#2 消费洞察
  └─ soft link #4 周期检测

#8 信用卡周期
  └─ soft link #6 对账助手（信用卡口径）

#10 草稿箱
  └─ 应复用 #5 规则学习（在草稿确认时触发）
```

## 附录 B：每个功能交付时必须提交的说明

实现 AI 或开发者在 PR / 交付说明里必须写：

1. 本功能做了什么
2. 明确没做什么
3. 新增/修改的表、Pref、Activity
4. 如何手工验证
5. 是否影响备份恢复
6. 默认开关状态

## 附录 C：建议优先级（给产品方）

如果一次只挑 3 个最先做：

1. **#5 编辑即学习规则** — 直接提升 AI 记账准确率，成本低
2. **#2 消费洞察卡片** — 让已有数据产生反馈价值
3. **#10 草稿箱** — 统一待确认体验，但可分 Phase 1 先做图片/多账单

#8 信用卡周期、#3 预算、#4 周期账单检测 适合作为第二波。  
#7 导入迁移 适合拉新时做，不必阻塞老用户。

---

*文档版本：2026-06-23*  
*维护说明：若实现过程中发现代码现状与本文冲突，以实现前实际代码为准，并在交付说明里写明偏差。*
