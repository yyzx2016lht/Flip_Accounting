# TapAccounting 项目全景文档

> 最后更新: 2026-06-23
> 包名: `com.taostudio.tapaccounting`
> 版本: 1.2 (versionCode 2)

---

## 1. 应用概述

### 1.1 应用名称与定位

**敲敲记账** (TapAccounting) 是一款 **AI 驱动的个人记账 Android 应用**。与传统表单式记账不同，用户可以通过自然语言、语音、拍照、截屏等方式完成记账，由云端 LLM 进行意图识别和信息提取，本地系统负责 JSON Schema 校验、规则纠错和数据库持久化。

核心流程:

```
自然语言 / 语音 / 图片 / 截屏 --> AI 理解 --> 结构化账单 --> 本地数据库执行
```

### 1.2 目标用户

- 需要快速、便捷记账的中文用户
- 希望通过语音、拍照等非传统方式录入账单的用户
- 需要多币种、多账本、投资理财管理的用户

### 1.3 SDK 版本

| 项目 | 值 |
|------|-----|
| minSdk | 24 (Android 7.0) |
| targetSdk | 34 (Android 14) |
| compileSdk | 34 |
| ABI filter | arm64-v8a |

---

## 2. 技术架构

### 2.1 分层架构

```
+-----------------------------------------------------------+
|                        UI 层                               |
|  Activities (38+) / Fragments (4+1) / Controllers         |
|  Adapters / Custom Views / Dialogs                        |
+-----------------------------------------------------------+
|                     业务逻辑层 (logic/)                     |
|  AccountingFormController (表单控制)                        |
|  BillMutationService (账单 CRUD)                           |
|  BillAssetImpactService (资产余额变动)                      |
|  CurrencyManager / MoneyConversionService (汇率转换)        |
|  InvestmentInterestService (投资利息计算)                    |
|  VoiceInputHandler (语音输入)                               |
+-----------------------------------------------------------+
|                      数据层 (data/)                         |
|  Repository: BillRepo / AssetRepo / CategoryRepo / BackupRepo |
|  DAO: BillDao / AssetDao / CategoryDao / ChatMessageDao 等   |
|  Entity: Bill / Asset / Category / ChatMessage 等            |
|  Room Database (SQLite, version 26)                          |
+-----------------------------------------------------------+
|                    基础设施层                                |
|  AI 集成 (Retrofit + 多 Provider)                           |
|  备份系统 (本地 + WebDAV 云端)                               |
|  Tap/Flip 手势检测 (TFLite + 传感器)                        |
|  WorkManager 后台任务                                        |
+-----------------------------------------------------------+
```

### 2.2 技术栈

| 领域 | 技术 |
|------|------|
| 语言 | Kotlin (仅 1 个 Java 文件: ShizukuHelper.java) |
| 数据库 | Room 2.6.1 (SQLite) |
| 网络 | Retrofit 2.9.0 + OkHttp 4.12.0 |
| 异步 | Kotlin Coroutines 1.7.3 |
| OCR | ML Kit Text Recognition Chinese 16.0.0 |
| 语音 | sherpa-onnx (本地 Whisper ASR) + 云端 ASR |
| 图表 | MPAndroidChart 3.1.0 |
| 图片加载 | Glide 4.16.0 |
| 图片裁剪 | UCrop 2.2.8 |
| ML 推理 | TensorFlow Lite 2.14.0 (敲击检测) |
| 系统集成 | Shizuku 13.1.5, Accessibility Service |
| 日历 | Kizitonwose Calendar 2.4.0 |
| 后台任务 | WorkManager 2.9.0 |

---

## 3. 数据模型

### 3.1 数据库概览

- 数据库名: `TapAccount_database`
- 当前版本: **26**
- 表数量: **7**
- 迁移历史: 21 次迁移 (v5 -> v6 ... v25 -> v26)

### 3.2 实体定义

#### Bill (表: `bills`)

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| id | Long | auto | 主键 |
| type | Int | - | 0=支出, 1=收入, 2=转账, 3=还款 |
| subType | Int | 0 | 0=普通, 1=还款, 2=退款, 3=余额调整, 4=余额调整(排除统计) |
| amount | Double | - | 折算后金额 |
| originalAmount | Double | = amount | 折算前原始金额 |
| currency | String | "CNY" | 币种 |
| exchangeRate | Double | 1.0 | 汇率 |
| categoryId | Long? | null | FK -> Category (SET NULL) |
| accountId | Long? | null | FK -> Asset (SET NULL) |
| toAccountId | Long? | null | FK -> Asset (SET NULL), 转账目标 |
| categoryName | String | "" | 冗余分类名 |
| accountName | String | "" | 冗余账户名 |
| toAccountName | String | "" | 冗余目标账户名 |
| time | Long | - | 时间戳(ms) |
| remark | String | "" | 备注 |
| fee | Double | 0.0 | 手续费 |
| accountBalanceAfter | Double? | null | 记账后余额快照 |
| toAccountBalanceAfter | Double? | null | 转账目标余额快照 |
| bookName | String | "日常账本" | 账本名 |
| relatedBillId | Long? | null | 关联账单(退款->原单) |
| isSynced | Boolean | false | 同步标记 |
| excludeFromStats | Boolean | false | 排除统计 |

**索引**: categoryId, accountId, toAccountId, time, bookName, relatedBillId, 复合索引 (bookName, time)

#### Asset (表: `assets`)

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| id | Long | auto | 主键 |
| name | String | - | 资产名称 |
| type | String | - | 类型标签(如"招商银行") |
| balance | Double | 0.0 | 当前余额 |
| initialBalance | Double | 0.0 | 初始余额 |
| currency | String | "CNY" | 币种 |
| icon | String | "" | 图标 URL 或内置 ID |
| remark | String | "" | 备注 |
| includeInNetAsset | Boolean | true | 是否计入净资产 |
| sortOrder | Int | 0 | 资产列表排序 |
| pickerSortOrder | Int | 0 | 选择器排序 |
| createTime | Long | now | 创建时间 |
| showBillBalanceAfter | Boolean | true | 是否显示账单余额 |
| billBalanceFromTime | Long | 0 | 余额推算起始时间 |
| assetCategory | String | "FUND" | FUND/CREDIT_CARD/RECHARGE/INVESTMENT |
| creditLimit | Double | 0.0 | 信用卡额度 |
| billingDay | Int | 0 | 账单日(预留) |
| annualInterestRate | Double | 0.0 | 年化利率(%) |
| interestLastSettledAt | Long | now | 上次利息结算时间 |
| isArchived | Boolean | false | 是否归档 |
| includeInNetBeforeArchive | Boolean | true | 归档前是否计入净资产 |

#### Category (表: `categories`)

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| id | Long | auto | 主键 |
| name | String | - | 分类名 |
| type | Int | - | 0=支出, 1=收入 |
| parentId | Long? | null | 父分类 FK (SET NULL) |
| iconId | String | "" | 图标 ID |
| sortOrder | Int | 0 | 排序 |

#### AiRule (表: `ai_rule`)

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| id | Int | auto | 主键 |
| keyword | String | - | 关键词(如"花呗") |
| targetType | Int? | null | 0=支出, 1=收入, 2=转账 |
| targetCategory | String? | null | 目标分类 |
| targetAccount1 | String? | null | 来源账户 |
| targetAccount2 | String? | null | 目标账户(转账) |
| isEnabled | Boolean | true | 是否启用 |

#### ChatMessage (表: `chat_messages`)

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| id | Long | auto | 主键 |
| msgType | Int | - | 0=用户文本, 1=用户图片, 2=用户语音, 3=AI文本, 4=AI账单JSON |
| content | String | "" | 文本或 JSON |
| imageUri | String | "" | 图片 URI (msgType=1) |
| timestamp | Long | now | 时间戳 |
| billIds | String | "" | 关联账单 ID JSON 数组 (msgType=4) |
| modelName | String | "" | AI 模型名 |
| bookName | String | "" | 所属账本 |
| conversationId | String | "" | 会话 ID |

#### InvestmentLot (表: `investment_lots`)

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| id | Long | auto | 主键 |
| assetId | Long | - | FK -> Asset (CASCADE) |
| sourceBillId | Long? | null | FK -> Bill (SET NULL), 唯一索引 |
| principalAmount | Double | - | 原始本金 |
| remainingPrincipal | Double | - | 剩余计息本金 |
| currency | String | - | 币种 |
| startEarningAt | Long | - | 开始计息时间 |
| firstPayoutAt | Long | - | 首次付息日 |
| lastSettledAt | Long | - | 上次结算时间 |
| createTime | Long | now | 创建时间 |

#### DeletedBill (表: `deleted_bills`)

镜像 Bill 字段 (去掉 accountBalanceAfter, toAccountBalanceAfter, isSynced)，额外增加:

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| originalBillId | Long | - | 原账单 ID |
| deletedAt | Long | now | 删除时间 |

### 3.3 实体关系

```
Category 1---* Category (parent-child)
Category 1---* Bill (categoryId FK)
Asset 1---* Bill (accountId FK)
Asset 1---* Bill (toAccountId FK, 转账)
Bill 1---* Bill (relatedBillId, 退款关联)
Asset 1---* InvestmentLot (assetId FK, CASCADE)
Bill 1---0..1 InvestmentLot (sourceBillId FK, 唯一)
```

### 3.4 迁移历史 (v5 -> v26)

| 迁移 | 变更内容 |
|------|---------|
| 5->6 | bills 增加 `fee` |
| 6->7 | assets 增加 `creditLimit` |
| 7->8 | bills 增加 `relatedBillId` + 索引 |
| 8->9 | assets 增加 `billingDay` |
| 9->10 | assets 增加 `pickerSortOrder` |
| 10->11 | 创建 `chat_messages` 表 |
| 11->12 | 重建 chat_messages (增加 bookName, conversationId) |
| 12->13 | assets 增加 `annualInterestRate`, `interestLastSettledAt` |
| 13->14 | 创建 `investment_lots` 表 |
| 14->15 | 重建 investment_lots (修复唯一索引) |
| 15->16 | bills 增加 `excludeFromStats` |
| 16->17 | 创建 `deleted_bills` 表 |
| 17->18 | 数据迁移: 规范化余额调整子类型 |
| 18->19 | assets 增加 `isArchived` |
| 19->20 | 归档资产设置 includeInNetAsset=0 |
| 20->21 | assets 增加 `billBalanceFromTime`, `showBillBalanceAfter`; bills 增加余额快照字段 |
| 21->22 | 修复 showBillBalanceAfter 默认值, 回填 billBalanceFromTime |
| 22->23 | 修复 billBalanceFromTime 基于最早关联账单时间 |
| 23->24 | assets 增加 `includeInNetBeforeArchive` |
| 24->25 | 创建复合索引 `index_bills_bookName_time` |
| 25->26 | 为已有投资资产生成 InvestmentLot 种子数据 |

---

## 4. 核心业务逻辑

所有业务逻辑位于 `logic/` 包下，共 **16 个生产类**。

### 4.1 主要 Service/Controller 职责

| 类 | 类型 | 行数 | 职责 |
|----|------|------|------|
| AccountingFormController | class | ~2529 | 记账表单 UI 控制器，管理类型选择、金额输入、资产选择、币种确认、退款模式、多账单队列 |
| BillAssetImpactService | object | ~309 | 账单对资产余额的影响/撤销引擎 |
| BillMutationService | object | ~281 | 账单 CRUD 操作，封装 Room 事务和余额校验 |
| BillDeleteHelper | object | ~187 | 账单删除(软删除 + 余额回退) |
| BillRestoreHelper | object | ~213 | 已删除账单恢复(ID 重映射) |
| CurrencyManager | object | ~265 | 汇率获取、缓存、查询 |
| MoneyConversionService | object | ~99 | 纯货币转换数学计算，无 Android 依赖 |
| InvestmentInterestService | object | ~278 | 投资本金批次管理、每日复利计算、自动生成利息账单 |
| InvestmentInterestWorker | class | ~52 | WorkManager 每日定时结算利息 |
| BillBalanceSnapshotService | object | ~65 | 重建账单余额快照 |
| BillDisplayFormatter | object | ~146 | 账单显示格式化(分类名、金额、公式) |
| CategoryNameNormalizer | object | ~38 | 分类名分隔符统一化 |
| CurrencyUtils | object | ~27 | 币种格式化和小数位规则 |
| VoiceInputHandler | class | ~484 | 长按语音录制 + ASR 集成 |
| RuleDialogHelper | object | ~227 | AI 规则创建/编辑对话框 |
| AssetBillBalanceHistory | object | ~94 | 计算资产每笔账单的余额时间线 |

### 4.2 账单创建流程

```
用户填写表单 (AccountingFormController)
  |
  +-> handleSave() 校验输入
  |     +-> 检查汇率 (ensureRequiredRatesReady)
  |     +-> 转账: 可能弹出汇率确认对话框或投资计划对话框
  |     +-> 收支: 若币种与账户不同，弹出跨币种汇率确认
  |     +-> 构建 Bill 对象
  |
  +-> BillMutationService.insertBillAndApplyImpact(db, bill)
        +-> CategoryNameNormalizer 规范化分类名
        +-> 校验所需汇率
        +-> db.billDao().insertBill(bill) 插入数据库
        +-> BillAssetImpactService.applyBillBalanceImpact(db, savedBill)
              +-> 支出: 资产余额 -= convert(amount, bill.currency, asset.currency)
              +-> 收入: 资产余额 += convert(amount, bill.currency, asset.currency)
              +-> 转账: 源资产 -= (amount + fee), 目标资产 += amount * exchangeRate
              +-> 若为投资资产: InvestmentInterestService.reconcileAssetLotsToBalance()
```

### 4.3 账单编辑流程

```
BillMutationService.replaceBill(db, oldBill, newBill)
  +-> revertBillBalanceImpact(db, oldBill)  -- 撤销旧影响
  +-> updateBill(normalizedBill)            -- 更新记录
  +-> applyBillBalanceImpact(db, newBill)   -- 应用新影响
```

### 4.4 账单删除流程

```
BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
  +-> 保存到 deleted_bills 表 (软删除)
  +-> 若为有关联退款的支出: 先删除退款单(含余额回退)
  +-> 若为退款单: 恢复原支出金额，撤销退款影响
  +-> BillAssetImpactService.revertBillBalanceImpact(db, bill)
  +-> db.billDao().delete(bill)
```

### 4.5 退款流程

```
BillMutationService.saveRefundBill(db, originalBill, refundBill)
  +-> 校验: 原单为支出且非退款单
  +-> 校验: 退款金额 <= 剩余可退金额
  +-> 减少原支出金额 (按退款差额)
  +-> 创建退款单: type=INCOME, subType=REFUND, category="退款：原分类"
  +-> 应用资产影响
```

### 4.6 投资利息结算流程

```
InvestmentInterestWorker.doWork() [每日 01:00]
  +-> InvestmentInterestService.settleDueInterest(db)
        +-> 对每个投资资产:
        |     reconcileAssetLotsToBalance()
        |       +-> 余额 > 本金总和: 创建新批次
        |       +-> 余额 < 本金总和: FIFO 减少批次本金
        |
        +-> 对每个未结批次:
              settleLotInterest()
                +-> 逐日计算: interest = principal * (annualRate/100/365)
                +-> 利息 >= 0.01: 自动生成收支账单
                +-> 利息复利: 每日利息加入次日本金
```

### 4.7 货币转换链

```
CurrencyManager (API 汇率, CNY 为基准)
  +-> rates["USD"] = 0.14 表示 1 CNY = 0.14 USD
  +-> convertToCny(100, "USD") = 100 / 0.14 = 714.29 CNY
  |
MoneyConversionService (校验 + 四舍五入)
  +-> from -> CNY -> to 的链式转换
  +-> roundMoneyForCurrency: 多数币种 2 位小数, JPY/KRW/VND 等 0 位
  +-> 汇率缺失时抛出 MissingCurrencyRateException
  |
BillAssetImpactService (包装汇率提供者)
  +-> 使用 CurrencyManager.getRate() 作为 rateProvider
```

### 4.8 后台任务

| Worker | 类 | 调度 | 职责 |
|--------|-----|------|------|
| 投资利息结算 | InvestmentInterestWorker | 每日 01:00 | 结算投资资产复利，自动生成利息账单 |
| 自动备份 | AutoBackupWorker | 可配置 6h/12h/24h | 本地备份 + 可选云端上传 |

---

## 5. UI 与导航

### 5.1 页面列表 (38 个 Activity)

#### 核心记账

| Activity | 功能 |
|----------|------|
| MainActivity | 主入口，底部 4 Tab 导航 + FAB 快速记账 |
| ChatActivity | AI 对话记账(文本/语音/图片，流式识别，会话管理) |
| ScreenCaptureActivity | 截屏记账(MediaProjection 截屏 + AI 识别) |
| EditBillActivity | 编辑账单(BottomSheetDialog) |
| BillDetailActivity | 账单详情(分类、金额、资产、备注) |
| RefundActivity | 退款处理 |
| BalanceAdjustmentActivity | 余额调整/对账 |
| QuickStartActivity | 快速启动跳板(透明 Activity，唤起 OverlayService) |

#### 资产管理

| Activity | 功能 |
|----------|------|
| AddAssetActivity | 添加/编辑资产(名称、类型、余额、币种、利率) |
| AssetDetailActivity | 资产详情(余额、账单历史) |
| AssetStatsActivity | 资产统计(柱状图、饼图) |

#### 分类管理

| Activity | 功能 |
|----------|------|
| SettingsActivity | 分类管理中心(增删改、拖拽排序) |
| AddCategoryActivity | 添加/编辑分类 |
| CategorySortActivity | 分类拖拽排序 |
| BuiltInPickerActivity | 内置分类网格选择器 |

#### AI 配置

| Activity | 功能 |
|----------|------|
| AiConfigActivity | AI 服务配置(API Key、模型选择) |
| AiFeatureSettingsActivity | AI 功能开关(语音、图片、截屏、对话模式) |
| AiRuleManageActivity | 记账习惯规则管理 |

#### 账本/搜索

| Activity | 功能 |
|----------|------|
| BookOverviewActivity | 多账本月度/年度概览 |
| CalendarActivity | 日历视图账单 |
| BillSearchActivity | 全文搜索账单 |

#### 备份/存储

| Activity | 功能 |
|----------|------|
| BackupActivity | 本地备份与恢复(.bak/.flip) |
| BackupHomeActivity | 备份中心入口 |
| StorageCleanupActivity | 存储清理(语音、图片、缓存) |
| StoragePreviewActivity | 清理文件预览 |
| StorageImageViewerActivity | 图片查看器 |

#### 货币/设置

| Activity | 功能 |
|----------|------|
| CurrencyManagerActivity | 多币种管理 |
| ExchangeRateActivity | 汇率设置与刷新 |
| BillDisplaySettingsActivity | 账单显示偏好 |
| SensitivityActivity | 手势灵敏度调节 |
| GesturePermissionGuideActivity | 手势权限设置向导 |
| AppListActivity | 手势触发应用白名单(Shizuku) |

#### 其他

| Activity | 功能 |
|----------|------|
| HistoryBillActivity | 回收站(已删除账单) |
| ChatSearchActivity | 聊天记录搜索 |
| ChatImagePreviewActivity | 聊天图片全屏预览 |
| LogViewerActivity | 运行日志查看 |
| PermissionRequestActivity | 透明权限请求辅助 |

### 5.2 Fragments (4 个主 Tab + 1 个 BottomSheet)

| Fragment | 功能 |
|----------|------|
| HomeFragment | 首页: 账单列表、趋势图、账本侧滑抽屉 |
| StatsFragment | 统计: 饼图、日报、分类明细 |
| AssetsFragment | 资产: 资产网格/列表、净资产汇总 |
| ProfileFragment | 我的: 设置入口、手势开关、AI 设置、版本信息 |
| ChartSettingsBottomSheetFragment | 图表配置弹窗 |

### 5.3 导航流程

```
                         +------------------+
                         |   MainActivity   |
                         |  (singleTask)    |
                         +--------+---------+
                                  |
           +----------+-----------+-----------+----------+
           |          |           |           |
     HomeFragment  StatsFragment  AssetsFragment  ProfileFragment
           |                      |           |
           v                      v           +-> SettingsActivity (分类管理)
    CalendarActivity         AddAssetActivity  +-> CurrencyManagerActivity
    BillSearchActivity       AssetDetailActivity +-> SensitivityActivity
    EditBillActivity              |             +-> AiFeatureSettingsActivity
           v                      v             +-> BackupActivity
    BillDetailActivity     AssetStatsActivity   +-> StorageCleanupActivity
           v                 BalanceAdjustment   +-> LogViewerActivity
    RefundActivity              Activity
```

**FAB 行为**:
- AI 对话模式: 打开 ChatActivity
- 传统模式: 打开底部记账表单 (AddBillEntrySheetLauncher)

**手势入口**: 翻转手机/双击背面 -> OverlayService -> 悬浮记账窗口

**滑动切换**: SwipeFrameLayout 支持水平滑动切换 4 个底部 Tab

### 5.4 资源文件统计

| 类型 | 数量 |
|------|------|
| 布局文件 | 126 |
| Drawable | 363 |
| 字符串 | 1,498 条 (纯中文简体) |
| 菜单 | 2 |

---

## 6. 基础设施

### 6.1 依赖库清单

| 库 | 版本 | 用途 |
|----|------|------|
| Room | 2.6.1 | 本地 SQLite 数据库 |
| Retrofit | 2.9.0 | REST API 客户端(AI 端点) |
| OkHttp | 4.12.0 | HTTP 客户端(AI + WebDAV) |
| Gson | 2.10.1 | JSON 解析 |
| Glide | 4.16.0 | 图片加载缓存 |
| UCrop | 2.2.8 | 图片裁剪 |
| MPAndroidChart | 3.1.0 | 图表 |
| TensorFlow Lite | 2.14.0 | 敲击检测 ML 推理 |
| ML Kit Chinese OCR | 16.0.0 | 本地 OCR |
| sherpa-onnx | bundled | 本地离线语音转文字 |
| Shizuku | 13.1.5 | 特权操作(截屏) |
| WorkManager | 2.9.0 | 后台任务 |
| Kizitonwose Calendar | 2.4.0 | 日历视图 |
| Spotlight | 2.0.5 | 新手引导 |
| Commons Compress | 1.24.0 | 压缩处理(备份) |
| Kotlin Coroutines | 1.7.3 | 异步操作 |

### 6.2 AI 集成细节

#### 架构

应用使用 **OpenAI 兼容 API** 的 provider-agnostic 架构，不依赖任何 AI 厂商 SDK。

核心类:
- `SiliconFlowApi` (Retrofit 接口): GET models, POST chat/completions (阻塞 + 流式), POST audio/transcriptions
- `AIService` (1400+ 行): 文本记账分析、图片 OCR、截屏记账、语音转文字、意图分类、通用对话
- `AIServiceCommon`: 基础 URL 规范化、JSON 清理、Provider 适配

#### 支持的 AI Provider

| Provider ID | 基础 URL | 视觉 | 云端语音 | 默认文本模型 |
|-------------|----------|------|---------|-------------|
| SiliconFlow (默认) | api.siliconflow.cn | 是 | 是 | Qwen/Qwen3-14B |
| DeepSeek | api.deepseek.com | 否 | 否 | deepseek-v4-flash |
| Kimi (Moonshot) | api.moonshot.cn | 是 | 否 | kimi-k2.5 |
| Qwen (Aliyun) | dashscope.aliyuncs.com | 是 | 是 | qwen3.5-flash |
| MiMo (Xiaomi) | api.xiaomimimo.com | 是 | 是 | mimo-v2.5 |

#### 模型槽位 (Model Slots)

应用为不同任务维护独立的模型选择:
文本模型、多账单模型、修改模型、分类细化模型、规则模型、收据模型、收据视觉模型、OCR 精炼模型、路由器模型、查询模型、语音模型、对话模型、截屏模型

#### 远程配置

RemoteConfigManager 从 GitHub Gist 拉取 JSON 配置，可预配置 API Key、Provider、所有模型槽位和功能开关。

### 6.3 备份机制

#### 备份模式

| 模式 | 内容 |
|------|------|
| 精简 (Lite) | 全部数据(不含聊天媒体文件) |
| 完整 (Full) | 全部数据 + 聊天媒体(头像、背景、语音) |
| 自定义 (Custom) | 用户选择模块 |

#### 备份模块 (17 个可选类别)

核心: 资产、分类、账单、规则
聊天: 聊天消息、聊天媒体
设置 (9 组): 通用基础、通用资产、通用云、显示入口、显示账单、显示多账单、AI 核心、AI 对话、账本、高级运行时
横幅

#### 恢复模式

| 模式 | 行为 |
|------|------|
| 覆盖 | 删除现有数据，插入备份数据(含 ID 重映射) |
| 合并 | 追加备份数据，按名称/时间+金额+类型去重 |

#### 云端备份 (WebDAV)

- 默认服务器: 坚果云 (jianguoyun.com)
- 操作: 测试连接、上传、下载、查找最新备份、清理旧备份
- 目录结构: `{remoteDir}/{deviceName}/`
- 保留策略: 保留 10 个精简 + 3 个完整备份

#### 加密

AI 核心设置(API Key)支持 PIN 加密，使用 AES 加密 + 4 位数字 PIN。

### 6.4 传感器/手势检测

#### 概述

敲击检测系统通过加速度计 + 陀螺仪数据，经 TensorFlow Lite 模型处理，检测手机背面的双击和三击手势。

#### 传感器

- `Sensor.TYPE_ACCELEROMETER` (必需)
- `Sensor.TYPE_GYROSCOPE` (ML 模式必需)

#### 架构 (tap/ 包，23 个文件)

**信号处理管线**:
Resample (2.5ms 重采样) -> Lowpass (alpha=0.2) -> Highpass (alpha=0.2) -> Slope -> PeakDetector

**ML 推理**:
TapTfClassifier (TFLite + 可选 NNAPI 低功耗代理)，4 个预训练模型按屏幕尺寸选择 (REDFIN/FLAME/BRAMBLE/CORAL)

**动态功耗管理**:
- 全功率模式: 启动后 3 分钟
- 启发式待机: 3 分钟静止后激活
- 运动追踪: 加速度 delta >= 1.15 或陀螺仪 >= 0.65 延长全功率
- 功率配置检查每 30 秒运行

#### 配置项

灵敏度等级 (11 级)、三击开关、NNAPI 低功耗模式、强制 ML 模式、敲击模型选择

### 6.5 Android 组件

| 组件 | 类型 | 功能 |
|------|------|------|
| OverlayService | 前台服务 | 手势检测 + 悬浮记账窗口 |
| QuickStartTileService | 快速设置磁贴 | 快速唤起悬浮窗 |
| KeepAliveAccessibilityService | 无障碍服务 | 截屏 + 后台保活 |
| BootReceiver | 广播接收器 | 开机/应用更新后重启服务 |

### 6.6 权限列表 (17 项)

| 权限 | 用途 |
|------|------|
| SYSTEM_ALERT_WINDOW | 悬浮窗 |
| FOREGROUND_SERVICE | 后台手势检测 |
| FOREGROUND_SERVICE_SPECIAL_USE | 传感器检测前台服务 |
| FOREGROUND_SERVICE_MICROPHONE | 前台服务录音 |
| VIBRATE | 触觉反馈 |
| RECORD_AUDIO | 语音记账 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| HIGH_SAMPLING_RATE_SENSORS | 高频加速度计 |
| INTERNET | AI API 调用、汇率获取 |
| ACCESS_NETWORK_STATE | 网络状态检查 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 防止 Doze 杀死服务 |
| WAKE_LOCK | CPU 唤醒锁 |
| POST_NOTIFICATIONS | 前台服务通知 |
| READ_MEDIA_IMAGES | 读取图片 (Android 13+) |
| READ_MEDIA_VISUAL_USER_SELECTED | 部分照片访问 (Android 14+) |
| READ_EXTERNAL_STORAGE | 读取存储 (<=Android 12) |
| QUERY_ALL_PACKAGES | 应用白名单查询 |

---

## 7. 代码规模统计

### 7.1 整体规模

| 指标 | 估计值 |
|------|--------|
| Kotlin 源文件数 | ~150+ |
| Java 源文件 | 1 (ShizukuHelper.java) |
| 总代码行数 | ~35,000-40,000 |
| 布局 XML 文件 | 126 |
| Drawable 资源 | 363 |
| 字符串资源 | 1,498 条 |

### 7.2 各模块代码量估计

| 模块 | 路径 | 估计行数 | 占比 |
|------|------|----------|------|
| Activity/UI 层 | 根目录 + ui/ | ~15,000 | ~40% |
| 业务逻辑层 | logic/ | ~5,500 | ~15% |
| AI 集成 | AIService.kt + chat/ + ai/ | ~5,000 | ~13% |
| 数据层 | data/ | ~3,500 | ~10% |
| 手势检测 | tap/ | ~3,000 | ~8% |
| 设置/偏好 | Prefs*.kt | ~2,000 | ~5% |
| 备份系统 | data/backup/ | ~1,500 | ~4% |
| 其他 (日志、工具等) | 其余 | ~2,000 | ~5% |

### 7.3 最大文件 (Top 5)

| 文件 | 估计行数 |
|------|----------|
| AccountingFormController.kt | ~2,529 |
| BackupActivity.kt | ~1,740 |
| AIService.kt | ~1,400+ |
| ChatActivity.kt | ~1,200+ |
| HomeFragment.kt | ~1,000+ |

---

## 附录: 包结构

```
com/taostudio/tapaccounting/
|
|-- [根目录: Activities, Services, Application, 工具类]
|   |-- TapApplication.kt          (Application)
|   |-- MainActivity.kt            (主入口)
|   |-- OverlayService.kt          (前台手势服务)
|   |-- KeepAliveAccessibilityService.kt
|   |-- BootReceiver.kt
|   |-- AIService.kt               (LLM API 客户端)
|   |-- Prefs.kt + Prefs*.kt       (SharedPreferences 包装)
|   |-- Logger.kt                  (日志基础设施)
|   |-- 30+ Activity 文件
|   |-- 10+ AI 相关文件
|
|-- chat/                           (对话子系统)
|   |-- ai/                         (AI 意图路由)
|   |-- query/                      (查询处理)
|   |-- time/                       (时间相关逻辑)
|   |-- voice/                      (语音输入)
|
|-- data/                           (数据层)
|   |-- local/
|   |   |-- AppDatabase.kt         (Room 数据库)
|   |   |-- MigrationManager.kt    (DB 迁移)
|   |   |-- dao/                   (DAO 接口)
|   |   |-- entity/                (实体定义)
|   |-- repository/                (Repository 封装)
|   |-- backup/                    (备份管理)
|
|-- logic/                          (业务逻辑)
|   |-- 16 个生产类
|
|-- tap/                            (敲击检测引擎)
|   |-- TapDetector.kt             (核心检测)
|   |-- 信号处理: Highpass/Lowpass/Slope/PeakDetector
|   |-- TapTfClassifier.kt        (TFLite 分类器)
|
|-- ui/                             (UI 层)
|   |-- activity/                   (Activity)
|   |-- main/                       (Fragment Tab)
|   |   |-- home/ / assets/ / stats/ / profile/
|   |-- common/ / dialog/ / widget/
```

---

## 8. 深度分析

### 8.1 业务逻辑层详细分析

# Logic 层深度文档

> 目录：`app/src/main/java/com/taostudio/tapaccounting/logic/`
> 共 17 个文件，本文档逐一分析每个类的结构、方法、依赖、线程安全及边界处理。

---

## 目录

1. [AccountingFormController.kt（核心记账表单控制器）](#1-accountingformcontrollerkt)
2. [BillAssetImpactService.kt（账单资产余额影响服务）](#2-billassetimpactservicekt)
3. [BillMutationService.kt（账单增删改服务）](#3-billmutationservicekt)
4. [CurrencyManager.kt（汇率管理器）](#4-currencymanagerkt)
5. [MoneyConversionService.kt（货币转换服务）](#5-moneyconversionservicekt)
6. [InvestmentInterestService.kt（理财利息结算服务）](#6-investmentinterestservicekt)
7. [BillDeleteHelper.kt（账单删除助手）](#7-billdeletehelperkt)
8. [BillRestoreHelper.kt（账单恢复助手）](#8-billrestorehelperkt)
9. [BillDisplayFormatter.kt（账单展示格式化）](#9-billdisplayformatterkt)
10. [CategoryNameNormalizer.kt（分类名称归一化）](#10-categorynamenormalizerkt)
11. [VoiceInputHandler.kt（语音输入处理器）](#11-voiceinputhandlerkt)
12. [RuleDialogHelper.kt（AI 规则编辑弹窗）](#12-ruledialoghelperkt)
13. [AssetBillBalanceHistory.kt（资产余额历史推算）](#13-assetbillbalancehistorykt)
14. [AssetBillBalanceDisplay.kt（资产余额展示辅助）](#14-assetbillbalancedisplaykt)
15. [CurrencyUtils.kt（货币工具函数）](#15-currencyutilskt)
16. [BillBalanceSnapshotService.kt（账单余额快照服务）](#16-billbalancesnapshotservicekt)
17. [InvestmentInterestWorker.kt（理财利息后台 Worker）](#17-investmentinterestworkert)

---

## 1. AccountingFormController.kt

**文件行数**：2529 行（logic 目录最大的文件）
**类声明**：`class AccountingFormController(ctx, rootView, onCloseRequest, onHeightLocked?)`

### 1.1 构造参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `ctx` | `Context` | Activity 或悬浮窗 Context |
| `rootView` | `View` | 表单根布局 |
| `onCloseRequest` | `(isSaved: Boolean) -> Unit` | 关闭回调 |
| `onHeightLocked` | `((lockedHeight: Int) -> Unit)?` | 悬浮窗高度锁定回调 |

### 1.2 内部状态字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `scope` | `CoroutineScope` | `Dispatchers.Main + SupervisorJob()`，主协程 |
| `editingBillId` | `Long?` | 编辑模式下的账单 ID |
| `isSaving` | `Boolean` | 防重复提交锁 |
| `selectedFormBook` | `String` | 当前表单选定账本 |
| `customTransferRate` | `Double?` | 用户确认的转账汇率 |
| `customTargetAmount` | `Double?` | 用户确认的转账目标金额 |
| `hasConfirmedExchangeRate` | `Boolean` | 转账汇率是否已确认 |
| `customCurrencyRate` | `Double?` | 支出/收入跨币种汇率 |
| `customCurrencyTargetAmount` | `Double?` | 支出/收入跨币种实际扣减金额 |
| `hasConfirmedCurrencyRate` | `Boolean` | 支出/收入汇率是否已确认 |
| `isProgrammaticCurrencyChange` | `Boolean` | 程序设置币种标记，防止触发用户切换逻辑 |
| `isRefundMode` | `Boolean` | 是否处于退款模式 |
| `selectedRefundSourceBill` | `Bill?` | 选中的退款来源账单 |
| `pendingBills` | `MutableList<JSONObject>` | 待处理账单队列 |
| `isProcessingPendingBillQueue` | `Boolean` | 是否正在处理队列 |
| `pendingInvestmentSchedule` | `InvestmentSchedule?` | 理财产品投资计划 |
| `formBodyMeasuredHeight` | `Int` | 表单体首次测量高度，用于自定义键盘 |

### 1.3 公有方法

#### `handleBackPressed(): Boolean`
- **功能**：处理返回键，若数字键盘可见则确认输入并关闭键盘
- **返回**：`true` 表示已消费事件

#### `fillDataToUi(json: JSONObject, showToast: Boolean, forceMultiMode: Boolean)`
- **功能**：将 AI 解析结果或编辑数据填充到 UI
- **步骤**：
  1. 解析 `_local_rule_corrected`、`original_text_from_user` 等 AI 标记
  2. 处理 `book_name` 切换账本
  3. 记录 AI 建议的 type/category/account 用于后续纠错拦截
  4. **多账单模式**（`bills` 数组）：
     - 若 `isMultiBillNotSync` 且非截图/审核，直接批量入库（不弹 UI）
     - 否则逐条排队到 `pendingBills`，逐条弹表单
  5. **单账单模式**：直接填充 amount/type/account/category/remark/time 等
  6. 处理币种自动匹配（优先 AI 返回 > 资产库查询）
  7. 转账约束校验：若转入账户不存在则降级为支出
  8. 编辑模式恢复 `exchangeRate`

#### `setCurrency(currencyCode: String)`
- **功能**：程序化设置币种 Spinner 选中项
- **逻辑**：若币种不在已启用列表中，自动追加并重建 Spinner

### 1.4 关键私有方法

#### `handleSave()`
- **核心保存流程**，步骤：
  1. 校验金额 > 0
  2. 确定类型（支出/收入/转账/还款）
  3. **转账汇率确认**：若多币种开启且未确认，先查询账户币种；同币种直接标记已确认，不同币种弹汇率对话框
  4. **支出/收入汇率确认**：若记账币种与账户币种不同，弹跨币种汇率确认
  5. **投资计划确认**：若转入账户为投资类资产，弹投资时间计划对话框
  6. 计算 `sourceDelta`（账户扣减）和 `targetDelta`（转账目标增加）
  7. 构建 `Bill` 对象，处理退款来源账单
  8. 调用 `BillMutationService` 执行入库
  9. 投资场景调用 `InvestmentInterestService.createOrReplaceLotForTransfer`
  10. AI 纠错拦截：若用户修改了 AI 建议的 type/category，弹规则保存提示
  11. `finally` 块重置 `isSaving = false`

#### `evaluateAmountExpression(raw: String): Double?`
- **功能**：四则运算表达式求值（支持 `+` `-` `x` `/`）
- **算法**：双栈法（操作数栈 + 运算符栈），处理运算符优先级
- **边界**：除零返回 null，空表达式返回 null

#### `showExchangeDialog()`
- **功能**：转账汇率确认对话框
- **流程**：IO 线程查资产币种 -> 主线程弹对话框 -> 回调设置 `customTargetAmount`

#### `showCurrencyExchangeDialog(onConfirmed: () -> Unit)`
- **功能**：支出/收入跨币种汇率确认对话框

#### `showInvestmentScheduleDialog(transferTime, targetName, onConfirm)`
- **功能**：投资起息日期选择弹窗，含自定义日历选择器

#### `ensureRequiredRatesReady(type, isRepayment, selectedCurrency, sourceAsset, targetAsset): Boolean`
- **功能**：确保所需币种汇率已就绪
- **流程**：
  1. 收集所有需要的币种
  2. 缺失时调用 `CurrencyManager.updateRates` 刷新
  3. 刷新后仍缺失则弹阻断对话框（可跳过/复制币种/跳转汇率设置页）

#### `enforceTransferAssetConstraintIfNeeded(json, showToast)`
- **功能**：校验转账约束，若转入/转出账户不存在则降级为支出

### 1.5 依赖

| 依赖 | 用途 |
|------|------|
| `AppDatabase` | 查询资产、分类 |
| `CurrencyManager` | 汇率查询、刷新 |
| `BillAssetImpactService` | 汇率估算 |
| `BillMutationService` | 账单入库/替换 |
| `InvestmentInterestService` | 投资利息计划 |
| `BillAssetImpactService` | 汇率换算 |
| `BookAccountManager` | 账本管理 |
| `AiAssistant` | AI 输入面板 |
| `VoiceInputHandler` | 语音输入 |
| `OverlayDialogs` | 弹窗管理 |
| `CategoryNameNormalizer` | 分类名归一化 |
| `RuleDialogHelper` | 规则编辑弹窗 |

### 1.6 线程安全

- 主协程 scope (`Dispatchers.Main + SupervisorJob()`)
- IO 操作通过 `withContext(Dispatchers.IO)` 切换
- `isSaving` 标志防止重复提交，`finally` 块确保重置
- `isProgrammaticCurrencyChange` 防止程序设置币种触发用户切换逻辑
- `ensureRequiredRatesReady` 使用 `suspendCancellableCoroutine` 桥接回调式汇率刷新

### 1.7 魔法数字/常量

| 值 | 说明 |
|------|------|
| `200ms` | 语音长按触发延迟 |
| `80L / 120L` | 保存按钮动画时长 |
| `0.93f` | 按钮按下缩放比例 |
| `0.88f / 0.9f / 0.92f` | 弹窗宽度比 |
| `-150f` | 上滑取消手势阈值 |
| `#FF5252` / `#4CAF50` / `#8A8A8E` / `#FF9800` | 支出/收入/转账/还款颜色 |

---

## 2. BillAssetImpactService.kt

**类声明**：`object BillAssetImpactService`（单例）

### 2.1 公有方法

#### `suspend applyBillBalanceImpact(db: AppDatabase, bill: Bill): Int`
- **功能**：将账单金额应用到关联资产余额
- **返回**：受影响的资产数量
- **步骤**：
  - 支出：`asset.balance -= sourceDelta`
  - 收入：`asset.balance += sourceDelta`
  - 转账：转出账户扣减（本金+手续费），转入账户增加（金额x汇率）
- **边界**：平账记录（`BALANCE_ADJUSTMENT`）直接跳过返回 0
- **异常**：捕获 `MissingCurrencyRateException`，跳过余额更新

#### `suspend revertBillBalanceImpact(db: AppDatabase, bill: Bill): Int`
- **功能**：回滚账单对资产余额的影响（与 apply 相反）
- **特殊**：平账记录的 revert 有独立逻辑（收入回滚扣减，其他回滚增加）

#### `convertAmountBetweenCurrencies(amount, fromCurrency, toCurrency): Double`
- **委托**：`MoneyConversionService.convertAmountBetweenCurrencies`

#### `estimateExchangeRateToTarget(amount, sourceCurrency, targetCurrency): Double`
- **委托**：`MoneyConversionService.estimateExchangeRateToTarget`

#### `estimateExchangeRateToCny(currency): Double`
- **委托**：`MoneyConversionService.estimateExchangeRateToCny`

#### `roundMoney(amount): Double` / `roundMoneyForCurrency(amount, currencyCode): Double` / `roundRate(rate): Double`
- **委托**：`MoneyConversionService` 对应方法

#### `suspend ensureRatesForImpact(bill, sourceAsset?, targetAsset?)`
- **功能**：确保账单影响计算所需的汇率可用
- **异常**：汇率缺失时抛 `MissingCurrencyRateException`

### 2.2 关键私有方法

#### `resolveSourceAsset(db, bill): Asset?` / `resolveTargetAsset(db, bill): Asset?`
- **解析优先级**：`accountId` 查 ID -> `accountName` 查名称 -> 归一化名称模糊匹配

#### `normalizeAssetName(name): String`
- **功能**：去除"银行卡"/"信用卡"/"银行"/"账户"/"账本"/"卡"等后缀，转小写去空格

#### `sourceDeltaInCurrency(bill, sourceCurrency): Double`
- **公式**：`本金换算 + 手续费换算`

#### `targetDeltaInCurrency(bill, targetCurrency): Double`
- **公式**：`bill.amount * bill.exchangeRate`

#### `syncInvestmentPrincipalAfterExternalImpact(db, asset, bill)`
- **功能**：外部账单影响投资资产后，调和投资份额与余额
- **跳过**：非投资资产 / 自动结息账单

### 2.3 常量

| 值 | 说明 |
|------|------|
| `REFUND_CATEGORY_PREFIX` | `"退款："` |

---

## 3. BillMutationService.kt

**类声明**：`object BillMutationService`（单例）

### 3.1 公有方法

#### `suspend insertBillAndApplyImpact(db, bill, applyAssetImpact = true): Bill`
- **功能**：插入新账单并应用资产余额影响
- **流程**：
  1. 分类名归一化
  2. 校验所需汇率
  3. `billDao.insertBill` 插入
  4. `BillAssetImpactService.applyBillBalanceImpact` 应用影响
  5. 若影响为 0 且为支出/收入/转账，输出警告日志
- **事务**：`db.withTransaction`

#### `suspend insertBillWithinActiveTransaction(db, bill, applyAssetImpact = true): Bill`
- **功能**：在已有事务内插入账单（不包裹新事务）
- **用途**：由外部已开启事务的调用方使用

#### `suspend upsertBillAndApplyImpact(db, bill, applyAssetImpact = true): Bill`
- **功能**：当前直接委托给 `insertBillAndApplyImpact`

#### `suspend replaceBill(db, oldBill, newBill, applyAssetImpact = true): Bill`
- **功能**：替换（编辑）已有账单
- **流程**：
  1. 分类名归一化
  2. 退款账单：保留 `subType=REFUND`、`relatedBillId`，分类加退款前缀
  3. 有退款的支出账单：新金额限制在 `[0, baseOriginalAmount]` 之间
  4. 若旧账单是退款且有来源，同步更新来源账单的实际金额
  5. 回滚旧账单余额影响
  6. 更新/插入新账单
  7. 应用新账单余额影响
- **事务**：`db.withTransaction`

#### `suspend saveRefundBill(db, originalBill, refundBill, previousRefundBill? = null): Bill`
- **功能**：保存退款账单
- **流程**：
  1. 校验原账单可退款（必须是支出且非退款）
  2. 计算退款增量，校验不超过剩余金额
  3. 更新原账单实际金额（扣减退款部分）
  4. 构建退款账单（type=INCOME, subType=REFUND, 分类加前缀）
  5. 回滚旧退款影响 -> 插入新退款 -> 应用影响
- **事务**：`db.withTransaction`
- **异常**：`IllegalArgumentException`（退款超额）、`IllegalStateException`（原账单无效）

#### `suspend resolveRefundSourceBill(db, refundBill): Bill?`
- **功能**：查找退款来源账单
- **优先级**：`relatedBillId` 直接查 -> 按分类/金额/时间/账户模糊匹配

### 3.2 依赖

| 依赖 | 用途 |
|------|------|
| `BillAssetImpactService` | 余额影响计算 |
| `CategoryNameNormalizer` | 分类名归一化 |
| `AppDatabase` | 数据库操作 |

---

## 4. CurrencyManager.kt

**类声明**：`object CurrencyManager`（单例）

### 4.1 常量

| 常量 | 值 | 说明 |
|------|------|------|
| `API_URL` | `https://api.exchangerate-api.com/v4/latest/CNY` | 汇率 API（CNY 基准） |
| `DEFAULT_RATES` | CNY=1.0, USD=0.14, EUR=0.13, PLN=0.56, HKD=1.09, JPY=20.0 | 离线默认汇率 |

### 4.2 公有方法

#### `init(context: Context)`
- **功能**：初始化汇率（从 SharedPreferences 加载缓存，过期则自动刷新）
- **刷新条件**：距上次更新超过 `intervalMins` 分钟

#### `convertToCny(amount, currency): Double`
- **功能**：将指定货币转换为 CNY
- **公式**：`amount / rate`（rate 表示 1 CNY = ? 该货币）
- **边界**：汇率缺失或为 0 返回 `NaN`，并标记缺失

#### `convertFromCny(amountCny, targetCurrency): Double`
- **功能**：将 CNY 转换为指定货币
- **公式**：`amountCny * rate`

#### `convert(amount, fromCurrency, toCurrency): Double`
- **功能**：任意币种互转（经 CNY 中转）

#### `hasConversionRate(fromCurrency, toCurrency): Boolean`
- **功能**：检查两种货币是否都有汇率

#### `getSupportedCurrencies(): List<String>`
- **功能**：返回所有支持的币种列表（常用优先 + 系统币种 + 已缓存币种）

#### `getEnabledCurrencies(context): List<String>`
- **功能**：返回用户启用的币种列表

#### `setEnabledCurrencies(context, list)`
- **功能**：保存用户启用的币种列表

#### `getRate(currency): Double?`
- **功能**：获取币种对 CNY 的汇率
- **边界**：缺失时标记 `missingRateCurrencies`

#### `updateRates(context, callback?)`
- **功能**：从 API 异步刷新汇率
- **线程**：`updateExecutor`（单线程池）
- **防重入**：`isUpdatingRates` AtomicBoolean
- **回调**：`pendingUpdateCallbacks` 列表，成功/失败后在主线程分发
- **超时**：连接/读取各 5 秒

#### `hasMissingRates() / getMissingRateCurrencies() / clearMissingRateCurrencies()`
- **功能**：缺失汇率币种管理

#### `getRateStatusSummary(context): String`
- **功能**：返回汇率状态摘要文本

#### `getSymbol(code): String`
- **功能**：获取币种符号

### 4.3 线程安全

- `rates`：`ConcurrentHashMap`
- `isUpdatingRates`：`AtomicBoolean` 防重入
- `pendingUpdateCallbacks`：`CopyOnWriteArrayList`
- `missingRateCurrencies`：`ConcurrentHashMap.newKeySet()`
- `updateExecutor`：单线程执行器

---

## 5. MoneyConversionService.kt

**类声明**：`object MoneyConversionService`（单例）

### 5.1 公有方法

#### `missingCurrencies(currencies, rateProvider): Set<String>`
- **功能**：返回缺失汇率的币种集合（排除 CNY）

#### `requireCurrenciesAvailable(currencies, rateProvider)`
- **功能**：校验所有币种汇率可用
- **异常**：缺失时抛 `MissingCurrencyRateException`

#### `convertAmountBetweenCurrencies(amount, fromCurrency, toCurrency, rateProvider): Double`
- **功能**：跨币种金额转换
- **公式**：
  - `from -> CNY`: `amount / fromRate`
  - `CNY -> to`: `amountCny * toRate`
- **精度**：结果按目标币种小数位四舍五入

#### `estimateExchangeRateToTarget(amount, sourceCurrency, targetCurrency, rateProvider): Double`
- **功能**：估算源币种到目标币种的汇率
- **公式**：`convertedAmount / amount`，精度 6 位小数

#### `estimateExchangeRateToCny(currency, rateProvider): Double`
- **功能**：估算某币种到 CNY 的汇率
- **公式**：`1.0 / rateToCurrency`

#### `roundMoney(amount): Double`
- **精度**：2 位小数，HALF_UP

#### `roundMoneyForCurrency(amount, currencyCode): Double`
- **精度**：根据币种决定（JPY/KRW 等 0 位，其他 2 位）

#### `roundRate(rate): Double`
- **精度**：6 位小数，HALF_UP

### 5.2 异常类

```kotlin
class MissingCurrencyRateException(val missingCurrencies: Set<String>)
    : IllegalStateException(...)
```

---

## 6. InvestmentInterestService.kt

**类声明**：`object InvestmentInterestService`（单例）

### 6.1 常量

| 常量 | 值 | 说明 |
|------|------|------|
| `CATEGORY_NAME` | `"理财产品"` | 自动结息分类名 |
| `CATEGORY_ICON` | `http://res3.qianjiapp.com/cateic_licai.png` | 分类图标 |
| `DAYS_IN_YEAR` | `365.0` | 年天数 |
| `MIN_INTEREST_AMOUNT` | `0.01` | 最小利息金额 |
| `BALANCE_EPSILON` | `0.000001` | 余额比较精度 |

### 6.2 数据类

```kotlin
data class InvestmentSchedule(
    val startEarningAt: Long,  // 起息日（毫秒）
    val firstPayoutAt: Long    // 首次到账日（毫秒）
)
```

### 6.3 公有方法

#### `suspend ensureInvestmentCategories(db)`
- **功能**：确保"理财产品"分类在支出和收入类型中都存在

#### `suspend createOrReplaceLotForTransfer(db, bill, targetAsset, schedule)`
- **功能**：为转账到投资资产创建/替换投资份额（Lot）
- **公式**：`principal = bill.amount * bill.exchangeRate`
- **逻辑**：已有同源账单的 Lot 则替换，否则新建

#### `suspend createLotForAssetBalance(db, asset, schedule)`
- **功能**：为资产当前余额创建投资份额（用于初始建仓）

#### `suspend settleDueInterest(db, now)`
- **功能**：结算到期利息
- **流程**：
  1. 调和所有投资资产的份额与余额
  2. 获取所有开放份额
  3. 确保分类存在
  4. 逐个份额执行 `settleLotInterest`

#### `suspend reconcileAssetLotsToBalance(db, asset, changedAt)`
- **功能**：调和资产份额与当前余额
- **逻辑**：
  - `delta > 0`：创建新 Lot
  - `delta < 0`：FIFO 方式扣减份额

#### `applyFifoPrincipalReduction(orderedLots, reductionAmount): List<InvestmentLot>`
- **功能**：FIFO 方式扣减份额本金
- **可见性**：`internal`（测试用）

#### `compoundDailyInterestTotal(initialPrincipal, annualInterestRatePercent, days): Double`
- **功能**：复利日利息计算
- **公式**：每日利息 = 本金 * 日利率，次日本金 += 当日利息
- **可见性**：`internal`（测试用）

#### `startOfDay(timeMillis): Long` / `plusDays(dayStartMillis, days): Long` / `daysBetween(startDayMillis, endDayMillis): Int`
- **功能**：日期工具函数

### 6.4 关键私有方法

#### `settleLotInterest(db, asset, lot, todayStart)`
- **功能**：单个份额的利息结算
- **流程**：
  1. 计算支付延迟天数
  2. 循环：从 `lastSettledAt` 到今天，每个支付周期生成一条利息账单
  3. 利息 >= 0 生成收入账单，< 0 生成支出账单
  4. 更新份额 `remainingPrincipal` 和 `lastSettledAt`
  5. 调用 `BillMutationService.insertBillAndApplyImpact` 入库

---

## 7. BillDeleteHelper.kt

**类声明**：`object BillDeleteHelper`（单例）

### 7.1 公有方法

#### `suspend deleteBillAndRevertBalance(db, bill)`
- **功能**：删除单条账单并回滚余额影响

#### `suspend deleteBillsAndRevertBalance(db, bills)`
- **功能**：批量删除账单

#### `suspend deleteBillsAndRevertBalanceScoped(db, bills, scopeBillIds)`
- **功能**：限定范围的批量删除（仅删除 scopeBillIds 中的关联退款）

### 7.2 删除逻辑

- **退款账单**：恢复来源账单的实际金额，回滚退款余额影响
- **平账记录**：直接回滚并删除
- **支出账单**：先删除关联退款账单，再回滚自身影响
- **收入/转账账单**：直接回滚并删除
- **所有删除前**：保存到 `deleted_bills` 表（软删除）

### 7.3 事务

- 所有操作在 `db.withTransaction` 内执行

---

## 8. BillRestoreHelper.kt

**类声明**：`object BillRestoreHelper`（单例）

### 8.1 公有方法

#### `suspend restoreBills(db, deletedBills): List<Bill>`
- **功能**：从软删除恢复账单
- **流程**：
  1. 按依赖关系排序（先恢复被依赖的，再恢复退款账单）
  2. 逐条插入并应用余额影响
  3. 处理 ID 重映射
  4. 更新聊天消息中的账单引用
  5. 从 `deleted_bills` 表删除记录

### 8.2 私有方法

#### `updateChatMessages(db, restoredBills, idRemapping)`
- **功能**：更新聊天消息中对恢复账单的引用
- **处理**：`billIds` JSON 数组重映射、`deprecatedBillIds` 清理、`content` JSON 中的账单数据更新

---

## 9. BillDisplayFormatter.kt

**类声明**：`object BillDisplayFormatter`（单例）

### 9.1 公有方法

| 方法 | 返回 | 说明 |
|------|------|------|
| `normalizeCategoryDisplayName(categoryName)` | `String` | 统一分类分隔符为 ` - ` |
| `formatCategoryByPreference(categoryName, showFullCategory)` | `String` | 按偏好格式化分类名 |
| `resolvePrimarySecondaryText(category, remark, suffix, remarkPriority)` | `Pair<String, String>` | 决定主/副文本 |
| `stripRefundPrefix(categoryName)` | `String` | 去除退款前缀 |
| `hasRefundPrefix(categoryName)` | `Boolean` | 是否有退款前缀 |
| `formatAccountNameWithDeletedTag(accountName)` | `CharSequence` | 已删除账户灰色标记 |
| `buildRefundCategoryLabel(categoryName)` | `String` | 构建退款分类标签 |
| `formatMoney(amount, currency)` | `String` | 格式化金额 |
| `formatRateValue(rate)` | `String` | 格式化汇率（2 位小数） |
| `originalAmountOfExpenseBill(bill)` | `Double` | 支出账单原始金额 |
| `refundAmountOfExpenseBill(bill)` | `Double` | 已退款金额 |
| `buildRefundedExpenseAmountText(net, original, currency)` | `CharSequence` | 退款后金额文本 |
| `buildRefundFlowRemark(baseRemark, refunds)` | `String` | 退款流备注 |
| `buildCrossCurrencyAmountFormula(bill, accountCurrency)` | `String?` | 跨币种换算公式 |
| `buildCrossCurrencyDetailFormula(bill, targetCurrency)` | `String?` | 跨币种详情公式 |

---

## 10. CategoryNameNormalizer.kt

**类声明**：`object CategoryNameNormalizer`（单例）

### 10.1 常量

| 常量 | 值 | 说明 |
|------|------|------|
| `REFUND_PREFIX` | `"退款："` | 标准退款前缀 |
| `REFUND_PREFIX_ALT` | `"退款·"` | 兼容旧格式退款前缀 |
| `UNIFIED_CHILD_SEPARATOR` | `" - "` | 统一子分类分隔符 |
| `childSeparators` | Regex | 匹配 `/:::/` `/::/` `>` `/` `\` `\|` `::` `:` `·` |

### 10.2 公有方法

#### `normalizeForStorage(raw: String): String`
- **功能**：将分类名归一化为存储格式
- **步骤**：去除退款前缀 -> 按分隔符拆分 -> trim -> 用 ` - ` 重新拼接 -> 恢复退款前缀

#### `stripRefundPrefix(categoryName: String): String`
- **功能**：循环去除退款前缀（支持嵌套）

---

## 11. VoiceInputHandler.kt

**类声明**：`class VoiceInputHandler(ctx, aiAssistant, onResult, onBeforeRecording?, onAfterRecording?)`

### 11.1 构造参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `ctx` | `Context` | 上下文 |
| `aiAssistant` | `AiAssistant` | AI 助手引用 |
| `onResult` | `(JSONObject) -> Unit` | 识别结果回调 |
| `onBeforeRecording` | `(() -> Boolean)?` | 录音前回调（如启动前台服务） |
| `onAfterRecording` | `(() -> Unit)?` | 录音后回调 |

### 11.2 公有方法

#### `setupVoiceButton(btnVoice: View)`
- **功能**：绑定语音按钮的触摸事件
- **手势**：
  - `ACTION_DOWN`：检查权限 -> 200ms 长按延迟 -> 开始录音
  - `ACTION_MOVE`：上滑 150px 进入取消模式
  - `ACTION_UP`：取消模式则丢弃，否则发送识别

#### `release()`
- **功能**：释放资源

### 11.3 录音参数

| 参数 | 值 |
|------|------|
| 采样率 | 16000 Hz |
| 声道 | 单声道 |
| 格式 | PCM 16-bit |
| 缓冲区 | `max(minBufferSize, sampleRate * 2)` |

### 11.4 ASR 模式

- **Whisper 本地**：流式识别 + 文件兜底
- **云端 API**：文件上传识别

---

## 12. RuleDialogHelper.kt

**类声明**：`object RuleDialogHelper`（单例）

### 12.1 常量

#### `DEFAULT_RULE_PROMPT`
- AI 关键词提取提示词模板，含 `{{REMARK}}` 和 `{{CATEGORY}}` 占位符

### 12.2 公有方法

#### `showDialog(ctx, rule?, referenceText?, defaultType?, defaultCat?, defaultAcc1?, defaultAcc2?, isOverlay, onSave, onDelete?, onCancel?)`
- **功能**：显示规则编辑弹窗
- **UI 元素**：关键词输入、类型 Spinner、分类选择、账户 1/2 Spinner、启用开关
- **AI 提取**：调用 `AIService.simpleChat` 从备注中提取关键词
- **多关键词**：支持逗号分隔批量创建规则
- **弹窗模式**：`isOverlay` 控制使用悬浮窗或页面弹窗

---

## 13. AssetBillBalanceHistory.kt

**类声明**：`object AssetBillBalanceHistory`（单例）

### 13.1 公有方法

#### `computeBalanceAfterByBillId(bills, assetId, assetName, assetCurrency, currentBalance): Map<Long, Double>`
- **功能**：从当前余额倒推每笔账单后的余额
- **算法**：按时间倒序遍历，`running -= delta`，记录每笔账单后的 `running` 值
- **返回**：`LinkedHashMap<billId, balanceAfter>`

#### `formatBalanceAfterLabel(balance, currency): String`
- **功能**：格式化余额标签（如 `"余额￥1,234.56"`）

#### `signedBalanceDelta(bill, assetId, assetName, assetCurrency): Double`
- **功能**：计算账单对该资产的有符号余额变动
- **逻辑**：
  - 退款：正向（+amount）
  - 支出：负向（-amount）
  - 收入：正向（+amount）
  - 转账来源：负向（-(principal + fee)）
  - 转账目标：正向（+amount * exchangeRate）

#### `matchesSource(bill, assetId, assetName): Boolean` / `matchesTarget(bill, assetId, assetName): Boolean`
- **匹配逻辑**：优先 ID 匹配，ID 为空时降级为名称匹配

---

## 14. AssetBillBalanceDisplay.kt

**类声明**：`object AssetBillBalanceDisplay`（单例）

### 14.1 公有方法

| 方法 | 返回 | 说明 |
|------|------|------|
| `effectiveFromTime(asset, bills, assetId, assetName)` | `Long` | 余额展示起始时间 |
| `assetCreationDayStart(asset, bills, assetId, assetName)` | `Long` | 资产创建日零点 |
| `earliestBillTimeMillis(bills, assetId, assetName)` | `Long?` | 最早关联账单时间 |
| `billTouchesAsset(bill, assetId, assetName)` | `Boolean` | 账单是否涉及该资产 |
| `formatFromDateLabel(timeMillis)` | `String` | 格式化日期标签 |
| `shouldShowBalanceForBill(asset, billTime, bills, assetId, assetName)` | `Boolean` | 是否应显示该账单余额 |

---

## 15. CurrencyUtils.kt

**类声明**：`object CurrencyUtils`（单例）

### 15.1 公有方法

#### `formatAmount(amount, currencyCode): String`
- **功能**：格式化金额（含币种符号和正确小数位）

#### `decimalPlaces(currencyCode): Int`
- **功能**：返回币种小数位数
- **规则**：JPY/KRW/VND/HUF/CLP/ISK 等 0 位，其他 2 位

---

## 16. BillBalanceSnapshotService.kt

**类声明**：`object BillBalanceSnapshotService`（单例）
**说明**：Legacy 辅助服务，资产详情 UI 已改用 `AssetBillBalanceHistory`

### 16.1 公有方法

#### `suspend rebuildAllAssetSnapshots(db)`
- **功能**：重建所有资产的账单余额快照

#### `suspend rebuildSnapshotsForAsset(db, assetId)`
- **功能**：重建指定资产的账单余额快照
- **流程**：获取所有关联账单 -> 计算余额 -> 批量更新 `accountBalanceAfter` / `toAccountBalanceAfter`

#### `balanceAfterForAsset(bill, assetId, assetName): Double?`
- **功能**：获取账单对该资产的余额快照值

---

## 17. InvestmentInterestWorker.kt

**类声明**：`class InvestmentInterestWorker(appContext, params) : CoroutineWorker`

### 17.1 方法

#### `override suspend doWork(): Result`
- **功能**：执行利息结算
- **逻辑**：成功返回 `Result.success()`，异常返回 `Result.retry()`

### 17.2 Companion Object

#### `schedule(ctx: Context)`
- **功能**：注册每日定时任务
- **执行时间**：每天凌晨 1:00
- **WorkManager**：`ExistingPeriodicWorkPolicy.UPDATE`（更新已有任务）

---

## 依赖关系图（核心链路）

```
AccountingFormController
  ├── BillMutationService
  │     ├── BillAssetImpactService
  │     │     ├── MoneyConversionService
  │     │     ├── CurrencyManager
  │     │     └── InvestmentInterestService
  │     └── CategoryNameNormalizer
  ├── CurrencyManager
  ├── InvestmentInterestService
  │     ├── BillMutationService（利息账单入库）
  │     └── BillAssetImpactService
  ├── AiAssistant
  ├── VoiceInputHandler
  │     ├── AIService / LocalAsrService
  │     └── AiAssistant
  └── BillDisplayFormatter / RuleDialogHelper

BillDeleteHelper
  └── BillAssetImpactService

BillRestoreHelper
  └── BillAssetImpactService

BillBalanceSnapshotService
  └── AssetBillBalanceHistory
      └── BillAssetImpactService
```

---

## 跨币种换算核心公式

CurrencyManager 中 `rates` 映射表示 **1 CNY = ? 该货币**。

- `USD = 0.14` 表示 1 CNY = 0.14 USD
- 反过来 1 USD = 1 / 0.14 = 7.14 CNY

换算公式：
- **A -> B**：`amount / rateA * rateB`
- **A -> CNY**：`amount / rateA`
- **CNY -> B**：`amount * rateB`

---

## 退款机制总结

1. **退款账单**：`type=INCOME, subType=REFUND, relatedBillId=原账单ID`
2. **原账单金额变化**：`newAmount = originalAmount - refundAmount`（保持 `originalAmount` 不变）
3. **退款超额保护**：`delta <= latestOriginal.amount + 1e-9`
4. **分类前缀**：`"退款：原始分类"`
5. **删除退款**：恢复原账单金额
6. **删除原账单**：级联删除所有退款账单

---

## 投资利息机制总结

1. **投资份额（Lot）**：每次转入投资资产创建一个 Lot
2. **起息规则**：T+1（转账次日起息）
3. **到账规则**：起息后 1 天到账（可自定义）
4. **结算方式**：复利日结（每日利息加入本金）
5. **调和机制**：外部操作改变投资资产余额时，FIFO 方式调和份额
6. **自动执行**：WorkManager 每日凌晨 1:00 执行

### 8.2 AI 集成详细分析

# TapAccounting AI 集成深度解析

## 目录

1. [架构总览](#1-架构总览)
2. [AI 函数/端点清单](#2-ai-函数端点清单)
3. [System Prompt 全文](#3-system-prompt-全文)
4. [意图路由机制](#4-意图路由机制)
5. [多账单解析流程](#5-多账单解析流程)
6. [OCR + AI 精炼管线](#6-ocr--ai-精炼管线)
7. [语音转写流程](#7-语音转写流程)
8. [截图 AI 识别流程](#8-截图-ai-识别流程)
9. [JSON Schema 验证](#9-json-schema-验证)
10. [错误处理与重试逻辑](#10-错误处理与重试逻辑)
11. [Token 使用模式与流式/阻塞对比](#11-token-使用模式与流式阻塞对比)
12. [本地规则覆盖系统](#12-本地规则覆盖系统)
13. [数据流向图](#13-数据流向图)

---

## 1. 架构总览

### 核心文件清单

| 文件 | 职责 |
|------|------|
| `AIService.kt` | 主 AI 服务门面，所有 AI 调用的入口 |
| `AIServiceCommon.kt` | 公共工具函数（URL 标准化、JSON 清洗、错误格式化等） |
| `AIModels.kt` | Retrofit API 接口定义 + 数据模型（SiliconFlowApi） |
| `AIPrompts.kt` | 所有系统提示词的集中管理 |
| `AIPromptsWithoutAccount.kt` | 无资产模式下的专用提示词 |
| `AIAccountingPromptBuilder.kt` | 记账系统提示词 + 用户提示词的动态构建器 |
| `AIAssistantPromptBuilder.kt` | 聊天助手系统提示词的动态构建器 |
| `AIChatRequestBuilder.kt` | Chat 请求 JSON 构建器（文本/多模态/音频/多轮） |
| `AIAccountingSupport.kt` | 记账上下文构建、结果规范化、日期解析 |
| `AiProviderRegistry.kt` | AI 供应商预设注册表（硅基流动/DeepSeek/Kimi/通义千问/MiMo） |
| `AiModelSlots.kt` | 模型槽位解析器（文本/视觉/语音/聊天各用哪个模型） |
| `AiAssistant.kt` | 悬浮窗 AI 助手 UI 控制器 |
| `AiRule.kt` / `data/local/entity/AiRule.kt` | 本地纠错规则数据模型 |
| `chat/query/QueryPlanner.kt` | 查询意图规划器（本地规则 + 模型混合） |
| `chat/query/QueryExecutor.kt` | 查询执行器（从数据库加载账单并渲染回复） |
| `chat/query/QueryModels.kt` | 查询领域模型（QueryIntent/QuerySlots/QueryAction 等） |
| `chat/query/QueryContextBuilder.kt` | 查询上下文构建器（资产/分类/账本等） |
| `chat/query/QueryPlannerContextSerializer.kt` | 查询上下文 JSON 序列化 |
| `chat/query/QueryNavigator.kt` | 查询结果页面跳转器 |
| `chat/ai/AiTimeRangeParser.kt` | 自然语言时间范围解析器 |
| `chat/voice/VoicePayloadCodec.kt` | 语音消息编解码器 |
| `chat/voice/VoicePlaybackController.kt` | 语音播放控制器 |
| `chat/time/ChatTimeFormatter.kt` | 聊天时间格式化器 |
| `ReceiptOcrHelper.kt` | OCR 识别帮助类（ML Kit + 多模态） |
| `ReceiptImageInputHelper.kt` | 图片输入编解码与草稿合并 |
| `ChatBillMessageParser.kt` | 聊天账单消息解析器 |
| `ChatMessagePipeline.kt` | 聊天消息管线（意图路由 -> 记账/闲聊分发） |
| `StreamingBillPreview.kt` | 流式账单预览渲染器 |

### 多供应商支持架构

系统通过 `AiProviderRegistry` 支持 5 个 AI 供应商，每个供应商有独立的预设配置：

```
硅基流动 (SiliconFlow)  -- 文本 + 视觉 + 云端语音
DeepSeek                -- 仅文本（不支持视觉/语音）
Kimi (Moonshot)         -- 文本 + 视觉（不支持云端语音）
通义千问 (Qwen/DashScope) -- 文本 + 视觉 + 云端语音
小米 MiMo               -- 文本 + 视觉 + 云端语音
```

每个供应商的 `thinking` 参数格式不同，通过 `adaptChatRequestForProvider()` 统一适配：
- **DeepSeek / MiMo / Kimi**: 使用 `{"thinking":{"type":"enabled/disabled"}}`
- **其他供应商**: 使用 `"enable_thinking": true/false`
- **DeepSeek / 通义千问** 的流式请求额外添加 `stream_options.include_usage: true`

---

## 2. AI 函数/端点清单

### SiliconFlowApi (Retrofit 接口)

```kotlin
interface SiliconFlowApi {
    // 获取可用模型列表
    @GET("v1/models")
    suspend fun getModels(auth: String): ModelsResponse

    // 阻塞式聊天补全
    @POST("v1/chat/completions")
    suspend fun chat(auth: String, body: ChatRequest): ChatResponse

    // 阻塞式聊天（原始 JSON）
    @POST("v1/chat/completions")
    suspend fun chatRaw(auth: String, body: JsonObject): ChatResponse

    // 流式聊天补全（SSE）
    @Streaming
    @POST("v1/chat/completions")
    suspend fun chatStreamRaw(auth: String, body: JsonObject): ResponseBody

    // 语音转写（Multipart）
    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribe(auth: String, model: Part, file: Part): AudioResponse
}
```

### AIService 公开函数

| 函数 | 功能 | 温度 | 流式 | thinking |
|------|------|------|------|----------|
| `analyzeAccounting()` | 文本记账分析 | 0.3 | 是 | 可选 |
| `analyzeReceiptByImage()` | 单图票据识别（返回自然语言） | 0.1 | 是 | 可选 |
| `analyzeReceiptByImages()` | 多图票据识别（返回自然语言） | 0.1 | 是 | 可选 |
| `analyzeScreenAccountingByImage()` | 单图截图记账（返回 JSON） | 0.1 | 是 | 可选 |
| `analyzeScreenAccountingByImages()` | 多图截图记账（返回 JSON） | 0.1 | 是 | 可选 |
| `classifyIntent()` | 意图分类（记账/闲聊） | 0.1 | 是 | 否 |
| `generateAccountingAssistantReply()` | 记账助手回复 | 0.7 | 是 | 否 |
| `generateGeneralChatReply()` | 通用闲聊回复 | 0.7 | 是 | 否 |
| `streamAccountingAssistantReply()` | 流式记账助手回复 | 0.7 | 是 | 否 |
| `simpleChat()` | 简单聊天（无上下文） | 默认 | 是 | 是 |
| `speechToText()` | 语音转文字 | N/A | 否 | 否 |
| `fetchModels()` / `fetchModelsWithDetails()` | 获取模型列表 | N/A | 否 | N/A |
| `probeVisionInputSupport()` | 探测视觉模型是否支持图片输入 | 0.1 | 否 | 可选 |
| `probeDirectAudioInputSupport()` | 探测模型是否支持直接音频输入 | 0.1 | 否 | 否 |

---

## 3. System Prompt 全文

### 3.1 多账单记账提示词（有资产模式）

```
你是一个智能记账助手。
默认把当前输入视为记账内容来拆分，请优先输出多条账单 JSON。
只有在你确实无法提取出任何明确账单时，才输出：
{"no_bill":true,"reply":"<简短自然回复>"}

【数据说明】资产库、支出分类、收入分类、当前时间、币种列表等数据在用户消息的【数据上下文】中提供，请参照其中的数据进行匹配和判断。

【核心规则】
1. 同一句中出现多个金额、多个动作、多个对象时，必须拆分成多条账单。
2. category_name 优先命中更细的子分类；命中子分类时格式必须为 一级 - 二级。
   - 多条账单属于同一大类时，必须逐条根据商品本体区分子分类，禁止全部归入同一个子分类。
   - 例如：香蕉→水果，胡萝卜→蔬菜，饼干→零食，它们虽然都是"吃的"但子分类不同。
3. asset_name 与 to_asset_name 只允许从资产库中选择；无法确定时留空。
4. type 只允许 0=支出，1=收入，2=转账，3=还款。
5. 还款语义必须单独拆出一条账单。
6. time 必须输出 yyyy-MM-dd HH:mm:ss；同段多条账单可按 1 秒递增。
7. currency 必须输出大写币种代码；未提及时默认 CNY。
8. 严禁输出 Markdown、解释、代码块、前后缀文本。
9. 跨币种转账时，若某条账单用户明确给出"到账/收到/入账"金额，该条必须额外输出：
   - target_amount（到账金额，数字）
   - target_currency（到账币种，3位大写代码）
   若未明确给出到账金额，不要臆造这两个字段。

【输出格式】
{"bills":[{"amount":0.0,"type":0,"asset_name":"","category_name":"","time":"yyyy-MM-dd HH:mm:ss","remarks":"","currency":"CNY","to_asset_name":"","fee":0.0}]}
```

### 3.2 多账单记账提示词（无资产模式）

```
你是一个智能记账助手。请把用户输入解析为多条账单 JSON。

【模式说明】
- 当前账本未启用资产功能。
- 不需要识别付款账户、收款账户、转账或还款。
- 每条账单只允许两种 type：
  - 0 = 支出
  - 1 = 收入

【数据说明】支出分类、收入分类、货币类型等数据在用户消息的【数据上下文】中提供，请参照其中的数据进行匹配和判断。

【规则】
1. 默认按记账内容处理；只有确实无法提取任何明确账单时，才输出 {"no_bill":true,"reply":"<简短自然回复>"}。
2. 同一句里出现多个金额或多个动作时，必须拆成多条账单。
3. 严禁输出转账、还款语义；相关输入一律按更接近的支出或收入理解。
4. 若未提及币种，默认 CNY。
5. time 必须输出 yyyy-MM-dd HH:mm:ss，可按 1 秒递增。
6. 不要输出 Markdown、解释、代码块。

【输出格式】
{"bills":[{"amount":12.34,"type":0,"category_name":"餐饮 - 午餐","time":"2026-06-15 12:30:00","remarks":"黄焖鸡米饭","currency":"CNY"}]}
```

### 3.3 图片记账视觉助手提示词

```
你是"截图/图片记账视觉助手"。
你会收到一张截图或图片。你的任务是从画面中只提取真实可记账的交易信息，输出"待用户核对"的账单草稿 JSON。

【数据说明】资产库、支出分类、收入分类、当前时间、可用货币等数据在用户消息的【数据上下文】中提供，请参照其中的数据进行匹配和判断。

【核心识别原则】
1. 输入可能是支付/订单/账单截图，也可能是小票、票据、转账或收款凭证图片；先判断画面类型，再按真实交易提取。
2. 请忽略页面标题、导航栏、搜索栏、筛选条件、统计汇总、广告、按钮、图标、页脚、浮层、推荐服务、条码、税号等非交易内容。
3. 只提取画面中真实存在且可确认的账单/交易信息，不要臆造金额、时间、商户、商品、账户、分类或币种。
4. 金额必须与真实交易逐条对应，优先读取交易详情页、小票商品行、订单实付金额或支付结果中的主金额。严禁把余额、红包、积分、优惠、统计汇总、订单号、流水号、时间、手机号等误当金额。
5. 金额只取实际入账或实际支出的交易金额，不要把商品编号、订单号、交易单号中的数字识别为金额。
6. 若画面中有多条真实交易，按真实条目逐条提取；若只有一条明确交易，只提取这一条。
7. 若无法确认画面里存在可记账内容，返回：
{"no_bill":true,"reply":"未识别到可记账内容"}
8. 截图/图片记账存在误识别风险，不要暗示已经最终入账；是否输出 requires_review 等字段以用户消息中的任务指令为准。
9. 每条交易最重要的四个要素是：什么时候、买了什么/发生了什么、用了什么支付方式、扣了/收了多少钱。支付方式是否提取以动态规则为准。

【金额识别】
1. 支出金额通常表现为负数、付款、消费、支付成功、交易成功等；收入金额通常表现为收款、退款、转入、到账等。
2. 输出 amount 使用正数，不带正负号；交易方向由 type 表达。
3. 截图中显示 "-292.41""-17.98""-20.00" 时，amount 分别应为 292.41、17.98、20.00，type 通常为 0。
4. 同一页面同时出现多个数字时，优先选择交易主金额；不要选择订单号、交易单号、商户单号、卡号尾号、时间、积分、优惠金额或按钮文字中的数字。
5. 【严禁输出总计行】"总计""合计""小计""Total""Sum""支付合计"等汇总金额不是独立交易，绝对不能作为一条账单输出。它们是下方各商品金额的加总，已在各商品行中体现。

【备注规则】
1. remarks 用于保存"这笔钱具体花在/收到什么"，不要只写"支出""收入""消费""付款""转账"等泛词。
2. 有商品名/商品说明时，优先保留商品；有交易对象/商户名时，也尽量保留。
3. 简短记录核心信息，使用名词短语；禁止重复金额、币种、账户名、交易单号。

【时间规则】
1. 每条 bill.time 必须根据截图中的支付时间/交易时间提取。
2. 截图中有完整年月日时，必须使用截图给出的年份、月份、日期和时间。
3. 截图中只有月日、没有年份时，用数据上下文中当前时间的年份补全。
4. 截图中没有具体时分秒时，保留数据上下文中当前时间的时分秒；多条同一时间账单可按 1 秒递增。
5. 严禁忽略截图中明确出现的交易时间，也不要把日期或时间误当金额、备注或订单号。

【还款识别】
1. 如果交易表现为信用卡还款、贷款还款、花呗还款等，应使用 type=3。
2. 还款时，asset_name 表示付款账户，to_asset_name 表示被还款账户；无法确认时可留空。
3. 普通消费使用 type=0，不要误判为还款。

【输出格式】
1. 必须只返回一个合法 JSON 对象，不要输出 Markdown、解释、代码块或额外文本。
2. 每条 bill 字段固定为：
amount,type,asset_name,category_name,time,remarks,currency,to_asset_name,fee
3. 字段无法确认时使用空字符串或 0，不要臆造。
4. fee 没有手续费时填 0。
```

### 3.4 图片自然语言提取提示词（票据视觉提取助手）

```
你是"图片记账视觉提取助手"。

目标：
从用户上传的图片中识别真实可记账内容，整理成可直接交给记账模型继续解析的中文自然语言清单。

图片可能是：
1. 购物小票、超市票据、餐饮票据。
2. 支付宝、微信、银行、信用卡、外卖、电商、打车、酒店、订票等订单/支付/退款/转账截图。
3. 账单列表、交易详情页、支付成功页、收款到账页。

通用硬规则：
1. 只提取图片中真实存在且可确认的交易，不要臆造金额、商品、商户、账户、时间或币种。
2. 严格排除页面标题、导航栏、广告、按钮、统计汇总、余额、积分、红包、优惠券、订单号、流水号、手机号、条码、税号等非交易内容。
3. 金额必须与真实交易逐条对应；不要把余额、订单号、日期、时间、卡号尾号、编号或汇总数字当作消费金额。
4. 不确定的行宁可跳过；如果完全没有可记账内容，输出：未识别到可记账内容。
5. 严禁输出 JSON、解释、标题、代码块或总计汇总。

小票/票据规则：
1. 优先提取"商品 + 实付金额"，只保留商品行。
2. 商品名尽量清洗干净，删除无意义编码、税码字母、行号前缀。
3. 若有折扣，取折后实付金额。
4. 同名商品可合并，在名称后补 xN，并合并金额。
5. 外语商品名必须翻译成中文；无法确定时可输出"未翻译商品(原文)"。
6. 输出格式：购买中文名 (原文) 花了金额 币种。
7. 【严禁输出总计行】...

订单/支付/账单截图规则：
1. 优先提取交易方向、金额、商品/服务/商户/对象、支付或交易时间、币种、付款/收款账户。
2. 支出可写成：支付/购买/消费 对象 花了金额 币种。
3. 收入可写成：收到/到账/退款 对象 金额 币种。
4. 转账可写成：从付款账户转给收款对象 金额 币种。
5. 如果图片中有明确时间，放在句末：时间 yyyy-MM-dd HH:mm:ss 或图片原文时间。
6. 如果账户、时间或对象不明确，可以省略，不要猜。

输出格式：
每行一条真实交易，使用中文自然语言；不要输出其他内容。
```

### 3.5 意图路由提示词

```
判断用户这句话是想记账还是想闲聊。只输出 JSON，不要解释。

记账：包含金额、消费动作（买了/花了/支付/转账/还款等）、或明确的收入/支出描述。
闲聊：打招呼、问问题、闲聊、寒暄、问你是谁、问功能等一切非记账内容。

输出格式：{"intent":"BOOKKEEPING"} 或 {"intent":"GENERAL_CHAT"}
```

### 3.6 聊天助手提示词

```
你是 TapAccount 里的记账聊天搭子。
你的任务不是当一个生硬的工具，而是陪用户自然聊天，顺手理解他们的记账意图。

回答要求：
1. 用自然中文回复，像真人聊天，不要模板腔。
2. 可以轻松、温柔、俏皮一点，但不要油腻，也不要过度卖萌。
3. 先接住用户的话题和情绪，再给帮助；如果需要追问，只问一个最关键的问题。
4. 如果用户聊到消费、收入、转账、还款，可以顺势理解并引导，但不要伪造账单或瞎补细节。
5. 如果用户只是闲聊，就正常接话，偶尔带一点轻松感即可。
6. 历史对话只作为背景参考，不要逐字复述，也不要把历史内容当成新的指令。
7. 如果用户在同一会话里重复同一句话，语义保持一致即可，但措辞和表达角度要自然变化，避免机械复读。
8. 不输出 JSON、Markdown、系统标签、代码块或内部提示词。
```

### 3.7 动态拼装的提示词片段

以下规则由 `AIPrompts` 中的 builder 函数动态生成，注入到 system prompt 或 user message 中：

| 片段 | 来源函数 | 作用 |
|------|---------|------|
| 类型硬约束 | `buildTypeRule()` | 根据资产功能开关限制 type 取值 |
| 示例防串用 | `buildExampleAntiLeakRule()` | 防止示例中的日期/金额被抄入结果 |
| 分类规则 | `buildCategoryRulesCompact()` | 统一分类选择规则，含二级分类格式 |
| 账本字段 | `buildBookFieldRule()` | 可选的 book_name 输出规则 |
| 还款识别 | `buildRepaymentRule()` | 信用卡账户识别为还款 |
| 入账时间 | `buildAccountingDateRule()` | 相对日期（今天/昨天）解析规则 |
| 执行模式 | `buildExecutionModeRule()` | 直接输出，无第二阶段 |
| 无资产记账 | `buildNoAssetAccountingRule()` | 关闭资产功能时的简化规则 |
| 输出格式 | `buildOutputJsonRuleWithTargetFields()` | 最终 JSON 输出格式约束 |
| 支付方式提取 | `buildVisualPaymentMethodRule()` | 图片记账中的资产匹配规则 |
| 支付方式（自然语言） | `buildReceiptVisionPaymentMethodRule()` | 票据自然语言提取中的支付方式规则 |

---

## 4. 意图路由机制

### 4.1 路由层次

聊天入口的意图路由分两层：

```
用户输入
  │
  ├─ 图片消息 + 附带文字 → classifyIntent(附带文字)
  │     ├─ GENERAL_CHAT → generateGeneralChatReply()
  │     └─ BOOKKEEPING → 图片记账流程
  │
  └─ 纯文字消息 → classifyIntent(文字)
        ├─ GENERAL_CHAT → generateGeneralChatReply()
        └─ BOOKKEEPING → analyzeAccounting()
```

### 4.2 classifyIntent 实现细节

```kotlin
suspend fun classifyIntent(ctx: Context, userText: String): String
```

- 使用 `INTENT_ROUTER_PROMPT_DEFAULT` 作为 system prompt
- 温度 0.1（极低，确保稳定分类）
- 不开启 thinking
- 返回 `"BOOKKEEPING"` 或 `"GENERAL_CHAT"`
- 失败时默认返回 `"BOOKKEEPING"`（宁可误判为记账，不丢用户输入）

### 4.3 查询意图路由（QueryPlanner）

聊天中的查询功能有独立的路由系统，采用「本地规则优先 + 模型兜底」的混合策略：

```
用户查询文本
  │
  ├─ looksLikeWrite() → UNSUPPORTED（拒绝写操作）
  │
  ├─ shouldBypassModel() = true → 直接用本地规则结果
  │     （有明确时间 + 实体，或"最近一笔"，或"打开统计"）
  │
  └─ 否则 → modelPlanProvider() 获取模型规划
        │
        ├─ shouldPreferLocal() = true → 用本地结果
        └─ 否则 → 用模型结果
```

查询意图类型（QueryIntent）：
- `QUERY_BILLS` — 查询账单列表/汇总
- `QUERY_ASSET_STATS` — 查询资产统计
- `QUERY_CATEGORY_STATS` — 查询分类统计
- `QUERY_EXISTENCE` — 查询是否存在某类账单
- `OPEN_STATS_PAGE` — 跳转统计页
- `OPEN_ASSET_STATS_PAGE` — 跳转资产统计页
- `CLARIFY` — 需要用户澄清
- `UNSUPPORTED` — 不支持的操作（写操作）

---

## 5. 多账单解析流程

### 5.1 完整流程

```
用户文本输入
  │
  ├─ 1. shortenForModel(userInput, 12000)  ← 截断过长输入
  │
  ├─ 2. buildAccountingPromptContext(ctx)  ← 构建动态上下文
  │     ├─ 资产列表（名称/类别/币种）
  │     ├─ 支出分类树（一级 - 二级）
  │     ├─ 收入分类树（一级 - 二级）
  │     ├─ 可用币种列表
  │     ├─ 当前时间（含星期）
  │     └─ 可用账本列表
  │
  ├─ 3. loadActivePromptRules(ctx)  ← 加载本地纠错规则
  │     ├─ 数据库中的 AiRule（启用的）
  │     └─ Prefs 中的旧版规则
  │
  ├─ 4. findMatchedPromptRules(userInput, rules)  ← 关键词匹配
  │     （所有空格分隔的关键词都必须命中）
  │
  ├─ 5. buildAccountingSystemPrompt()  ← 构建系统提示词
  │     ├─ 基础提示词（MULTI_BILL_PROMPT_DEFAULT）
  │     ├─ 类型硬约束
  │     ├─ 示例防串用
  │     ├─ 分类规则（含二级分类适配）
  │     ├─ 账本字段规则
  │     ├─ 还款识别规则
  │     ├─ 入账时间规则
  │     ├─ 执行模式/无资产规则
  │     └─ 输出格式规则
  │
  ├─ 6. buildAccountingUserPrompt()  ← 构建用户提示词
  │     ├─ 【数据上下文】（JSON 格式的资产/分类/币种/时间）
  │     ├─ 【场景】对话记账 or 独立记账
  │     ├─ 【本地记账习惯修正规则】（如有命中）
  │     └─ 【用户输入】
  │
  ├─ 7. requestAccountingContentStreamed()  ← 流式请求
  │     ├─ 流式接收 SSE delta
  │     ├─ 实时回调 onProgress("AI_STREAM_TEXT::xxx")
  │     └─ 失败时 fallback 到非流式
  │
  ├─ 8. parseAnalyzeResult(content, isMultiMode=true)  ← 解析 JSON
  │     ├─ cleanJsonString()  ← 去除 ```json 包裹
  │     ├─ 支持 [{"amount":...}] 数组格式
  │     ├─ 支持 {"bills":[...]} 对象格式
  │     └─ 支持 {"amount":...} 单账单格式
  │
  ├─ 9. enforceExpenseForReceiptSummaries()  ← 小票场景强制支出
  │
  ├─ 10. normalizeAccountingResult()  ← 结果规范化
  │     ├─ type 归一化（3→2）
  │     ├─ 时间解析与补全
  │     ├─ 币种继承（资产绑定币种）
  │     ├─ 分类匹配（精确→叶子→紧凑→兜底"其他"）
  │     ├─ 资产名修正（支出/收入上的误放资产）
  │     ├─ 转账有效性校验（无有效资产→降级为支出）
  │     └─ 无资产模式强制清理
  │
  └─ 11. applyLocalRuleOverrideOnResult()  ← 本地规则最终覆盖
        （在规范化之后执行，确保规则优先级最高）
```

### 5.2 关键设计决策

**System Prompt 保持静态，动态数据注入 User Message**：这是为了提升 prompt cache 命中率。系统提示词只包含规则文本，资产/分类/时间等每次请求可能变化的数据放在 user message 开头的 `【数据上下文】` 块中。

**二级分类自适应**：`adaptPromptForCategoryDepth()` 会检测分类库是否包含二级分类，动态调整提示词中的格式要求。

---

## 6. OCR + AI 精炼管线

### 6.1 两种 OCR 模式

```kotlin
const val OCR_MODE_LOCAL      = 0   // 本地 ML Kit OCR + 文本 AI
const val OCR_MODE_MULTIMODAL = 1   // 直接多模态 AI（发送图片）
```

### 6.2 多模态模式流程（当前主要路径）

```
图片 URI
  │
  ├─ loadBitmapFromUri()  ← 加载并缩放到 2048px
  │
  ├─ bitmapToBase64()  ← 缩放到 1024px，JPEG 质量 80%
  │
  └─ AIService.analyzeReceiptByImage(base64, mimeType, supplementText)
        │
        ├─ buildVisionChatRequest()  ← 构建多模态请求
        │     ├─ system: RECEIPT_VISION_RETRY_PROMPT_DEFAULT + 支付方式规则
        │     └─ user: [image_url] + "请分析这张用于记账的图片..."
        │
        └─ requestChatContentStreamedWithReasoning()  ← 流式接收
              └─ ReceiptImageInputHelper.normalizeVisionSummary()  ← 标准化输出
```

### 6.3 本地 ML Kit OCR 模式（备用路径）

```
图片 URI
  │
  ├─ loadBitmapFromUri()  ← 缩放到 2048px
  │
  ├─ recognizeTextFromBitmap()  ← ML Kit ChineseTextRecognizer
  │     ├─ 同时支持中文 + 拉丁字符（波兰语/英文/数字）
  │     └─ 按行输出，块之间加空行
  │
  └─ 识别文字 → AIService.analyzeAccounting()  ← 文本记账流程
```

### 6.4 两阶段图片记账流程（草稿确认模式）

```
图片 → 多模态 AI → 自然语言清单（草稿）
  │
  ├─ 用户在 UI 上查看/编辑草稿
  │
  └─ 用户确认 → AIService.analyzeAccounting(草稿文本)
        │
        └─ 标准记账流程 → JSON 账单
```

---

## 7. 语音转写流程

### 7.1 两条路径

```kotlin
suspend fun speechToText(ctx: Context, audioFile: File): String?
```

根据供应商不同，走不同的路径：

**路径 A：Chat 输入音频（通义千问 / MiMo）**
```
音频文件（≤8MB）
  │
  ├─ Base64 编码
  │
  ├─ 构建 chat request:
  │     messages[0].content[0] = {
  │       type: "input_audio",
  │       input_audio: { data: "data:audio/wav;base64,..." }
  │     }
  │
  ├─ MiMo 额外添加: asr_options.language = "auto"
  │
  └─ chatRaw() → response.choices[0].message.content
```

**路径 B：标准转写 API（硅基流动等）**
```
音频文件
  │
  ├─ 检测 MIME 类型（wav/mp4/mpeg/ogg/flac）
  │
  ├─ Multipart 上传:
  │     model = resolveSpeechModel()
  │     file = audioFile
  │
  └─ transcribe() → response.text / result / transcript
```

### 7.2 语音消息编解码（VoicePayloadCodec）

语音消息在聊天中以特殊格式存储：

```
__voice_v2__:{"audioPath":"/path/to/audio.m4a","durationSec":15,"transcript":"用户说的话"}
```

解析时支持严格模式和宽松模式（正则回退）。

---

## 8. 截图 AI 识别流程

### 8.1 单图截图记账

```kotlin
suspend fun analyzeScreenAccountingByImage(
    ctx, imageBase64, mimeType, isMultiModeOverride, sourceKind,
    supplementText, onProgress, isFromChat, chatTurns
): JSONObject?
```

流程：
1. 使用 `resolveVisionModel()` 获取视觉模型
2. 构建 `buildScreenAccountingSystemPrompt()`（静态系统提示词）
3. 构建 `taskInstruction`（区分对话模式 vs 独立模式）
4. 注入本地纠错规则（如有 supplementText）
5. 流式请求，实时回调进度
6. 解析 JSON → `normalizeAccountingResult()` → `markVisualAccountingReviewDraft()`

### 8.2 多图截图记账

```kotlin
suspend fun analyzeScreenAccountingByImages(
    ctx, images: List<Pair<String, String>>, ...
): JSONObject?
```

一次性将所有图片发给多模态 AI，使用 `buildMultiImageVisionChatRequest()` 或 `buildMultiTurnMultiImageVisionChatRequest()`。

### 8.3 视觉记账草稿标记

`markVisualAccountingReviewDraft()` 为截图记账结果添加审核元数据：

```json
{
  "source_kind": "screen_capture",
  "requires_review": true,
  "natural_summary": "1. 支出 ¥292.41，xxx，时间...",
  "risk_flags": ["missing_asset", "unclear_item"],
  "bills": [...]
}
```

风险标记类型：
- `unclear_amount` — 金额 ≤ 0
- `missing_asset` — 资产功能开启但缺少 asset_name
- `unclear_item` — 备注为空
- `unclear_category` — 分类为空
- `missing_time` — 时间为空

---

## 9. JSON Schema 验证

### 9.1 输入验证

AI 输出的 JSON 经过以下验证和清洗链：

```
AI 原始输出
  │
  ├─ cleanJsonString()  ← 去除 ```json ``` 包裹
  │
  ├─ parseAnalyzeResult()
  │     ├─ 如果以 "[" 开头 → 包装为 {"bills": [...]}
  │     ├─ 如果有 "bills" 字段 → 直接使用
  │     ├─ 如果有 "amount" 字段 → 包装为 {"bills": [单条]}
  │     ├─ 如果有 "no_bill" 字段 → 返回（允许无账单）
  │     └─ 否则 → 抛出异常 "缺少关键字段"
  │
  └─ normalizeAccountingResult()
        ├─ type: 0/1/2 保留，3→2，其他→0
        ├─ time: 正则解析 + 日期补全 + 格式化
        ├─ currency: 资产绑定币种自动覆盖
        ├─ category_name: 模糊匹配（精确→叶子→紧凑→"其他"）
        ├─ asset_name: 从资产库精确匹配
        └─ to_asset_name: 同上
```

### 9.2 分类匹配算法

```kotlin
fun findBestMatch(input: String, candidates: List<String>): String?
```

四级匹配：
1. **精确匹配** — `candidates.contains(rawInput)`
2. **标准化路径匹配** — 统一分隔符后比较
3. **叶子节点匹配** — 只比较最后一级分类名
4. **紧凑匹配** — 去除所有分隔符后比较

分隔符标准化：`/::/`、`/:::/`、`::`、`/`、`\`、`|`、`>`、`->`、`=>`、`→`、`:`、`·` 全部统一为 `/::/`

### 9.3 查询结果解析

QueryPlanner 的模型输出也经过 JSON 提取和验证：

```kotlin
fun parseModelAction(rawContent: String, context: QueryContext, userText: String): QueryAction?
```

- 使用 `extractFirstJsonObjectText()` 提取第一个完整 JSON 对象
- 解析 intent、slots（timeRange/accountName/categoryName/keyword/billType/aggregation）
- 通过 `resolveAsset()` / `resolveCategory()` 匹配本地数据库实体
- `validateOrClarify()` 检查缺失信息并可能返回 CLARIFY 意图

---

## 10. 错误处理与重试逻辑

### 10.1 流式请求的三层降级

```kotlin
private suspend fun requestAccountingContentStreamed(...): String {
    val streamed = requestChatContentStreamedWithReasoning(...)
    
    // 第一层：流式成功完成
    if (streamed.completed) return streamed.content
    
    // 第二层：流式未完成但有部分内容
    if (streamed.content.isNotBlank()) {
        Logger.d("Stream incomplete but has content, using partial result")
        return streamed.content
    }
    
    // 第三层：完全无内容，fallback 到非流式阻塞请求
    val response = getApi(ctx).chatRaw(...)
    return response.choices.firstOrNull()?.message?.content
        ?: throw IllegalStateException("API returned empty choices")
}
```

### 10.2 流式结果数据结构

```kotlin
data class StreamResult(
    val content: String,        // 累积的 content delta
    val reasoning: String,      // 累积的 reasoning delta
    val completed: Boolean,     // 是否收到 [DONE]
    val sawDone: Boolean,       // 是否看到 [DONE] 标记
    val parseError: Exception?, // JSON 解析错误
    val transportError: Exception? // 网络传输错误
)
```

### 10.3 意图分类的容错

```kotlin
suspend fun classifyIntent(ctx, userText): String {
    // ... 
    catch (e: Exception) {
        if (e is CancellationException) throw e
        Logger.d("classifyIntent failed: ${e.message}, fallback to BOOKKEEPING")
        return "BOOKKEEPING"  // 永远不丢用户输入
    }
}
```

### 10.4 HTTP 错误详细化

```kotlin
internal fun detailedHttpError(e: Exception): String {
    if (e is HttpException) {
        val code = e.code()
        val body = runCatching { e.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
        return if (body.isNotBlank()) "HTTP $code, errorBody=$body" else "HTTP $code, message=${e.message()}"
    }
    return e.message ?: e.javaClass.simpleName
}
```

### 10.5 网络超时配置

| 场景 | 连接超时 | 读取超时 | 写入超时 |
|------|---------|---------|---------|
| 通用 API | 60s | 90s | 90s |
| 语音转写 | 60s | 180s | 180s |
| 模型列表获取 | 30s | 30s | 30s |
| 连接池保活 | 5 连接，5 分钟 | - | - |

### 10.6 DeepSeek null 值处理

DeepSeek 的 reasoning 流会发送大量 `content=null` 的 chunk，系统专门处理：

```kotlin
private fun jsonOptStringOrEmpty(obj: JSONObject?, key: String): String {
    if (obj == null || obj.isNull(key)) return ""
    val value = obj.optString(key, "")
    return if (value.equals("null", ignoreCase = true)) "" else value
}

private fun stripAccidentalNullPrefix(raw: String): String {
    var text = raw
    while (text.startsWith("null", ignoreCase = true)) {
        text = text.substring(4)
    }
    return text
}
```

---

## 11. Token 使用模式与流式/阻塞对比

### 11.1 流式 vs 阻塞

| 场景 | 模式 | 原因 |
|------|------|------|
| 记账分析 | 流式 + fallback | 用户需要实时进度反馈 |
| 图片记账 | 流式 | 长时间等待需要进度 |
| 意图分类 | 流式（但不展示） | 轻量请求，流式更可靠 |
| 闲聊回复 | 流式 | 逐字展示体验好 |
| 助手回复 | 流式/阻塞两种 | 根据调用场景选择 |
| 语音转写 | 阻塞 | 等待完整转写结果 |
| 模型列表 | 阻塞 | 一次性获取 |
| 能力探测 | 阻塞 | 简单探测请求 |

### 11.2 Prompt Cache 优化

系统通过 `logPromptCacheUsage()` 记录缓存命中情况：

```kotlin
private fun logPromptCacheUsage(ctx, requestTag, usage: JSONObject) {
    val hit = usage.optLong("prompt_cache_hit_tokens", -1)
    val miss = usage.optLong("prompt_cache_miss_tokens", -1)
    val promptTokens = usage.optLong("prompt_tokens", -1)
    val cachedTokens = usage.optLong("cached_tokens", -1)
    // ...
}
```

关键优化策略：
- **System Prompt 保持静态**：所有动态数据放在 User Message 中
- **流式请求添加 `stream_options.include_usage`**（DeepSeek/通义千问）以获取 token 统计

### 11.3 输入长度限制

| 场景 | 最大字符数 | 截断策略 |
|------|-----------|---------|
| 记账输入 | 12,000 | 保留头 70% + 尾 30%，中间省略 |
| 助手输入 | 4,000 | 保留头部 |
| 助手账单摘要 | 2,500 | 保留头部 |
| 聊天历史 | 48,000 总 | 从最新向最旧截断 |
| 单条历史消息 | 6,000 | 截断尾部 |
| 语音历史消息 | 400 | 截断尾部 |

### 11.4 Reasoning/Thinking 支持

系统支持 DeepSeek 等模型的 reasoning 输出：

```kotlin
private fun extractReasoningDelta(deltaObj: JSONObject?): String {
    if (deltaObj == null) return ""
    val candidates = listOf(
        jsonOptStringOrEmpty(deltaObj, "reasoning_content"),
        jsonOptStringOrEmpty(deltaObj, "reasoning"),
        jsonOptStringOrEmpty(deltaObj, "thinking")
    )
    return candidates.firstOrNull { it.isNotBlank() }.orEmpty()
}
```

Reasoning 支持的场景：
- 记账分析（可通过 `Prefs.isAiThinkingMultiBillEnabled()` 开关）
- 视觉识别（可通过 `Prefs.isAiThinkingVisionEnabled()` 开关）
- 简单聊天（默认开启）

---

## 12. 本地规则覆盖系统

### 12.1 规则数据模型

```kotlin
@Entity(tableName = "ai_rule")
data class AiRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val keyword: String,        // 匹配关键词，如 "花呗"
    val targetType: Int?,       // 目标类型 (0:支出, 1:收入, 2:转账, 3:还款)
    val targetCategory: String?, // 目标分类
    val targetAccount1: String?, // 目标出账账户
    val targetAccount2: String?, // 目标入账账户（转账用）
    val isEnabled: Boolean = true
)
```

### 12.2 规则应用流程

```
用户输入
  │
  ├─ 1. loadActivePromptRules()  ← 加载 DB + Prefs 中的规则
  │
  ├─ 2. findMatchedPromptRules()  ← 关键词匹配
  │     （关键词按空格分隔，所有词都必须在输入中出现）
  │
  ├─ 3. 匹配的规则注入到 User Prompt 的【本地记账习惯修正规则】块
  │     （AI 在生成时参考）
  │
  └─ 4. AI 输出后，applyLocalRuleOverrideOnResult()  ← 最终覆盖
        （在 normalizeAccountingResult() 之后执行，确保规则优先级最高）
        ├─ 多账单场景：用 remarks + categoryName 匹配（避免 A 账单规则命中 B）
        └─ 单账单场景：用 userInput 匹配
```

---

## 13. 数据流向图

### 13.1 记账主流程

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│ 用户输入     │────→│ ChatMessage  │────→│ AIService   │
│ (文字/语音/  │     │ Pipeline     │     │ .classify   │
│  图片)       │     │              │     │ Intent()    │
└─────────────┘     └──────┬───────┘     └──────┬──────┘
                           │                     │
                    ┌──────▼───────┐     ┌──────▼──────┐
                    │ BOOKKEEPING  │     │ GENERAL_CHAT│
                    │              │     │             │
                    ▼              │     ▼             │
            ┌───────────────┐     │  ┌────────────┐   │
            │ analyze       │     │  │ generate   │   │
            │ Accounting()  │     │  │ General    │   │
            │               │     │  │ ChatReply()│   │
            └───────┬───────┘     │  └────────────┘   │
                    │             │                    │
                    ▼             │                    │
            ┌───────────────┐     │                    │
            │ parseAnalyze  │     │                    │
            │ Result()      │     │                    │
            └───────┬───────┘     │                    │
                    │             │                    │
                    ▼             │                    │
            ┌───────────────┐     │                    │
            │ normalize     │     │                    │
            │ Accounting    │     │                    │
            │ Result()      │     │                    │
            └───────┬───────┘     │                    │
                    │             │                    │
                    ▼             │                    │
            ┌───────────────┐     │                    │
            │ applyLocal    │     │                    │
            │ RuleOverride  │     │                    │
            └───────┬───────┘     │                    │
                    │             │                    │
                    ▼             ▼                    ▼
            ┌─────────────────────────────────────────┐
            │ ChatBillMessageParser → 数据库持久化     │
            └─────────────────────────────────────────┘
```

### 13.2 图片记账流程

```
┌──────────┐     ┌──────────────┐     ┌─────────────────┐
│ 图片选择  │────→│ ReceiptImage │────→│ 两条路径分发     │
│          │     │ InputHelper  │     │                 │
└──────────┘     └──────────────┘     ├─ 自然语言模式   │
                                      │  analyzeReceipt │
                                      │  ByImage()     │
                                      │  → 文本清单     │
                                      │  → 用户确认     │
                                      │  → analyze     │
                                      │    Accounting() │
                                      │                 │
                                      └─ 直出模式       │
                                       analyzeScreen   │
                                       AccountingBy    │
                                       Image()         │
                                       → JSON 账单     │
                                       → markVisual    │
                                         ReviewDraft() │
```

### 13.3 查询流程

```
┌──────────┐     ┌──────────────┐     ┌─────────────┐
│ 用户查询  │────→│ QueryPlanner │────→│ Query       │
│ "本月花了 │     │              │     │ Executor    │
│  多少"    │     │ 本地规则     │     │             │
└──────────┘     │ + 模型规划   │     │ loadAnd     │
                 └──────────────┘     │ FilterBills │
                                      └──────┬──────┘
                                             │
                                      ┌──────▼──────┐
                                      │ render*Reply │
                                      │ (自然语言)   │
                                      └──────┬──────┘
                                             │
                                      ┌──────▼──────┐
                                      │ Query       │
                                      │ Navigator   │
                                      │ (可选跳转)   │
                                      └─────────────┘
```

---

## 附录：模型槽位配置

通过 `AiModelSlots` 解析每个 AI 功能使用的模型：

| 槽位 | 解析函数 | 用途 | 默认值 |
|------|---------|------|--------|
| TextModel | `resolveTextModel()` | 文本记账、意图分类 | 预设的 defaultTextModel |
| ChatModel | `resolveChatModel()` | 聊天助手、闲聊 | 跟随 TextModel 或独立设置 |
| VisionModel | `resolveVisionModel()` | 图片记账、截图识别 | 预设的 defaultVisionModel |
| SpeechModel | `resolveSpeechModel()` | 语音转写 | 预设的 defaultSpeechModel |

供应商预设默认模型：

| 供应商 | 文本模型 | 视觉模型 | 语音模型 |
|--------|---------|---------|---------|
| 硅基流动 | Qwen/Qwen3-14B | Qwen/Qwen3-VL-30B-A3B-Instruct | FunAudioLLM/SenseVoiceSmall |
| DeepSeek | deepseek-v4-flash | （不支持） | （不支持） |
| Kimi | kimi-k2.5 | kimi-k2.5 | （不支持） |
| 通义千问 | qwen3.5-flash | qwen3.5-flash | qwen3-asr-flash |
| 小米 MiMo | mimo-v2.5 | mimo-v2.5 | mimo-v2.5-asr |

### 8.3 数据层详细分析

# 数据层深度文档

> 数据库版本: **26** | 数据库名: `TapAccount_database`
> SharedPreferences: `flip_prefs` / `tap_backup_prefs` / `tap_cloud_backup_prefs` / `flip_currency_prefs`

---

## 一、实体 (Entity)

### 1.1 Asset (`assets`)

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | Long (PK, autoGenerate) | 0 | 主键 |
| `name` | String | - | 资产名称 |
| `type` | String | - | 资产类型名称 (如 "招商银行") |
| `balance` | Double | 0.0 | 当前余额 |
| `initialBalance` | Double | 0.0 | 初始金额 |
| `currency` | String | "CNY" | 币种 |
| `icon` | String | "" | 图标 URL 或内置标识 |
| `remark` | String | "" | 备注 |
| `includeInNetAsset` | Boolean | true | 是否计入总资产 |
| `sortOrder` | Int | 0 | 资产页面排序 |
| `pickerSortOrder` | Int | 0 | 记账选择器排序 |
| `createTime` | Long | System.currentTimeMillis() | 创建时间 |
| `showBillBalanceAfter` | Boolean | true | 资产详情是否显示每笔账单后的余额 |
| `billBalanceFromTime` | Long | 0L | 开始显示余额的时间起点 (0=用createTime) |
| `assetCategory` | String | "FUND" | 资产类别 |
| `creditLimit` | Double | 0.0 | 信用卡额度 (0=未设置) |
| `billingDay` | Int | 0 | 信用卡还款日 (保留字段) |
| `annualInterestRate` | Double | 0.0 | 年化利率百分比 (如 1.8 = 1.8%) |
| `interestLastSettledAt` | Long | System.currentTimeMillis() | 最近一次自动结息时间 |
| `isArchived` | Boolean | false | 是否收纳 |
| `includeInNetBeforeArchive` | Boolean | true | 收纳前是否计入总资产 (移出收纳时恢复) |

**资产类别常量:**
- `FUND` = "资金"
- `CREDIT_CARD` = "信用卡"
- `RECHARGE` = "充值账户"
- `INVESTMENT` = "投资理财"

**索引:** 无显式索引 (仅主键)

**外键:** 无

---

### 1.2 Bill (`bills`)

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | Long (PK, autoGenerate) | 0 | 主键 |
| `type` | Int | - | 主类型: 0=支出, 1=收入, 2=转账, 3=还款 |
| `subType` | Int | 0 | 子类型: 0=普通, 1=还款, 2=退款, 3=余额调整, 4=余额调整(不计入统计) |
| `amount` | Double | - | 金额 |
| `originalAmount` | Double | amount | 原始金额 (外币场景) |
| `currency` | String | "CNY" | 币种 |
| `exchangeRate` | Double | 1.0 | 汇率 |
| `categoryId` | Long? | null | 分类 ID (外键) |
| `accountId` | Long? | null | 出账资产 ID (外键) |
| `toAccountId` | Long? | null | 入账资产 ID (外键, 转账用) |
| `categoryName` | String | "" | 分类名称快照 |
| `accountName` | String | "" | 出账账户名快照 |
| `toAccountName` | String | "" | 入账账户名快照 |
| `time` | Long | - | 账单时间 (毫秒) |
| `remark` | String | "" | 备注 |
| `fee` | Double | 0.0 | 手续费 |
| `accountBalanceAfter` | Double? | null | 记账后出账资产余额快照 |
| `toAccountBalanceAfter` | Double? | null | 记账后入账资产余额快照 |
| `bookName` | String | "日常账本" | 所属账本 |
| `relatedBillId` | Long? | null | 关联账单 ID (如退款->原支出) |
| `isSynced` | Boolean | false | 是否已同步 |
| `excludeFromStats` | Boolean | false | 是否不计入统计 |

**类型常量:**
```
TYPE_EXPENSE = 0, TYPE_INCOME = 1, TYPE_TRANSFER = 2, TYPE_REPAYMENT = 3
SUBTYPE_NORMAL = 0, SUBTYPE_REPAYMENT = 1, SUBTYPE_REFUND = 2,
SUBTYPE_BALANCE_ADJUSTMENT = 3, SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED = 4
```

**外键:**
- `categoryId` -> `categories.id` (ON DELETE SET NULL)
- `accountId` -> `assets.id` (ON DELETE SET NULL)
- `toAccountId` -> `assets.id` (ON DELETE SET NULL)

**索引:**
- `categoryId`
- `accountId`
- `toAccountId`
- `time`
- `bookName`
- `relatedBillId`
- `(bookName, time)` 复合索引

---

### 1.3 Category (`categories`)

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | Long (PK, autoGenerate) | 0 | 主键 |
| `name` | String | - | 分类名称 |
| `type` | Int | - | 0=支出, 1=收入 |
| `parentId` | Long? | null | 父分类 ID (支持二级分类) |
| `iconId` | String | "" | 图标标识 |
| `sortOrder` | Int | 0 | 排序 |

**外键:**
- `parentId` -> `categories.id` (ON DELETE SET NULL, 自引用)

**索引:**
- `parentId`

---

### 1.4 AiRule (`ai_rule`)

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | Int (PK, autoGenerate) | 0 | 主键 |
| `keyword` | String | - | 触发关键词 (如 "花呗") |
| `targetType` | Int? | null | 目标记账类型 (0/1/2...) |
| `targetCategory` | String? | null | 目标分类名称 |
| `targetAccount1` | String? | null | 出账/收入账户名 |
| `targetAccount2` | String? | null | 入账账户 (转账用) |
| `isEnabled` | Boolean | true | 是否启用 |

**索引:** 无显式索引

---

### 1.5 ChatMessage (`chat_messages`)

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | Long (PK, autoGenerate) | 0 | 主键 |
| `msgType` | Int | - | 消息类型: 0=用户文本, 1=用户图片, 2=用户语音, 3=AI文本, 4=AI账单JSON |
| `content` | String | "" | 消息内容 |
| `imageUri` | String | "" | 图片 URI (仅 msgType=1) |
| `timestamp` | Long | System.currentTimeMillis() | 时间戳 (毫秒) |
| `billIds` | String | "" | 关联账单 ID 列表 (JSON) |
| `modelName` | String | "" | 使用的 AI 模型名称 |
| `bookName` | String | "" | 所属账本 |
| `conversationId` | String | "" | 会话 ID |

**索引:** 无显式索引

---

### 1.6 DeletedBill (`deleted_bills`)

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | Long (PK, autoGenerate) | 0 | 主键 |
| `originalBillId` | Long | - | 原始账单 ID |
| `type` | Int | - | 账单类型 |
| `subType` | Int | 0 | 子类型 |
| `amount` | Double | - | 金额 |
| `originalAmount` | Double | amount | 原始金额 |
| `currency` | String | "CNY" | 币种 |
| `exchangeRate` | Double | 1.0 | 汇率 |
| `categoryId` | Long? | null | 分类 ID |
| `accountId` | Long? | null | 出账资产 ID |
| `toAccountId` | Long? | null | 入账资产 ID |
| `categoryName` | String | "" | 分类名快照 |
| `accountName` | String | "" | 出账账户名快照 |
| `toAccountName` | String | "" | 入账账户名快照 |
| `time` | Long | - | 账单时间 |
| `remark` | String | "" | 备注 |
| `fee` | Double | 0.0 | 手续费 |
| `bookName` | String | "日常账本" | 所属账本 |
| `relatedBillId` | Long? | null | 关联账单 ID |
| `excludeFromStats` | Boolean | false | 是否不计入统计 |
| `deletedAt` | Long | System.currentTimeMillis() | 删除时间 |

**索引:** 无显式索引 (Bill 的快照表, 无外键)

---

### 1.7 InvestmentLot (`investment_lots`)

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | Long (PK, autoGenerate) | 0 | 主键 |
| `assetId` | Long | - | 关联资产 ID |
| `sourceBillId` | Long? | null | 来源账单 ID |
| `principalAmount` | Double | - | 本金金额 |
| `remainingPrincipal` | Double | - | 剩余本金 (>0 表示未赎回) |
| `currency` | String | - | 币种 |
| `startEarningAt` | Long | - | 开始计息时间 |
| `firstPayoutAt` | Long | - | 首次付息时间 |
| `lastSettledAt` | Long | - | 最近结息时间 |
| `createTime` | Long | System.currentTimeMillis() | 创建时间 |

**外键:**
- `assetId` -> `assets.id` (ON DELETE CASCADE)
- `sourceBillId` -> `bills.id` (ON DELETE SET NULL)

**索引:**
- `assetId`
- `sourceBillId` (UNIQUE)
- `lastSettledAt`

---

## 二、DAO 查询方法

### 2.1 AssetDao

| 方法 | SQL | 参数 | 返回类型 |
|---|---|---|---|
| `insertAsset` | `@Insert(REPLACE)` | Asset | Long |
| `updateAsset` | `@Update` | Asset | Unit |
| `deleteAsset` | `@Delete` | Asset | Unit |
| `getAllAssets` | `SELECT * FROM assets ORDER BY sortOrder ASC, includeInNetAsset DESC, id ASC` | - | Flow\<List\<Asset\>\> |
| `getAllAssetsList` | 同上 | - | suspend List\<Asset\> |
| `getAllAssetsListForPicker` | `SELECT * FROM assets ORDER BY CASE WHEN pickerSortOrder=0 THEN sortOrder ELSE pickerSortOrder END ASC, id ASC` | - | suspend List\<Asset\> |
| `updatePickerSortOrder` | `UPDATE assets SET pickerSortOrder=:order WHERE id=:assetId` | assetId: Long, order: Int | suspend Unit |
| `countAssetsWithDefaultPickerOrder` | `SELECT COUNT(*) FROM assets WHERE pickerSortOrder=0` | - | suspend Int |
| `getMaxPickerSortOrder` | `SELECT MAX(pickerSortOrder) FROM assets` | - | suspend Int? |
| `getAssetById` | `SELECT * FROM assets WHERE id=:assetId` | assetId: Long | suspend Asset? |
| `observeAssetById` | `SELECT * FROM assets WHERE id=:assetId` | assetId: Long | Flow\<Asset?\> |
| `updateBalance` | `UPDATE assets SET balance=:newBalance WHERE id=:assetId` | assetId: Long, newBalance: Double | suspend Unit |
| `updateBillBalanceDisplay` | `UPDATE assets SET showBillBalanceAfter=:showBillBalanceAfter, billBalanceFromTime=:billBalanceFromTime WHERE id=:assetId` | 3 参数 | suspend Unit |
| `updateAssetInfo` | `UPDATE assets SET name=:name, type=:type, ... WHERE id=:id` | 16 参数 | suspend Unit |
| `updateBalanceAfterInterest` | `UPDATE assets SET balance=:newBalance, interestLastSettledAt=:settledAt WHERE id=:assetId` | 3 参数 | suspend Unit |
| `updateInterestLastSettledAt` | `UPDATE assets SET interestLastSettledAt=:settledAt WHERE id=:assetId` | 2 参数 | suspend Unit |
| `addBalanceDelta` | `UPDATE assets SET balance=balance+:delta WHERE id=:assetId` | 2 参数 | suspend Unit |
| `updateSortOrder` | `UPDATE assets SET sortOrder=:sortOrder WHERE id=:assetId` | 2 参数 | suspend Unit |
| `archiveAsset` | `UPDATE assets SET isArchived=1, includeInNetBeforeArchive=:includeBeforeArchive, includeInNetAsset=0 WHERE id=:assetId` | 2 参数 | suspend Unit |
| `unarchiveAsset` | `UPDATE assets SET isArchived=0, includeInNetAsset=includeInNetBeforeArchive WHERE id=:assetId` | 1 参数 | suspend Unit |
| `getMaxSortOrderInCategory` | `SELECT MAX(sortOrder) FROM assets WHERE assetCategory=:category` | category: String | suspend Int? |
| `getAssetByName` | `SELECT * FROM assets WHERE name=:name LIMIT 1` | name: String | suspend Asset? |
| `deleteAll` | `DELETE FROM assets` | - | suspend Unit |

**事务方法:**
- `ensurePickerSortOrderBackfilled()` -- 检查并回填 pickerSortOrder
- `reorderPickerSortOrders(assetIdsInOrder)` -- 批量重排选择器排序
- `updateArchived(assetId, archived)` -- 归档/取消归档
- `reorderAssets(orders)` -- 批量设置排序

---

### 2.2 BillDao

| 方法 | SQL | 返回类型 |
|---|---|---|
| `delete(bill)` | `@Delete` | suspend Unit |
| `delete(bills)` | `@Delete` | suspend Unit |
| `insertBill` | `@Insert(REPLACE)` | suspend Long |
| `insertBills` | `@Insert(REPLACE)` | suspend Unit |
| `updateBill` | `@Update` | suspend Unit |
| `updateBills` | `@Update` | suspend Unit |
| `getAllBillsList` | `SELECT * FROM bills` | suspend List\<Bill\> |
| `getBillsBetweenTimes(startTime, endTime)` | `SELECT * FROM bills WHERE time BETWEEN :startTime AND :endTime ORDER BY time DESC` | Flow\<List\<Bill\>\> |
| `getBillsBetweenTimesList(startTime, endTime)` | 同上 | suspend List\<Bill\> |
| `getBillsByBookNamesBetweenTimes(bookNames, startTime, endTime)` | `...WHERE bookName IN (:bookNames) AND time BETWEEN ...` | Flow\<List\<Bill\>\> |
| `getBillsByBookNamesBetweenTimesList(bookNames, startTime, endTime)` | 同上 | suspend List\<Bill\> |
| `getBillsByAssetId(assetId)` | `WHERE accountId=:assetId OR toAccountId=:assetId ORDER BY time DESC` | Flow\<List\<Bill\>\> |
| `getBillsByAssetIdOrName(assetId, assetName)` | `WHERE accountId=:assetId OR toAccountId=:assetId OR (assetName!='' AND accountName=:assetName) OR ...` | Flow\<List\<Bill\>\> |
| `backfillAccountIdByName` | `UPDATE bills SET accountId=(SELECT assets.id FROM assets WHERE assets.name=bills.accountName LIMIT 1) WHERE accountId IS NULL AND accountName IS NOT NULL AND accountName!=''` | suspend Unit |
| `backfillToAccountIdByName` | 同上 (toAccountId/toAccountName) | suspend Unit |
| `bindAccountIdByLegacyName(assetId, oldName)` | `UPDATE bills SET accountId=:assetId WHERE accountId IS NULL AND accountName=:oldName` | suspend Unit |
| `bindToAccountIdByLegacyName(assetId, oldName)` | 同上 (toAccountId/toAccountName) | suspend Unit |
| `syncAccountNameByAssetId(assetId, newName)` | `UPDATE bills SET accountName=:newName WHERE accountId=:assetId` | suspend Unit |
| `syncToAccountNameByAssetId(assetId, newName)` | `UPDATE bills SET toAccountName=:newName WHERE toAccountId=:assetId` | suspend Unit |
| `syncCategoryNameByCategoryId(categoryId, newName)` | `UPDATE bills SET categoryName=:newName WHERE categoryId=:categoryId` | suspend Unit |
| `syncCategoryNameByOldName(oldLeaf, newLeaf)` | `UPDATE bills SET categoryName=REPLACE(categoryName, :oldLeaf, :newLeaf) WHERE categoryId IS NULL AND (categoryName=:oldLeaf OR categoryName LIKE '% - '||:oldLeaf OR categoryName='退款：'||:oldLeaf OR categoryName LIKE '退款：% - '||:oldLeaf)` | suspend Unit |
| `getUnsyncedBills` | `WHERE isSynced=0` | suspend List\<Bill\> |
| `markAsSynced(billIds)` | `UPDATE bills SET isSynced=1 WHERE id IN (:billIds)` | suspend Unit |
| `getBillById(id)` | `WHERE id=:id LIMIT 1` | suspend Bill? |
| `getBillsByIds(ids)` | `WHERE id IN (:ids)` | suspend List\<Bill\> |
| `getRefundBillsBySourceId(sourceBillId, refundSubtype)` | `WHERE relatedBillId=:sourceBillId AND subType=:refundSubtype ORDER BY time DESC` | suspend List\<Bill\> |
| `findLikelyRefundSourceBill(...)` | 多条件匹配退款源账单 (同账本、同分类、金额>=退款额、时间<=退款时间，按金额精确匹配优先排序) | suspend Bill? |
| `getRefundTotalBySourceId(sourceBillId)` | `SELECT COALESCE(SUM(amount),0.0) FROM bills WHERE relatedBillId=:sourceBillId AND subType=:refundSubtype` | suspend Double |
| `getAllBookNames` | `SELECT DISTINCT bookName FROM bills ORDER BY bookName ASC` | suspend List\<String\> |
| `getBillsByBookNamesList(bookNames)` | `WHERE bookName IN (:bookNames)` | suspend List\<Bill\> |
| `countBillsByBookName(bookName)` | `SELECT COUNT(*) FROM bills WHERE bookName=:bookName` | suspend Int |
| `renameBookName(oldBookName, newBookName)` | `UPDATE bills SET bookName=:newBookName WHERE bookName=:oldBookName` | suspend Unit |
| `moveBillsToBook(ids, newBookName)` | `UPDATE bills SET bookName=:newBookName WHERE id IN (:ids)` | suspend Unit |
| `getBillsByAssetIdList(assetId)` | `WHERE accountId=:assetId OR toAccountId=:assetId` | suspend List\<Bill\> |
| `getBillsByAssetIdOrNameList(assetId, assetName)` | 同 getBillsByAssetIdOrName 但 suspend 版 | suspend List\<Bill\> |
| `getBillsByAssetIdOrNameListLimited(assetId, assetName, limit)` | 同上 + LIMIT | suspend List\<Bill\> |
| `getBillsByCategoryIdList(categoryId)` | `WHERE categoryId=:categoryId` | suspend List\<Bill\> |
| `getBillIdsByCategoryIdList(categoryId)` | `SELECT id FROM bills WHERE categoryId=:categoryId` | suspend List\<Long\> |
| `getBillsByCategoryNameList(name)` | `WHERE categoryId IS NULL AND (categoryName=:name OR categoryName LIKE '% - '||:name OR categoryName='退款：'||:name OR categoryName LIKE '退款：% - '||:name)` | suspend List\<Bill\> |
| `getBillIdsByCategoryNameList(name)` | 同上但只 SELECT id | suspend List\<Long\> |
| `clearAccountId(assetId)` | `UPDATE bills SET accountId=NULL WHERE accountId=:assetId` | suspend Unit |
| `clearToAccountId(assetId)` | `UPDATE bills SET toAccountId=NULL WHERE toAccountId=:assetId` | suspend Unit |
| `markDeletedAccountName(oldName, deletedLabel)` | `UPDATE bills SET accountName=:deletedLabel WHERE accountId IS NULL AND accountName=:oldName` | suspend Unit |
| `markDeletedToAccountName(oldName, deletedLabel)` | 同上 (toAccountName) | suspend Unit |
| `countBillsByCategoryId(categoryId)` | `SELECT COUNT(*) FROM bills WHERE categoryId=:categoryId` | suspend Int |
| `migrateCategoryId(oldCategoryId, newCategoryId)` | `UPDATE bills SET categoryId=:newCategoryId WHERE categoryId=:oldCategoryId` | suspend Unit |
| `clearCategoryId(categoryId)` | `UPDATE bills SET categoryId=NULL WHERE categoryId=:categoryId` | suspend Unit |
| `countBillsByCategoryName(name)` | `SELECT COUNT(*) FROM bills WHERE categoryName=:name OR ... (同上模式)` | suspend Int |
| `migrateCategoryByName(name, newCategoryId)` | `UPDATE bills SET categoryId=:newCategoryId WHERE categoryId IS NULL AND (categoryName=:name OR ...)` | suspend Unit |
| `clearCategoryByName(name)` | `UPDATE bills SET categoryName='' WHERE categoryId IS NULL AND (categoryName=:name OR ...)` | suspend Unit |
| `deleteAll` | `DELETE FROM bills` | suspend Unit |
| `countDuplicateBills(time, amount, type, accountName)` | `SELECT COUNT(*) FROM bills WHERE time=:time AND ABS(amount-:amount)<0.001 AND type=:type AND accountName=:accountName LIMIT 1` | suspend Int |
| `deleteBillsBetweenTimes(startTime, endTime)` | `DELETE FROM bills WHERE time BETWEEN :startTime AND :endTime` | suspend Unit |
| `deleteBillsByBookNameBetweenTimes(bookName, startTime, endTime)` | `DELETE FROM bills WHERE bookName=:bookName AND time BETWEEN :startTime AND :endTime` | suspend Unit |
| `countBillsBetweenTimes(startTime, endTime)` | `SELECT COUNT(*) ...` | suspend Int |
| `countBillsByBookNameBetweenTimes(bookName, startTime, endTime)` | `SELECT COUNT(*) ...` | suspend Int |
| `sumAmountBetweenTimes(startTime, endTime)` | `SELECT COALESCE(SUM(amount),0.0) ...` | suspend Double |
| `sumAmountByBookNameBetweenTimes(bookName, startTime, endTime)` | `SELECT COALESCE(SUM(amount),0.0) ...` | suspend Double |
| `updateExcludeStats(billId, exclude)` | `UPDATE bills SET excludeFromStats=:exclude WHERE id=:billId` | suspend Unit |
| `updateExcludeStatsForBills(billIds, exclude)` | `UPDATE bills SET excludeFromStats=:exclude WHERE id IN (:billIds)` | suspend Unit |
| `deleteAllByBookName(bookName)` | `DELETE FROM bills WHERE bookName=:bookName` | suspend Unit |
| `getRecentBillsByBookName(bookName, limit)` | `WHERE bookName=:bookName ORDER BY time DESC, id DESC LIMIT :limit` | suspend List\<Bill\> |
| `getRecentBillsByBookNames(bookNames, limit)` | `WHERE bookName IN (:bookNames) ORDER BY time DESC, id DESC LIMIT :limit` | suspend List\<Bill\> |
| `getRecentBills(limit)` | `ORDER BY time DESC, id DESC LIMIT :limit` | suspend List\<Bill\> |
| `getRecentExpenseBills(limit, expenseType, refundSubtype)` | `WHERE type=:expenseType AND subType!=:refundSubtype ORDER BY time DESC LIMIT :limit` | suspend List\<Bill\> |

**事务方法:**
- `backfillAssetLinksByName()` -- 批量回填 accountId 和 toAccountId
- `backfillAssetLinksByName()` -- 同上

---

### 2.3 AiRuleDao

| 方法 | SQL | 返回类型 |
|---|---|---|
| `getAllRules` | `SELECT * FROM ai_rule ORDER BY id DESC` | Flow\<List\<AiRule\>\> |
| `getAllRulesList` | `SELECT * FROM ai_rule` | suspend List\<AiRule\> |
| `getEnabledRulesList` | `SELECT * FROM ai_rule WHERE isEnabled=1` | suspend List\<AiRule\> |
| `getRulesByKeyword(keyword)` | `SELECT * FROM ai_rule WHERE keyword=:keyword` | suspend List\<AiRule\> |
| `insertRule` | `@Insert(REPLACE)` | suspend Unit |
| `updateRule` | `@Update` | suspend Unit |
| `deleteRule` | `@Delete` | suspend Unit |
| `deleteRuleById(id)` | `DELETE FROM ai_rule WHERE id=:id` | suspend Unit |
| `deleteAll` | `DELETE FROM ai_rule` | suspend Unit |

---

### 2.4 CategoryDao

| 方法 | SQL | 返回类型 |
|---|---|---|
| `insertCategory` | `@Insert(REPLACE)` | suspend Long |
| `getAllCategoriesList` | `SELECT * FROM categories` | suspend List\<Category\> |
| `getCategoriesByType(type)` | `SELECT * FROM categories WHERE type=:type ORDER BY sortOrder ASC, id ASC` | Flow\<List\<Category\>\> |
| `getCategoriesListByType(type)` | 同上 | suspend List\<Category\> |
| `getCategoryByName(name)` | `WHERE name=:name LIMIT 1` | suspend Category? |
| `getCategoryById(id)` | `WHERE id=:id LIMIT 1` | suspend Category? |
| `getCategoryByNameAndType(name, type)` | `WHERE name=:name AND type=:type LIMIT 1` | suspend Category? |
| `getMaxSortOrder(type, parentId)` | `SELECT MAX(sortOrder) FROM categories WHERE type=:type AND IFNULL(parentId,0)=IFNULL(:parentId,0)` | suspend Int? |
| `updateCategory` | `@Update` | suspend Unit |
| `deleteById(id)` | `DELETE FROM categories WHERE id=:id` | suspend Unit |
| `getChildrenByParentId(parentId)` | `WHERE parentId=:parentId ORDER BY sortOrder ASC, id ASC` | suspend List\<Category\> |
| `deleteAll` | `DELETE FROM categories` | suspend Unit |

---

### 2.5 ChatMessageDao

| 方法 | SQL | 返回类型 |
|---|---|---|
| `insert` | `@Insert(REPLACE)` | suspend Long |
| `delete` | `@Delete` | suspend Unit |
| `deleteByIds(ids)` | `DELETE FROM chat_messages WHERE id IN (:ids)` | suspend Unit |
| `getAll` | `ORDER BY timestamp ASC` | suspend List\<ChatMessage\> |
| `getAllByBook(bookName)` | `WHERE bookName=:bookName ORDER BY timestamp ASC` | suspend List\<ChatMessage\> |
| `getAllByBookLimited(bookName, limit)` | 子查询倒序取最近N条再正序 | suspend List\<ChatMessage\> |
| `getAllByBookAndConversation(bookName, conversationId)` | `WHERE bookName=:bookName AND conversationId=:conversationId ORDER BY timestamp ASC` | suspend List\<ChatMessage\> |
| `getRecentMessages(bookName, conversationId, limit)` | 子查询倒序取最近N条再正序 | suspend List\<ChatMessage\> |
| `getLatestConversationIdByBook(bookName)` | `SELECT conversationId ... WHERE bookName=:bookName ORDER BY timestamp DESC LIMIT 1` | suspend String? |
| `getLatestConversationIdByBookAndPrefix(bookName, convIdPrefix)` | `WHERE conversationId LIKE :convIdPrefix` (已废弃) | suspend String? |
| `getLatestAccountingConversationIdByBook(bookName)` | `WHERE conversationId NOT GLOB 'agent_*' ORDER BY timestamp DESC LIMIT 1` | suspend String? |
| `search(query)` | `WHERE content LIKE :query ORDER BY timestamp DESC` | suspend List\<ChatMessage\> |
| `searchByBook(bookName, query)` | `WHERE bookName=:bookName AND content LIKE :query ORDER BY timestamp DESC` | suspend List\<ChatMessage\> |
| `count()` | `SELECT COUNT(*) FROM chat_messages` | suspend Int |
| `deleteByBookAndConversation(bookName, conversationId)` | `DELETE ... WHERE bookName=:bookName AND conversationId=:conversationId` | suspend Unit |
| `renameBookName(oldBookName, newBookName)` | `UPDATE chat_messages SET bookName=:newBookName WHERE bookName=:oldBookName` | suspend Unit |
| `deleteAllByBookName(bookName)` | `DELETE FROM chat_messages WHERE bookName=:bookName` | suspend Unit |
| `migrateLegacyBookAndConversation(bookName, conversationId)` | `UPDATE ... SET bookName=:bookName, conversationId=:conversationId WHERE (bookName IS NULL OR bookName='' OR conversationId IS NULL OR conversationId='')` | suspend Unit |
| `migrateLegacyConversationId(conversationId)` | `UPDATE ... SET conversationId=:conversationId WHERE conversationId IS NULL OR conversationId=''` | suspend Unit |
| `migrateLegacyBookName(bookName)` | `UPDATE ... SET bookName=:bookName WHERE bookName IS NULL OR bookName=''` | suspend Unit |
| `update` | `@Update` | suspend Unit |
| `getById(id)` | `WHERE id=:id` | suspend ChatMessage? |
| `findNextUserMessage(...)` | 查找指定位置之后的下一条用户消息 (按 timestamp+id 排序) | suspend ChatMessage? |
| `findAssistantMessageIdsBetween(...)` | 查找两个位置之间的 AI 消息 ID 列表 | suspend List\<Long\> |
| `getLatestMessageByType(bookName, conversationId, msgType)` | `WHERE ... AND msgType=:msgType ORDER BY timestamp DESC LIMIT 1` | suspend ChatMessage? |

---

### 2.6 DeletedBillDao

| 方法 | SQL | 返回类型 |
|---|---|---|
| `insert` | `@Insert(REPLACE)` | suspend Long |
| `insertAll` | `@Insert(REPLACE)` | suspend Unit |
| `delete` | `@Delete` | suspend Unit |
| `delete(deletedBills)` | `@Delete` | suspend Unit |
| `getAllDeletedBills` | `SELECT * FROM deleted_bills ORDER BY deletedAt DESC` | suspend List\<DeletedBill\> |
| `getDeletedBillById(id)` | `WHERE id=:id LIMIT 1` | suspend DeletedBill? |
| `deleteByIds(ids)` | `DELETE FROM deleted_bills WHERE id IN (:ids)` | suspend Unit |
| `deleteAll` | `DELETE FROM deleted_bills` | suspend Unit |
| `getCount` | `SELECT COUNT(*) FROM deleted_bills` | suspend Int |

---

### 2.7 InvestmentLotDao

| 方法 | SQL | 返回类型 |
|---|---|---|
| `insertLot` | `@Insert(REPLACE)` | suspend Long |
| `updateLot` | `@Update` | suspend Unit |
| `getOpenLots` | `WHERE remainingPrincipal>0.0 ORDER BY startEarningAt ASC, id ASC` | suspend List\<InvestmentLot\> |
| `getOpenLotsByAssetId(assetId)` | `WHERE assetId=:assetId AND remainingPrincipal>0.0 ORDER BY startEarningAt ASC, id ASC` | suspend List\<InvestmentLot\> |
| `getAllLots` | `ORDER BY startEarningAt ASC, id ASC` | suspend List\<InvestmentLot\> |
| `getLotBySourceBillId(billId)` | `WHERE sourceBillId=:billId LIMIT 1` | suspend InvestmentLot? |
| `deleteBySourceBillId(billId)` | `DELETE FROM investment_lots WHERE sourceBillId=:billId` | suspend Unit |
| `deleteByAssetId(assetId)` | `DELETE FROM investment_lots WHERE assetId=:assetId` | suspend Unit |
| `deleteAll` | `DELETE FROM investment_lots` | suspend Unit |

---

## 三、AppDatabase

- **数据库名:** `TapAccount_database`
- **当前版本:** 26
- **exportSchema:** false
- **注册实体:** Bill, Asset, Category, AiRule, ChatMessage, InvestmentLot, DeletedBill
- **注册 DAO:** BillDao, AssetDao, CategoryDao, AiRuleDao, ChatMessageDao, InvestmentLotDao, DeletedBillDao
- **TypeConverters:** 无
- **Callbacks:** 无
- **单例模式:** `@Volatile` + `synchronized`

---

## 四、Migration (Room 版本迁移)

### MIGRATION_5_6
```sql
ALTER TABLE Bill ADD COLUMN fee REAL NOT NULL DEFAULT 0.0
```

### MIGRATION_6_7
```sql
ALTER TABLE assets ADD COLUMN creditLimit REAL NOT NULL DEFAULT 0.0
```

### MIGRATION_7_8
```sql
ALTER TABLE bills ADD COLUMN relatedBillId INTEGER
CREATE INDEX IF NOT EXISTS index_bills_relatedBillId ON bills(relatedBillId)
```

### MIGRATION_8_9
```sql
ALTER TABLE assets ADD COLUMN billingDay INTEGER NOT NULL DEFAULT 0
```

### MIGRATION_9_10
```sql
ALTER TABLE assets ADD COLUMN pickerSortOrder INTEGER NOT NULL DEFAULT 0
```

### MIGRATION_10_11
```sql
CREATE TABLE IF NOT EXISTS `chat_messages` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `msgType` INTEGER NOT NULL,
    `content` TEXT NOT NULL DEFAULT '',
    `imageUri` TEXT NOT NULL DEFAULT '',
    `timestamp` INTEGER NOT NULL,
    `billIds` TEXT NOT NULL DEFAULT '',
    `modelName` TEXT NOT NULL DEFAULT ''
)
```

### MIGRATION_11_12
重建 chat_messages 表，移除 SQL DEFAULT、添加 `bookName` 和 `conversationId` 列：
```sql
CREATE TABLE chat_messages_new (...)
INSERT INTO chat_messages_new SELECT ..., '日常账本', 'legacy' FROM chat_messages
DROP TABLE chat_messages
ALTER TABLE chat_messages_new RENAME TO chat_messages
```

### MIGRATION_12_13
```sql
ALTER TABLE assets ADD COLUMN annualInterestRate REAL NOT NULL DEFAULT 0.0
ALTER TABLE assets ADD COLUMN interestLastSettledAt INTEGER NOT NULL DEFAULT $now
```

### MIGRATION_13_14
创建 `investment_lots` 表，含外键 (assetId->assets, sourceBillId->bills) 和 3 个索引。

### MIGRATION_14_15
重建 `investment_lots` 表 (修复索引/外键定义)。

### MIGRATION_15_16
```sql
ALTER TABLE bills ADD COLUMN excludeFromStats INTEGER NOT NULL DEFAULT 0
```

### MIGRATION_16_17
创建 `deleted_bills` 表 (Bill 的快照副本)。

### MIGRATION_17_18
数据迁移：将 subType=3/4 的旧平账记录转换为普通账单 + excludeFromStats 标记。
```sql
-- 非换币平账: subType=3 -> subType=0, subType=4 -> subType=0 + excludeFromStats=1
-- 换币平账: 保留 subType, 设置 excludeFromStats=1
-- 同步处理 deleted_bills 表
```

### MIGRATION_18_19
```sql
ALTER TABLE assets ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0
```

### MIGRATION_19_20
```sql
UPDATE assets SET includeInNetAsset = 0 WHERE isArchived = 1
```

### MIGRATION_20_21
```sql
ALTER TABLE assets ADD COLUMN billBalanceFromTime INTEGER NOT NULL DEFAULT 0
ALTER TABLE assets ADD COLUMN showBillBalanceAfter INTEGER NOT NULL DEFAULT 0
ALTER TABLE bills ADD COLUMN accountBalanceAfter REAL
ALTER TABLE bills ADD COLUMN toAccountBalanceAfter REAL
```

### MIGRATION_21_22
```sql
UPDATE assets SET showBillBalanceAfter = 1 WHERE showBillBalanceAfter = 0
UPDATE assets SET billBalanceFromTime = createTime WHERE billBalanceFromTime = 0
```

### MIGRATION_22_23
修复 billBalanceFromTime：当 createTime 晚于最早账单时间时，回退到最早账单时间。

### MIGRATION_23_24
```sql
ALTER TABLE assets ADD COLUMN includeInNetBeforeArchive INTEGER NOT NULL DEFAULT 1
UPDATE assets SET includeInNetBeforeArchive = includeInNetAsset WHERE isArchived = 0
UPDATE assets SET includeInNetBeforeArchive = 1 WHERE isArchived = 1
```

### MIGRATION_24_25
```sql
CREATE INDEX IF NOT EXISTS index_bills_bookName_time ON bills (bookName, time)
```

### MIGRATION_25_26
为投资类资产创建初始 InvestmentLot 记录 (从资产余额回溯)，并修复 interestLastSettledAt。

---

## 五、MigrationManager (应用层数据迁移)

文件: `data/local/MigrationManager.kt`

### 5.1 旧版 SharedPreferences -> Room 迁移
- **触发条件:** `flip_prefs` 中 `has_migrated_to_room = false`
- **逻辑:**
  1. 从 `Prefs.getAssets()` 读取旧资产 -> 插入 Room `assets` 表
  2. 从 `Prefs.getCategories()` 读取旧分类 -> 插入 Room `categories` 表 (递归处理父子关系)
  3. 从 `Prefs.getBills()` 读取旧账单 -> 规范化 type/subType -> 插入 Room `bills` 表
  4. 标记 `has_migrated_to_room = true`

### 5.2 分类名规范化
- **触发条件:** `has_normalized_category_name_storage_v2 = false`
- **逻辑:** 遍历所有账单，调用 `CategoryNameNormalizer.normalizeForStorage()` 规范化 categoryName

### 5.3 余额快照回填
- **触发条件:** `balance_snapshot_backfilled_v1 = false`
- **逻辑:** 跳过 (显示层使用倒推余额, 不需要快照列)

---

## 六、Repository 层

### 6.1 AssetRepository

| 方法 | 逻辑 |
|---|---|
| `allAssets` | `assetDao.getAllAssets()` (Flow) |
| `getAllAssetsList()` | 直接委托 DAO |
| `addAsset(asset)` | `assetDao.insertAsset(asset)` |
| `updateAsset(asset)` | `assetDao.updateAsset(asset)` |
| `getAssetById(id)` | 直接委托 DAO |
| `updateBalance(id, amount)` | 直接委托 DAO |
| `deleteAssetWithCleanup(asset)` | **事务内:** 1) 回填账单资产关联 (backfillAssetLinksByName) 2) 解除账单关联 (clearAccountId/clearToAccountId) 3) 标记已删除账户名 (加"已删除"后缀) 4) 删除资产。**不删除任何历史账单** |

### 6.2 BillRepository

| 方法 | 逻辑 |
|---|---|
| `addBill(bill)` | 规范化 categoryName 后 insertBill |
| `addBills(bills)` | 批量规范化后 insertBills |
| `getBillsBetweenTimes(startTime, endTime)` | Flow 委托 |
| `getBillsByAssetId(assetId)` | Flow 委托 |
| `getUnsyncedBills()` | 委托 |
| `markAsSynced(billIds)` | 委托 |
| `getBillById(id)` | 委托 |
| `updateBill(bill)` | 规范化后 updateBill |

### 6.3 CategoryRepository

| 方法 | 逻辑 |
|---|---|
| `expenseCategories` / `incomeCategories` | Flow 按 type 查询 |
| `getCategoriesListByType(type)` | 委托 |
| `buildCategoryTree(flatList)` | 扁平列表 -> 父子嵌套 CategoryNode 树 (子分类 iconId 为空时继承父分类) |
| `getCategoryTree(type)` | 同步读取 + buildCategoryTree |
| `findCategoryByDisplayName(type, displayName)` | 解析 "父 - 子" 格式，查找精确分类 |
| `addCategory(category)` | 自动分配 sortOrder (max+10) |
| `getCategoryByName(name)` | 委托 |
| `updateCategory(category)` | 更新分类 + 同步关联账单的 categoryName |
| `saveOrderedCategories(categories)` | 批量更新 sortOrder (10, 20, 30...) |
| `saveOrderedCategoryTree(categories)` | 批量更新分类树排序 (父子分别递增) |
| `deleteById(id)` | 删除分类及其所有子分类 |
| `countBillsUnderCategory(categoryId)` | 统计分类及子分类下的账单数量 (按 id 去重) |
| `deleteCategoryAndMigrateBills(categoryId, targetCategoryId)` | 删除分类并将账单迁移到目标分类 (支持 categoryId 和 categoryName 双路径) |
| `deleteCategoryAndBills(categoryId, db)` | 删除分类及其下所有账单 (含余额回退) |
| `promoteToChild(categoryId)` | 子分类提升为一级 (parentId=null) |
| `demoteToChild(categoryId, newParentId)` | 一级分类降为子分类 |
| `getChildren(parentId)` | 获取子分类列表 |

### 6.4 BackupRepository

| 方法 | 逻辑 |
|---|---|
| `getFullData()` | 返回 Map: assets, bills, deleted_bills, investment_lots, categories, rules, chat_messages |
| `restoreFullData(...)` | **全量恢复 (事务内):** 1) 删除全部旧数据 2) 先插入分类 (父子顺序) 3) 插入资产 4) 插入账单 (重映射 categoryId/accountId/toAccountId/relatedBillId) 5) 插入已删除账单 6) 插入投资批次 7) 插入规则 8) 插入聊天记录 (重映射账单引用) |
| `mergeRestoreFullData(...)` | **合并恢复 (事务内):** 1) 分类/资产按名称去重 2) 账单按 时间+金额+类型+账户名 去重 3) 投资批次按 sourceBillId 去重 4) 规则/聊天记录追加。返回 `MergeRestoreResult` 统计 |

---

## 七、备份系统 (data/backup/)

### 7.1 BackupManager

- **备份格式:** ZIP 压缩包 (.bak)
- **压缩级别:** `Deflater.BEST_COMPRESSION`
- **ZIP 结构:**
  - `{tableName}.json` -- 每个数据表一个 JSON 文件
  - `banners/{filename}` -- 账本封面图片
  - `chat_media/{relativePath}` -- 聊天媒体文件 (背景、语音、头像)
- **安全措施:** `safeZipOutputFile()` 防止路径遍历攻击 (拒绝绝对路径、反斜杠、跳出目标目录)
- **方法:** `backup()`, `restore()`, `restoreBanners()`, `restoreChatMedia()`, `hasBanners()`, `hasChatMedia()`

### 7.2 DataExportManager

- 基于 Gson 的序列化/反序列化
- `serialize(data)` -> JSON String
- `deserialize{Entity}(json)` -> List\<Entity\>
- 支持: Asset, Bill, DeletedBill, InvestmentLot, Category, AiRule, ChatMessage

### 7.3 BackupPinCrypto

- **用途:** 备份中 AI 凭据的 PIN 保护
- **PIN:** 4 位数字
- **加密方案:** PBKDF2WithHmacSHA256 (60000 次迭代) + AES-256-GCM
- **字段映射:**
  - `ai_api_key` -> `ai_api_key_enc_v1`
  - `ai_provider_keys_v1` -> `ai_provider_keys_enc_v1`
- **加密 JSON 结构:** `{v, kdf, iter, salt, iv, ct}` (均 Base64 编码)

### 7.4 WebDavClient

- **协议:** WebDAV (基于 OkHttp)
- **超时:** 连接 20s, 读写 60s
- **认证:** HTTP Basic Auth
- **方法:**
  - `testConnection(config)` -- PROPFIND Depth:0 测试连接
  - `uploadBackup(config, fileName, bytes)` -- 自动创建目录 + PUT 上传
  - `findLatestBackup(config)` -- 列出目录 + 按时间戳排序取最新
  - `downloadBackup(config, entry)` -- GET 下载
  - `cleanupBackups(config, keepLite=10, keepFull=3)` -- 保留策略: lite 最多 10 个, full 最多 3 个
- **目录结构:** `{remoteDir}/{deviceName}/`
- **文件名格式:** `backup_{deviceName}_{mode}_{yyyyMMdd_HHmmss}.bak`

### 7.5 CsvManager

- **导出:** 带 BOM 的 UTF-8 CSV，16 列 header
- **导入:** 支持两种格式:
  1. **FlipAccounting 原生格式** -- 按 header 列名解析
  2. **钱迹 (QianJi) 格式** -- 自动检测中文 header (时间/类型/金额/账户1)
- **钱迹类型映射:** 支出->EXPENSE, 收入->INCOME, 转账->TRANSFER, 退款->INCOME+REFUND, 还款->TRANSFER+REPAYMENT, 不计收支->EXPENSE+excludeFromStats, 报销->INCOME
- **编码检测:** UTF-8 -> GB18030 -> 系统默认
- **退款识别:** 备注含 "退款"/"[退款]"/"【退款】" 自动标记为 SUBTYPE_REFUND

### 7.6 AutoBackupWorker

- **类型:** `CoroutineWorker` (WorkManager)
- **调度:** `PeriodicWorkRequest`, 间隔 1~72 小时 (默认 12h)
- **约束:** `RequiresBatteryNotLow`
- **退避:** 指数退避, 30 分钟
- **备份模式:**
  - `lite` -- 跳过 chatMedia 和 AI 核心设置
  - `full` -- 包含 chatMedia
- **流程:**
  1. 读取 BackupRepository 全量数据
  2. 按选项过滤数据表和设置模块
  3. 本地备份到 SAF 目录 (TapAccount_Backup_Latest.bak)
  4. 若启用云端 -> WebDAV 上传 + 清理旧备份
  5. 记录结果到 `tap_backup_prefs`

---

## 八、SharedPreferences 键值完整清单

### 8.1 flip_prefs (主偏好)

#### 数据 (PrefsDataSupport)
| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `assets_v1` | String (JSON) | "" | 旧版资产列表 (迁移用) |
| `cat_expense_v1` | String (JSON) | "" | 旧版支出分类树 (迁移用) |
| `cat_income_v1` | String (JSON) | "" | 旧版收入分类树 (迁移用) |
| `bills_list` | String (JSON) | null | 旧版账单列表 (迁移用) |

#### 通用设置 (PrefsGeneralSupport)
| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `vibrate_feedback` | Boolean | true | 触觉反馈开关 |
| `save_vibrate_feedback` | Boolean | true | 保存时振动反馈 |
| `flip_disable_landscape` | Boolean | false | 禁止横屏 |
| `hide_recents_card` | Boolean | false | 隐藏最近账单卡片 |
| `advanced_shizuku_persistence` | Boolean | false | Shizuku 持久化 |
| `advanced_shizuku_mode` | Boolean | false | Shizuku 模式 |
| `logging_enabled` | Boolean | false | 日志开关 |
| `privacy_debug_until_ms_v1` | Long | 0L | 隐私调试日志截止时间 (自动过期) |
| `developer_full_logging_v1` | Boolean | false | 开发者完整日志 |
| `app_usage_mode` | Int | 0 | 应用使用模式 |
| `asr_engine_mode` | Int | 0 (API) | ASR 模式: 0=API, 1=Whisper |
| `asr_download_source_v1` | String | "github" | ASR 下载源 |
| `asset_feature_enabled_v1` | Boolean | true | 资产功能开关 |
| `app_white_list` | StringSet | emptySet | 应用白名单 (悬浮窗) |
| `active_currencies_v1` | StringSet | {"CNY"} | 活跃币种 |
| `exchange_refresh_interval_v1` | Long | 43200000 (12h) | 汇率刷新间隔 (毫秒) |
| `asset_amount_display_mode_v1` | String | "source:ALL;target:CNY" | 资产金额显示模式 |
| `quick_gesture_enabled` | Boolean | false | 快速手势总开关 |
| `flip_enabled` | Boolean | false | 翻转检测开关 |
| `flip_sensitivity_level` | Int | 50 | 翻转灵敏度 (0-100) |
| `flip_action` | String | "show_overlay" | 翻转动作 |
| `flip_guide_seen_v1` | Boolean | false | 翻转引导已看 |
| `double_tap_enabled` | Boolean | false | 双击检测开关 |
| `double_tap_guide_seen` | Boolean | false | 双击引导已看 |
| `quick_gesture_setup_guide_seen_v1` | Boolean | false | 手势设置引导已看 |
| `sensitivity_onboarding_seen_v2` | Boolean | false | 灵敏度引导已看 |
| `home_onboarding_seen_v1` | Boolean | false | 首页引导已看 |
| `settings_guide_dismissed_v1` | Boolean | false | 设置引导已关闭 |
| `gesture_permission_prompt_defer_until_ms_v1` | Long | 0L | 手势权限提示延迟至 |
| `tap_model` | String | "" | Tap 检测模型路径 |
| `tap_sensitivity_level` | Int | 5 | Tap 灵敏度等级 |
| `tap_nnapi_low_power` | Boolean | false | Tap NNAPI 低功耗 |
| `tap_low_power` | Boolean | false | Tap 强制全 ML (迁移后) |
| `tap_force_full_ml_migrated_v1` | Boolean | false | Tap 全 ML 迁移标记 |
| `tap_triple_enabled` | Boolean | false | 三击检测开关 |
| `tap_action_double` | String | "" | 双击动作 |
| `tap_action_triple` | String | "" | 三击动作 |
| `api_config_unlocked_v1` | Boolean | false | API 配置已解锁 |
| `ai_detail_config_unlocked_v1` | Boolean | false | AI 详情配置已解锁 |
| `shizuku_unlocked_v1` | Boolean | false | Shizuku 已解锁 |
| `aggressive_keep_alive` | Boolean | false | 激进保活 |
| `first_day_of_week` | Int | Calendar.MONDAY | 每周起始日 |

#### 显示设置 (PrefsDisplaySupport)
| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `show_ai_text` | Boolean | false | 显示 AI 文字记账入口 |
| `show_ai_voice` | Boolean | false | 显示 AI 语音记账入口 |
| `show_ai_image` | Boolean | false | 显示 AI 图片记账入口 |
| `show_screen_accounting` | Boolean | false | 显示截屏记账入口 |
| `show_multi_currency` | Boolean | false | 显示多币种 |
| `show_home_trend_card` | Boolean | true | 显示首页趋势卡片 |
| `multi_bill_enabled` | Boolean | false | 多账单模式 |
| `multi_bill_not_sync` | Boolean | false | 多账单不同步 |
| `show_book_entry` | Boolean | false | 显示账本入口 |
| `ocr_engine_mode` | Int | 1 (MULTIMODAL) | OCR 模式: 0=本地, 1=多模态 |
| `receipt_lang_mode` | Int | 0 (AUTO) | 小票语言模式: 0=自动, 1=中文, 2=外文 |
| `save_ocr_debug_before_ai` | Boolean | false | 保存 OCR 调试信息 |
| `amount_grouping_enabled` | Boolean | true | 金额千分位分组 |
| `bill_show_category_icon_v1` | Boolean | true | 账单显示分类图标 |
| `bill_show_full_category_v1` | Boolean | true | 账单显示完整分类路径 |
| `bill_remark_priority_v1` | Boolean | false | 账单备注优先显示 |
| `independent_detail_enabled_v1` | Boolean | false | 独立详情页 |

#### AI 设置 (PrefsAiSupport)
| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `ai_api_key` | String | "" | 主 API Key |
| `ai_provider_keys_v1` | String (JSON) | "" | 多提供商 API Keys 映射 |
| `ai_provider_keys_migrated_v1` | Boolean | false | 旧 Key 迁移标记 |
| `ai_model_id` | String | "Qwen/Qwen3-14B" | 主文本模型 (旧) |
| `ai_multi_model_id` | String | "" (回退 ai_model_id) | 多账单文本模型 |
| `ai_modify_model_id` | String | "" (回退 multi) | 修改账单模型 |
| `ai_category_refine_model_id` | String | "" (回退 multi) | 分类优化模型 |
| `ai_router_model_id` | String | "" (回退 multi) | 路由模型 (隐藏) |
| `ai_llm_router_enabled` | Boolean | false | LLM 路由开关 (隐藏) |
| `ai_query_model_id` | String | "" (回退 router) | 查询模型 (隐藏) |
| `ai_rule_model_id` | String | "" (回退 ai_model) | 规则模型 |
| `ai_receipt_model_id` | String | "" (回退 ai_model) | 小票文本模型 |
| `ai_receipt_vision_model_id` | String | "" (按供应商预设) | 小票视觉模型 |
| `ai_screen_model_id` | String | "" (回退 vision) | 截屏模型 |
| `ai_receipt_ocr_refine_model_id` | String | "" (回退 receipt) | OCR 优化模型 |
| `ai_speech_model_id` | String | "" (按供应商预设) | 语音模型 |
| `screen_vision_supported_models` | StringSet | emptySet | 已验证支持视觉的模型列表 |
| `receipt_ocr_refine_enabled` | Boolean | false | OCR 优化开关 |
| `receipt_image_draft_confirm_enabled` | Boolean | true | 图片记账草稿确认 |
| `image_accounting_natural_language` | Boolean | false | 图片记账自然语言模式 |
| `ai_api_url` | String | "" (按供应商预设) | API URL |
| `ai_provider` | String | "siliconflow" | AI 供应商 |
| `ai_models_cache` | StringSet | emptySet | 模型列表缓存 |
| `ai_manual_model_selection_v1` | Boolean | false | 手动选模型 |
| `ai_enable_thinking` | Boolean | false | AI 思考模式 |
| `ai_thinking_multi_bill` | Boolean | false | 多账单思考模式 |
| `ai_thinking_modify_bill` | Boolean | false | 修改账单思考模式 |
| `ai_thinking_vision` | Boolean | false | 视觉思考模式 |
| `ai_thinking_category_refine` | Boolean | false | 分类优化思考模式 |
| `ai_query_enabled` | Boolean | false | AI 查询开关 (隐藏) |
| `ai_rules_v1` | String (JSON) | null | AI 规则列表 |
| `ocr_debug_records_v1` | String (JSON) | null | OCR 调试记录 (最多 20 条, 每条最长 12000 字符) |
| `enable_ai_prompt_correction` | Boolean | true | AI 提示词纠正 |
| `enable_local_rule_override` | Boolean | true | 本地规则覆盖 |

#### 聊天设置 (PrefsChatSupport)
| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `show_ai_chat_entry` | Boolean | false | 显示 AI 聊天入口 |
| `ai_entry_mode` | Int | 0 (TRADITIONAL) | 记账入口模式: 0=传统, 1=聊天 |
| `ai_chat_name` | String | "小记" | AI 聊天名称 |
| `ai_chat_identity` | String | "" | AI 身份设定 |
| `user_chat_name` | String | "我" | 用户聊天名称 |
| `user_profile_desc` | String | "点击设置名字和头像" | 用户简介 |
| `ai_chat_avatar_path` | String | "" | AI 头像路径 |
| `user_chat_avatar_path` | String | "" | 用户头像路径 |
| `ai_chat_bg_path` | String | "" | 聊天背景路径 |
| `ai_chat_model` | String | "" (回退主模型) | 聊天模型 |
| `ai_chat_reply_style` | String | "cute" | 聊天回复风格 |
| `ai_chat_reply_style_custom` | String | "" | 自定义回复风格提示词 |
| `ai_chat_model_audio_support` | String (JSON) | "" | 模型音频支持映射 |
| `ai_chat_session_title_{bookName}_{convId}` | String | "" | 聊天会话标题 (动态键) |

#### 备份设置 (PrefsBackupSupport)
| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `has_migrated_to_room` | Boolean | false | Room 迁移完成标记 |
| `has_normalized_category_name_storage_v2` | Boolean | false | 分类名规范化完成标记 |
| `balance_snapshot_backfilled_v1` | Boolean | false | 余额快照回填完成标记 |
| `book_accounts_v1` | String (JSON) | - | 账本列表 |
| `collapsed_book_accounts_v1` | String (JSON) | - | 已折叠账本列表 |
| `selected_book_name_v1` | String | - | 当前选中账本 |
| `default_book_name_v1` | String | - | 默认账本 |
| `book_color_{bookName}` | Int | - | 账本颜色 (动态键) |
| `book_banner_{bookName}` | String | - | 账本封面路径 (动态键) |

---

### 8.2 tap_backup_prefs (自动备份)

| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `auto_backup_enabled` | Boolean | false | 自动备份开关 |
| `auto_backup_interval_hours` | Int | 12 | 备份间隔 (小时, 1-72) |
| `auto_backup_cloud_enabled` | Boolean | false | 云端备份开关 |
| `auto_backup_mode` | String | "lite" | 备份模式: lite/full |
| `last_auto_backup_time` | Long | 0 | 最后备份时间 |
| `last_auto_backup_result` | String | "" | 最后备份结果 |
| `backup_tree_uri_v1` | String | null | 本地备份 SAF 目录 URI |

### 8.3 tap_cloud_backup_prefs (WebDAV 配置)

| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `webdav_url` | String | "" | WebDAV 服务器地址 |
| `webdav_user` | String | "" | WebDAV 用户名 |
| `webdav_pass` | String | "" | WebDAV 密码 |
| `webdav_dir` | String | "TapAccount" | 远程目录 |
| `webdav_device_name` | String | Build.MODEL | 设备名称 |

### 8.4 flip_currency_prefs (汇率)

| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `currency_rates_json` | String | "" | 汇率 JSON |
| `currency_rates_update_time` | Long | 0L | 汇率更新时间 |
| `currency_refresh_interval_min` | Int | 60 | 刷新间隔 (分钟) |
| `cm_enabled_currencies_v1` | String | - | CurrencyManager 启用的币种 |

---

## 九、关键设计模式总结

1. **双写兼容:** 旧版 SharedPreferences 数据与 Room 并存，MigrationManager 负责一次性迁移
2. **软删除:** 删除资产时保留账单，仅解除关联并标记 "已删除"；删除账单时存入 deleted_bills 表
3. **外键 SET NULL:** 资产/分类删除时，关联账单的外键自动置 null
4. **分类名快照:** 账单同时存储 categoryId (外键) 和 categoryName (文本快照)，兼容旧数据
5. **账本隔离:** 通过 bookName 字段隔离不同账本的数据
6. **备份全量+增量:** 全量恢复覆盖所有数据，合并恢复按名称/时间去重
7. **加密保护:** AI 凭据在备份中通过 PIN + AES-GCM 加密

### 8.4 UI 层关键页面分析

# TapAccounting UI 深度文档

## 目录

1. [MainActivity.kt - 主页容器与底部导航](#1-mainactivitykt)
2. [ChatActivity.kt - AI 聊天记账界面](#2-chatactivitykt)
3. [ScreenCaptureActivity.kt - 截屏识别记账](#3-screencaptureactivitykt)
4. [OverlayService.kt - 悬浮窗前台服务](#4-overlayservicekt)
5. [EditBillActivity.kt - 账单编辑](#5-editbillactivitykt)
6. [AssetDetailActivity.kt - 资产管理](#6-assetdetailactivitykt)
7. [BackupActivity.kt - 备份恢复](#7-backupactivitykt)
8. [HomeFragment.kt - 首页账单列表](#8-homefragmentkt)

---

## 1. MainActivity.kt

**路径**: `app/src/main/java/com/taostudio/tapaccounting/MainActivity.kt`

### 1.1 类结构

#### SwipeFrameLayout（内部类/同文件）
自定义 `FrameLayout`，用于水平滑动切换 Tab 手势识别。

| 字段 | 类型 | 说明 |
|------|------|------|
| `onSwipeStart` | `(dir, rawX, rawY) -> Boolean` | 滑动意图确认回调，返回 true 才接管事件 |
| `onHorizontalDrag` | `(dx) -> Unit` | 拖动中回调 |
| `onHorizontalSettle` | `(dx, vx) -> Unit` | 松手后回调（距离 + 速度） |
| `vt` | `VelocityTracker?` | 速度追踪器 |
| `dragging` | `Boolean` | 是否已确认水平拖动 |
| `rejected` | `Boolean` | 已判定为非水平手势，不再接管 |
| `slop` | `Int` | 系统最小滑动距离阈值 |

#### MainActivity

| 字段 | 类型 | 说明 |
|------|------|------|
| `fabApp` | `FloatingActionButton?` | 添加账单 FAB |
| `bottomNavigationView` | `BottomNavigationView?` | 底部导航栏 |
| `swipeContainer` | `SwipeFrameLayout` | 支持水平滑动的 Fragment 容器 |
| `homeRecycledViewPool` | `RecycledViewPool` | 跨 Fragment 重建共享的 ViewHolder 缓存池 |
| `tabIds` | `List<Int>` | 4 个 Tab 的菜单 ID 列表 |
| `tabFragments` | `Array<Fragment?>` | 4 个 Tab 的 Fragment 实例（一次创建，永不销毁） |
| `currentTabIndex` | `Int` | 当前显示的 Tab 索引 |
| `settleAnimator` | `ValueAnimator?` | 当前运行中的回弹/切换动画 |
| `peekFragment` | `Fragment?` | 滑动预加载的下一个 Fragment |
| `swipeDir` | `Int` | 滑动方向：+1 左滑（下一页）/ -1 右滑（上一页） |
| `isSwitching` | `Boolean` | 防止快速连续切换的锁 |

### 1.2 生命周期方法

| 方法 | 说明 |
|------|------|
| `onCreate` | 初始化视图、一次性 add 全部 4 个 Fragment（hide 非当前）、设置 BottomNav 点击监听、设置滑动手势三阶段回调、设置 FAB 点击（根据 AI 入口模式跳转 ChatActivity 或弹出 BottomSheet）、预加载 HomeViewModel 数据、启动 OverlayService（如已开启手势）、显示首次引导 Snackbar |
| `onSaveInstanceState` | 保存 `currentTabIndex` |
| `onResume` | 刷新隐藏最近任务偏好、刷新底部导航 Tab 可见性 |
| `onNewIntent` | 处理外部传入的 `EXTRA_OPEN_TAB_INDEX`，切换到指定 Tab |
| `onDestroy` | 取消 settleAnimator |

### 1.3 核心 UI 逻辑

#### Tab 切换动画系统
- **滑动切换**（`commitSwipe`）：使用 `ValueAnimator` 同步驱动当前页与目标页的 `translationX`、`alpha`、`scaleX/Y`，动画结束后通过 `hide/show` 完成 Fragment 切换，零重建。
- **点击切换**（`switchTab`）：使用 `View.animate()` 分别驱动当前页淡出缩小和目标页淡入放大，支持 `fromSwipe` 参数区分动画风格。
- **回弹**（`snapBack`）：未达到切换阈值时，将当前页恢复到 offset=0。

#### FAB 可见性
- 仅在首页（index=0）且 `HomeFragment.shouldShowMainFab()` 返回 true 时显示。
- `showAddBillBottomSheet()` 委托给 `AddBillEntrySheetLauncher`。

#### 权限与手势冲突处理
- `onSwipeStart` 中检查：Stats 页 PieChart 区域、Home 书抽屉、Assets 资产抽屉均不拦截。
- Home 页左滑且触摸在左 1/3 区域时，打开书抽屉而非切换 Tab。

### 1.4 数据加载模式

- **HomeViewModel 预加载**：在 `onCreate` 中直接通过 `ViewModelProvider` 获取 HomeViewModel 并调用 `syncAndLoad`，比 Fragment.onViewCreated 早很多。
- **RecycledViewPool 共享**：`TYPE_HEADER=0` 缓存 80 个，`TYPE_ITEM=1` 缓存 260 个，跨 Fragment 重建复用。

---

## 2. ChatActivity.kt

**路径**: `app/src/main/java/com/taostudio/tapaccounting/ChatActivity.kt`

### 2.1 类结构

#### 消息类型常量

| 常量 | 值 | 说明 |
|------|----|------|
| `MSG_TYPE_USER_TEXT` | 0 | 用户文本消息 |
| `MSG_TYPE_USER_IMAGE` | 1 | 用户图片消息 |
| `MSG_TYPE_USER_VOICE` | 2 | 用户语音消息 |
| `MSG_TYPE_AI_TEXT` | 3 | AI 文本回复 |
| `MSG_TYPE_AI_BILL` | 4 | AI 账单回复 |

#### 核心字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `rvMessages` | `RecyclerView` | 消息列表 |
| `etInput` | `EditText` | 输入框 |
| `displayMessages` | `MutableList<ChatDisplayItem>` | 当前显示的消息列表 |
| `allSessionRows` | `MutableList<ChatSessionRow>` | 所有会话列表 |
| `pendingImages` | `MutableList<PendingImage>` | 待发送图片列表 |
| `currentBookName` | `String` | 当前账本名 |
| `currentConversationId` | `String` | 当前会话 ID |
| `isVoiceMode` | `Boolean` | 是否处于语音模式 |
| `isRecording` | `Boolean` | 是否正在录音 |
| `inlineAmountEditingBillId` | `Long?` | 当前内联编辑金额的账单 ID |

#### 子控制器（lazy 初始化）

| 控制器 | 职责 |
|--------|------|
| `messagePipeline` | 消息发送与 AI 记账管线 |
| `billCorrectionService` | 账单纠错服务 |
| `voiceController` | 语音消息播放/选择/删除 |
| `adapter` | `ChatAdapter` 消息列表适配器 |
| `sessionAdapter` | 会话列表适配器 |
| `searchResultAdapter` | 搜索结果适配器 |
| `sessionController` | 会话管理（新建/切换/删除/重命名） |
| `mediaController` | 媒体处理（图片选择/裁剪/AI头像/背景） |
| `panelController` | 面板控制（回复风格/模型切换） |
| `messageMenuController` | 消息长按菜单 |
| `uiHelperController` | UI 工具方法（键盘/Dialog/时间格式化） |
| `audioRecordController` | 录音控制 |
| `historyController` | 历史消息加载 |
| `messagePersistenceController` | 消息持久化 |
| `voiceInputController` | 语音输入 UI 控制 |

### 2.2 生命周期方法

| 方法 | 说明 |
|------|------|
| `onCreate` | 绑定视图、设置 Toolbar、RecyclerView、会话抽屉、输入框、键盘监听、语音 UI、刷新 AI 头像/背景、启动会话状态引导（`bootstrapConversationState`）并加载历史消息 |
| `onResume` | 更新会话副标题（模型名）、探测模型音频支持 |
| `onDestroy` | 取消 AI 请求、取消 aiScopeJob、清理长按 Runnable、停止语音播放、停止录音 |
| `onBackPressed` | 优先退出语音多选模式、关闭会话抽屉、最后默认返回 |
| `dispatchTouchEvent` | ACTION_DOWN 时检查是否需要清除内联金额编辑焦点 |

### 2.3 关键 UI 逻辑

#### 消息发送流程
1. `sendText()` -> 检查文本和待发图片
2. 纯文本：`messagePipeline.sendText()`
3. 含图片：`dispatchToAccounting(text, images)` -> 编码多图 payload -> 追加用户消息气泡 -> `messagePipeline.callAiAccounting(payload)`

#### AI 记账管线
- `callAiAccounting` 委托给 `messagePipeline`，支持文本/图片/语音输入
- `processBillResult` 委托给 `billCorrectionService`，处理 AI 返回的账单 JSON
- `confirmVisualAccountingDraftInChat` 使用 `suspendCancellableCoroutine` 实现协程式对话框确认

#### 会话管理
- 右侧抽屉（`DrawerLayout`）展示会话列表
- 支持搜索、新建、重命名、删除会话
- 会话 ID 格式：`conv_{timestamp}_{uuid8}`

#### 语音功能
- 长按录音、上滑取消
- 录音完成后转文字（本地 Whisper 或云端 ASR）
- 语音消息支持多选删除、转写显示

#### 图片功能
- 支持多图选择（最多限制）
- 预览区显示待发图片缩略图
- 图片压缩（超过 4MB 时降采样 + JPEG 82%）

### 2.4 数据类

```kotlin
data class ChatDisplayItem(
    dbId: Long, uiKey: String, msgType: Int, content: String,
    imageUri: String, voice: VoicePayload?, bills: MutableList<Bill>,
    timestamp: Long, isLoading: Boolean, isDeprecated: Boolean,
    deprecatedBillIds: MutableSet<Long>, editedBillIds: MutableSet<Long>,
    billHint: String, billInteractionMode: Int, billInteractionToken: String
)

data class VoicePayload(audioPath: String, durationSec: Int, transcript: String)

data class ChatSessionRow(
    bookName: String, conversationId: String, title: String,
    preview: String, displayTime: String, timestamp: Long, isCurrent: Boolean
)
```

---

## 3. ScreenCaptureActivity.kt

**路径**: `app/src/main/java/com/taostudio/tapaccounting/ScreenCaptureActivity.kt`

### 3.1 类结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `mediaProjectionManager` | `MediaProjectionManager` | 系统截屏服务 |
| `tvStatus` | `TextView` | 状态文字 |
| `captureCard` | `View` | 扫描动画卡片 |
| `scanLine` | `View` | 扫描线动画 |
| `mediaProjection` | `MediaProjection?` | 截屏投影实例 |
| `imageReader` | `ImageReader?` | 图像读取器 |
| `virtualDisplay` | `VirtualDisplay?` | 虚拟显示 |
| `isMultiMode` | `Boolean` | 是否多账单模式 |
| `finished` | `Boolean` | 防止重复完成 |

#### 静态回调（companion object）

| 回调 | 说明 |
|------|------|
| `onRecognitionResult` | 识别成功回调，传递 `JSONObject` |
| `onRecognitionError` | 识别失败回调 |
| `onRecognitionCancelled` | 用户取消回调 |
| `cachedProjectionResultCode/Data` | 缓存的截屏权限，避免重复申请 |

### 3.2 截屏流程

```
onCreate
  ├─ Shizuku 可用? → startShizukuCapture() [静默截屏]
  ├─ 有缓存权限? → startMediaProjectionCapture() [复用权限]
  └─ 否则 → capturePermissionLauncher [申请权限]
       └─ 权限授予 → startMediaProjectionCapture()
            ├─ 创建 ImageReader + VirtualDisplay
            └─ tryAcquireImage() [重试最多 8 次，间隔 120ms]
                 └─ imageToBitmap() → bitmapToBase64() → submitBitmapForRecognition()
                      └─ AIService.analyzeScreenAccountingByImage()
                           └─ onRecognitionResult?.invoke(result)
                                └─ finishSafely()
```

### 3.3 动画

- **卡片入场**：`AnimatorSet` 组合 scaleX/Y 0.85->1.0 + alpha 0->1，220ms，DecelerateInterpolator
- **扫描线**：`ValueAnimator` 循环 translationY 从 -屏幕高 到 +屏幕高，2200ms 周期

### 3.4 资源管理

- `finishSafely()` 确保只执行一次：停止动画、移除 Handler 回调、取消分析任务、释放截屏资源、清空静态回调
- `releaseCaptureResources()` 依次释放 virtualDisplay、imageReader、mediaProjection

---

## 4. OverlayService.kt

**路径**: `app/src/main/java/com/taostudio/tapaccounting/OverlayService.kt`

### 4.1 类结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `overlayManager` | `OverlayManager` | 悬浮窗管理器 |
| `flipDetector` | `FlipDetector?` | 翻转检测器 |
| `tapDetector` | `TapDetector?` | 敲击检测器 |
| `isFlipEnabled` | `Boolean` | 翻转手势是否启用 |
| `isDoubleTapEnabled` | `Boolean` | 双击手势是否启用 |
| `keepAliveManager` | `KeepAliveManager` | 保活与健康检测管理器 |
| `lastTapFeedbackAtMs` | `Long` | 上次触觉反馈时间戳（节流） |

#### Action 常量

| Action | 说明 |
|--------|------|
| `ACTION_SHOW_OVERLAY` | 显示悬浮窗 |
| `ACTION_HIDE_OVERLAY` | 隐藏悬浮窗 |
| `ACTION_SHOW_AI_INPUT` | 显示 AI 输入面板 |
| `ACTION_SCREEN_CAPTURE` | 触发截屏 |
| `ACTION_START_FLIP / STOP_FLIP` | 启停翻转检测 |
| `ACTION_START_DOUBLE_TAP / STOP_DOUBLE_TAP` | 启停双击检测 |
| `ACTION_RESTART_DOUBLE_TAP` | 重启双击检测（设置变更后） |

### 4.2 KeepAliveManager（内部类）

负责服务保活与传感器健康检测：

| 功能 | 说明 |
|------|------|
| **屏幕状态监听** | 注册 `SCREEN_OFF/ON/USER_PRESENT` 广播接收器 |
| **WakeLock** | 短暂持有 `PARTIAL_WAKE_LOCK`（默认 4 秒） |
| **Watchdog** | 每 180 秒检查传感器健康，连续 2 次无事件则重启检测器 |
| **冷却机制** | 连续重启超过 3 次进入 5 分钟冷却期 |
| **延迟启动** | 解锁后延迟 1s/5s/15s 三次 reconcile 检测器状态 |
| **方向变化** | 横屏时暂停检测（如果用户设置了禁用横屏） |

### 4.3 Service 生命周期

| 方法 | 说明 |
|------|------|
| `onCreate` | 初始化 overlayManager、读取手势开关、提升前台服务、attach 保活管理器、启动翻转/双击检测、调度保活 Worker |
| `onStartCommand` | 根据 action 分发：启停翻转/双击检测、显示/隐藏悬浮窗、截屏、重启检测器。返回 `START_STICKY` |
| `onDestroy` | detach 保活管理器、停止所有检测、移除悬浮窗 |
| `onConfigurationChanged` | 方向变化时重新评估检测器状态 |

### 4.4 手势处理

- **敲击处理** (`handleTapAction`)：根据敲击次数（2/3）查找配置的 action，通过 `TapActionRegistry.findById` 执行
- **翻转处理** (`handleFlipAction`)：查找翻转 action 并执行
- **触觉反馈** (`triggerTapFeedback`)：节流 650ms，振动 45ms，振幅 210
- **横屏检测**：如果 `Prefs.isDisableLandscape` 为 true，横屏时忽略手势

### 4.5 前台服务类型

- 默认：`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`（Android 14+）
- 录音模式：切换为 `FOREGROUND_SERVICE_TYPE_MICROPHONE | SPECIAL_USE`

---

## 5. EditBillActivity.kt

**路径**: `app/src/main/java/com/taostudio/tapaccounting/ui/activity/EditBillActivity.kt`

### 5.1 类结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `billId` | `Long` | 要编辑的账单 ID（-1 表示新建） |
| `isCopy` | `Boolean` | 是否复制模式（时间设为当前） |
| `formController` | `AccountingFormController?` | 记账表单控制器 |
| `bottomSheet` | `BottomSheetDialog?` | 底部弹窗 |

### 5.2 生命周期

| 方法 | 说明 |
|------|------|
| `onCreate` | 创建空 FrameLayout 作为容器，获取 `billId` 和 `isCopy` 参数，调用 `showBottomSheet()` |

### 5.3 UI 逻辑

- **表单加载**：`showBottomSheet()` 创建 `BottomSheetDialog`，inflate `R.layout.layout_floating_window`，实例化 `AccountingFormController`
- **数据填充**：`loadBillData()` 在 IO 线程从 `BillRepository` 查询账单，构建 JSON（amount/type/category_name/asset_name/remark/currency/exchange_rate/fee/subType/bookName/to_asset_name/time/recordTime），主线程调用 `formController.fillDataToUi(json)`
- **复制模式**：时间设为当前时间，不传 `recordTime`
- **返回键处理**：委托给 `formController.handleBackPressed()`
- **关闭回调**：`onCloseRequest(isSaved)` 中如果已保存则 `setResult(RESULT_OK)`，然后 dismiss

---

## 6. AssetDetailActivity.kt

**路径**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/assets/AssetDetailActivity.kt`

#### 类结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `assetId` | `Long` | 资产 ID |
| `currentAsset` | `Asset?` | 当前资产对象 |
| `allAssetBills` | `List<Bill>` | 该资产所有账单 |
| `balanceAfterByBillId` | `Map<Long, Double>` | 每笔账单后的余额映射 |
| `searchQuery` | `String` | 搜索关键词 |
| `adapter` | `TransactionAdapter` | 交易列表适配器 |
| `billDetailSheetController` | `AssetBillDetailSheetController` | 账单详情弹窗控制器 |

#### 静态缓存

```kotlin
companion object {
    private data class AssetDetailCache(asset: Asset?, bills: List<Bill>, updatedAtMs: Long)
    private val detailCacheByAssetId = mutableMapOf<Long, AssetDetailCache>()
}
```

跨 Activity 实例缓存资产详情，避免重复查询。

#### 数据加载

- **资产观察**：`db.assetDao().observeAssetById(assetId)` 返回 `Flow<Asset>`，`collectLatest` 更新 UI
- **账单观察**：`db.billDao().getBillsByAssetIdOrName(assetId, assetName)` 返回 `Flow<List<Bill>>`
- **余额计算**：`AssetBillBalanceHistory.computeBalanceAfterByBillId()` 从当前余额反推每笔账单后的余额

#### TransactionAdapter（内部类）

- 两种 ViewType：`MonthHeaderRow`（月份头）和 `BillRow`（账单项）
- 月份头显示当月流入/流出汇总
- 支持 Payload 局部更新：`PAYLOAD_MODE_CHANGE`、`PAYLOAD_SELECTION_CHANGE`、`PAYLOAD_BALANCE_DISPLAY_CHANGE`
- 多选模式：长按进入，支持全选/批量删除/批量移动到其他资产
- 分组背景：根据相邻 item 类型动态设置圆角背景

#### 多选操作

- **删除**：`BillDeleteHelper.deleteBillsAndRevertBalance(db, targets)`
- **移动**：`OverlayDialogs.showGridAssetPicker` 选择目标资产，`moveBillToTargetAsset()` 更新账单关联

#### UI 功能

- **搜索**：支持按金额/分类/备注/账户/账本/币种模糊搜索
- **余额显示设置**：`showBillBalanceDisplaySheet()` 弹出 BottomSheet，控制是否显示每笔账单后余额及起始日期
- **收纳资产**：`toggleArchiveCurrentAsset()` 切换资产收纳状态
- **FAB 滚动隐藏**：向下滚动 >20px 隐藏，向上滚动 <8px 显示
- **Toolbar 双击回顶**：`GestureDetector` 检测双击

---

## 7. BackupActivity.kt

**路径**: `app/src/main/java/com/taostudio/tapaccounting/BackupActivity.kt`

### 7.1 类结构

#### 枚举

```kotlin
private enum class BackupPreset { LITE, FULL, CUSTOM }
private enum class BackupPinMode { AUTO, FORCE, PLAIN }
```

#### ActivityResult Launchers

| Launcher | 说明 |
|----------|------|
| `pickBackupFolderLauncher` | 选择备份目录（持久化 URI 权限） |
| `saveBackupAsLauncher` | 另存为备份文件 |
| `openDocumentLauncher` | 选择备份文件恢复 |
| `saveCsvLauncher` | 导出 CSV |
| `openCsvLauncher` | 导入 CSV |

### 7.2 生命周期

| 方法 | 说明 |
|------|------|
| `onCreate` | 设置备份预设 UI、PIN 模式 UI、自动备份 UI、云备份设置 UI、清理按钮、处理 `EXTRA_OPEN_SECTION` Intent |

### 7.3 备份功能

#### 备份预设
- **LITE**：全选除聊天媒体外的所有模块
- **FULL**：全选所有模块
- **CUSTOM**：显示自定义勾选面板

#### PIN 加密
- **AUTO**：有 API Key 时自动提示设置 PIN
- **FORCE**：强制要求 PIN
- **PLAIN**：不加密

#### 备份流程
1. `collectBackupOptions()` 收集所有 CheckBox 状态
2. `resolvePinForBackup()` 根据 PIN 模式决定是否加密
3. `buildBackupArchiveFile()` 在 IO 线程构建备份文件
4. 通过 `BackupManager.backup()` 写入 .bak 文件

#### 恢复流程
1. `showRestoreDialog()` 读取备份文件，解析可用模块
2. 弹出模块选择对话框（含合并/覆盖模式选择）
3. `restoreData()` 或 `mergeRestoreData()` 在 IO 线程恢复
4. 恢复后修复 Banner 路径、聊天偏好路径、语音消息路径

#### CSV 导入
- 选择目标账本 -> 解析 CSV -> 确认 -> `importCsvBills()` 批量插入
- 自动创建缺失的资产账户（标记为"CSV导入待确认"）
- 修复资产关联：`backfillAssetLinksByName()`

#### 云备份（WebDAV）
- 配置：URL/用户名/密码/目录/设备名
- 上传：构建备份 -> `WebDavClient.uploadBackup()` -> 清理旧备份
- 下载：`WebDavClient.findLatestBackup()` -> `downloadBackup()` -> 弹出恢复对话框

#### 数据清理
- 按日期范围清理：`deleteBillsBetweenTimes()`
- 按账本清理：`deleteAllByBookName()`
- 清空全部：`deleteAll()`

---

## 8. HomeFragment.kt

**路径**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeFragment.kt`

### 8.1 类结构

#### 核心字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `barChart` | `BarChart` | 趋势柱状图 |
| `cvChartContainer` | `View` | 图表卡片（动态 inflate） |
| `rvTransactions` | `RecyclerView` | 账单列表 |
| `homeAdapter` | `HomeAdapter` | 账单列表适配器 |
| `homeViewModel` | `HomeViewModel` | Activity 作用域 ViewModel（`activityViewModels`） |
| `selectedBookName` | `String` | 当前账本名 |
| `selectedYear/selectedMonth` | `Int` | 当前选中的年/月 |
| `currentTimeRange` | `Int` | 图表时间范围（0=7天, 1=15天, 2=本周） |
| `currentType` | `Int` | 图表类型（0=支出, 1=收入, 2=全部） |
| `isChartHidden` | `Boolean` | 趋势卡片是否隐藏 |
| `isMultiSelectModeActive` | `Boolean` | 是否多选模式 |

#### 子控制器（lazy/lateinit）

| 控制器 | 职责 |
|--------|------|
| `bookDrawerController` | 书抽屉管理（账本列表/新建/切换） |
| `bannerController` | 顶部封面图管理（长按菜单/选图/裁剪） |
| `multiSelectController` | 多选操作（全选/删除/移动到账本） |
| `chartController` | 趋势图控制（渲染/设置/数据更新） |
| `uiListController` | 列表 UI 控制（RecyclerView 设置/FAB/状态栏） |
| `refreshController` | 下拉刷新控制 |
| `dataController` | 数据控制（账户币种缓存） |
| `billSheetsController` | 账单详情/退款弹窗 |

### 8.2 生命周期

| 方法 | 说明 |
|------|------|
| `onCreateView` | inflate `R.layout.fragment_home` |
| `onViewCreated` | 初始化所有视图和控制器、设置 AppBarLayout 折叠监听（月份摘要渐隐 + Banner 白底渐变 + 状态栏同步）、设置 RecyclerView/图表/多选/书抽屉/封面长按、收集 `homeViewModel.uiState` StateFlow、观察账单表变更、刷新账本列表 |
| `onResume` | 应用首页状态栏样式、同步日期、刷新封面、刷新账本数据（跳过 onViewCreated 后的首次） |
| `onPause` | 清除多选模式 |
| `onHiddenChanged` | Tab 切换时：隐藏时恢复默认状态栏、显示时同步日期/账本/状态栏 |
| `onDestroyView` | 清理刷新控制器 |

### 8.3 数据加载模式

#### StateFlow 驱动
```kotlin
viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
    homeViewModel.uiState.collect { state ->
        // state.monthlyBills - 当月账单
        // state.filteredByChart - 图表过滤后的账单
        // state.isLoading - 加载状态
        // state.selectedBookName/Year/Month - 当前参数
        homeAdapter.submitList(state.monthlyBills)
        updateChart(filteredForChart)
    }
}
```

- `homeViewModel.syncAndLoad()` 参数变化时触发重新查询
- StateFlow 缓存上次数据，Fragment 重建后立即渲染

#### Room InvalidationTracker
- `observeBillTableChanges()` 监听账单表变更，触发 `homeViewModel.reload()`

### 8.4 UI 功能

#### 顶部封面区
- 支持长按更换封面图（UCrop 裁剪 16:9）
- AppBarLayout 折叠联动：月份摘要渐隐、Banner 白底渐变
- 状态栏颜色随折叠进度动态切换（深色/浅色）

#### 月份选择器
- 支持月模式、年模式、全部模式
- 通过 `YearMonthPickerDialog` 选择
- 与 Stats 页通过 `SharedYearMonthSession` 双向同步

#### 书抽屉（DrawerLayout）
- 左侧抽屉展示账本列表
- 支持新建账本、切换默认账本
- 切换账本时：关闭抽屉 -> 动画淡入新数据

#### 趋势图
- `BarChart`（MPAndroidChart）显示每日收支趋势
- 支持设置：时间范围（7天/15天/本周）、类型（支出/收入/全部）、隐藏/显示
- `RoundedBarChartRenderer` 自定义圆角柱状图

#### 账单列表
- `HomeAdapter` 使用 DiffUtil 异步计算差异
- 首次加载 stagger 入场动画（前 6 项，每项延迟 40ms）
- 切换账本时轻量淡入动画（140ms）
- 金额 crossfade 过渡（180ms）

#### 多选模式
- 长按进入，支持全选/批量删除/批量移动到账本
- 底部操作栏随键盘/导航栏自适应偏移

#### 空状态
- 无账单时显示"暂无账单"布局 + "记一笔"按钮
- 空状态时自动展开 AppBar

#### 下拉刷新
- `SwipeRefreshLayout` 仅在 AppBar 完全展开时允许触发
- 刷新时重新调用 `homeViewModel.syncAndLoad()`

---

## 架构总结

### 数据流模式

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐
│  Room DAO    │────▶│  Repository  │────▶│  ViewModel   │
│  (Flow)      │     │              │     │  (StateFlow) │
└─────────────┘     └──────────────┘     └──────┬───────┘
                                                 │ collect
                                                 ▼
                                          ┌──────────────┐
                                          │  Fragment /   │
                                          │  Activity     │
                                          │  (UI 更新)    │
                                          └──────────────┘
```

### 导航模式

- **Tab 切换**：Fragment hide/show（非 replace），动画由 SwipeFrameLayout + ValueAnimator 驱动
- **Activity 跳转**：标准 Intent + `startActivityForResult`（旧式）/ `ActivityResultContracts`（新式）
- **结果传递**：`setResult(RESULT_OK)` + `onActivityResult` / ActivityResultCallback
- **跨页面状态**：`SharedYearMonthSession`（年月同步）、静态回调（截屏结果）、ViewModel（首页数据）

### UI 组件复用

- `OverlayDialogs.showPageCenterDialog()` 统一弹窗样式
- `AddBillEntrySheetLauncher.show()` 统一添加账单入口
- `AccountingFormController` 记账表单在多处复用（EditBillActivity、悬浮窗、OverlayService）
- `ElegantDatePickerSheet` 日期选择器在多处复用

### 8.5 手势检测系统详细分析

# Tap 检测系统深度分析

> 目录：`app/src/main/java/com/taostudio/tapaccounting/tap/`（共 23 个文件）

---

## 一、系统总览

Tap 检测系统通过手机加速度计和陀螺仪传感器实时采集运动数据，利用信号处理管道提取特征，再通过启发式算法或 TensorFlow Lite 神经网络模型识别"敲击手机背面"动作，最终触发用户配置的操作（弹出悬浮窗、AI 记账、截屏记账等）。

### 类继承关系

```
BaseTapRT (interface)
  └── EventIMURT (open class) — 传感器数据缓冲 & 陀螺仪处理
        └── TapRT (open class) — 核心双击检测（ML + 启发式）
              ├── TapTapTapRT — ML 模式下的三击检测
              └── HeuristicTapTapTapRT — 启发式模式下的三击检测

TapDetector — 顶层协调器，SensorEventListener 实现
```

### 数据类型

| 类 | 用途 |
|---|---|
| `Point3f` | 三维浮点坐标 `(x, y, z)` |
| `Sample3C` | 带时间戳的三维采样 `(Point3f + t: Long)` |

---

## 二、TapDetector — 顶层协调器

### 2.1 类签名与构造

```kotlin
class TapDetector(
    private val context: Context,
    private val sensorManager: SensorManager,
    private val onTapAction: (tapCount: Int) -> Unit  // 回调：2=双击，3=三击
) : SensorEventListener
```

### 2.2 常量与阈值

| 常量 | 值 | 含义 |
|---|---|---|
| `SAMPLING_INTERVAL_NS` | `2,500,000` (2.5ms) | 重采样目标间隔，对应 400Hz |
| `FULL_POWER_SENSOR_SAMPLING_PERIOD_US` | `0` | Android `registerListener` fastest 模式 |
| `SENSOR_BATCHING_PERIOD_US` | `0` | 禁用传感器批处理 |
| `FULL_POWER_AFTER_START_MS` | `180,000` (3min) | 启动后全功率运行时长 |
| `STILLNESS_TO_LOW_POWER_MS` | `180,000` (3min) | 无显著运动后进入低功耗 |
| `POWER_CHECK_INTERVAL_MS` | `30,000` (30s) | 功耗状态检查间隔 |
| `SIGNIFICANT_ACCEL_DELTA` | `1.15` | 加速度计幅值变化阈值 (m/s^2) |
| `SIGNIFICANT_GYRO_ABS` | `0.65` | 陀螺仪绝对值阈值 (rad/s) |
| `TAP_THROTTLE_MS` | `500` | 双击/三击最小间隔（防抖） |
| `TAP_SENSITIVITY_VALUES` | `[0.75, 0.53, 0.40, 0.25, 0.1, 0.05, 0.04, 0.03, 0.02, 0.01, 0.0]` | 11 级灵敏度预设 |

### 2.3 传感器注册

```kotlin
accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)  // 必需
gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)      // ML 模式必需
```

- 传感器通过专用 `HandlerThread("TapSensorThread")` 接收事件，不占用主线程
- 注册参数：`samplingPeriodUs = 0`（fastest），`maxReportLatencyUs = 0`（无批处理）
- **启发式待机模式下不注册陀螺仪**（省电）

### 2.4 功耗管理状态机

```
┌─────────────────────────────────────────────────────────────┐
│                     forceFullMlMode = true                   │
│                  （用户强制全 ML，无状态切换）                  │
└─────────────────────────────────────────────────────────────┘

┌──────────────┐    3min 无运动     ┌───────────────────────┐
│   Full Power  │ ──────────────────> │ HeuristicStandby      │
│  (ML 推理)    │ <────────────────── │ (启发式 + 仅加速度计)  │
└──────────────┘    检测到运动        └───────────────────────┘
      ^                                          │
      │         检测到启发式候选敲击               │
      └──────────────────────────────────────────┘
```

**进入 Full Power 的触发条件：**
- 启动后 3 分钟内（`FULL_POWER_AFTER_START_MS`）
- 加速度计幅值变化 >= 1.15 m/s^2（`SIGNIFICANT_ACCEL_DELTA`）
- 陀螺仪任一轴绝对值 >= 0.65 rad/s（`SIGNIFICANT_GYRO_ABS`）
- 启发式待机模式下检测到敲击候选（`result >= 1`）

**进入 HeuristicStandby 的条件（同时满足）：**
- 距离启动已过 3 分钟
- 距离最近显著运动已过 3 分钟
- 非 `forceFullMlMode`

**状态切换时的动作：**
1. 注销所有传感器监听
2. 关闭当前 TFLite 分类器（`closeClassifier()`）
3. 重新创建 `TapRT` 实例（ML 或启发式）
4. 重新注册传感器（启发式模式下不注册陀螺仪）

### 2.5 事件处理管线 (`onSensorChanged`)

```
传感器事件
  │
  ├─ trackMotionForDynamicPower(event)     // 更新功耗追踪状态
  │
  ├─ tap.updateData(type, x, y, z, t, interval, isHeuristic)
  │     // 数据进入信号处理 + 分类管道
  │
  ├─ result = tap.checkDoubleTapTiming(timestamp)
  │     // 检查时间窗口内是否有 2/3 次敲击
  │     // 返回：0=无, 1=等待中(已识别1次), 2=双击, 3=三击
  │
  ├─ if HeuristicStandby && result >= 1 → extendFullPower()
  │     // 启发式候选触发全功率升级
  │
  ├─ if result >= 2 → 节流检查（500ms）
  │
  └─ when(result) → onTapAction(2) 或 onTapAction(3)
```

### 2.6 运行时创建逻辑 (`createTapRuntime`)

| 条件 | 创建的类 | 分类器 |
|---|---|---|
| 启发式 + 三击 | `HeuristicTapTapTapRT` | 无（纯启发式） |
| 启发式 + 双击 | `TapRT` (heuristic) | 无（纯启发式） |
| ML + 三击 | `TapTapTapRT` | `TapTfClassifier` |
| ML + 双击 | `TapRT` | `TapTfClassifier` |

**公共滤波器配置（`configureCommonFilters`）：**
- `lowpassKey.para = 0.2f`
- `highpassKey.para = 0.2f`
- `positivePeakDetector.minNoiseTolerate = sensitivity`
- `positivePeakDetector.windowSize = 64`
- 启发式模式额外设置：`negativePeakDetector.minNoiseTolerate = sensitivity`，`negativePeakDetector.windowSize = 64`

---

## 三、信号处理管道

### 3.1 管道总览

原始传感器数据经过以下处理链：

```
原始传感器数据 (accel/gyro, ~400Hz, 不等间隔)
  │
  ├─ Resample3C — 线性插值重采样至 2.5ms 间隔 (400Hz)
  │
  ├─ Slope3C — 计算一阶差分（微分），乘以缩放因子
  │
  ├─ Lowpass3C — 一阶 IIR 低通滤波
  │
  ├─ Highpass3C — 一阶 IIR 高通滤波
  │
  └─ 输出到特征缓冲区 (ArrayDeque)
```

**ML 模式**：加速度计和陀螺仪各走一条独立的 3 通道管道，最终 6 通道 × 50 样本 = 300 维特征向量送入 NN。

**启发式模式**：仅处理加速度计 Z 轴（`slope.z`），额外经过一对低通+高通滤波和正/负峰值检测器。

### 3.2 Resample — 重采样器

#### Resample1C（单通道基类）

```kotlin
open class Resample1C {
    tInterval    // 目标采样间隔（2,500,000 ns）
    tRawLast     // 上一个原始采样时间戳
    tResampledLast // 上一个重采样输出时间戳
    xRawLast     // 上一个原始值
    xResampledThis // 当前重采样输出值
}
```

#### Resample3C（三通道）

**`update(x, y, z, t)` 算法：**
1. 若当前时间 `t` < 上次重采样时间 + 目标间隔 → 缓存原始数据，返回 `false`
2. 计算插值比例：`scaledInterval = (interval - tLastSecond) / (t - tLastSecond)`
3. 对 X/Y/Z 各通道进行线性插值：`xResampled = xRawLast + (x - xRawLast) * scaledInterval`
4. 更新重采样时间戳
5. 返回 `true`（表示有一个新的重采样点可用）

**关键特性：** `update` 返回 `true` 时可连续调用（while 循环），处理传感器采样率高于目标率时一次原始数据可能跨越多个重采样间隔的情况。

### 3.3 Slope — 一阶差分

#### Slope1C

```kotlin
fun update(value: Float, d: Float): Float {
    val x = value * d        // 乘以缩放因子 d = 2.5ms / 实际间隔
    val delta = x - xRawLast // 一阶差分
    xRawLast = x
    return delta
}
```

- `d`（缩放因子）= `2500000.0f / 实际重采样间隔`，用于归一化不同采样率下的差分幅度
- 本质上是对信号求导（速度 → 加速度变化率）

#### Slope3C

三通道独立调用 `Slope1C.update`。

### 3.4 Lowpass — 一阶 IIR 低通滤波

#### Lowpass1C

```kotlin
fun update(value: Float): Float {
    val newXLast = value * para + (1.0f - para) * xLast
    xLast = newXLast
    return newXLast
}
```

- 标准指数移动平均（EMA）公式
- `para`（alpha）越小，滤波越强（截止频率越低）
- 配置值：
  - 加速度计/陀螺仪管道：`para = 1.0f`（无滤波，直通）
  - 键盘信号管道：`para = 0.2f`

#### Lowpass3C

三个 `Lowpass1C` 分别处理 X/Y/Z。

### 3.5 Highpass — 一阶 IIR 高通滤波

#### Highpass1C

```kotlin
fun update(value: Float): Float {
    val newYLast = (value - xLast) * para + yLast * para
    yLast = newYLast
    xLast = value
    return newYLast
}
```

- 结合输入差分 `(value - xLast)` 和前一输出 `yLast` 的一阶高通滤波
- `para` 越小，截止频率越低
- 配置值：
  - 加速度计/陀螺仪管道：`para = 0.05f`（低截止，去除直流偏移和低频漂移）
  - 键盘信号管道：`para = 0.2f`（较高截止，保留更多瞬态信号）

#### Highpass3C

三个 `Highpass1C` 分别处理 X/Y/Z。

### 3.6 PeakDetector — 峰值检测器

```kotlin
class PeakDetector {
    _amplitudeMajorPeak  // 窗口内最大峰值幅度
    _amplitudeReference  // 参考基线
    _idMajorPeak         // 主峰在窗口中的位置（倒计数）
    _minNoiseTolerate    // 最小噪声容忍度（灵敏度阈值）
    _noiseTolerate       // 动态噪声容忍度
    _windowSize          // 检测窗口大小（默认 64）
}
```

**`update(value)` 算法：**

1. `_idMajorPeak` 递减 1（表示主峰"老化"一帧）
2. 若递减后 < 0 → 清零主峰幅度
3. 动态噪声容忍度：`max(_minNoiseTolerate, _amplitudeMajorPeak / 5.0)`
4. 基线更新逻辑：
   - 若 `reference - value >= noiseTolerate` → 基线跟踪下降
   - 若 `reference - value < 0`（信号上升）且 `value > noiseTolerate` → 检测到新峰
     - 若幅度 > 当前主峰 → 更新主峰位置和幅度

**用途：** `getIdMajorPeak()` 返回主峰距离当前的帧数，用于在 ML 模式下对齐特征窗口。

---

## 四、核心检测类 TapRT

### 4.1 类结构

```kotlin
open class TapRT(
    val sizeWindowNs: Long = 160,000,000  // 160ms 窗口
    minTimeGapNs: Long = 100,000,000      // 最小敲击间隔 100ms
    maxTimeGapNs: Long = 500,000,000      // 最大敲击间隔 500ms
) : EventIMURT(), BaseTapRT
```

### 4.2 关键常量

| 常量 | 值 | 含义 |
|---|---|---|
| `mMinTimeGapNs` | `100,000,000` (100ms) | 两次敲击最小间隔 |
| `mMaxTimeGapNs` | `500,000,000` (500ms) | 两次敲击最大间隔 |
| `HEURISTIC_MIN_TIME_GAP_NS` | `180,000,000` (180ms) | 启发式模式最小间隔（更宽松） |
| `mFrameAlignPeak` | `12` | 帧对齐峰值阈值 |
| `_sizeFeatureWindow` | `50` | 每通道特征窗口长度 |
| `_numberFeature` | `300` | 总特征维度 (50 × 6 通道) |

### 4.3 ML 推理流程 (`recognizeTapML`)

```
1. 获取主峰位置 majorPeakId
2. 若 majorPeakId > 12 → 标记 _wasPeakApproaching = true
3. 计算对齐位置：adjustedMajorPeakId = majorPeakId - 6
4. 检查条件：
   - adjustedMajorPeakId >= 0
   - 陀螺仪时间偏移合理
   - 特征窗口不越界
   - _wasPeakApproaching == true
   - majorPeakId <= 12（峰在最近 12 帧内）
5. 构建 300 维特征向量：
   [0..49]   = xsAcc (加速度 X)
   [50..99]  = ysAcc (加速度 Y)
   [100..149] = zsAcc (加速度 Z)
   [150..199] = xsGyro (陀螺仪 X)
   [200..249] = ysGyro (陀螺仪 Y)
   [250..299] = zsGyro (陀螺仪 Z)
6. 陀螺仪数据乘以 10.0 缩放
7. 调用 _tflite.predict(featureVector, 7)
8. 取最大概率类别作为结果
```

**输出类别 (TapClass 枚举)：**

| 枚举值 | 序号 | 含义 |
|---|---|---|
| `Front` | 0 | 正面敲击 |
| `Back` | 1 | **背面敲击（有效触发）** |
| `Left` | 2 | 左侧敲击 |
| `Right` | 3 | 右侧敲击 |
| `Top` | 4 | 顶部敲击 |
| `Bottom` | 5 | 底部敲击 |
| `Others` | 6 | 其他/噪声 |

**只有 `TapClass.Back`（序号 1）才记录为有效敲击时间戳。**

### 4.4 启发式识别流程 (`recognizeTapHeuristic`)

```
1. 获取正峰主峰位置 (positiveIdMajorPeak)
2. 计算负峰相对位置：negativeIdMajorPeak - positiveIdMajorPeak
3. 判断条件：
   - positiveIdMajorPeak == 4（正峰在窗口第 4 帧）
   - 负峰相对位置在 [1, 2] 范围内（正峰后紧跟负峰）
4. 满足条件 → result = Back，否则 → Others
```

启发式模式仅使用 Z 轴信号，通过正峰-负峰的相对位置关系判断敲击。

### 4.5 双击/三击时间判定 (`checkDoubleTapTiming`)

**基类 TapRT 版本（双击）：**

```
1. 清理超过 maxTimeGapNs (500ms) 的旧时间戳
2. 若队列为空 → 返回 0
3. 遍历检查最早和最晚时间戳间距：
   - 若 > minTimeGapNs (100ms) → 清空队列，返回 2（双击确认）
4. 否则返回 1（等待中）
```

---

## 五、三击检测

### 5.1 TapTapTapRT（ML 模式）

```kotlin
class TapTapTapRT(
    sizeWindowNs: Long,
    isTripleTapEnabled: Boolean,
    sensitivity: Float,
    classifier: TfClassifier,
    minTimeGapNs: Long = 100,000,000
)
```

**`checkDoubleTapTiming` 覆盖逻辑：**

```
1. 若 tripleEnabled == false → 退化为双击（super）
2. 清理超过 750ms 的旧时间戳
3. 统计间隔 > minTimeGapNs 的敲击次数
4. 判断：
   - tapCount >= 3 || 首次时间戳距今 > 750ms：
     - tapCount == 1 → 返回 2（双击）
     - tapCount >= 2 → 返回 3（三击）
5. 否则返回 1（等待中）
```

### 5.2 HeuristicTapTapTapRT（启发式模式）

逻辑与 `TapTapTapRT` 完全一致，区别在于不持有分类器。

**三击时间窗口常量：**

| 常量 | 值 | 含义 |
|---|---|---|
| `mMaxTimeGapTripleNs` | `750,000,000` (750ms) | 三击检测总时间窗口 |

**三击判定逻辑：**
- 在 750ms 窗口内累计敲击
- 两次敲击间隔必须 > 100ms（`minTimeGapNs`）才算独立敲击
- 累计 2 次间隔 → 三击（返回 3）
- 累计 1 次间隔 → 双击（返回 2）

---

## 六、TensorFlow Lite 分类器

### 6.1 TfClassifier（基类）

提供两种推理接口：
- `predict11`：输入形状 `[1, N]`，适用于一维输入模型
- `predict12`：输入形状 `[1, N, 1, 1]`，适用于四维输入模型（**实际使用此方法**）

### 6.2 TapTfClassifier

```kotlin
class TapTfClassifier(
    assetManager: AssetManager,
    modelPath: String,
    lowPowerEnabled: Boolean = false
)
```

**NNAPI 委托：**
- 仅当 `lowPowerEnabled = true` 且 `Build.VERSION >= O_MR1 (27)` 时启用
- 配置：`EXECUTION_PREFERENCE_LOW_POWER` + `useNnapiCpu = true`
- 加载失败时降级到 CPU 推理

**模型加载：**
- 从 assets 目录通过 `AssetManager.openFd` 加载
- 使用 `FileChannel.map(MAP_READ_ONLY, offset, length)` 内存映射
- 延迟初始化（`by lazy`）

**推理：**
```kotlin
override fun predict(input: ArrayList<Float>, size: Int): ArrayList<ArrayList<Float>>
// size = 7（7 类输出）
// 使用 predict12：输入形状 [1, 300, 1, 1]，输出形状 [1, 7]
```

---

## 七、模型文件

### 7.1 可用模型

| 模型枚举 | 文件路径 | 对应设备 | 屏幕尺寸 | 文件大小 |
|---|---|---|---|---|
| `REDFIN` | `columbus/12/tap7cls_redfin.tflite` | Pixel 5 | 6.0 寸 | 13,520 B |
| `FLAME` | `columbus/12/tap7cls_flame.tflite` | Pixel 4 | 5.7 寸 | 11,880 B |
| `BRAMBLE` | `columbus/12/tap7cls_bramble.tflite` | Pixel 4a 5G | 6.2 寸 | 13,520 B |
| `CORAL` | `columbus/12/tap7cls_coral.tflite` | Pixel 4 XL | 6.3 寸 | 11,880 B |

所有模型位于 `app/src/main/assets/columbus/12/` 目录。

### 7.2 模型选择逻辑 (`TapModel.resolve`)

```
1. 读取 Prefs 中保存的模型路径
2. 若有保存且能找到对应枚举 → 使用保存的模型
3. 否则 → 自动推荐（recommend）
```

**自动推荐算法 (`recommend`)：**
```
1. 计算当前设备屏幕对角线：sqrt((wInches)^2 + (hInches)^2)
2. 选择屏幕尺寸与设备最接近的模型
3. 默认回退到 CORAL (6.3寸)
```

### 7.3 模型特征

- 模型名格式：`tap7cls_{device}.tflite` — 7 类分类器
- 输入：300 维浮点向量（6 通道 × 50 样本）
- 输出：7 维概率向量（Front/Back/Left/Right/Top/Bottom/Others）
- 非常轻量（约 12-14KB），适合端侧实时推理

---

## 八、EventIMURT — 基础运行时

### 8.1 数据缓冲区

| 缓冲区 | 类型 | 用途 |
|---|---|---|
| `_xsAcc`, `_ysAcc`, `_zsAcc` | `ArrayDeque<Float>` | 加速度计 X/Y/Z 高通滤波后数据 |
| `_xsGyro`, `_ysGyro`, `_zsGyro` | `ArrayDeque<Float>` | 陀螺仪 X/Y/Z 高通滤波后数据 |

缓冲区大小 = `sizeWindowNs / 重采样间隔` = 160ms / 2.5ms = **64 样本**

### 8.2 处理管道组件

| 组件 | 用途 |
|---|---|
| `_resampleAcc` / `_resampleGyro` | Resample3C — 重采样 |
| `_slopeAcc` / `_slopeGyro` | Slope3C — 差分 |
| `_lowpassAcc` / `_lowpassGyro` | Lowpass3C — 低通滤波 |
| `_highpassAcc` / `_highpassGyro` | Highpass3C — 高通滤波 |

### 8.3 陀螺仪处理 (`processGyro`)

```
原始陀螺仪数据 → Resample3C → Slope3C(d=2.5ms/interval) → Lowpass3C(para=1) → Highpass3C(para=0.05)
→ 输出到 xsGyro/ysGyro/zsGyro 缓冲区（窗口 64 样本）
```

### 8.4 陀螺仪缩放

```kotlin
fun scaleGyroData(data: ArrayList<Float>, scale: Float): ArrayList<Float>
// 将后半部分（陀螺仪通道）乘以 scale = 10.0
// 使得陀螺仪特征与加速度计特征在数值上可比
```

---

## 九、TapAction — 动作注册

### 9.1 接口

```kotlin
interface TapAction {
    val id: String
    val displayName: String
    val description: String
    fun execute(context: Context)
}
```

### 9.2 已注册动作

| 类 | ID | 显示名 | 描述 |
|---|---|---|---|
| `ShowOverlayAction` | `show_overlay` | 弹出悬浮窗 | 快速呼出记账悬浮窗 |
| `OpenAiChatAction` | `open_ai_chat` | AI 智能记账助手 | 打开 AI 对话记账界面 |
| `ScreenCaptureAction` | `screen_capture` | 截屏记账 | 截取屏幕并识别账单 |

所有动作通过 `OverlayService` 的不同 `action` 实现。

---

## 十、完整数据流图

```
┌─────────────────────────────────────────────────────────────────┐
│                       Android 传感器系统                          │
│              加速度计 (TYPE_ACCELEROMETER)                        │
│              陀螺仪 (TYPE_GYROSCOPE)                              │
└──────────────────┬──────────────────────────────────────────────┘
                   │ SensorEvent (不等间隔, ~400Hz)
                   ▼
┌─────────────────────────────────────────────────────────────────┐
│                    TapDetector.onSensorChanged                   │
│                                                                  │
│  1. trackMotionForDynamicPower → 更新功耗状态                     │
│  2. tap.updateData → 进入信号处理管道                              │
│  3. tap.checkDoubleTapTiming → 时间窗口判定                       │
│  4. 节流 + 回调 onTapAction(tapCount)                             │
└──────────────────┬──────────────────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────────────────┐
│                       TapRT / 子类                                │
│                                                                  │
│  ┌─ ML 路径 ─────────────────────────────────────────────┐      │
│  │ Acc → Resample3C → Slope3C → Lowpass3C → Highpass3C  │      │
│  │     → xsAcc/ysAcc/zsAcc 缓冲区 (64 样本)              │      │
│  │                                                       │      │
│  │ Gyro → Resample3C → Slope3C → Lowpass3C → Highpass3C │      │
│  │     → xsGyro/ysGyro/zsGyro 缓冲区 (64 样本)           │      │
│  │                                                       │      │
│  │ PeakDetector → 主峰位置对齐                            │      │
│  │ 300 维特征向量 → TapTfClassifier.predict → 7 类分类    │      │
│  │ Back 类 → 记录敲击时间戳                               │      │
│  └───────────────────────────────────────────────────────┘      │
│                                                                  │
│  ┌─ 启发式路径 ──────────────────────────────────────────┐      │
│  │ Acc → Resample3C → Slope3C → Lowpass3C → Highpass3C  │      │
│  │     → Lowpass1C(0.2) → Highpass1C(0.2)               │      │
│  │     → PeakDetector(+) / PeakDetector(-)               │      │
│  │                                                       │      │
│  │ 正峰位置==4 且 负峰相对位置在[1,2] → Back 类            │      │
│  └───────────────────────────────────────────────────────┘      │
│                                                                  │
│  checkDoubleTapTiming:                                            │
│    收集 Back 时间戳队列 → 检查间隔 [100ms, 500ms]                │
│    双击: 1 次间隔 → 返回 2                                        │
│    三击: 2 次间隔 → 返回 3 (需 tripleEnabled)                     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 十一、配置项汇总

| 配置键（推测） | 来源 | 类型 | 说明 |
|---|---|---|---|
| `isTapForceFullMl` | `Prefs` | Boolean | 强制全 ML 模式，禁用功耗管理 |
| `getTapSensitivityLevel` | `Prefs` | Int (0-10) | 灵敏度等级，映射到 `TAP_SENSITIVITY_VALUES` |
| `isTapNnapiLowPower` | `Prefs` | Boolean | 启用 NNAPI 低功耗推理 |
| `isTapTripleEnabled` | `Prefs` | Boolean | 启用三击检测 |
| `getTapModel` | `Prefs` | String | 保存的模型路径（空则自动推荐） |

---

## 十二、所有常量与阈值汇总

### TapDetector

| 名称 | 值 | 单位 | 用途 |
|---|---|---|---|
| `SAMPLING_INTERVAL_NS` | 2,500,000 | ns | 重采样间隔 (2.5ms = 400Hz) |
| `FULL_POWER_AFTER_START_MS` | 180,000 | ms | 启动后全功率持续时间 (3min) |
| `STILLNESS_TO_LOW_POWER_MS` | 180,000 | ms | 静止后进入低功耗 (3min) |
| `POWER_CHECK_INTERVAL_MS` | 30,000 | ms | 功耗检查间隔 (30s) |
| `SIGNIFICANT_ACCEL_DELTA` | 1.15 | m/s^2 | 加速度变化显著阈值 |
| `SIGNIFICANT_GYRO_ABS` | 0.65 | rad/s | 陀螺仪显著阈值 |
| `TAP_THROTTLE_MS` | 500 | ms | 敲击动作最小间隔 |
| `TAP_SENSITIVITY_VALUES` | 11 级 | - | 灵敏度预设值 |

### TapRT

| 名称 | 值 | 单位 | 用途 |
|---|---|---|---|
| `mMinTimeGapNs` | 100,000,000 | ns | 双击最小间隔 (100ms) |
| `mMaxTimeGapNs` | 500,000,000 | ns | 双击最大间隔 (500ms) |
| `HEURISTIC_MIN_TIME_GAP_NS` | 180,000,000 | ns | 启发式模式最小间隔 (180ms) |
| `mFrameAlignPeak` | 12 | 帧 | 帧对齐峰值阈值 |
| `_sizeFeatureWindow` | 50 | 样本 | 每通道特征窗口 |
| `_numberFeature` | 300 | 维 | 总特征维度 |
| `sizeWindowNs` | 160,000,000 | ns | 信号窗口 (160ms) |

### TapTapTapRT / HeuristicTapTapTapRT

| 名称 | 值 | 单位 | 用途 |
|---|---|---|---|
| `mMaxTimeGapTripleNs` | 750,000,000 | ns | 三击总时间窗口 (750ms) |

### 滤波器参数

| 管道 | 滤波器 | para 值 | 效果 |
|---|---|---|---|
| 加速度计/陀螺仪 | Lowpass3C | 1.0 | 直通（无滤波） |
| 加速度计/陀螺仪 | Highpass3C | 0.05 | 去除直流和低频漂移 |
| 键盘信号 (Key) | Lowpass1C | 0.2 | 轻度低通 |
| 键盘信号 (Key) | Highpass1C | 0.2 | 轻度高通 |

### 峰值检测器默认参数

| 参数 | ML 模式 | 启发式模式 |
|---|---|---|
| positivePeakDetector.minNoiseTolerate | sensitivity | sensitivity |
| positivePeakDetector.windowSize | 64 | 64 |
| negativePeakDetector.minNoiseTolerate | N/A | sensitivity |
| negativePeakDetector.windowSize | N/A | 64 |

---

## 十三、校准系统

**当前无显式校准系统。** 灵敏度通过 `TAP_SENSITIVITY_VALUES` 数组的 11 级预设值手动调节，影响峰值检测器的 `minNoiseTolerate` 参数。模型选择通过屏幕尺寸自动匹配设备专属模型，无运行时校准过程。

### 8.6 基础设施详细分析

# 备份与基础设施深度分析

## 1. 备份系统架构

### 1.1 备份格式 (BackupManager.kt)

备份文件为 ZIP 格式（`.bak`），压缩级别 `Deflater.BEST_COMPRESSION`。

**内部结构：**

```
backup.zip (.bak)
├── assets.json          # 资产列表
├── bills.json           # 账单列表
├── deleted_bills.json   # 已删除账单（回收站）
├── investment_lots.json # 投资份额
├── categories.json      # 分类
├── rules.json           # AI 规则
├── chat_messages.json   # 聊天消息
├── settings_general_basic.json      # 基本设置
├── settings_general_assets.json     # 资产设置
├── settings_general_cloud.json      # 云端设置
├── settings_display_entries.json    # 录入显示设置
├── settings_display_bills.json      # 账单显示设置
├── settings_display_multibill.json  # 多账单显示设置
├── settings_ai_core.json            # AI 核心设置（默认不备份）
├── settings_ai_chat.json            # AI 聊天设置
├── settings_books.json              # 账本设置
├── settings_advanced_runtime.json   # 运行时高级设置
├── banners/                         # 资产横幅图片
│   ├── xxx.jpg
│   └── ...
└── chat_media/                      # 聊天媒体文件
    ├── chat_bg/                     # 聊天背景
    ├── chat_voice/                  # 语音消息
    ├── chat_ai_avatar.jpg           # AI 头像
    └── chat_user_avatar.jpg         # 用户头像
```

**关键特性：**
- ZIP 内路径安全校验：`safeZipOutputFile()` 防止路径穿越攻击（检查 `..`、绝对路径、跨目录写入）
- JSON 序列化使用 Gson
- 支持 banner 图片和聊天媒体的独立恢复检测（`hasBanners()`、`hasChatMedia()`）

### 1.2 数据导出 (DataExportManager.kt)

纯序列化/反序列化层，支持以下实体类型：
- `Asset`、`Bill`、`DeletedBill`、`InvestmentLot`、`Category`、`AiRule`、`ChatMessage`

### 1.3 CSV 导入/导出 (CsvManager.kt)

**导出格式（UTF-8 BOM）：**

| 列名 | 说明 |
|------|------|
| time | 时间戳或 `yyyy-MM-dd HH:mm:ss` |
| id | 账单 ID |
| type | 0=支出, 1=收入, 2=转账 |
| subType | 0=普通, 1=退款, 2=还款 |
| amount | 金额 |
| originalAmount | 原始金额（外币） |
| currency | 币种代码 |
| exchangeRate | 汇率 |
| categoryName | 分类名 |
| accountName | 账户名 |
| toAccountName | 目标账户（转账） |
| remark | 备注 |
| fee | 手续费 |
| bookName | 账本名 |
| relatedBillId | 关联账单 ID（退款） |
| excludeFromStats | 是否排除统计 |

**导入兼容性：**
- 自动识别"钱迹"（QianJi）格式导出：通过检测中文表头（`时间`、`类型`、`金额`、`账户1`）
- 钱迹类型映射：支出/收入/转账/退款/还款/不计收支/报销
- 编码回退：UTF-8 -> GB18030 -> 系统默认
- 退款自动识别：备注含"退款"关键词自动标记 `SUBTYPE_REFUND`
- 旧版平账记录兼容：`subType=3/4` 自动转换为新的排除统计模式

### 1.4 加密模块 (BackupPinCrypto.kt)

**用途：** 备份中 AI 凭据（API Key）的 PIN 保护

**技术方案：**
- PIN：4 位数字
- KDF：PBKDF2WithHmacSHA256，60,000 次迭代
- 密钥长度：256 位
- 加密算法：AES-GCM（128 位 Tag）
- Salt：16 字节随机值
- IV：12 字节随机值

**加密字段：**
- `ai_api_key_v1` -> `ai_api_key_enc_v1`
- `ai_provider_keys_v1` -> `ai_provider_keys_enc_v1`

**JSON 格式：**
```json
{
  "v": 1,
  "kdf": "PBKDF2WithHmacSHA256",
  "iter": 60000,
  "salt": "<base64>",
  "iv": "<base64>",
  "ct": "<base64>"
}
```

### 1.5 WebDAV 云备份客户端 (WebDavClient.kt)

**协议：** 标准 WebDAV（基于 OkHttp）

**超时配置：**
- 连接：20 秒
- 读取：60 秒
- 写入：60 秒

**认证方式：** HTTP Basic Auth（`Credentials.basic`）

**操作：**
| 方法 | 说明 |
|------|------|
| `testConnection()` | PROPFIND 深度 0，测试连通性和认证 |
| `uploadBackup()` | 自动创建远程目录 + PUT 上传 |
| `findLatestBackup()` | 列出所有备份，按时间戳排序取最新 |
| `downloadBackup()` | GET 下载指定备份 |
| `cleanupBackups()` | 保留策略：lite 最新 10 个，full 最新 3 个 |

**目录结构：**
```
<remoteDir>/
└── <deviceName>/
    ├── backup_<device>_lite_20260623_143000.bak
    ├── backup_<device>_full_20260623_143000.bak
    └── ...
```

**备份文件名解析：** 正则 `_(lite|full|custom)_\d{8}_\d{6}\.bak$`

**URL 编码：** 路径段使用 `URLEncoder.encode` + `+` 替换为 `%20`

### 1.6 自动备份 (AutoBackupWorker.kt)

**调度方式：** `WorkManager.PeriodicWorkRequest`

**约束条件：**
- 电量不低（`setRequiresBatteryNotLow(true)`）
- 退避策略：指数退避，30 分钟基准
- 间隔范围：1-72 小时（默认 12 小时）

**备份模式：**
- `lite`：不含聊天媒体和 AI 核心设置
- `full`：含聊天媒体，不含 AI 核心设置（API Key 等敏感信息始终排除）
- `custom`：用户自定义

**执行流程：**
1. 读取备份选项
2. 从数据库获取全量数据（`BackupRepository.getFullData()`）
3. 序列化设置模块（`Prefs.serializeSettingsModules()`）
4. 本地备份：写入 SAF 目录（`DocumentFile`）为 `TapAccount_Backup_Latest.bak`
5. 云端备份（可选）：WebDAV 上传 + 清理旧备份

**SharedPreferences 键：**
- `tap_backup_prefs`：自动备份开关、间隔、模式、上次时间/结果
- `tap_cloud_backup_prefs`：WebDAV URL/用户名/密码/目录/设备名

### 1.7 恢复逻辑 (BackupRepository.kt)

**两种恢复模式：**

#### 全量恢复 (`restoreFullData`)
- 清空所有现有数据后写入
- ID 重映射：分类、资产、账单全部从 0 重新分配
- 关联修复：退款关联、投资份额关联、聊天消息中的账单引用
- 事务保证：`db.withTransaction {}`

#### 合并恢复 (`mergeRestoreFullData`)
- 不删除现有数据
- 资产/分类：按名称去重，已存在则跳过
- 账单：按 `时间+金额+类型+账户名` 四元组去重
- 投资份额：按 `sourceBillId` 去重
- 规则/聊天：追加模式
- 返回 `MergeRestoreResult` 统计插入/跳过数量

---

## 2. 启动与保活机制

### 2.1 BootReceiver.kt

**触发条件（Intent Filter）：**
- `ACTION_BOOT_COMPLETED` — 系统启动完成
- `ACTION_MY_PACKAGE_REPLACED` — 应用自身更新
- `com.taostudio.tapaccounting.RESTART_SERVICE` — 自定义重启广播

**执行逻辑：**
1. 若 `ACTION_MY_PACKAGE_REPLACED`：调度 `InvestmentInterestWorker`（投资利息计算）
2. 检查翻转/双击功能是否启用
3. 启动 `OverlayService`（悬浮窗服务）

### 2.2 OverlayService（前台服务）

**前台服务类型：** `microphone | specialUse`

**特殊用途声明：** `flip_sensor_detection`（翻转传感器检测）

**保活手段（从代码和配置推断）：**
- 前台通知常驻
- Shizuku 深度持久化（可选）：`ShizukuShell.applyAggressivePersistence()`
- `ShizukuRecoveryService` 自动恢复
- AccessibilityService 辅助保活（`KeepAliveAccessibilityService`）
- 心跳机制：`ProcessExitLogger.recordHeartbeat()` 记录最后心跳时间

### 2.3 ProcessExitLogger.kt

**功能：** 应用冷启动时计算距上次 OverlayService 心跳的间隔，用于诊断进程被杀的频率。

**存储：** `SharedPreferences("flip_prefs")` 中的 `last_overlay_heartbeat_ms`

---

## 3. 媒体处理 (ChatMediaController.kt)

### 3.1 功能模块

| 功能 | 说明 |
|------|------|
| AI 头像编辑 | 选择 -> 预览 -> 1:1 裁剪 -> 保存到 `filesDir/chat_ai_avatar.jpg` |
| 用户头像编辑 | 选择 -> 预览 -> 1:1 裁剪 -> 保存到 `filesDir/chat_user_avatar.jpg` |
| 聊天背景 | 选择 -> 预览 -> 按设备比例裁剪 -> 保存到 `filesDir/chat_bg/` |
| 图片发送 | 多选 -> 复制到 `filesDir/chat_images/` -> 压缩 -> Base64 编码 |

### 3.2 图片处理管线

1. **选择：** `ACTION_PICK` 支持多选（`EXTRA_ALLOW_MULTIPLE`）
2. **复制：** 从 ContentProvider 复制到应用私有存储 `chat_images/chat_img_<timestamp>_<uuid>.<ext>`
3. **压缩：** 若文件 > 4MB，执行 `compressImageInPlace()`
   - 采样降分辨率（最长边不超过 1600px）
   - JPEG 质量 82%
4. **编码：** Base64（`NO_WRAP`）传给 AI 模型

### 3.3 裁剪配置

- **头像裁剪：** 1:1 比例，最大 1080x1080，JPEG 质量 92%
- **背景裁剪：** 设备屏幕比例，最大 2160x2160，JPEG 质量 92%
- **裁剪库：** UCrop 2.2.8

### 3.4 图片加载

使用 `GlideLocalFiles` 工具类（封装 Glide），特性：
- 禁用磁盘缓存（`DiskCacheStrategy.NONE`）
- 跳过内存缓存（`skipMemoryCache`）
- 支持 `circleCrop` 和 `overrideSize`

---

## 4. 日志系统 (Logger.kt)

### 4.1 日志级别

| 方法 | 说明 |
|------|------|
| `d(ctx, tag, msg)` | Debug 日志：debug 包写 Logcat，启用日志时写文件 |
| `d(tag, msg)` | 纯 Logcat 输出 |
| `crash(ctx, thread, throwable)` | 崩溃日志：始终写文件，不依赖 Prefs 开关 |
| `dPriv(ctx, tag, safe, detail)` | 隐私保护日志：敏感信息自动脱敏 |

### 4.2 日志文件

- **应用日志：** `app_logs.txt`（外部存储优先，回退内部存储）
- **崩溃日志：** `crash_logs.txt`（同目录）
- **轮转策略：** 单文件最大 2MB，超过后重命名为 `.bak`

### 4.3 隐私日志脱敏 (`dPriv`)

三层控制：
1. `isPrivacyDebugLoggingEnabled` — 总开关
2. `isDeveloperFullLoggingEnabled` — 开发者模式，截断 2000 字符
3. Debug 包 + 脱敏处理

**脱敏规则：**
- Base64 数据 -> `<redacted-base64>`
- 文件路径 -> `<redacted-path>`
- 备注/商户名 -> `<redacted>`
- 金额/费用 -> `<redacted>`
- 最终截断 800 字符

---

## 5. 远程配置 (RemoteConfigManager.kt)

### 5.1 配置来源

**静态 URL：** `https://gist.githubusercontent.com/yyzx2016lht/410018a849271da1a0e39efaa0978c2a/raw/flipaccounting-config.json`

### 5.2 配置字段

| 字段 | 默认值 | 说明 |
|------|--------|------|
| apiKey | "" | AI API Key |
| apiUrl | "https://api.siliconflow.cn" | API 端点 |
| provider | "硅基流动" | AI 提供商 |
| textModelId | "" | 文本模型（优先级最高） |
| visionModelId | "" | 视觉模型 |
| onlineSpeechModelId | "" | 在线语音模型 |
| modelId | "Qwen/Qwen3-14B" | 通用模型（回退） |
| singleModelId | "Qwen/Qwen3-14B" | 单账单模型 |
| multiModelId | "Qwen/Qwen3-14B" | 多账单模型 |
| modifyModelId | "Qwen/Qwen3-14B" | 修改模型 |
| categoryRefineModelId | "Qwen/Qwen3-14B" | 分类优化模型 |
| routerModelId | "Qwen/Qwen3-8B" | 路由模型 |
| queryModelId | "Qwen/Qwen3-14B" | 查询模型 |
| ruleModelId | "Qwen/Qwen3-8B" | 规则模型 |
| receiptModelId | "Qwen/Qwen3-14B" | 收据文本模型 |
| receiptVisionModelId | "Qwen/Qwen3-VL-30B-A3B-Instruct" | 收据视觉模型 |
| ocrRefineModelId | "Qwen/Qwen3-8B" | OCR 优化模型 |
| speechModelId | "FunAudioLLM/SenseVoiceSmall" | 语音模型 |
| chatModelId | "Qwen/Qwen3-14B" | 聊天模型 |
| ocrRefineEnabled | true | OCR 优化开关 |
| queryEnabled | true | 查询功能开关 |
| thinkingEnabled | true | 思维链开关 |

### 5.3 应用逻辑

- `syncIfConfigured()` — 拉取配置并写入 Prefs
- `applyConfig()` — 文本模型统一用 `firstNonBlank()` 选择最高优先级值
- 网络超时：连接 10 秒，读取 10 秒
- 失败静默返回 null，不影响应用运行

---

## 6. 构建配置

### 6.1 项目级构建 (build.gradle.kts)

**插件版本（通过 Version Catalog）：**

| 插件 | 版本 |
|------|------|
| AGP (Android Gradle Plugin) | 8.7.3 |
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.25 |
| Room | 2.6.1 |

### 6.2 应用级构建 (app/build.gradle.kts)

**基础配置：**

| 配置项 | 值 |
|--------|-----|
| namespace | `com.taostudio.tapaccounting` |
| applicationId | `com.taostudio.tapaccounting` |
| compileSdk | 34 |
| minSdk | 24 (Android 7.0) |
| targetSdk | 34 (Android 14) |
| versionCode | 2 |
| versionName | 1.2 |
| NDK ABI | arm64-v8a |
| Java 版本 | 17 |
| coreLibraryDesugaring | 启用 |

**Release 配置：**
- 签名：外部 `key.properties` 文件
- R8 混淆：启用
- 资源缩减：启用
- ProGuard：`proguard-android-optimize.txt` + `proguard-rules.pro`

**无 buildConfigField 定义，无 productFlavors。**

### 6.3 依赖清单

#### AndroidX

| 库 | 版本 |
|----|------|
| core-ktx | 1.12.0 |
| appcompat | 1.6.1 |
| documentfile | 1.0.1 |
| fragment-ktx | 1.6.2 |
| lifecycle-runtime-ktx | 2.8.7 |
| lifecycle-viewmodel-ktx | 2.8.7 |
| swiperefreshlayout | 1.1.0 |
| work-runtime-ktx | 2.9.0 |
| Room (runtime/ktx/compiler) | 2.6.1 |

#### 网络

| 库 | 版本 |
|----|------|
| Retrofit | 2.9.0 |
| Retrofit Gson Converter | 2.9.0 |
| OkHttp | 4.12.0 |
| OkHttp Logging Interceptor | 4.12.0 |
| Gson | 2.10.1 |

#### 图像与 UI

| 库 | 版本 |
|----|------|
| Glide | 4.16.0 |
| UCrop | 2.2.8 |
| MPAndroidChart | v3.1.0 |
| Material Components | 1.11.0 |
| Calendar View (kizitonwose) | 2.4.0 |
| Spotlight (onboarding) | 2.0.5 |

#### AI / ML

| 库 | 版本 |
|----|------|
| ML Kit Text Recognition Chinese | 16.0.0 |
| TensorFlow Lite | 2.14.0 |
| TensorFlow Lite Support | 0.4.0 |
| sherpa-onnx (本地 AAR) | 自带 |

#### 系统权限

| 库 | 版本 |
|----|------|
| Shizuku API | 13.1.5 |
| Shizuku Provider | 13.1.5 |

#### 工具

| 库 | 版本 |
|----|------|
| kotlinx-coroutines-android | 1.7.3 |
| commons-compress | 1.24.0 |
| desugar_jdk_libs | 2.1.5 |

#### 测试

| 库 | 版本 |
|----|------|
| JUnit | 4.13.2 |

### 6.4 ProGuard 规则摘要

- **sherpa-onnx**：保留 JNI 类和 native 方法
- **Gson**：保留 `TypeToken` 及其子类的泛型签名
- **Retrofit**：保留 suspend API 的 `Continuation` 签名
- **Room 实体**：保留 `data.local.entity.**`
- **AI DTO**：保留 `SiliconFlowApi`、`ChatRequest`、`Message` 等
- **Shizuku**：保留 `Shizuku`、`ShizukuRemoteProcess`、`ShizukuHelper`、`ShizukuShell`
- **TensorFlow Lite**：保留 `org.tensorflow.lite.**`
- **内置分类预设**：保留 `BuiltInCategory`

---

## 7. AndroidManifest 组件清单

### 7.1 权限

| 权限 | 用途 |
|------|------|
| SYSTEM_ALERT_WINDOW | 悬浮窗 |
| FOREGROUND_SERVICE | 前台服务 |
| FOREGROUND_SERVICE_SPECIAL_USE | 特殊用途前台服务（传感器检测） |
| FOREGROUND_SERVICE_MICROPHONE | 麦克风前台服务（语音识别） |
| VIBRATE | 振动反馈 |
| RECORD_AUDIO | 录音（语音记账） |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| HIGH_SAMPLING_RATE_SENSORS | 高采样率传感器（翻转检测） |
| INTERNET | 网络访问 |
| ACCESS_NETWORK_STATE | 网络状态检测 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 忽略电池优化 |
| WAKE_LOCK | 唤醒锁 |
| POST_NOTIFICATIONS | 通知（Android 13+） |
| READ_MEDIA_IMAGES | 相册读取（Android 13+） |
| READ_MEDIA_VISUAL_USER_SELECTED | 部分相册授权（Android 14+） |
| READ_EXTERNAL_STORAGE | 相册读取（Android 12 及以下） |
| QUERY_ALL_PACKAGES | 查询所有应用（应用白名单功能） |

### 7.2 Application

- **类名：** `TapApplication`
- **allowBackup：** true
- **usesCleartextTraffic：** true（允许 HTTP 明文）

### 7.3 Activity 列表

| Activity | 说明 | 特殊配置 |
|----------|------|----------|
| MainActivity | 主界面 | singleTask, LAUNCHER |
| QuickStartActivity | 快速启动 | 透明主题, noHistory, excludeFromRecents |
| PermissionRequestActivity | 权限请求 | 透明主题, noHistory |
| AppListActivity | 应用白名单 | - |
| AddAssetActivity | 新增资产 | - |
| BalanceAdjustmentActivity | 平账 | - |
| SettingsActivity | 分类管理 | - |
| CategorySortActivity | 排序分类 | - |
| BackupHomeActivity | 备份与恢复主页 | - |
| StorageCleanupActivity | 存储清理 | - |
| HistoryBillActivity | 回收站 | - |
| StoragePreviewActivity | 预览待清理内容 | - |
| StorageImageViewerActivity | 图片预览 | - |
| BackupActivity | 本地备份与恢复 | 注册 `.bak`/`.flip` 文件打开 Intent |
| AddCategoryActivity | 新增分类 | - |
| LogViewerActivity | 日志查看器 | - |
| CurrencyManagerActivity | 货币管理 | - |
| ExchangeRateActivity | 汇率管理 | - |
| BillDisplaySettingsActivity | 账单显示效果 | - |
| SensitivityActivity | 灵敏度调节 | - |
| GesturePermissionGuideActivity | 快捷记账准备 | - |
| BillDetailActivity | 账单详情 | - |
| RefundActivity | 退款 | - |
| EditBillActivity | 编辑账单 | 透明主题 |
| CalendarActivity | 日历账单 | NoActionBar 主题 |
| BillSearchActivity | 搜索账单 | stateVisible\|adjustResize |
| BookOverviewActivity | 账本总览 | NoActionBar 主题 |
| AssetDetailActivity | 资产详情 | - |
| AssetStatsActivity | 资产统计 | - |
| AiRuleManageActivity | AI 规则管理 | - |
| AiFeatureSettingsActivity | AI 功能设置 | - |
| AiConfigActivity | AI 配置 | - |
| ChatActivity | AI 聊天 | adjustResize |
| ChatImagePreviewActivity | 图片预览 | portrait |
| ChatSearchActivity | 搜索聊天记录 | adjustResize |
| ScreenCaptureActivity | 截屏 | 透明主题, noHistory |
| ImagePickerActivity | 图片选择 | 透明主题, excludeFromRecents |
| UCropActivity | 图片裁剪（三方） | portrait |

### 7.4 Service 列表

| Service | 类型 | 说明 |
|---------|------|------|
| OverlayService | foreground (microphone\|specialUse) | 悬浮窗 + 传感器检测 |
| QuickStartTileService | Quick Settings Tile | 快速启动磁贴 |
| KeepAliveAccessibilityService | Accessibility | 截屏能力 + 保活 |

### 7.5 Receiver

| Receiver | Intent Filter |
|----------|---------------|
| BootReceiver | BOOT_COMPLETED, MY_PACKAGE_REPLACED, RESTART_SERVICE |

### 7.6 Provider

| Provider | authorities | 说明 |
|----------|-------------|------|
| FileProvider | `${applicationId}.fileprovider` | 文件共享（日志、横幅） |
| ShizukuProvider | `${applicationId}.shizuku` | Shizuku 权限代理 |

**FileProvider 路径配置：**
- `external-files-path` -> 外部文件目录
- `files-path` -> 内部文件目录
- `files-path/banners/` -> 横幅图片
- `cache-path/banner_crop/` -> 裁剪缓存

---

## 8. Application 类 (TapApplication.kt)

### 8.1 初始化顺序

1. `ProcessExitLogger.onAppCreate()` — 记录进程重启间隔
2. `SharedYearMonthSession.resetToCurrentMonth()` — 重置年月会话
3. `installCrashHandler()` — 注册全局崩溃处理器
4. `CurrencyManager.init()` — 初始化货币管理器
5. `InvestmentInterestWorker.schedule()` — 调度投资利息计算
6. `MigrationManager.migrateIfNecessary()` — 后台执行数据迁移
7. `InvestmentInterestService.settleDueInterest()` — 结算到期利息
8. `CategoryIconPreloader.preloadAll()` — 预热分类图标缓存

### 8.2 懒加载实例

```kotlin
val database by lazy { AppDatabase.getDatabase(this) }
val billRepository by lazy { BillRepository(database.billDao()) }
val assetRepository by lazy { AssetRepository(database.assetDao()) }
val categoryRepository by lazy { CategoryRepository(database.categoryDao()) }
```

### 8.3 崩溃处理

- 捕获未处理异常
- 写入 `crash_logs.txt`（通过 `Logger.crash()`）
- 保留系统默认处理器行为

---

## 9. 数据迁移 (MigrationManager.kt)

### 9.1 迁移阶段

| 阶段 | 说明 | Pref Key |
|------|------|----------|
| 旧版 -> Room | 从 SharedPreferences 迁移资产、分类、账单到 Room 数据库 | `has_migrated_to_room` |
| 分类名规范化 | 批量更新所有账单的分类名格式 | `has_normalized_category_name_storage_v2` |
| 余额快照回填 | 已跳过（显示层使用反向推导） | `balance_snapshot_backfilled_v1` |

### 9.2 旧版数据迁移细节

- 资产类型映射：`资金`/`信用`/`投资`/`其他`
- 分类递归迁移：保留父子关系
- 账单类型标准化：`normalizeLegacyBillTypeAndSubtype()`
- 默认币种：CNY，汇率 1.0

---

## 10. 基础设施扫描结果

### 10.1 ContentProvider

**无自定义 ContentProvider 实现。** 仅使用：
- `androidx.core.content.FileProvider`（文件共享）
- `rikka.shizuku.ShizukuProvider`（Shizuku 权限代理）

### 10.2 DI 框架

**无 DI 框架。** 项目不使用 Hilt/Dagger/Koin。依赖管理方式：
- `TapApplication` 中懒加载 Repository 实例
- 直接构造函数传递（如 `BackupRepository(AppDatabase.getDatabase(ctx))`）
- 单例模式（`object` 关键字广泛使用）

### 10.3 分析/崩溃上报

**无第三方分析或崩溃上报服务。** 不集成：
- Firebase Analytics / Crashlytics
- Sentry
- Bugly
- 友盟

自建方案：
- `Logger` 文件日志 + 隐私脱敏
- `crash_logs.txt` 本地崩溃记录
- `LogViewerActivity` 应用内查看/分享日志
- `ProcessExitLogger` 进程存活监控

### 10.4 Shizuku 集成

**用途：** 在无需 root 的情况下获取 shell 权限

**功能：**
- 截屏（通过 `Shizuku.newProcess()` 反射调用）
- 深度保活（`ShizukuShell.applyAggressivePersistence()`）
- 应用白名单管理（`AppListActivity`）
- 自动恢复服务（`ShizukuRecoveryService`）

### 10.5 AccessibilityService

**类名：** `KeepAliveAccessibilityService`

**配置：**
- 事件类型：`typeWindowStateChanged`
- 反馈类型：`feedbackGeneric`
- 标志：`flagRetrieveInteractiveWindows`
- 能力：`canTakeScreenshot=true`、`canRetrieveWindowContent=true`

**用途：** 截屏记账 + 辅助保活
