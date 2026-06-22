# 修复提示词模板

每个批次新开一个窗口，复制以下内容（替换批次号和具体发现）：

---

我有一个 Android 记账应用项目，需要你帮我修复代码审计发现的 Bug。

## 项目位置
e:\FlipAccounting-AI

## 要修的 Bug

（从下面选对应批次的内容粘贴）

## 要求
1. 先读相关源文件，理解现有代码
2. 给出修复方案让我确认
3. 确认后执行修改
4. 改完后检查是否有遗漏或副作用
5. 不要改无关代码

---

## 批次 1：数据一致性（优先级最高）

### Bug 1: 编辑账单时资产影响在事务外应用
- 文件: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt`
- 问题: handleSave() 编辑路径调用 replaceBill(applyAssetImpact=false)，旧余额在事务内撤销，新余额在事务外应用。崩溃窗口导致数据损坏。
- 修复: 将 applyAssetImpact 改为 true，删除事务外的 applyBillBalanceImpact 调用。注意退款路径 saveRefundBill 已在事务内处理，不需要改。
- 需要同时检查: BillMutationService.replaceBill()、BillMutationService.upsertBillAndApplyImpact() 的事务逻辑

### Bug 2: handleSave 无重入保护
- 文件: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt`
- 问题: 无 isSaving 标志，快速点击可创建重复账单
- 修复: 添加 isSaving 标志位 + btnSave.isEnabled = false，在 finally 中恢复。注意所有 early-return 路径都要重置标志。

### Bug 3: BillDao.clearCategoryByName 是空操作
- 文件: `app/src/main/java/com/taostudio/tapaccounting/data/local/dao/BillDao.kt`
- 问题: WHERE categoryId IS NULL 然后 SET categoryId = NULL，等于啥也没干
- 修复: 改为 SET categoryName = ''（清空名称而非重复写 NULL）
- 需要同时检查: CategoryRepository.deleteCategoryAndMigrateBills 调用此方法的地方

---

## 批次 2：数据正确性

### Bug 4: 删除-恢复退款账单后余额偏差
- 文件: `app/src/main/java/com/taostudio/tapaccounting/logic/BillRestoreHelper.kt`
- 问题: 删除时用 baseOriginalAmount 撤销（全额），恢复时用 bill.amount（扣退款后），不对称。每次删除-恢复循环多出退款金额。
- 修复: 恢复支出账单时与删除对称，使用 baseOriginalAmount
- 需要同时检查: BillDeleteHelper、BillAssetImpactService.baseOriginalAmount()、BillRestoreHelper 的完整逻辑

### Bug 5: 合并恢复不重映射聊天消息的账单引用
- 文件: `app/src/main/java/com/taostudio/tapaccounting/data/repository/BackupRepository.kt`
- 问题: restoreFullData 有 remapChatBillReferences，mergeRestoreFullData 漏了
- 修复: 在 merge 的账单插入循环中维护 billIdMap，聊天消息插入前调用 remapChatBillReferences
- 需要同时检查: remapChatBillReferences 方法的实现、billIdMap 的构建方式

### Bug 6: 零汇率当 1:1 处理
- 文件: `app/src/main/java/com/taostudio/tapaccounting/logic/MoneyConversionService.kt`
- 问题: fromRate == 0.0 时返回原始金额（当人民币处理），而非报错
- 修复: 改为 throw MissingCurrencyRateException
- 需要同时检查: 所有调用 convertAmountBetweenCurrencies 的地方是否都有异常处理

### Bug 7: 日元/韩元四舍五入到 2 位小数
- 文件: `app/src/main/java/com/taostudio/tapaccounting/logic/MoneyConversionService.kt`
- 问题: roundMoney() 固定 2 位小数，JPY/KRW 应该是 0 位
- 修复: 添加 roundMoneyForCurrency(amount, currencyCode) 重载，使用 CurrencyUtils.decimalPlaces()
- 需要同时检查: BillAssetImpactService 中所有调用 roundMoney 的地方，传入正确的 currency

---

## 批次 3：优化与健壮性

### Bug 8: MainActivity commitNow() 在动画回调中
- 文件: `app/src/main/java/com/taostudio/tapaccounting/MainActivity.kt`
- 问题: 第 591、648 行在 onAnimationEnd 中调用 commitNow()，设备旋转时可能抛 IllegalStateException
- 修复: 改为 commitNowAllowingStateLoss()

### Bug 9: 图片压缩失败时 Bitmap 未回收
- 文件: `app/src/main/java/com/taostudio/tapaccounting/ChatMediaController.kt`
- 问题: compressImageInPlace() 中 bitmap.recycle() 不在 try-finally 中
- 修复: 用 try-finally 包裹 compress 调用

### Bug 10: BillBalanceSnapshotService N+1 更新
- 文件: `app/src/main/java/com/taostudio/tapaccounting/logic/BillBalanceSnapshotService.kt`
- 问题: 循环内逐条 UPDATE，500 条 = 500 次 SQL
- 修复: 考虑批量更新或评估是否可移除该 legacy 路径（UI 已用内存推导）

### Bug 11: TapDetector 每次传感器事件读 SharedPreferences
- 文件: `app/src/main/java/com/taostudio/tapaccounting/tap/TapDetector.kt`
- 问题: onSensorChanged 中每次调 Prefs.isTapTripleEnabled(context)，400Hz
- 修复: 缓存到 volatile boolean，仅在设置变更时刷新

### Bug 12: Bill 表缺少 (bookName, time) 复合索引
- 文件: `app/src/main/java/com/taostudio/tapaccounting/data/local/entity/Bill.kt`
- 问题: 主查询只有单列索引
- 修复: 添加 @Index(value = ["bookName", "time"])，创建 MIGRATION_24_25
