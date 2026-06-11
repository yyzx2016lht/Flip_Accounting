# Chat Agent 实施提示词（敲敲记账 / FlipAccounting-AI）

> 本文档供复制给另一个 AI 或 Cursor Agent，用于在现有 App 中实现对话 Agent 工具系统。  
> 目标：用户在 `ChatActivity` 通过自然语言完成 App 内约 80% 的操作（含设置、权限、手势）。

---

## 任务概述

你是 Android Kotlin 工程师。请在现有 App 中实现一套 **Chat Agent 工具架构**，使用户在 `ChatActivity` 对话界面通过自然语言完成 App 内绝大部分操作。

**不要**从零重写业务逻辑；**必须**复用现有 Service / DAO / Prefs / Activity。  
**不要**让大模型直接写 SQL 或直接改数据库；所有写操作经本地 Tool 执行。  
**不要**删除现有手动 UI；对话是第二入口，不是替代所有界面。

---

## 一、项目背景

| 项 | 说明 |
|----|------|
| 技术栈 | Kotlin Android，`app` 模块，Room 数据库，OpenAI 兼容 API |
| 主入口 | `ChatActivity` + `ChatMessagePipeline.kt` |
| AI 请求 | `AIService.kt`，多提供商 `AiProviderRegistry.kt` |
| 查询层（已写未接入） | `chat/query/QueryPlanner.kt`、`QueryExecutor.kt`、`QueryContextBuilder.kt` |
| 改账（已写未调用） | `ChatMessagePipeline.callAiAccountingModify()` |
| 记账写入 | `AIService.analyzeAccounting()` + `BillMutationService.kt` |
| 当前问题 | 关键词分流 `isLikelyGeneralChat`、pipeline 割裂、大量能力未暴露 |

**工作区路径：** `E:/FlipAccounting-AI`

---

## 二、目标架构（必须实现）

### 2.1 核心组件

新建包：`com.taostudio.tapaccounting.chat.agent`

| 类 | 职责 |
|----|------|
| `AgentTool` | 工具接口：`id`、`category`、`risk`、`description`、`parameterSchema`、`execute()` |
| `AgentToolRegistry` | 注册所有工具，按 category 列出 |
| `ChatAgentOrchestrator` | 主编排：用户文本 + 历史 → 选工具 → 执行 → 回复 |
| `AgentSessionContext` | `bookName`、`conversationId`、`QueryContext`、权限状态摘要 |
| `AgentConfirmationController` | 写操作/危险操作确认卡 |
| `AgentToolResult` | `success`、`facts`（结构化事实）、`userMessage`、`uiAction` |
| `AgentPromptBuilder` | 构建 system prompt + 工具列表注入 |

### 2.2 模型协议（JSON Tool Call）

每轮模型只输出一个 JSON（不要 markdown）：

**选工具：**
```json
{
  "tool": "stats.query_category",
  "params": {
    "timeRangeKey": "this_month",
    "categoryName": "餐饮",
    "billType": "EXPENSE"
  },
  "assistant_hint": "用户问本月餐饮花了多少"
}
```

**纯聊天：**
```json
{
  "tool": "chat.reply",
  "params": { "message": "..." }
}
```

**追问：**
```json
{
  "tool": "agent.clarify",
  "params": { "question": "你想查微信还是支付宝？" }
}
```

**确认前预览：**
```json
{
  "tool": "agent.preview",
  "params": {
    "pendingTool": "bill.delete",
    "pendingParams": { "billId": 123 },
    "summary": "将删除 1 笔支出 35 元"
  }
}
```

System prompt 注入：工具列表、紧凑 context（账本/资产名/分类名）、规则（数字只来自 tool result）。

### 2.3 接入点

改造 `ChatMessagePipeline.sendText()`：

- 默认走 `ChatAgentOrchestrator.handle(userText)`
- 图片/语音/多模态保留专用入口，结果仍回 Agent 会话
- 逐步废弃 `isLikelyGeneralChat` 关键词分流

### 2.4 编排流程

```
用户消息
  → 构建 AgentSessionContext + QueryContext
  → LLM 选择 tool + params
  → 若 risk >= WRITE 且非免确认：agent.preview → 用户确认 → 执行
  → 执行 tool，得到 AgentToolResult.facts
  → 可选：第二轮短 LLM 润色 facts（禁止改数字）
  → 流式输出 + UI 卡（账单/确认/导航）
  → 多步链式调用，最多 5 步
```

**免确认（可配置）：** `bill.create_from_text` 单笔简单记账。

---

## 三、风险等级

| 等级 | 含义 | 处理 |
|------|------|------|
| `READ` | 只读查询 | 直接执行 |
| `NAV` | 打开页面 | 直接或确认后执行 |
| `WRITE` | 新增/修改 | 确认卡（简单记账可免） |
| `DESTRUCTIVE` | 删除/恢复/导入覆盖 | 预览影响 + 二次确认 |
| `SENSITIVE` | API Key、云密码 | 禁止明文回显；引导配置页 |
| `SYSTEM` | 系统权限 | 可读状态；改 Prefs 可对话化；授权跳转系统设置 |

---

## 四、完整工具清单（约 120+，须全部注册）

格式：`tool_id` | 风险 | 说明 | 主要复用代码

---

### A. 对话与元操作（8）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `chat.reply` | READ | 纯闲聊/解释功能 | `AIService.generateGeneralChatReply` |
| `agent.clarify` | READ | 向用户追问 | 编排层 |
| `agent.preview` | READ | 展示待确认操作 | 编排层 |
| `agent.list_capabilities` | READ | 列出能做什么 | 注册表 |
| `agent.cancel` | READ | 取消当前确认/请求 | `ChatMessagePipeline.cancelCurrentRequest` |
| `chat.session.create` | WRITE | 新建对话会话 | `ChatSessionController` |
| `chat.session.switch` | NAV | 切换会话 | `ChatSessionController` |
| `chat.session.delete` | DESTRUCTIVE | 删除会话及消息 | `ChatSessionController` + DAO |

---

### B. 记账 - 创建（10）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `bill.create_from_text` | WRITE | 文本记账（单/多笔） | `AIService.analyzeAccounting` |
| `bill.create_from_voice` | WRITE | 语音记账 | `AIService.analyzeAccountingByAudio` |
| `bill.create_from_image` | WRITE | 图片/小票记账 | `AIService.analyzeReceiptByImage` |
| `bill.create_from_screen` | WRITE | 截屏记账 | `AIService.analyzeScreenAccountingByImage` |
| `bill.create_manual` | NAV | 打开手动记账页 | `EditBillActivity` / `AddBillEntrySheetLauncher` |
| `bill.confirm_draft` | WRITE | 确认图片/截屏草稿 | `confirmVisualAccountingDraft` |
| `bill.cancel_draft` | READ | 取消草稿 | pipeline 现有逻辑 |
| `bill.refund` | WRITE | 对某笔支出退款 | `RefundActivity` |
| `bill.create_repayment` | WRITE | 信用卡还款 | 记账 type=还款 |
| `bill.create_transfer` | WRITE | 转账 | 记账 type=转账 |

---

### C. 记账 - 修改/删除（12）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `bill.modify_by_instruction` | WRITE | 自然语言改账 | `callAiAccountingModify` + `generateAccountingModifyReply` |
| `bill.edit` | NAV | 打开账单编辑页 | `EditBillActivity` |
| `bill.delete` | DESTRUCTIVE | 软删除单笔 | `BillDeleteHelper` |
| `bill.delete_batch` | DESTRUCTIVE | 批量删除 | `BillDeleteHelper` |
| `bill.move_to_book` | WRITE | 移账本 | `BillSearchActivity` 同类逻辑 |
| `bill.toggle_exclude_stats` | WRITE | 不计入统计开关 | Bill DAO |
| `bill.get_detail` | READ | 查单笔详情 | `BillDao.getBillById` |
| `bill.search` | READ | 关键词搜账单 | `BillSearchActivity` / `BillDao` |
| `bill.list_recent` | READ | 最近 N 笔 | `BillDao.getRecentBills` |
| `bill.list_by_date` | READ | 按日期列账单 | `CalendarActivity` |
| `bill.restore_from_bin` | WRITE | 回收站恢复 | `BillRestoreHelper` |
| `bill.permanent_delete` | DESTRUCTIVE | 永久删除 | `HistoryBillActivity` |

---

### D. 统计与查询（18）

优先复用 `chat/query/*`

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `stats.query_spending` | READ | 通用花销查询 | `QueryPlanner` + `QueryExecutor` → `QUERY_BILLS` |
| `stats.query_category` | READ | 分类花销 | `QUERY_CATEGORY_STATS` |
| `stats.query_asset_spending` | READ | 某资产花销 | `QUERY_ASSET_STATS` |
| `stats.query_existence` | READ | 有没有买过 X | `QUERY_EXISTENCE` |
| `stats.query_latest_bill` | READ | 最近一笔 | `aggregation=LATEST` |
| `stats.query_month_summary` | READ | 本月收支总览 | `StatsViewModel` |
| `stats.query_year_summary` | READ | 本年收支 | Stats |
| `stats.query_compare_period` | READ | 环比/同比 | Stats 上期 |
| `stats.query_top_categories` | READ | Top 分类 | Stats pie |
| `stats.query_transfer_repayment` | READ | 转账/还款/退款汇总 | `StatsFragment` |
| `stats.open_page` | NAV | 打开统计页带筛选 | `QueryNavigator` / `StatsExternalQueryBridge` |
| `stats.open_asset_page` | NAV | 打开资产统计页 | `AssetStatsActivity` |
| `stats.set_filter` | WRITE | 设置统计筛选 | Stats filter state |
| `home.query_trend` | READ | 首页趋势卡数据 | `HomeFragment` chart |
| `book.query_overview` | READ | 各账本收支概览 | `BookOverviewActivity` |
| `calendar.query_day` | READ | 某日账单 | `CalendarActivity` |
| `calendar.open` | NAV | 打开日历 | `CalendarActivity` |
| `stats.export_summary_text` | READ | 导出文字摘要 | 本地渲染 |

---

### E. 资产（16）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `asset.list` | READ | 列出所有资产 | `AssetDao.getAllAssetsList` |
| `asset.count` | READ | 有多少个账户 | AssetDao |
| `asset.get_balance` | READ | 某账户余额 | `Asset.balance` + 多币种 |
| `asset.get_net_worth` | READ | 净资产/总资产/总负债 | `AssetsFragment.updateHeader` / `AssetActivity` |
| `asset.query_spending` | READ | 某资产花销（账单维度） | `QUERY_ASSET_STATS` |
| `asset.create` | WRITE | 新建资产 | `AddAssetActivity` |
| `asset.edit` | WRITE | 改名称/图标/备注等 | `AddAssetActivity` |
| `asset.delete` | DESTRUCTIVE | 删除资产 | AssetDao |
| `asset.archive` | WRITE | 收纳资产 | `AssetDao.archiveAsset` |
| `asset.unarchive` | WRITE | 取消收纳 | AssetDao |
| `asset.adjust_balance` | WRITE | 平账 | `BalanceAdjustmentActivity` |
| `asset.reorder` | WRITE | 排序 | `AssetsFragment` |
| `asset.toggle_include_net` | WRITE | 是否计入总资产 | Asset 字段 |
| `asset.open_detail` | NAV | 打开资产详情 | `AssetDetailActivity` |
| `asset.move_bills` | WRITE | 批量移动账单到其他资产 | `AssetDetailActivity` |
| `asset.set_interest` | WRITE | 投资利率/结息 | `AddAssetActivity` |

---

### F. 账本（12）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `book.list` | READ | 列出账本 | `BookAccountManager.getBookAccounts` |
| `book.get_current` | READ | 当前账本 | `getSelectedBook` |
| `book.switch` | WRITE | 切换当前账本 | `setSelectedBook` |
| `book.create` | WRITE | 新建账本 | `addBookAccount` |
| `book.rename` | WRITE | 重命名 | `renameBookAccount` |
| `book.delete` | DESTRUCTIVE | 删除账本 | `removeBookAccount` |
| `book.set_default` | WRITE | 设默认账本 | `setDefaultBook` |
| `book.collapse` | WRITE | 收纳账本 | `setBookCollapsed` |
| `book.expand` | WRITE | 取消收纳 | `setBookCollapsed` |
| `book.reorder` | WRITE | 排序 | `reorderBookAccounts` |
| `book.set_color` | WRITE | 设主题色 | `setBookColor` |
| `book.set_banner` | NAV | 设封面图（需选图则跳转） | `setBookBannerPath` |

---

### G. 分类（10）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `category.list_expense` | READ | 支出分类树 | `CategoryDao` |
| `category.list_income` | READ | 收入分类树 | CategoryDao |
| `category.create` | WRITE | 新建子分类 | `SettingsActivity` |
| `category.rename` | WRITE | 重命名 | Settings |
| `category.delete` | DESTRUCTIVE | 删除（含账单处理策略） | Settings |
| `category.sort` | WRITE | 排序 | `CategorySortActivity` |
| `category.promote` | WRITE | 升为一级 | Settings |
| `category.demote` | WRITE | 降为二级 | Settings |
| `category.open_manage` | NAV | 打开分类管理 | `SettingsActivity` |
| `category.map_bills` | WRITE | 批量改账单分类 | Settings 同类逻辑 |

---

### H. 备份 / 导入导出（14）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `backup.export_full` | READ | 全量备份 | `BackupActivity` / `BackupManager` |
| `backup.export_lite` | READ | 精简备份 | Backup |
| `backup.export_custom` | READ | 自定义模块备份 | `PrefsBackupSupport` modules |
| `backup.import` | DESTRUCTIVE | 导入备份 | `PrefsBackupSupport.importAll` |
| `backup.export_csv` | READ | 导出 CSV | `CsvManager` |
| `backup.import_csv` | DESTRUCTIVE | 导入 CSV | CsvManager |
| `backup.list_modules` | READ | 可备份模块列表 | backup modules 常量 |
| `cloud.get_config` | READ | 读 WebDAV 配置（密码打码） | cloud prefs |
| `cloud.set_config` | SENSITIVE | 设 WebDAV | `BackupActivity` cloud |
| `cloud.backup_now` | WRITE | 立即云备份 | Cloud backup |
| `cloud.open_settings` | NAV | 云备份设置页 | `CloudBackupActivity` |
| `storage.get_usage` | READ | 存储占用 | `StorageCleanupActivity` |
| `storage.cleanup` | DESTRUCTIVE | 清理缓存/语音/图片 | Storage cleanup |
| `storage.open` | NAV | 打开存储管理 | `StorageCleanupActivity` |

---

### I. AI 配置与规则（20）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `ai.get_provider` | READ | 当前提供商 | `AiProviderRegistry` |
| `ai.set_provider` | WRITE | 切换提供商 | `applyProvider` |
| `ai.get_models` | READ | 当前各槽位模型 | `AiModelSlots` |
| `ai.set_text_model` | WRITE | 设主文本模型 | Prefs |
| `ai.set_vision_model` | WRITE | 设视觉模型 | Prefs |
| `ai.set_speech_model` | WRITE | 设语音模型 | Prefs |
| `ai.set_chat_model` | WRITE | 设聊天模型 | `PrefsChatSupport` |
| `ai.test_connection` | READ | 测试 API | `AiConfigActivity` |
| `ai.open_config` | NAV | AI 服务配置页 | `AiConfigActivity` |
| `ai.open_features` | NAV | AI 功能页 | `AiFeatureSettingsActivity` |
| `ai.rule.list` | READ | 列记账规则 | `AiRule` DAO |
| `ai.rule.create` | WRITE | 新建规则 | `AiRuleManageActivity` |
| `ai.rule.update` | WRITE | 改规则 | AiRule |
| `ai.rule.delete` | DESTRUCTIVE | 删规则 | AiRule |
| `ai.set_reply_style` | WRITE | 聊天风格 | `PrefsChatSupport` |
| `ai.set_chat_profile` | WRITE | AI 名字/身份/用户档案 | Prefs chat |
| `ai.toggle_prompt_correction` | WRITE | 纠错规则开关 | Prefs |
| `ai.toggle_thinking` | WRITE | 思考模式各开关 | Prefs thinking flags |
| `ai.toggle_query` | WRITE | 启用 AI 查询 | `isAiQueryEnabled` |
| `ai.set_api_key` | SENSITIVE | 设置 Key（仅确认已保存，不回显） | `setAiProviderKey` |

---

### J. 显示与记账体验设置（22）

| tool_id | risk | 说明 | Prefs/复用 |
|---------|------|------|------------|
| `pref.get` | READ | 读任意已知 pref 开关 | Prefs* |
| `pref.set` | WRITE | 写 pref 开关 | Prefs* |
| `display.toggle_ai_text` | WRITE | 显示 AI 文本入口 | `setShowAiText` |
| `display.toggle_ai_voice` | WRITE | 语音记账入口 | `setShowAiVoice` |
| `display.toggle_ai_image` | WRITE | 图片记账入口 | `setShowAiImage` |
| `display.toggle_screen_accounting` | WRITE | 截屏记账入口 | `setShowScreenAccounting` |
| `display.toggle_ai_chat_entry` | WRITE | 对话页入口 | `show_ai_chat_entry` |
| `display.toggle_multi_currency` | WRITE | 多币种 | `setShowMultiCurrency` |
| `display.toggle_home_trend` | WRITE | 首页趋势卡 | `setShowHomeTrendCard` |
| `display.toggle_book_entry` | WRITE | 记账页账本入口 | `setShowBookEntry` |
| `display.toggle_multi_bill` | WRITE | 多笔记账 | `setMultiBillEnabled` |
| `display.toggle_multi_bill_fast` | WRITE | 极简多账单 | `setMultiBillFastMode` |
| `display.toggle_amount_grouping` | WRITE | 金额分组 | `setAmountGroupingEnabled` |
| `display.toggle_category_icon` | WRITE | 账单分类图标 | bill display prefs |
| `display.toggle_full_category` | WRITE | 完整分类路径 | bill display prefs |
| `display.toggle_remark_priority` | WRITE | 备注优先显示 | bill display prefs |
| `display.toggle_independent_detail` | WRITE | 独立详情页 | `setIndependentDetailEnabled` |
| `display.set_ocr_mode` | WRITE | OCR 模式 | `setOcrMode` |
| `display.set_receipt_lang` | WRITE | 小票语言 | `setReceiptLangMode` |
| `display.toggle_ocr_debug` | WRITE | 保存 OCR 调试 | `setSaveOcrDebugEnabled` |
| `display.set_ai_entry_mode` | WRITE | 传统/对话入口模式 | `setAiEntryMode` |
| `display.open_bill_display_settings` | NAV | 账单显示设置 | `BillDisplaySettingsActivity` |

---

### K. 手势 / 悬浮窗 / 权限 / 系统（30）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `gesture.get_status` | READ | 翻转/双击/三击是否开启 | Prefs flip/tap |
| `gesture.enable_flip` | WRITE | 开翻转 | `setFlipEnabled` |
| `gesture.enable_double_tap` | WRITE | 开双击 | `setDoubleTapEnabled` |
| `gesture.enable_triple_tap` | WRITE | 开三击 | `setTapTripleEnabled` |
| `gesture.enable_quick_gesture` | WRITE | 快捷手势总开关 | `setQuickGestureEnabled` |
| `gesture.set_flip_sensitivity` | WRITE | 翻转灵敏度 | `setFlipSensitivity` |
| `gesture.set_tap_sensitivity` | WRITE | 敲击灵敏度 | `setTapSensitivityLevel` |
| `gesture.set_flip_action` | WRITE | 翻转触发动作 | `setFlipAction` |
| `gesture.set_double_tap_action` | WRITE | 双击动作 | `setTapActionDouble` |
| `gesture.set_triple_tap_action` | WRITE | 三击动作 | `setTapActionTriple` |
| `gesture.list_actions` | READ | 可用动作列表 | `TapActionRegistry` |
| `gesture.open_settings` | NAV | 打开灵敏度页 | `SensitivityActivity` |
| `gesture.open_permission_guide` | NAV | 手势权限引导 | `GesturePermissionGuideActivity` |
| `overlay.show` | NAV | 显示悬浮窗 | `OverlayService.ACTION_SHOW_OVERLAY` |
| `overlay.show_ai_panel` | NAV | 显示 AI 输入面板 | `ACTION_SHOW_AI_INPUT` |
| `overlay.get_whitelist` | READ | 白名单 App | `getWhiteList` |
| `overlay.set_whitelist` | WRITE | 设白名单 | `setWhiteList` / `AppListActivity` |
| `overlay.open_app_picker` | NAV | 选白名单应用 | `AppListActivity` |
| `perm.get_status` | READ | 汇总权限状态 | 悬浮窗/无障碍/电池/Shizuku/录屏 |
| `perm.request_overlay` | SYSTEM | 跳转悬浮窗权限 | Settings |
| `perm.request_accessibility` | SYSTEM | 跳转无障碍 | Settings |
| `perm.request_battery_whitelist` | SYSTEM | 电池优化白名单 | Intent |
| `perm.request_screen_capture` | SYSTEM | 截屏授权 | `ScreenCaptureActivity` |
| `system.toggle_shizuku_mode` | WRITE | Shizuku 模式 | `setShizukuMode` |
| `system.toggle_shizuku_persistence` | WRITE | Shizuku 持久化 | Prefs |
| `system.toggle_vibrate` | WRITE | 震动反馈 | `setVibrateFeedback` |
| `system.toggle_save_vibrate` | WRITE | 保存震动 | `setSaveVibrate` |
| `system.toggle_logging` | WRITE | 日志开关 | `setLoggingEnabled` |
| `system.toggle_hide_recents` | WRITE | 隐藏最近任务卡片 | `setHideRecents` |
| `system.toggle_landscape` | WRITE | 禁止横屏 | `setDisableLandscape` |
| `system.toggle_aggressive_keepalive` | WRITE | 激进保活 | Prefs |
| `system.open_quick_start` | NAV | 快速启动磁贴说明 | `QuickStartActivity` |

**`TapActionRegistry` 可用动作 ID：**

- `show_overlay` — 弹出悬浮窗
- `open_ai_chat` — AI 智能记账助手
- `screen_capture` — 截屏记账（`ScreenCaptureAction`）

---

### L. 币种与汇率（8）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `currency.list_enabled` | READ | 已启用币种 | `getActiveCurrencies` |
| `currency.enable` | WRITE | 启用币种 | `CurrencyManagerActivity` |
| `currency.disable` | WRITE | 禁用币种 | Currency |
| `currency.get_rates` | READ | 当前汇率 | `CurrencyManager` |
| `currency.update_rates` | WRITE | 拉取最新汇率 | `CurrencyManager.updateRates` |
| `currency.set_refresh_interval` | WRITE | 刷新间隔 | Prefs |
| `currency.open_manage` | NAV | 币种管理页 | `CurrencyManagerActivity` |
| `currency.open_rates` | NAV | 汇率编辑页 | `ExchangeRateActivity` |

---

### M. 导航 - 主界面（10）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `nav.open_home` | NAV | 账单 Tab | `MainActivity` tab |
| `nav.open_stats` | NAV | 统计 Tab | MainActivity |
| `nav.open_assets` | NAV | 资产 Tab | MainActivity |
| `nav.open_settings` | NAV | 设置 Tab | `ProfileFragment` |
| `nav.open_chat` | NAV | 对话页 | `ChatActivity` |
| `nav.open_backup` | NAV | 备份页 | `BackupActivity` |
| `nav.open_book_overview` | NAV | 账本总览 | `BookOverviewActivity` |
| `nav.open_recycle_bin` | NAV | 回收站 | `HistoryBillActivity` |
| `nav.open_chat_search` | NAV | 搜聊天记录 | `ChatSearchActivity` |
| `nav.open_logs` | NAV | 日志查看 | `LogViewerActivity` |

---

### N. 用户资料（6）

| tool_id | risk | 说明 | 复用 |
|---------|------|------|------|
| `user.get_profile` | READ | 用户名/简介 | Prefs |
| `user.set_name` | WRITE | 改用户名 | Profile |
| `user.set_description` | WRITE | 改简介 | Profile |
| `user.set_avatar` | NAV | 换头像（需选图） | ProfileFragment |
| `chat.set_user_avatar` | NAV | 对话用户头像 | Chat media |
| `chat.set_background` | NAV | 对话背景 | `Prefs.setAiChatBgPath` |

---

## 五、用户说法示例（tool 对照）

| 用户可能说 | 对应 tool |
|-----------|-----------|
| 本月餐饮花了多少 | `stats.query_category` |
| 我有多少资产 / 几个账户 | `asset.count` / `asset.list` |
| 总资产多少钱 / 净资产 | `asset.get_net_worth` |
| 微信里还有多少钱 | `asset.get_balance` |
| 午饭花了35 | `bill.create_from_text` |
| 刚才那笔改成40 | `bill.modify_by_instruction` |
| 删掉上一笔 | `bill.delete` + preview |
| 切换到账本「旅行」 | `book.switch` |
| 打开统计页看餐饮 | `stats.open_page` |
| 有没有买过咖啡 | `stats.query_existence` |
| 导出 CSV | `backup.export_csv` |
| 开启翻转手势，翻转打开 AI 对话 | `gesture.enable_flip` + `gesture.set_flip_action` |
| 关闭首页趋势卡 | `display.toggle_home_trend` |
| 帮我打开悬浮窗权限 | `perm.request_overlay` |
| 切换到 DeepSeek | `ai.set_provider` |
| 聊天风格俏皮一点 | `ai.set_reply_style` |

---

## 六、与现有代码映射

| 已有代码 | Agent 用途 |
|----------|-----------|
| `chat/query/QueryPlanner.kt` | `stats.*` 参数规划 |
| `chat/query/QueryExecutor.kt` | 账单聚合查询执行 |
| `chat/query/QueryContextBuilder.kt` | 紧凑 context |
| `ChatMessagePipeline.callAiAccountingModify` | `bill.modify_by_instruction` |
| `AIService.analyzeAccounting` | `bill.create_from_text` |
| `BillDeleteHelper` / `BillRestoreHelper` | 删/恢复 |
| `BookAccountManager` | 账本工具 |
| `AssetDao` + `AssetsFragment.updateHeader` | 资产余额/净资产 |
| `Prefs*` / `PrefsDisplaySupport` / `PrefsGeneralSupport` | 设置类工具 |
| `TapActionRegistry` + `SensitivityActivity` | 手势工具 |
| `OverlayService` | 悬浮窗工具 |
| `PrefsBackupSupport` | 备份模块 |

---

## 七、实施阶段

### Phase 0 — 骨架

- `AgentTool` / `Registry` / `Orchestrator` / `ConfirmationController`
- 接入 `ChatMessagePipeline`
- `chat.reply` + `agent.clarify` + `agent.list_capabilities`

### Phase 1 — 读多写少

- 全部 `stats.*` + `asset.list/count/balance/net_worth` + `book.*` 读 + `nav.*`
- 接 `QueryExecutor`

### Phase 2 — 记账写

- `bill.create_from_text` / `modify` / `delete` / `search`

### Phase 3 — 管理写

- 账本/分类/资产 CRUD
- 备份导出；导入仅 DESTRUCTIVE 确认

### Phase 4 — 设置/手势/权限

- `display.*` / `gesture.*` / `perm.*` / `system.*`

### Phase 5 — 多步 Agent

- 链式：「先查本月餐饮，再和上月比」

---

## 八、测试要求

**单元测试：** 每个 `AgentTool` 参数校验与执行。

**集成测试：** 扩展 `QueryPlannerExecutorTest` 风格到 Agent。

**手动用例（至少）：**

1. 本月餐饮花了多少
2. 总资产多少
3. 午饭35 → 改成40
4. 打开统计页看餐饮
5. 开启翻转手势，翻转打开 AI 对话
6. 关闭首页趋势卡
7. 导出 CSV
8. 切换到账本「旅行」

---

## 九、约束与禁止

1. 禁止模型编造金额、余额、笔数
2. 禁止在聊天中回显完整 API Key / WebDAV 密码
3. 禁止跳过 DESTRUCTIVE 确认
4. 禁止删除 `ChatActivity` 现有 UI
5. 新代码复用 `Prefs` 门面，不直接散写 SharedPreferences
6. 编译目标：`:app:compileDebugKotlin` 必须通过

---

## 十、交付物

1. `chat/agent/` 包下完整实现
2. `ChatMessagePipeline` 改造
3. `AgentPromptBuilder.kt`（system prompt 模板）
4. 工具注册表 JSON（供 prompt 注入）
5. 至少 10 个单元测试
6. `docs/AGENT_TOOLS.md`（tool_id 与用户示例对照，可由实施者补充）

---

## 十一、实施者开始前须输出

1. 类图计划
2. Phase 0 文件清单
3. 第一批实现的 15 个 `tool_id`

然后再写代码。

---

## 附录：关键源文件路径

```
app/src/main/java/com/taostudio/tapaccounting/
├── ChatActivity.kt
├── ChatMessagePipeline.kt
├── AIService.kt
├── AiProviderRegistry.kt
├── AiModelSlots.kt
├── Prefs.kt
├── PrefsAiSupport.kt
├── PrefsDisplaySupport.kt
├── PrefsGeneralSupport.kt
├── PrefsChatSupport.kt
├── PrefsBackupSupport.kt
├── BookAccountManager.kt
├── chat/query/
│   ├── QueryPlanner.kt
│   ├── QueryExecutor.kt
│   ├── QueryContextBuilder.kt
│   ├── QueryModels.kt
│   └── QueryNavigator.kt
├── logic/
│   ├── BillMutationService.kt
│   ├── BillDeleteHelper.kt
│   └── BillRestoreHelper.kt
├── tap/TapAction.kt
├── OverlayService.kt
└── ui/
    ├── SensitivityActivity.kt
    ├── main/stats/StatsFragment.kt
    └── main/assets/AssetsFragment.kt
```

---

*文档版本：2026-06-09 · 与 FlipAccounting-AI 当前分支对齐*
