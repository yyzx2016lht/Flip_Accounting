# 功能优化批次接线提示词（第三轮）

> 目标读者：负责实现接线与产品化的 AI 编程代理或开发者  
> 项目：敲敲记账 / TapAccounting / FlipAccounting-AI  
> 前置文档：  
> - `docs/feature-optimization-implementation-prompts.md`（9 项需求原文）  
> - `docs/feature-optimization-fix-prompt.md`（第二轮：编译修复，**已完成**）  
> 本文档目的：**把已能编译的骨架代码接上 UI 入口和主流程**，让用户真正用得上  
> 注意：**不要恢复完整 Agent**；**不要改动 AI 查询助手**（`ai_query_enabled` 默认 false）

---

## 0. 给修复 AI 的一句话

**第二轮已让 `assembleDebug` 通过，但 6 个新 Activity 没有导航入口，核心服务写了却没被调用。**  
你的任务：**加 UI 入口 + 接主流程 + 补 HomeDashboardProvider**，让用户从 App 内能走到新功能，且草稿/周期检测/信用卡周期真正生效。

---

## 1. 当前状态（第三轮审查基线）

### 已完成（不要回退）

| 项 | 状态 |
|----|------|
| `assembleDebug` | ✅ 通过 |
| P0 编译错误（9 项） | ✅ 已修 |
| 5 个布局 `xmlns:app` | ✅ |
| Budget / Draft / Recurring / Import Adapter | ✅ |
| 对账页逻辑 | ✅ |
| 洞察卡片（`InsightEngine` + 首页/统计页 + `switch_insight_cards`） | ✅ 已接入 |
| 数据库 26→30 迁移 | ✅ |
| 降级防护 | ✅ `DatabaseDowngradeHelper` + `fallbackToDestructiveMigrationOnDowngrade()` |

### 未完成（本轮必须做）

| 项 | 问题 |
|----|------|
| **6 个 Activity 无入口** | Manifest 已注册，全项目无 `startActivity` 跳转 |
| `HomeDashboardProvider` | 永远返回空列表 |
| `home_dashboard_enabled` | Pref 有，**设置页无开关** |
| `RecurringBillDetector` | 有实现，**从未调用** |
| `AccountingDraftRepository.saveDraft()` | **聊天/记账未写入草稿** |
| `CreditCardCycleService` | 有实现，**资产编辑无账单日/还款日 UI** |
| `AssetReconcileActivity` | 无入口 |
| CSV 导入后 | **未自动跳转 `ImportReviewActivity`** |
| 备份模块 | 新表未纳入 |
| Room 迁移测试 26→30 | 无 |
| `ChatActivity.pendingHabitSuggestion` | 死代码（可选本轮接通 `AiRuleSuggestionCoordinator`） |

---

## 2. 修复优先级

### P0 — 用户找不到功能（入口）

| # | 入口 | 目标 Activity |
|---|------|----------------|
| P0-1 | 统计页 | `BudgetManageActivity` |
| P0-2 | 资产详情页 | `AssetReconcileActivity` |
| P0-3 | 备份首页 / CSV 导入后 | `ImportOnboardingActivity` / `ImportReviewActivity` |
| P0-4 | 设置或统计 | `RecurringBillsActivity` |
| P0-5 | 首页驾驶舱 / 设置 | `AccountingDraftInboxActivity` |

### P1 — 主流程接线

| # | 任务 |
|---|------|
| P1-1 | `HomeDashboardProvider` 展示待确认草稿数并可点击跳转 |
| P1-2 | AI 设置页增加「首页驾驶舱」开关（`home_dashboard_enabled`） |
| P1-3 | `RecurringBillDetector` 后台检测 + 写入 `recurring_patterns` |
| P1-4 | 聊天记账可选进草稿箱（至少图片/多账单 `requires_review` 路径） |
| P1-5 | 信用卡资产编辑：`statementDay` / `dueDay` / `creditLimit` |
| P1-6 | 资产详情展示信用卡周期快照（`CreditCardCycleService`） |

### P2 — 质量（时间允许）

| # | 任务 |
|---|------|
| P2-1 | Room 迁移测试 26→30 |
| P2-2 | 备份纳入 `budgets` / `recurring_patterns` / `accounting_drafts` |
| P2-3 | 洞察卡片 `RECURRING_HINT` 点击跳转 `RecurringBillsActivity` |
| P2-4 | 接通 `AiRuleSuggestionCoordinator` 到聊天改账路径 |

---

## 3. P0-1：统计页 → 预算管理

### 目标

用户从 **统计 Tab** 能进入 `BudgetManageActivity`。

### 建议入口

**文件：** `app/src/main/java/com/taostudio/tapaccounting/ui/main/stats/StatsFragment.kt`  
**布局：** `app/src/main/res/layout/fragment_stats.xml` 或 `card_stats_overview.xml`

在统计页顶部概览卡片区域增加一行：

- 文案：「本月预算」或「管理预算」
- 无预算时：「设置预算」
- 有预算时：显示总进度（可调用已有 `BudgetService.getMonthBudgetsWithProgress`）

点击整行：

```kotlin
startActivity(Intent(requireContext(), BudgetManageActivity::class.java))
```

### 验收

- 统计页可见预算入口
- 点击能打开预算管理页，列表/编辑/删除正常

---

## 4. P0-2：资产详情 → 对账

### 目标

用户从 **资产详情** 能进入 `AssetReconcileActivity`。

### 建议入口

**文件：** `app/src/main/java/com/taostudio/tapaccounting/ui/main/assets/AssetDetailActivity.kt`

在现有「统计」「余额调整」等按钮旁增加 **「对账」** 按钮（或菜单项）。

跳转：

```kotlin
startActivity(
    Intent(this, AssetReconcileActivity::class.java)
        .putExtra(AssetReconcileActivity.EXTRA_ASSET_ID, assetId)
)
```

`AssetReconcileActivity` 已读取 `EXTRA_ASSET_ID`，不要改 extra 名。

### 验收

- 任意资金资产详情页可点对账
- 输入实际余额后能算差额并展示原因

---

## 5. P0-3：导入迁移引导 + 导入后审查

### 5.1 备份首页增加迁移入口

**文件：** `BackupHomeActivity.kt` + `activity_backup_home.xml`

在「快捷操作」区域增加按钮：**「从其他 App 迁移」**

```kotlin
startActivity(Intent(this, ImportOnboardingActivity::class.java))
```

`ImportOnboardingActivity` 已有 4 个选项按钮（随手记/钱迹/CSV/本应用备份），只需确保每个选项能跳到对应现有页面：

| 选项 | 跳转 |
|------|------|
| CSV 导入 | `BackupActivity` + `SECTION_CSV` |
| 本应用备份 | `BackupActivity` + restore |
| 其他 | 可先 Toast 说明导出教程，再跳 CSV |

### 5.2 CSV 导入完成后跳审查页

**文件：** `BackupActivity.kt` — `performCsvImportInternal` / `importCsvBills` 成功回调

导入成功后，若存在临时资产（`CSV_TEMP_ASSET_MARKER` 或 `type == "CSV导入待确认"`）：

```kotlin
startActivity(Intent(this, ImportReviewActivity::class.java))
```

或 Toast +「去审查」按钮，二选一，推荐直接跳转。

### 5.3 首启引导（可选，简单版）

若 `!Prefs.isImportOnboardingSeen(ctx)` 且用户首次打开 `BackupHomeActivity`，可弹一次 Snackbar 指向迁移按钮。  
**不要**做成阻塞式全屏引导，除非产品明确要求。

### 验收

- 备份页能进迁移引导
- CSV 导入后有临时资产时，能进审查页并完成合并/重命名

---

## 6. P0-4：周期账单管理入口

### 目标

用户能打开 `RecurringBillsActivity` 查看待确认/已确认/已忽略。

### 建议入口（二选一或都做）

**方案 A — 统计页**（推荐）  
在统计页洞察区下方或预算入口旁加「周期账单」文字链。

**方案 B — AI 功能设置**  
在 `activity_ai_feature_settings.xml` 洞察开关下方加一行「周期账单管理」，点击跳转。

```kotlin
startActivity(Intent(this, RecurringBillsActivity::class.java))
```

### 验收

- 有至少 1 个可见入口
- 打开后三个 Tab 可切换（P1-3 写入数据后能看到列表）

---

## 7. P0-5：待确认账单箱入口

### 目标

用户能打开 `AccountingDraftInboxActivity`。

### 建议入口

**方案 A — 首页驾驶舱**（与 P1-1 一起做，推荐）  
有待确认草稿时，驾驶舱卡片显示「你有 N 笔待确认」，点击进入。

**方案 B — 我的 / 设置**  
在 `ProfileFragment` 或 `AiFeatureSettingsActivity` 增加「待确认账单」菜单项。  
若 `countPending() == 0` 可隐藏或显示「暂无」。

### 验收

- 有草稿时能进 Inbox，确认后账单入库、首页统计变化
- 无草稿时入口不误导（隐藏或显示空状态）

---

## 8. P1-1：`HomeDashboardProvider` 接线

### 文件

`app/src/main/java/com/taostudio/tapaccounting/ui/main/home/dashboard/HomeDashboardProvider.kt`

### 最低实现

```kotlin
suspend fun loadDashboardCards(ctx, db, currentBills): List<HomeDashboardCard> {
    val cards = mutableListOf<HomeDashboardCard>()

    // 1. 待确认草稿
    val pendingCount = db.accountingDraftDao().countPending()
    if (pendingCount > 0) {
        cards.add(
            HomeDashboardCard.PendingActions(
                count = pendingCount,
                title = "待确认账单",
                body = "你有 $pendingCount 笔待确认",
                action = InsightAction(
                    type = InsightActionType.OPEN_DRAFT_INBOX, // 需新增 enum
                    payload = emptyMap()
                )
            )
        )
    }

    // 2. 预算超支（可选，有 BudgetService 时）
    // 3. 信用卡还款提醒（可选，有 CreditCardCycleService 时）

    return cards.sortedBy { it.priority }.take(MAX_DASHBOARD_CARDS)
}
```

### 点击跳转

**文件：** `HomeAdapter.kt` 中 `DashboardCardsViewHolder` 或 `HomeDashboardAdapter` 的 `onCardClick`

```kotlin
when (card) {
    is HomeDashboardCard.PendingActions ->
        ctx.startActivity(Intent(ctx, AccountingDraftInboxActivity::class.java))
    // ...
}
```

需在 `InsightActionType` 增加 `OPEN_DRAFT_INBOX`，或驾驶舱单独用 callback，不要和统计页洞察混用错误。

### 前置条件

`HomeFragment` 已在 `homeViewModel.dashboardCards` 收集并交给 `homeAdapter.dashboardCards`，只需 Provider 返回非空 + 点击处理。

### 验收

- `Prefs.isHomeDashboardEnabled()` 为 true 时，有待确认草稿则首页出现橙色卡片
- 点击跳转 Inbox

---

## 9. P1-2：首页驾驶舱开关

### 问题

`PrefsDisplaySupport` 已有 `home_dashboard_enabled`，但 **AI 设置页没有对应 Switch**。

### 要求

**文件：** `activity_ai_feature_settings.xml` + `AiFeatureSettingsActivity.kt`

在「消费洞察」开关下方增加：

- 标题：「首页驾驶舱」
- 描述：「在首页显示待处理、预算与提醒卡片」
- Switch 绑定 `Prefs.isHomeDashboardEnabled` / `setHomeDashboardEnabled`
- 默认：**true** 或 **false**（与 `PrefsDisplaySupport` 默认值保持一致）

参考已有 `switch_insight_cards` 的写法。

### 验收

- 关闭后首页不显示驾驶舱卡片区域（`HomeViewModel` 已有分支）
- 打开后 Provider 有数据则显示

---

## 10. P1-3：周期账单检测接入

### 文件

- `logic/RecurringBillDetector.kt`（已有 `detect()`）
- `data/local/dao/RecurringPatternDao.kt`
- 新建 `logic/RecurringPatternSyncService.kt`（建议）

### 逻辑

```text
读取近 12 个月支出账单
  -> RecurringBillDetector.detect(bills)
  -> 对每个 candidate：
       若 recurringPatternDao.getByMerchantKey 已存在且 status=DISMISSED -> 跳过
       若不存在或 status=SUGGESTED -> insert/update RecurringPattern(status=SUGGESTED)
```

### 触发时机（至少一种）

1. **App 冷启动后台**：`TapApplication.onCreate` 里 `CoroutineScope(Dispatchers.IO).launch { ... }`（低优先级）
2. **保存支出账单后**：`BillMutationService.insertBillAndApplyImpact` 末尾增量检测（可选）

不要阻塞主线程；检测失败静默忽略。

### 与洞察联动

`InsightEngine` 的 `RECURRING_HINT` 可调用同一检测器或读 `recurring_patterns` 表 `status=SUGGESTED` 的数量。

洞察卡片点击：扩展 `InsightActionType` 增加 `OPEN_RECURRING_BILLS`，跳转 `RecurringBillsActivity`。

### 验收

- 手动插入 3 笔同备注同金额月度账单后，重启 App 或触发检测
- `RecurringBillsActivity` 待确认 Tab 出现 1 条
- 确认后 status 变 CONFIRMED

---

## 11. P1-4：草稿箱接入记账主流程

### 原则

- **未确认草稿不入 `bills` 表**，不影响统计和资产
- 确认逻辑已在 `AccountingDraftInboxActivity.saveDraftAsBill()`，不要重复实现
- 本轮 **至少** 接一条生产路径，不要一次改遍所有入口

### 推荐路径（按优先级）

#### 路径 A：图片/视觉记账 `requires_review == true`

**文件：** `ChatMessagePipeline.kt`、`ChatActivity` 的 `confirmVisualAccountingDraft`

当 AI 返回 `requires_review: true` 且用户点 **「稍后处理」**（若无此按钮则新增）：

```kotlin
AccountingDraftRepository(db.accountingDraftDao()).saveDraft(
    source = DraftSource.CHAT_IMAGE,
    sourceMessageId = messageId,
    bookName = currentBook,
    payloadJson = aiJson.toString(),
    naturalSummary = aiJson.optString("natural_summary"),
    riskFlagsJson = ...
)
// 聊天里显示：「已存入待确认账单」
// 不要调用 processBillResult 直接入账
```

#### 路径 B：多账单 Overlay `pendingBills` 队列

**文件：** `AccountingFormController.kt` / `OverlayManager.kt`

用户关闭表单且未保存时，可选「稍后确认」→ 每笔写 `accounting_drafts`。

#### 路径 C：聊天文字记账（Phase 2，本轮可选）

需产品开关，默认 **不要** 让所有文字记账都进草稿（会破坏现有习惯）。  
若做，加 Pref `draft_inbox_for_chat_text_enabled` 默认 false。

### 验收

- 图片识别后点「稍后处理」→ Inbox 有记录 → 统计不变
- Inbox 确认后 → 账单入库 → 统计变化
- 原「当场确认」路径不受影响

---

## 12. P1-5 / P1-6：信用卡账单周期

### P1-5 资产编辑 UI

**文件：** `AddAssetActivity.kt` + 对应布局

当 `assetCategory == CREDIT_CARD` 时显示：

| 字段 | 类型 | 说明 |
|------|------|------|
| 信用额度 | 数字输入 | 映射 `creditLimit` |
| 账单日 | 1–31 Spinner | 映射 `statementDay` |
| 还款日 | 1–31 Spinner | 映射 `dueDay` |

保存时写入 `Asset` 实体。`billingDay` 旧字段可同步到 `dueDay` 做兼容。

### P1-6 资产详情展示

**文件：** `AssetDetailActivity.kt`

若 `CreditCardCycleService.buildSnapshot(asset)` 非 null，顶部卡片显示：

- 本期待还 ¥X
- 未出账 ¥Y
- 距还款日 N 天

### 驾驶舱提醒（可选）

`HomeDashboardProvider` 中：信用卡 3 天内到期 → `HomeDashboardCard.Reminder`

### 验收

- 可设置账单日 10、还款日 28
- 详情页显示周期信息，数字与本地账单一致

---

## 13. P2：迁移测试与备份

### P2-1 Room 迁移测试

新增 `AppDatabaseMigrationTest`（androidTest 或 test）：

- v26 fixture → open v30
- 断言新表存在、旧 `bills` 行保留

### P2-2 备份模块

检查 `BackupRepository` / 备份 JSON 结构：

- 增加 `budgets`、`recurring_patterns`、`accounting_drafts` 导出与恢复
- `assets` 导出包含 `statementDay`、`dueDay`

若本期只做导出不做恢复，交付说明必须写明。

---

## 14. 不要做的事

1. 不要恢复 Agent 系统  
2. 不要改 AI 查询助手（保持默认关闭）  
3. 不要删除已有 Adapter / Activity / 新表  
4. 不要为实现入口而 `fallbackToDestructiveMigration()` 清库  
5. 不要在没有开关的情况下强制所有聊天记账进草稿箱  
6. 不要重复实现 `saveDraftAsBill`（用 Inbox 已有逻辑或抽到 `AccountingDraftConfirmService` 复用）

---

## 15. 建议实施顺序

```text
1. P0-1 统计页 → 预算
2. P0-2 资产详情 → 对账
3. P0-3 备份页 → 导入引导 + CSV 后审查
4. P1-2 驾驶舱开关（设置页）
5. P1-1 HomeDashboardProvider + P0-5 草稿入口
6. P0-4 周期账单入口
7. P1-3 RecurringBillDetector 后台检测
8. P1-4 图片记账 → 草稿箱（稍后处理）
9. P1-5/P1-6 信用卡周期 UI
10. P2 迁移测试 / 备份
```

---

## 16. 交付前自检清单

- [ ] `./gradlew assembleDebug` 成功  
- [ ] 统计页能进预算管理  
- [ ] 资产详情能进对账  
- [ ] 备份页能进迁移引导；CSV 导入后能进审查  
- [ ] 周期账单页有入口且检测后能出数据  
- [ ] 首页驾驶舱有待确认时显示卡片并可进 Inbox  
- [ ] 图片「稍后处理」进草稿且确认后入账  
- [ ] 信用卡可设账单日/还款日并在详情展示  
- [ ] 设置页有「首页驾驶舱」开关  
- [ ] 交付说明列出：降级会清库但有文件备份；新表备份范围

---

## 17. 关键文件索引

| 用途 | 路径 |
|------|------|
| 统计入口 | `ui/main/stats/StatsFragment.kt` |
| 资产详情 | `ui/main/assets/AssetDetailActivity.kt` |
| 资产编辑 | `AddAssetActivity.kt` |
| 备份首页 | `BackupHomeActivity.kt` |
| CSV 导入 | `BackupActivity.kt` |
| 首页 | `ui/main/home/HomeFragment.kt`, `HomeViewModel.kt`, `HomeAdapter.kt` |
| 驾驶舱 | `ui/main/home/dashboard/HomeDashboardProvider.kt` |
| 洞察 | `logic/insight/InsightEngine.kt`, `InsightCardModel.kt` |
| 周期检测 | `logic/RecurringBillDetector.kt` |
| 信用卡周期 | `logic/CreditCardCycleService.kt` |
| 草稿 | `data/repository/AccountingDraftRepository.kt`, `ui/draft/AccountingDraftInboxActivity.kt` |
| 聊天管线 | `ChatMessagePipeline.kt`, `ChatActivity.kt` |
| AI 设置 | `AiFeatureSettingsActivity.kt`, `activity_ai_feature_settings.xml` |
| 我的页 | `ui/main/profile/ProfileFragment.kt` |
| 数据库 | `data/local/AppDatabase.kt` |

---

## 18. 交给实现 AI 的复制粘贴指令

```text
请阅读并严格按 docs/feature-optimization-wiring-prompt.md（第三轮）执行。

目标：为 6 个新 Activity 加 UI 入口，接 HomeDashboardProvider、周期检测、草稿箱主流程、信用卡周期 UI。

规则：
- 只修文档列出的 P0/P1/P2，不要扩 scope
- 不要动 AI 查询助手
- 不要恢复 Agent
- 复用已有 Activity/Adapter/Service，不要重写
- 修完后 assembleDebug，并按文档 §16 手工验收路径自测
- 交付说明写：加了哪些入口、接了哪条草稿路径、降级与备份注意事项
```

---

*文档版本：2026-06-23（第三轮：接线与入口）*  
*前置：第二轮编译修复已完成，`assembleDebug` 已通过*
