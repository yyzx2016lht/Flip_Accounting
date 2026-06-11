# Chat Agent Skill 架构与 Phase 0.5 实施提示词

> 本文档用于交给另一个 AI/Cursor/Codex，在现有 FlipAccounting-AI 项目中继续完善 Chat Agent。
> 本阶段不追求立刻实现全部 120+ 工具，而是先把现有 23 个工具升级为可靠、可扩展的 Agent 基础设施。

## 一、你的角色

你是一名资深 Android/Kotlin Agent 系统工程师。请直接阅读并修改现有项目，不要只给方案。

工作区：

```text
E:/FlipAccounting-AI
```

核心参考文档：

```text
docs/AGENT_IMPLEMENTATION_PROMPT.md
docs/AGENT_IMPLEMENTATION_PLAN.md
```

核心代码：

```text
app/src/main/java/com/taostudio/tapaccounting/chat/agent/
app/src/main/java/com/taostudio/tapaccounting/chat/agent/tool/
app/src/main/java/com/taostudio/tapaccounting/ChatMessagePipeline.kt
```

## 二、当前状态

项目已经具备：

- `AgentTool`、`AgentToolRegistry`、`AgentToolResult`
- `AgentSessionContext`
- `ChatAgentOrchestrator`
- `AgentConfirmationController`
- `AgentPromptBuilder`
- `AgentToolRegistrar`
- ChatMessagePipeline 接入
- 23 个已注册工具
- 两次 LLM 调用：选择工具、生成自然语言回复
- OpenAI 兼容 API，可使用 MiMo、DeepSeek 等模型
- 资产、分类、账本上下文注入
- `response_format: json_object`

当前已注册工具：

```text
chat.reply
agent.clarify
agent.list_capabilities

stats.query_category
stats.query_spending
stats.query_month_summary
stats.query_existence

asset.list
asset.get_balance
asset.count
asset.get_net_worth

book.get_current
book.list

bill.list_recent
bill.search
bill.get_detail
bill.create_from_text
bill.modify_by_instruction
bill.delete
bill.list_by_date

nav.open_stats

pref.get
pref.set
```

## 三、架构决策

不要为账单、资产、统计分别创建拥有独立人格、独立会话和独立编排器的多个 Agent。

采用：

```text
一个 ChatAgentOrchestrator
        |
        +-- SkillRouter
                |
                +-- general
                +-- bill
                +-- stats
                +-- asset_book
                +-- settings
                +-- backup
```

原则：

1. 用户始终面对同一个助手。
2. Skill 是能力域和工具集合，不是另一个聊天机器人。
3. 所有 Skill 共用会话上下文、确认机制、风险等级和工具注册表。
4. 先选择 Skill，再只向模型注入相关工具，避免一次传入 120+ 工具。
5. 跨域任务由总控编排器组合多个 Skill 的工具。

## 四、优先修复的问题

### 4.1 完成确认闭环

当前编排器只返回“回复确认执行”，但没有可靠保存待执行操作。

实现会话级 `PendingAgentAction`：

```kotlin
data class PendingAgentAction(
    val conversationId: String,
    val toolId: String,
    val params: JSONObject,
    val preview: String,
    val createdAt: Long,
    val expiresAt: Long
)
```

要求：

- 每个 conversation 独立保存 pending action。
- 用户回复“确认”“执行”“好的”等明确确认词时，直接执行原工具，不再让 LLM 重新生成参数。
- 用户回复“取消”“算了”“不要了”时清除 pending action。
- 用户发送其他内容时默认取消旧 pending，并按新请求处理。
- pending action 必须过期，建议 5 分钟。
- `DESTRUCTIVE` 必须确认。
- `SENSITIVE` 和 `SYSTEM` 必须确认或跳转安全页面。
- 确认执行前重新校验目标是否仍然存在。

### 4.2 修正确认判断

检查 `AgentConfirmationController.shouldConfirm()`。

目标规则：

```text
READ           不确认
NAV            通常不确认
WRITE          默认确认
DESTRUCTIVE    必须确认
SENSITIVE      必须确认，禁止回显秘密
SYSTEM         必须确认或展示系统授权说明
```

`bill.create_from_text` 的单笔简单记账允许免确认。当前代码若对简单账单返回 `true`，应修正为不确认。

免确认只适用于满足全部条件的情况：

- 单笔账单
- 金额明确
- 没有删除、覆盖、批量修改含义
- 没有明显的多账本或多资产歧义

### 4.3 参数校验

不要相信模型生成的参数。

为工具增加统一校验能力，可扩展 `AgentTool`：

```kotlin
fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult
```

至少校验：

- 必填参数
- 字符串是否为空
- 金额、limit 和 ID 范围
- `timeRangeKey` 枚举
- `bookName`、`assetName`、`categoryName` 是否真实存在
- `billId` 是否存在且属于正确账本
- `pref.set` 只能访问白名单 key，禁止任意 SharedPreferences 写入

校验失败时：

- 信息可由用户补充：返回 `agent.clarify`
- 模型参数错误：返回可理解的错误，不执行工具
- 不允许自动猜测删除目标

### 4.4 结构化回复安全

第二次 LLM 调用只能润色，不能改变事实。

要求：

- Prompt 明确声明金额、日期、数量、ID 只能来自 `facts`。
- 对写操作优先使用本地模板，不必每次调用第二次 LLM。
- LLM 润色失败时使用 `toolResult.userMessage`。
- 不把 API Key、密码、Token、完整敏感配置放入 Prompt 或日志。

## 五、实现 Skill 层

### 5.1 新建接口

建议新增：

```text
chat/agent/skill/AgentSkill.kt
chat/agent/skill/AgentSkillRegistry.kt
chat/agent/skill/AgentSkillRouter.kt
chat/agent/skill/BuiltInAgentSkills.kt
```

参考接口：

```kotlin
interface AgentSkill {
    val id: String
    val displayName: String
    val description: String
    val toolIds: Set<String>
    val routingExamples: List<String>

    fun buildInstructions(context: AgentSessionContext): String = ""
}
```

注册表必须检查：

- Skill ID 不重复
- Skill 引用的 tool ID 已注册
- 一个工具可以属于多个 Skill
- `general` 始终可用

### 5.2 首批 Skill

#### general

```text
chat.reply
agent.clarify
agent.list_capabilities
nav.open_stats
```

#### bill

```text
bill.list_recent
bill.list_by_date
bill.search
bill.get_detail
bill.create_from_text
bill.modify_by_instruction
bill.delete
```

#### stats

```text
stats.query_category
stats.query_spending
stats.query_month_summary
stats.query_existence
bill.search
bill.list_by_date
```

#### asset_book

```text
asset.list
asset.get_balance
asset.count
asset.get_net_worth
book.get_current
book.list
```

#### settings

```text
pref.get
pref.set
```

#### backup

暂时可为空或不注册。不要为了填充 Skill 创建假的工具。

### 5.3 Skill 路由

首轮路由只负责选择能力域，不执行操作。

推荐输出：

```json
{
  "skills": ["bill"],
  "reason": "用户要修改最近一笔账单"
}
```

要求：

- 简单请求最多选择 1 个主要 Skill。
- 明确跨域请求最多选择 3 个 Skill。
- 无法判断时使用 `general`。
- 本地规则可以处理非常明确的路由，但不能重新引入庞大的关键词业务分流。
- 路由失败时降级为注入全部现有 23 个工具，不能让请求直接失败。

为了控制 API 调用次数，可以采用以下任一方案：

1. 一个轻量路由 LLM 调用，然后工具选择调用。
2. 单次 LLM 同时输出 Skill 和第一个工具，但 Prompt 中只提供 Skill 摘要；若需要，再进入对应 Skill。
3. 对已知上下文延续请求复用上轮 Skill。

请结合现有 API 结构选择改动最小、可测试的方案，并在代码注释或文档中说明。

## 六、动态 Prompt

当前 `AgentPromptBuilder` 手写列举工具，应改为从注册表和 Skill 动态生成。

目标：

```kotlin
AgentPromptBuilder.buildSystemPrompt(
    context = context,
    selectedSkills = selectedSkills,
    tools = selectedTools
)
```

Prompt 包含：

- Agent 总规则
- 当前账本
- 紧凑资产、分类和账本信息
- 当前会话最近实体引用
- 已选择 Skill 的专用说明
- 当前允许使用的工具 JSON Schema
- 风险和确认规则
- 严格 JSON 输出协议

禁止：

- 在 Prompt 中继续维护一份与注册表重复的硬编码工具清单
- 将全部历史账单放入上下文
- 将余额等实时数据直接当作可信事实注入并让模型回答；实时数字仍应通过工具读取
- 将敏感配置注入模型

## 七、会话记忆与引用

扩展 `AgentSessionContext` 或新增 `AgentConversationState`：

```kotlin
data class AgentConversationState(
    val activeSkillIds: Set<String>,
    val lastToolId: String?,
    val recentBillIds: List<Long>,
    val recentAssetIds: List<Long>,
    val recentBookNames: List<String>,
    val pendingAction: PendingAgentAction?
)
```

至少支持：

```text
“刚才那笔改成40”
“删除上一笔”
“那个账户余额呢”
“换成旅行账本查”
“和上个月比呢”
```

要求：

- 引用必须解析为真实实体后才能执行。
- 删除、批量修改等危险操作不能仅依赖模糊引用直接执行。
- 会话切换后状态隔离。
- 状态应有数量和时间上限，避免无限增长。

## 八、多步调用

实现受控的多步执行，不要做无限自主循环。

模型协议建议：

```json
{
  "calls": [
    {
      "tool": "stats.query_category",
      "params": {
        "categoryName": "餐饮",
        "timeRangeKey": "this_month"
      }
    },
    {
      "tool": "stats.query_category",
      "params": {
        "categoryName": "餐饮",
        "timeRangeKey": "last_month"
      }
    }
  ],
  "response_goal": "比较本月与上月餐饮支出"
}
```

约束：

- 最多 5 步。
- 每一步执行前单独校验。
- 任一步需要确认时暂停整条链，保存剩余步骤。
- 任一步失败时停止后续写操作。
- 只读步骤可顺序执行并汇总 facts。
- 不要自动重试写操作。
- 同一链中存在多个危险操作时，预览必须完整说明影响。

优先支持：

1. 本月与上月分类支出比较
2. 先搜索账单，再修改目标账单
3. 先查询最近账单，再删除指定账单
4. 指定账本后执行统计查询

## 九、错误与降级

统一定义错误类型，例如：

```text
INVALID_PARAMS
NOT_FOUND
AMBIGUOUS
CONFIRMATION_REQUIRED
PERMISSION_REQUIRED
NETWORK_ERROR
TOOL_EXECUTION_ERROR
MODEL_PROTOCOL_ERROR
```

要求：

- 模型返回 Markdown 包裹 JSON 时可以兼容提取。
- JSON 解析失败允许进行一次修复或重试。
- 未知工具不能执行。
- 工具失败后不得让模型声称操作成功。
- 第二次 LLM 失败不影响已经成功的本地工具结果。
- 网络不可用时，本地不依赖模型的确认/取消流程仍应工作。

## 十、暂时不要做

本阶段不要：

- 创建“账单大师”“资产大师”等独立人格 Agent
- 重写已有 DAO、Service 或业务逻辑
- 一次性实现全部 120+ 工具
- 让模型直接执行 SQL
- 让模型直接写 SharedPreferences 任意 key
- 让模型自行跳过确认
- 删除现有手动 UI
- 引入重量级 Agent 框架

## 十一、下一批工具规划

完成 Phase 0.5 和 Skill 架构后，再优先增加以下工具。

### P1：补齐高频查询与导航

```text
stats.query_asset_spending
stats.query_latest_bill
stats.query_year_summary
stats.query_compare_period
stats.query_top_categories
stats.open_page
asset.query_spending
asset.open_detail
book.query_overview
calendar.query_day
calendar.open
nav.open_home
nav.open_assets
nav.open_settings
```

### P2：完整账单操作

```text
bill.edit
bill.delete_batch
bill.move_to_book
bill.toggle_exclude_stats
bill.restore_from_bin
bill.refund
bill.create_transfer
bill.create_repayment
```

### P3：资产、账本、分类管理

```text
asset.create/edit/archive/unarchive/adjust_balance
book.switch/create/rename/set_default
category.list_expense/list_income/create/rename
```

### P4：设置、权限与备份

```text
display.*
gesture.*
perm.*
system.*
backup.*
cloud.*
currency.*
ai.*
```

所有新增工具必须复用现有业务入口，不能复制业务逻辑。

## 十二、测试要求

至少新增或完善以下测试：

1. 简单记账免确认
2. 普通 WRITE 操作需要确认
3. DESTRUCTIVE 操作必须确认
4. 确认后执行原始参数，不重新调用 LLM 生成参数
5. 取消后不执行工具
6. pending action 超时
7. 不同 conversation 的 pending action 隔离
8. Skill 路由到 bill
9. Skill 路由到 stats
10. 跨域请求选择多个 Skill
11. Prompt 只包含所选 Skill 的工具
12. 未知工具被拒绝
13. 非法参数被拒绝
14. 模糊删除请求要求澄清
15. 多步调用不超过 5 步
16. 多步中途失败停止后续写操作
17. 第二次 LLM 失败后使用本地回复
18. `pref.set` 白名单保护

测试尽量使用 fake tool、fake model client 或注入接口，避免真实网络请求。

## 十三、工程要求

- 遵循现有 Kotlin 风格。
- 优先复用现有 Service、DAO、Prefs 门面和 Activity。
- 不修改与本任务无关的代码。
- 不覆盖工作区已有未提交改动。
- 新增抽象必须有明确用途，避免过度设计。
- 日志不能包含 API Key、密码或完整敏感参数。
- 编译必须通过：

```text
./gradlew :app:compileDebugKotlin
```

- 运行相关单元测试，并报告无法运行的测试及原因。

## 十四、执行顺序

请按以下顺序直接实施：

1. 阅读现有 Agent 和 Pipeline 代码。
2. 输出简短的现状诊断及将修改的文件清单。
3. 修正确认逻辑并完成 pending action 闭环。
4. 增加参数校验和错误类型。
5. 实现 Skill 接口、注册表和路由。
6. 将 Prompt 改为动态工具注入。
7. 增加会话引用状态。
8. 实现最多 5 步的受控链式调用。
9. 添加测试。
10. 编译并运行测试。
11. 更新 `docs/AGENT_TOOLS.md` 或新增架构说明。
12. 最后列出修改内容、测试结果和仍未实现的能力。

不要在完成分析后停下。除非遇到无法从代码库判断的破坏性决策，否则请直接实现并验证。

## 十五、验收用例

以下用例必须能正确处理：

```text
本月餐饮花了多少
和上个月比呢
微信还有多少钱
最近三笔账单
刚才那笔改成40
删除上一笔
确认
算了
法国账本昨天买过红酒吗
关闭震动
你能做什么
```

重点验收：

- 数字只来自工具结果。
- 删除不会因模糊引用直接执行。
- 确认后参数不会被模型重新解释。
- 上下文引用在同一会话有效，在不同会话隔离。
- Prompt 中不会始终携带全部工具。

