# 代码审计验证提示词

## 背景

我有一个 Android 记账应用项目 FlipAccounting-AI，刚完成了一次全面代码审计，产出了 230 个发现。我需要你帮我**逐一验证以下最关键的发现**，判断哪些是真实 Bug，哪些是误报或严重程度被高估了。

## 你的任务

对每个发现，请：
1. **读取对应的源文件**，找到报告描述的具体代码位置
2. **判断是否真实存在**该问题
3. **评估实际严重程度**（可能比报告说的更高或更低）
4. **如果确认是 Bug**，给出具体的修复方案和代码
5. **如果是误报**，解释为什么不是问题

## 需要验证的发现

### 🔴 Critical 级（最高优先级验证）

#### 1. 编辑账单时资产影响在事务外应用
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt` 约 1704-1775 行
- **声称的问题**: handleSave() 调用 BillMutationService.replaceBill(applyAssetImpact=false)，旧账单余额在事务内撤销，但新账单余额在事务外应用。如果 app 崩溃在两者之间，资产余额永久损坏。
- **请验证**: 读 AccountingFormController.handleSave() 和 BillMutationService.replaceBill()，确认事务边界是否真的有这个间隙。

#### 2. 快照余额推导使用实时汇率而非历史汇率
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AssetBillBalanceHistory.kt` 约 81-83 行
- **声称的问题**: computeBalanceAfterByBillId 向后推导余额时，signedBalanceDelta 使用当前实时汇率而非账单创建时的汇率。汇率变化后历史余额全错。
- **请验证**: 读 AssetBillBalanceHistory 和 BillAssetImpactService 的汇率使用逻辑，确认是否真的用的是实时汇率。

#### 3. 转账目标端 delta 未四舍五入
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillAssetImpactService.kt` 约 292-304 行
- **声称的问题**: sourceDeltaInCurrency 经过 roundMoney()，但 targetDeltaInCurrency 直接返回 bill.amount * bill.exchangeRate 没有四舍五入。
- **请验证**: 读这两个函数，确认是否存在四舍五入的不对称。

#### 4. handleSave 无重入保护，可创建重复账单
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt` 约 1425-1863 行
- **声称的问题**: handleSave() 没有 isSaving 守卫。IO 协程执行期间按钮仍可点击，快速点击可创建重复账单。
- **请验证**: 读 handleSave() 完整流程，确认是否有按钮禁用、标志位或其他防重入机制。

#### 5. MainActivity commitNow() 在动画回调中必崩
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/MainActivity.kt` 约 591, 648 行
- **声称的问题**: commitSwipe() 和 snapBack() 的 onAnimationEnd 回调中调用 commitNow()，设备旋转时必抛 IllegalStateException。
- **请验证**: 读这些动画回调的代码，确认 commitNow vs commitNowAllowingStateLoss 的使用情况。

#### 6. 缺少数据库版本 1-5 的 Room 迁移
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/AppDatabase.kt` 约 338-358 行
- **声称的问题**: 只有 MIGRATION_5_6 到 MIGRATION_23_24，缺少 v1-v5 的迁移。早期用户升级会崩溃。
- **请验证**: 读 AppDatabase 的 addMigrations() 和 builder 配置，确认是否有 fallbackToDestructiveMigration 或其他保护。

### 🟠 High 级（第二批验证）

#### 7. 零汇率当 1:1 处理
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/MoneyConversionService.kt` 约 47-48 行
- **声称的问题**: fromRate == 0.0 时返回原始金额（当人民币处理），而非报错。
- **请验证**: 读 convertAmountBetweenCurrencies 的完整逻辑。

#### 8. 日元/韩元四舍五入到 2 位小数
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/MoneyConversionService.kt` 约 82-84 行
- **声称的问题**: roundMoney() 始终 round 到 2 位小数，对 JPY/KRW 等零小数货币不正确。
- **请验证**: 读 roundMoney 和 CurrencyUtils.decimalPlaces()，确认是否有货币感知的四舍五入。

#### 9. BillDao.clearCategoryByName 是空操作
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/dao/BillDao.kt` 约 330-341 行
- **声称的问题**: WHERE categoryId IS NULL 然后 SET categoryId = NULL，等于啥也没干。
- **请验证**: 读这个 SQL 方法，确认是否真的是空操作。

#### 10. 删除-恢复退款账单后余额偏差
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillRestoreHelper.kt` 约 46-53 行
- **声称的问题**: 删除时用 baseOriginalAmount 撤销（退款前金额），恢复时用 bill.amount 应用（退款后金额），不对称。
- **请验证**: 对比 BillDeleteHelper 和 BillRestoreHelper 对退款账单的处理逻辑。

#### 11. 查询"本月交通花了多少"触发资产消歧
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/chat/query/QueryPlanner.kt` 约 179-189 行
- **声称的问题**: validateOrClarify 对所有意图都做资产名称匹配，类别查询也可能被拦截。
- **请验证**: 读 validateOrClarify 的逻辑，确认是否限定了意图类型。

#### 12. 合并恢复不重映射聊天消息的账单引用
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/BackupRepository.kt` 约 298-303 行
- **声称的问题**: restoreFullData 有 remapChatBillReferences，但 mergeRestoreFullData 漏了。
- **请验证**: 对比两个恢复方法的聊天消息处理逻辑。

#### 13. AiAssistant CoroutineScope 永不取消
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AiAssistant.kt` 约 42 行
- **声称的问题**: SupervisorJob() + Dispatchers.IO 的 scope 从未 cancel，dismiss() 只取消 analyzeJob。
- **请验证**: 读 AiAssistant 的生命周期管理，确认是否有任何路径会取消 scope。

#### 14. 图片压缩失败时 Bitmap 未回收
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatMediaController.kt` 约 455-469 行
- **声称的问题**: compressImageInPlace() 中 bitmap.recycle() 不在 try-finally 中，compress 失败则泄漏。
- **请验证**: 读这个方法的异常处理逻辑。

### 🟡 性能问题（快速验证几个关键的）

#### 15. BillBalanceSnapshotService N+1 更新
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillBalanceSnapshotService.kt` 约 32-38 行
- **声称的问题**: 循环内逐条 UPDATE，500 条账单 = 500 次 SQL。
- **请验证**: 读 rebuildSnapshotsForAsset 确认是否在循环内调 updateBill。

#### 16. TapDetector 每次传感器事件读 SharedPreferences
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapDetector.kt` 约 171 行
- **声称的问题**: onSensorChanged 中每次调 Prefs.isTapTripleEnabled(context)，400Hz。
- **请验证**: 读 onSensorChanged 确认是否有缓存机制。

#### 17. Bill 表缺少 (bookName, time) 复合索引
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/entity/Bill.kt`
- **声称的问题**: 最常用的 getBillsByBookNamesBetweenTimes 查询只有单列索引。
- **请验证**: 读 Bill entity 的 @Index 注解和 DAO 的查询方法。

## 输出格式

对每个发现，输出：

```
### 发现 N: [标题]
- **结论**: ✅ 确认 / ❌ 驳回 / ⚠️ 部分确认
- **实际严重程度**: Critical / High / Medium / Low / 非 Bug
- **分析**: [你读了代码后的判断，引用具体行号]
- **修复建议**: [如果确认是 Bug，给出具体代码修复]
```

## 项目结构

- 源码在 `app/src/main/java/com/taostudio/tapaccounting/`
- 业务逻辑在 `logic/`
- 数据层在 `data/`
- UI 在 `ui/`
- AI 聊天在 `chat/`
- Tap 引擎在 `tap/`
- Server 模块在 `server/src/main/java/org/ezbook/server/`

请从 Critical 级开始逐个验证，时间充裕再验证 High 级。每个结论都要有代码证据支撑。
