# Chat Agent 实施计划（敲敲记账 / FlipAccounting-AI）

> 本文档基于 `AGENT_IMPLEMENTATION_PROMPT.md` 制定，包含完整实施计划、类图设计、文件清单和工具优先级。

**文档版本：** 2026-06-09

---

## 一、类图架构设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                      ChatAgentOrchestrator                          │
│  - handle(userText): AgentToolResult                                │
│  - chainExecution(steps: List<ToolCall>): AgentToolResult           │
│  - buildContext(): AgentSessionContext                               │
└──────────────┬──────────────────────────────────────────────────────┘
               │ uses
               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      AgentToolRegistry                              │
│  - register(tool: AgentTool)                                        │
│  - findById(id: String): AgentTool?                                 │
│  - getByCategory(category: String): List<AgentTool>                 │
│  - getToolDescriptions(): String (for prompt)                       │
└──────────────┬──────────────────────────────────────────────────────┘
               │ contains
               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         AgentTool (interface)                        │
│  + id: String                                                       │
│  + category: String                                                 │
│  + risk: RiskLevel                                                  │
│  + description: String                                              │
│  + parameterSchema: JSONObject                                      │
│  + execute(params: JSONObject, context: AgentSessionContext)         │
│    : AgentToolResult                                                │
└─────────────────────────────────────────────────────────────────────┘
               │ implemented by
               ▼
┌─────────────────────────┐  ┌─────────────────────────┐  ┌──────────┐
│ StatsQueryTool          │  │ BillCreateTool          │  │ ...      │
│ (stats.query_*)         │  │ (bill.create_*)         │  │ 120+     │
└─────────────────────────┘  └─────────────────────────┘  └──────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                       AgentSessionContext                            │
│  - bookName: String                                                 │
│  - conversationId: String                                           │
│  - queryContext: QueryContext                                        │
│  - permissionState: Map<String, Boolean>                            │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                     AgentConfirmationController                      │
│  - shouldConfirm(tool: AgentTool, params: JSONObject): Boolean      │
│  - showPreview(pendingTool, pendingParams, summary)                 │
│  - awaitUserConfirmation(): Boolean                                 │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                         AgentToolResult                              │
│  - success: Boolean                                                 │
│  - facts: JSONObject? (structured data)                             │
│  - userMessage: String?                                             │
│  - uiAction: UiAction? (NAV/SHOW_DIALOG/etc)                        │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                        AgentPromptBuilder                            │
│  - buildSystemPrompt(context: AgentSessionContext): String          │
│  - buildToolDescriptions(): String                                  │
│  - buildContextSummary(context: AgentSessionContext): String         │
└─────────────────────────────────────────────────────────────────────┘
```

### 类职责说明

| 类 | 职责 | 关键方法 |
|----|------|----------|
| `AgentTool` | 工具接口，定义工具的基本契约 | `execute()`, `getParameterSchema()` |
| `AgentToolRegistry` | 管理所有工具的注册和查找 | `register()`, `findById()`, `getByCategory()` |
| `ChatAgentOrchestrator` | 主编排器，协调整个工具调用流程 | `handle()`, `chainExecution()` |
| `AgentSessionContext` | 封装会话状态和上下文信息 | `buildFromCurrentState()` |
| `AgentConfirmationController` | 处理写操作的确认流程 | `shouldConfirm()`, `showPreview()` |
| `AgentToolResult` | 工具执行结果的数据封装 | `isSuccess()`, `getFacts()` |
| `AgentPromptBuilder` | 构建发送给LLM的提示词 | `buildSystemPrompt()`, `buildToolDescriptions()` |

---

## 二、Phase 0 文件清单

### 2.1 核心接口和数据类

| 文件 | 路径 | 职责 | 行数估算 |
|------|------|------|----------|
| `AgentTool.kt` | `chat/agent/` | 工具接口定义 | ~50 |
| `RiskLevel.kt` | `chat/agent/` | 风险等级枚举（READ/NAV/WRITE/DESTRUCTIVE/SENSITIVE/SYSTEM） | ~30 |
| `AgentToolResult.kt` | `chat/agent/` | 工具执行结果数据类 | ~40 |
| `AgentToolRegistry.kt` | `chat/agent/` | 工具注册表单例 | ~80 |
| `AgentSessionContext.kt` | `chat/agent/` | 会话上下文数据类 | ~60 |
| `UiAction.kt` | `chat/agent/` | UI动作枚举（NAV/SHOW_DIALOG/SHOW_TOAST） | ~30 |

### 2.2 核心服务类

| 文件 | 路径 | 职责 | 行数估算 |
|------|------|------|----------|
| `ChatAgentOrchestrator.kt` | `chat/agent/` | 主编排器，处理用户消息 | ~300 |
| `AgentConfirmationController.kt` | `chat/agent/` | 确认控制器 | ~150 |
| `AgentPromptBuilder.kt` | `chat/agent/` | 提示词构建器 | ~200 |

### 2.3 Phase 0 工具实现（15个）

| 文件 | 路径 | tool_id | 风险 |
|------|------|---------|------|
| `ChatReplyTool.kt` | `chat/agent/tool/` | `chat.reply` | READ |
| `AgentClarifyTool.kt` | `chat/agent/tool/` | `agent.clarify` | READ |
| `AgentListCapabilitiesTool.kt` | `chat/agent/tool/` | `agent.list_capabilities` | READ |
| `StatsQueryCategoryTool.kt` | `chat/agent/tool/` | `stats.query_category` | READ |
| `StatsQuerySpendingTool.kt` | `chat/agent/tool/` | `stats.query_spending` | READ |
| `AssetListTool.kt` | `chat/agent/tool/` | `asset.list` | READ |
| `AssetGetBalanceTool.kt` | `chat/agent/tool/` | `asset.get_balance` | READ |
| `BookGetCurrentTool.kt` | `chat/agent/tool/` | `book.get_current` | READ |
| `BillListRecentTool.kt` | `chat/agent/tool/` | `bill.list_recent` | READ |
| `BillSearchTool.kt` | `chat/agent/tool/` | `bill.search` | READ |
| `BillGetDetailTool.kt` | `chat/agent/tool/` | `bill.get_detail` | READ |
| `BillCreateFromTextTool.kt` | `chat/agent/tool/` | `bill.create_from_text` | WRITE |
| `NavOpenPageTool.kt` | `chat/agent/tool/` | `nav.*` | NAV |
| `PrefGetTool.kt` | `chat/agent/tool/` | `pref.get` | READ |
| `PrefSetTool.kt` | `chat/agent/tool/` | `pref.set` | WRITE |

### 2.4 改造现有文件

| 文件 | 改动内容 |
|------|----------|
| `ChatMessagePipeline.kt` | 在 `sendText()` 中接入 `ChatAgentOrchestrator`，替代 `isLikelyGeneralChat()` 关键词分流 |

### 2.5 测试文件

| 文件 | 路径 | 职责 |
|------|------|------|
| `AgentToolRegistryTest.kt` | `test/.../chat/agent/` | 注册表单元测试 |
| `ChatAgentOrchestratorTest.kt` | `test/.../chat/agent/` | 编排器单元测试 |
| `StatsQueryToolTest.kt` | `test/.../chat/agent/tool/` | 查询工具测试 |
| `BillCreateToolTest.kt` | `test/.../chat/agent/tool/` | 记账工具测试 |

---

## 三、第一批15个tool_id优先级

### P0 - 核心对话能力（必须首先实现）

| tool_id | 风险 | 说明 | 复用代码 | 实现复杂度 |
|---------|------|------|----------|------------|
| `chat.reply` | READ | 纯闲聊/解释功能 | `AIService.generateGeneralChatReply` | 低 |
| `agent.clarify` | READ | 向用户追问 | 编排层 | 低 |
| `agent.list_capabilities` | READ | 列出能做什么 | 注册表 | 低 |

**选择理由：** 这是Agent系统的基础对话能力，没有这些工具，用户无法进行基本交互。

### P1 - 高频查询功能（用户最常使用）

| tool_id | 风险 | 说明 | 复用代码 | 实现复杂度 |
|---------|------|------|----------|------------|
| `stats.query_category` | READ | 分类花销查询 | `QueryPlanner` + `QueryExecutor` | 中 |
| `stats.query_spending` | READ | 通用花销查询 | `QueryPlanner` + `QueryExecutor` | 中 |
| `asset.list` | READ | 列出资产 | `AssetDao.getAllAssetsList` | 低 |
| `asset.get_balance` | READ | 资产余额 | `Asset.balance` | 低 |
| `book.get_current` | READ | 当前账本 | `getSelectedBook` | 低 |
| `bill.list_recent` | READ | 最近账单 | `BillDao.getRecentBills` | 低 |
| `bill.search` | READ | 搜索账单 | `BillDao` | 中 |
| `bill.get_detail` | READ | 账单详情 | `BillDao.getBillById` | 低 |

**选择理由：** 这些是用户最高频的查询需求（"本月餐饮花了多少"、"我有多少资产"、"最近几笔账单"），实现后能覆盖80%的只读场景。

### P2 - 记账核心功能（写操作）

| tool_id | 风险 | 说明 | 复用代码 | 实现复杂度 |
|---------|------|------|----------|------------|
| `bill.create_from_text` | WRITE | 文本记账 | `AIService.analyzeAccounting` | 高 |
| `nav.open_stats` | NAV | 打开统计页 | `MainActivity` tab | 低 |

**选择理由：** `bill.create_from_text` 是最核心的写操作，需要实现确认机制；`nav.open_stats` 是最常见的导航需求。

### P3 - 设置类工具

| tool_id | 风险 | 说明 | 复用代码 | 实现复杂度 |
|---------|------|------|----------|------------|
| `pref.get` | READ | 读取设置 | `Prefs*` | 低 |
| `pref.set` | WRITE | 写入设置 | `Prefs*` | 低 |

**选择理由：** 设置查询和修改是常见需求，实现简单，能快速展示Agent系统的配置能力。

---

## 四、完整实施阶段

### Phase 0 — 骨架 + 核心工具（第1-2周）

**目标：** 建立Agent系统骨架，实现15个核心工具，完成Pipeline改造

**交付物：**
- `chat/agent/` 包下完整核心接口实现
- 15个工具实现（3个元工具 + 5个读取工具 + 2个写入工具 + 5个查询工具）
- `ChatMessagePipeline` 改造接入
- 4个单元测试
- 基本确认机制

**验收标准：**
- 用户可以说"本月餐饮花了多少"得到正确回答
- 用户可以说"午饭花了35"完成记账（带确认）
- 用户可以说"你能做什么"列出能力

### Phase 1 — 读多写少（第3周）

**目标：** 扩展查询和导航能力

**新增工具（+25个）：**
- 全部 `stats.*`（18个）
- `asset.list/count/balance/net_worth`（4个读取）
- `book.*` 读取（3个）
- `calendar.*`（2个）
- `home.query_trend`（1个）

**交付物：**
- 完整的统计查询能力
- 资产和账本查询
- 日历查询
- 集成测试扩展

**验收标准：**
- 支持所有统计维度查询
- 支持资产余额和净资产查询
- 支持日历视图查询

### Phase 2 — 记账写（第4周）

**目标：** 实现完整的记账写操作

**新增工具（+15个）：**
- `bill.create_from_text/voice/image/screen`（4个创建方式）
- `bill.modify_by_instruction`（自然语言改账）
- `bill.delete/delete_batch`（删除）
- `bill.edit`（打开编辑页）
- `bill.refund/create_repayment/create_transfer`（特殊记账类型）
- `bill.confirm_draft/cancel_draft`（草稿管理）
- `bill.restore_from_bin/permanent_delete`（回收站）

**交付物：**
- 完整的记账CRUD
- 草稿确认流程
- 回收站管理
- 批量操作确认

**验收标准：**
- 支持文本/语音/图片记账
- 支持自然语言改账
- 支持批量删除（带二次确认）

### Phase 3 — 管理写（第5周）

**目标：** 实现账本、分类、资产的管理功能

**新增工具（+25个）：**
- `book.*` 写操作（8个：create/rename/delete/set_default/collapse/expand/reorder/set_color/set_banner）
- `category.*`（10个：list/create/rename/delete/sort/promote/demote/open_manage/map_bills）
- `asset.*` 写操作（8个：create/edit/delete/archive/unarchive/adjust_balance/reorder/toggle_include_net/set_interest）
- `backup.*`（7个：export_full/lite/custom/import/export_csv/import_csv/list_modules）
- `cloud.*`（4个：get_config/set_config/backup_now/open_settings）
- `storage.*`（3个：get_usage/cleanup/open）

**交付物：**
- 完整的账本管理
- 分类管理
- 资产管理
- 备份导出

**验收标准：**
- 支持账本CRUD
- 支持分类管理
- 支持备份导出（导入需DESTRUCTIVE确认）

### Phase 4 — 设置/手势/权限（第6周）

**目标：** 实现所有设置、手势和权限相关功能

**新增工具（+30个）：**
- `display.*`（21个：各种显示开关和设置）
- `gesture.*`（12个：手势开关、灵敏度、动作设置）
- `overlay.*`（4个：悬浮窗控制）
- `perm.*`（5个：权限状态和请求）
- `system.*`（10个：系统设置）
- `currency.*`（8个：币种和汇率）
- `ai.*`（20个：AI配置和规则）
- `user.*`（6个：用户资料）

**交付物：**
- 完整的设置控制
- 手势配置
- 权限管理
- AI配置

**验收标准：**
- 支持所有设置项查询和修改
- 支持手势开关和灵敏度设置
- 支持权限状态查看和跳转

### Phase 5 — 多步Agent（第7周）

**目标：** 实现链式调用和复杂查询组合

**新增能力：**
- 链式工具调用（最多5步）
- 复杂查询组合（"先查本月餐饮，再和上月比"）
- 上下文记忆和引用
- 错误恢复和重试

**交付物：**
- 链式执行引擎
- 上下文管理增强
- 错误处理机制
- 集成测试

**验收标准：**
- 支持"本月餐饮花了多少，和上月比呢"这样的组合查询
- 支持"先打开统计页，然后筛选餐饮"这样的导航组合
- 错误时能正确回滚或重试

---

## 五、与现有代码映射

| 已有代码 | Agent 用途 | 集成方式 |
|----------|-----------|----------|
| `chat/query/QueryPlanner.kt` | `stats.*` 参数规划 | 直接调用 |
| `chat/query/QueryExecutor.kt` | 账单聚合查询执行 | 直接调用 |
| `chat/query/QueryContextBuilder.kt` | 紧凑 context | 直接调用 |
| `ChatMessagePipeline.callAiAccountingModify` | `bill.modify_by_instruction` | 包装调用 |
| `AIService.analyzeAccounting` | `bill.create_from_text` | 包装调用 |
| `BillDeleteHelper` / `BillRestoreHelper` | 删/恢复 | 直接调用 |
| `BookAccountManager` | 账本工具 | 直接调用 |
| `AssetDao` + `AssetsFragment.updateHeader` | 资产余额/净资产 | DAO调用 |
| `Prefs*` / `PrefsDisplaySupport` / `PrefsGeneralSupport` | 设置类工具 | 直接调用 |
| `TapActionRegistry` + `SensitivityActivity` | 手势工具 | 直接调用 |
| `OverlayService` | 悬浮窗工具 | Intent调用 |
| `PrefsBackupSupport` | 备份模块 | 直接调用 |

---

## 六、测试要求

### 6.1 单元测试（每个Phase）

| Phase | 测试文件 | 测试内容 |
|-------|----------|----------|
| Phase 0 | `AgentToolRegistryTest.kt` | 工具注册、查找、分类 |
| Phase 0 | `ChatAgentOrchestratorTest.kt` | 消息处理流程 |
| Phase 0 | `StatsQueryToolTest.kt` | 统计查询工具 |
| Phase 0 | `BillCreateToolTest.kt` | 记账工具 |
| Phase 1 | `QueryToolsTest.kt` | 所有查询工具 |
| Phase 2 | `BillWriteToolsTest.kt` | 记账写操作 |
| Phase 3 | `ManagementToolsTest.kt` | 管理类工具 |
| Phase 4 | `SettingsToolsTest.kt` | 设置类工具 |
| Phase 5 | `ChainExecutionTest.kt` | 链式调用 |

### 6.2 集成测试

扩展 `QueryPlannerExecutorTest.kt` 风格到Agent系统：

```kotlin
class AgentIntegrationTest {
    @Test fun `test full flow - query category spending`() { ... }
    @Test fun `test full flow - create bill with confirmation`() { ... }
    @Test fun `test full flow - chain execution`() { ... }
}
```

### 6.3 手动测试用例

| 用例 | 预期结果 | Phase |
|------|----------|-------|
| 本月餐饮花了多少 | 返回本月餐饮总金额 | 0 |
| 总资产多少 | 返回净资产金额 | 0 |
| 午饭35 → 改成40 | 修改成功 | 2 |
| 打开统计页看餐饮 | 跳转统计页并筛选 | 0 |
| 开启翻转手势，翻转打开AI对话 | 设置成功 | 4 |
| 关闭首页趋势卡 | 设置成功 | 4 |
| 导出CSV | 导出成功 | 3 |
| 切换到账本「旅行」 | 切换成功 | 1 |

---

## 七、风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| LLM工具选择准确率不足 | 用户体验差 | 本地fallback + 重试机制 |
| 确认流程打断对话流 | 用户体验差 | 简单操作免确认 + 快捷确认 |
| 工具执行超时 | 响应慢 | 超时控制 + 进度提示 |
| 链式调用错误累积 | 结果错误 | 步骤验证 + 错误回滚 |
| 现有代码改造风险 | 功能回归 | 渐进式改造 + 回归测试 |

---

## 八、交付物清单

| 交付物 | 文件/目录 | Phase |
|--------|-----------|-------|
| 核心接口 | `chat/agent/AgentTool.kt` 等 | 0 |
| 工具实现 | `chat/agent/tool/*.kt` | 0-4 |
| 编排器 | `chat/agent/ChatAgentOrchestrator.kt` | 0 |
| 提示词构建 | `chat/agent/AgentPromptBuilder.kt` | 0 |
| Pipeline改造 | `ChatMessagePipeline.kt` | 0 |
| 单元测试 | `test/.../chat/agent/*.kt` | 0-5 |
| 集成测试 | `test/.../chat/agent/AgentIntegrationTest.kt` | 5 |
| 工具清单文档 | `docs/AGENT_TOOLS.md` | 0 |

---

*文档版本：2026-06-09 · 与 FlipAccounting-AI 当前分支对齐*
