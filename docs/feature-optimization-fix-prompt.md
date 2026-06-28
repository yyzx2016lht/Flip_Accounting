# 功能优化批次修复提示词（第二轮）

> 目标读者：负责修复当前实现的 AI 编程代理或开发者  
> 项目：敲敲记账 / TapAccounting / FlipAccounting-AI  
> 需求来源：`docs/feature-optimization-implementation-prompts.md`（9 项功能优化）  
> 审查基线：2026-06-23 代码审查  
> 注意：**不要恢复完整 Agent**；**不要改动 AI 查询助手**（保持 `ai_query_enabled` 默认关闭）

---

## 0. 给修复 AI 的一句话

**第一轮修了一半：布局 XML 已好、多个 Adapter 已加、对账页和草稿入账逻辑已写，但 Kotlin 仍有约 25 处编译错误，APK 仍打不出来。**  
你的任务：**只修本文档列出的编译错误和剩余 P1/P2 项，不要扩 scope，不要重做已完成的 Adapter。**

---

## 1. 审查结论快照

| 类别 | 状态 |
|------|------|
| P0 布局 `xmlns:app`（5 个文件） | ✅ **已完成** |
| P0 `assembleDebug` 可编译 | ❌ **未完成** — 卡在 `compileDebugKotlin` |
| P1 四个 Activity Adapter | ⚠️ **大部分已完成** — 需验证能编译运行 |
| P1 对账页金额字段 | ✅ **已完成** |
| P1 草稿确认入账 | ⚠️ **逻辑已写在 Activity，但 DAO 类型错误导致编译失败** |
| P1 草稿金额多账单合计 | ✅ **已完成**（`parseTotalAmount`） |
| P2 迁移测试 26→30 | ❌ **无** |
| P2 驾驶舱 Provider | ❌ **仍返回空列表** |
| 数据库 26→30 迁移设计 | ✅ **OK**（4 段迁移已注册，只增不删） |
| v30 降级装 v26 | ⚠️ **会闪退**（已知限制，见 §12） |

验证命令（修复后必须全绿）：

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

---

## 2. 第二轮 P0：Kotlin 编译错误（必须全部修完）

以下错误来自 `./gradlew compileDebugKotlin`，**不修无法安装**。

### P0-A. `AccountingDraftDao.deleteById` 参数类型错误

**文件：** `app/src/main/java/com/taostudio/tapaccounting/data/local/dao/AccountingDraftDao.kt`

**问题：** 实体 `AccountingDraft.id` 是 **String**（UUID），但 DAO 写成了 `Long`：

```kotlin
// 错误
suspend fun deleteById(id: Long)

// 应改为
suspend fun deleteById(id: String)
```

**连带影响：** `AccountingDraftRepository.delete()` / `deleteBatch()` 编译报错：

```text
Argument type mismatch: actual type is 'kotlin.String', but 'kotlin.Long' was expected.
```

---

### P0-B. `InsightEngine.kt` 拼写错误

**文件：** `app/src/main/java/com/taostudio/tapaccounting/logic/insight/InsightEngine.kt`  
**约第 232–233 行**

```kotlin
val toleranceOk = amounts.all { ... }
if (!tolanceOk) continue   // ❌ 拼写错
```

改为：

```kotlin
if (!toleranceOk) continue
```

---

### P0-C. `AssetReconcileActivity.kt` 非法 return 标签

**文件：** `app/src/main/java/com/taostudio/tapaccounting/ui/activity/AssetReconcileActivity.kt`  
**约第 56–58 行**

```kotlin
if (asset == null) {
    finish()
    return@lifecycleScope   // ❌ 标签错误
}
```

`lifecycleScope.launch { }` 块内应使用：

```kotlin
return@launch
```

---

### P0-D. `AccountingDraftInboxActivity.kt` 传错参数类型

**文件：** `app/src/main/java/com/taostudio/tapaccounting/ui/draft/AccountingDraftInboxActivity.kt`  
**约第 207 行**

```kotlin
// 错误：第一个参数是 Activity
BillMutationService.insertBillAndApplyImpact(this@AccountingDraftInboxActivity, bill)

// 正确：第一个参数是 AppDatabase
BillMutationService.insertBillAndApplyImpact(db, bill)
```

`BillMutationService.insertBillAndApplyImpact` 签名：

```kotlin
suspend fun insertBillAndApplyImpact(db: AppDatabase, bill: Bill, applyAssetImpact: Boolean = true): Bill
```

Activity 内已有 `private lateinit var db: AppDatabase`，直接传 `db`。

---

### P0-E. `ImportReviewActivity.kt` DAO 方法名错误

**文件：** `app/src/main/java/com/taostudio/tapaccounting/ui/import/ImportReviewActivity.kt`

| 错误写法 | 正确写法 |
|----------|----------|
| `db.assetDao().delete(tempAsset)` | `db.assetDao().deleteAsset(tempAsset)` |
| `db.assetDao().update(asset.copy(...))` | `db.assetDao().updateAsset(asset.copy(...))` |

参考 `AssetDao.kt` 中的实际方法名。

---

### P0-F. `RecurringBillsActivity.kt` 局部函数 forward reference

**文件：** `app/src/main/java/com/taostudio/tapaccounting/ui/recurring/RecurringBillsActivity.kt`

**问题：** `RecurringPatternAdapter` 的 `onConfirm` / `onDismiss` 回调里调用了 `loadBills()`，但 `fun loadBills(...)` 定义在 Adapter **之后**。Kotlin 报：

```text
Unresolved reference 'loadBills'.
```

**修复：** 将 `fun loadBills(status: RecurringStatus)` **移到 Adapter 创建之前**；或改用 `lateinit var loadBillsRef` / 类成员函数。

---

### P0-G. `HomeDashboardAdapter.kt` sealed class 属性访问

**文件：** `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/dashboard/HomeDashboardAdapter.kt`  
**约第 57–58 行**

```kotlin
// 错误：HomeDashboardCard 基类没有 title/body
tvTitle.text = card.title
tvBody.text = card.body
```

**修复：** 用 `when (card)` 分支取值：

```kotlin
when (card) {
    is HomeDashboardCard.PendingActions -> {
        tvTitle.text = card.title
        tvBody.text = card.body
    }
    is HomeDashboardCard.BudgetProgress -> { ... }
    is HomeDashboardCard.Reminder -> { ... }
}
```

---

### P0-H. `HomeAdapter.kt` 缺少 import

**文件：** `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeAdapter.kt`  
**约第 362、374 行** 使用了 `LinearLayoutManager` 但未 import。

补上：

```kotlin
import androidx.recyclerview.widget.LinearLayoutManager
```

---

### P0-I. `StatsViewModel.kt` 缺少 import

**文件：** `app/src/main/java/com/taostudio/tapaccounting/ui/main/stats/StatsViewModel.kt`  
**约第 94 行** 使用了 `StateFlow` 但未 import。

补上：

```kotlin
import kotlinx.coroutines.flow.StateFlow
```

**连带修复：** `StatsFragment.kt` 对 `viewModel.insightCards.collect` 的报错会随之消失。

---

### P0 验收

```bash
./gradlew compileDebugKotlin
./gradlew assembleDebug
```

**零编译错误。**

---

## 3. 第一轮已完成项（不要重复做、不要删）

以下工作 **已经完成**，修复 AI 不要回退：

### 3.1 布局 XML（5 个文件已补 `xmlns:app`）

```
activity_draft_inbox.xml
activity_budget_manage.xml
activity_asset_reconcile.xml
activity_import_review.xml
activity_import_onboarding.xml
```

### 3.2 Adapter 已存在

| 组件 | 文件 |
|------|------|
| 预算列表 | `ui/budget/BudgetAdapter.kt` + `BudgetManageActivity` 已接入 |
| 草稿列表 | `ui/draft/DraftAdapter.kt` + `AccountingDraftInboxActivity` 已接入 |
| 周期账单 | `ui/recurring/RecurringPatternAdapter.kt` |
| 导入审查 | `ui/import/TempAssetAdapter.kt` |

**不要**再写 `// TODO: 设置 XxxAdapter`，只需修编译错误并验证列表能显示。

### 3.3 对账页已修复

`AssetReconcileActivity` 已正确绑定：

- `tvActualBalance` → `report.actualBalance`
- `tvDiff` → `report.diff`
- `showCausesAndBills()` 已实现原因和相关账单展示

### 3.4 草稿确认入账逻辑已写在 Activity

`AccountingDraftInboxActivity` 已有：

- `saveDraftAsBill()` — 解析 JSON 并调用 `BillMutationService`
- `confirmSingleDraft()` / 批量确认流程
- `parseTotalAmount()` — 支持 `{"bills":[...]}` 多账单格式

**剩余工作：** 修 P0-D / P0-A 让这条链路能编译；可选把保存逻辑下沉到 `AccountingDraftRepository` 或 `AccountingDraftConfirmService`（非必须，能跑即可）。

---

## 4. 第二轮 P1：编译通过后需验证的功能

### P1-1. 预算页可编辑 / 可删除

`BudgetManageActivity` 已有 Adapter，确认：

- 点击行可编辑预算金额
- 可删除预算
- “根据历史推荐”按钮有反馈

若编辑/删除未实现，补最小交互（`AlertDialog` 即可）。

### P1-2. 周期账单页 Tab 切换

修完 P0-F 后验证：

- 待确认 / 已确认 / 已忽略 三个 Tab 能切换
- 确认 / 忽略 后列表刷新

### P1-3. 导入审查合并 / 重命名

修完 P0-E 后验证：

- 临时资产列表能展示
- 合并到已有资产后，账单 `accountId` 转移、临时资产删除
- 重命名后 `type`/`remark` 恢复正常

### P1-4. 草稿箱端到端

修完 P0-A / P0-D 后验证：

1. 手动插入一条 `PENDING` draft（payload 含 `"amount":35`）
2. 打开 `AccountingDraftInboxActivity`
3. 点确认 → `bills` 表新增记录，首页统计变化，draft 变 `CONFIRMED`
4. 批量删除能删掉 draft 记录

### P1-5. 洞察卡片 / 统计页

修完 P0-B / P0-I 后验证：

- 首页洞察区（开关 `insight_cards_enabled`）不崩溃
- 统计页洞察列表不崩溃
- 有账单时能出至少 1 张卡片（如环比）

---

## 5. 第二轮 P2：质量项（时间允许再做）

### P2-1. Room 迁移测试 26→30

当前 **没有** 针对新迁移的测试。要求：

- 使用 `MigrationTestHelper` 或等价方案
- 从 v26 fixture 升级到 v30
- 断言新表存在、旧数据保留

### P2-2. `HomeDashboardProvider` 接入草稿数量

**文件：** `ui/main/home/dashboard/HomeDashboardProvider.kt`

当前仍返回空列表。至少：

```kotlin
val pendingCount = db.accountingDraftDao().countPending()
if (pendingCount > 0) {
    cards.add(
        HomeDashboardCard.PendingActions(
            count = pendingCount,
            title = "待确认账单",
            body = "你有 $pendingCount 笔待确认",
            action = InsightAction(...跳转 AccountingDraftInboxActivity...)
        )
    )
}
```

修完 P0-G 后驾驶舱 Adapter 才能正确渲染。

### P2-3. 备份模块纳入新表

检查 `BackupRepository` / `BackupActivity`：

- `budgets`、`recurring_patterns`、`accounting_drafts` 是否导出/恢复
- `assets.statementDay` / `dueDay` 是否一并备份

若本期不做，在交付说明写明 **“预算/周期/草稿暂不包含在备份中”**。

### P2-4. `ChatActivity.pendingHabitSuggestion` 死代码

已定义但从未赋值。要么接通 `AiRuleSuggestionCoordinator`，要么删除，不要悬空。

---

## 6. 数据库兼容性说明（给修复 AI 参考，一般不需改代码）

| 场景 | 结果 |
|------|------|
| 全新安装 v30 | ✅ Room 直接建 v30，不应因 DB 闪退 |
| v26 升级到 v30 | ✅ 4 段迁移已注册（26→27→28→29→30），旧数据保留 |
| v30 降级装 v26 | ❌ **会闪退** — `Migration from 30 to 26 was required but not found` |

**不要**为降级加 `fallbackToDestructiveMigration()`（会清空全部账单）。

迁移定义位置：`AppDatabase.kt` 中 `MIGRATION_26_27` ~ `MIGRATION_29_30`。

---

## 7. 建议修复顺序（第二轮）

```text
1. P0-A  AccountingDraftDao.deleteById → String
2. P0-B  InsightEngine tolanceOk 拼写
3. P0-C  AssetReconcileActivity return@launch
4. P0-D  AccountingDraftInboxActivity 传 db
5. P0-E  ImportReviewActivity deleteAsset/updateAsset
6. P0-F  RecurringBillsActivity loadBills 顺序
7. P0-G  HomeDashboardAdapter when(card)
8. P0-H  HomeAdapter import LinearLayoutManager
9. P0-I  StatsViewModel import StateFlow
10. ./gradlew assembleDebug 确认全绿
11. P1 手工验收 5 个新页面
12. P2 迁移测试 / Dashboard 草稿数（可选）
```

---

## 8. 不要做的事

1. 不要恢复 `docs/archived-agent-code/` Agent 系统  
2. 不要改动 AI 查询助手（`ai_query_enabled` 默认 false）  
3. 不要删除已写好的 Adapter / 新表 / 新 Activity  
4. 不要为实现降级而 `fallbackToDestructiveMigration()`  
5. 不要引入新的大型依赖  
6. 不要重写 `AppDatabase` 迁移（26→30 设计已 OK，除非测试发现 SQL 与实体不一致）

---

## 9. 交付前自检清单

- [ ] `./gradlew assembleDebug` 成功  
- [ ] `./gradlew testDebugUnitTest` 通过（既有无关失败可注明，新增测试必须通过）  
- [ ] 新安装冷启动不崩溃  
- [ ] 从 v26 升级后不崩溃，旧账单仍在  
- [ ] 预算 / 周期 / 草稿 / 导入审查 / 对账 5 页可打开  
- [ ] 草稿确认后 `bills` 表真有新记录  
- [ ] 对账页：账本 1000、输入 1200 → 差额 +200  
- [ ] 交付说明写明：**不支持 v30 降级到 v26**

---

## 10. 关键文件索引

| 类型 | 路径 |
|------|------|
| 数据库 | `data/local/AppDatabase.kt` |
| 草稿 DAO | `data/local/dao/AccountingDraftDao.kt` |
| 草稿 Inbox | `ui/draft/AccountingDraftInboxActivity.kt` |
| 草稿仓库 | `data/repository/AccountingDraftRepository.kt` |
| 账单写入 | `logic/BillMutationService.kt` |
| 洞察引擎 | `logic/insight/InsightEngine.kt` |
| 统计 VM | `ui/main/stats/StatsViewModel.kt` |
| 首页 Adapter | `ui/main/home/HomeAdapter.kt` |
| 驾驶舱 | `ui/main/home/dashboard/HomeDashboardAdapter.kt` |
| 驾驶舱 Provider | `ui/main/home/dashboard/HomeDashboardProvider.kt` |
| 周期账单 | `ui/recurring/RecurringBillsActivity.kt` |
| 导入审查 | `ui/import/ImportReviewActivity.kt` |
| 对账 | `ui/activity/AssetReconcileActivity.kt` |
| 资产 DAO | `data/local/dao/AssetDao.kt` |
| 功能需求原文 | `docs/feature-optimization-implementation-prompts.md` |

---

## 11. 交给修复 AI 的复制粘贴指令

```text
请阅读并严格按 docs/feature-optimization-fix-prompt.md（第二轮）执行。

目标：让 ./gradlew assembleDebug 通过，并验证 P1 五项功能。

规则：
- 只修文档中列出的 P0/P1/P2 项，不要扩 scope
- 不要删除已有 Adapter 和新表
- 不要动 AI 查询助手
- 修完后运行 assembleDebug 和 testDebugUnitTest
- 交付说明列出：改了什么、测了什么、不支持 v30 降级 v26
```

---

*文档版本：2026-06-23（第二轮审查更新）*  
*上一版问题：第一轮修复未完成编译；本文档基于实际 compileDebugKotlin 报错更新*

---

## 12. 第三轮（接线与入口）

第二轮编译修复完成后，请继续执行：

**`docs/feature-optimization-wiring-prompt.md`**

覆盖：6 个 Activity 的 UI 入口、`HomeDashboardProvider`、周期检测、草稿箱主流程、信用卡周期 UI、迁移测试与备份。
