# AI 记账提示词深度审查报告

> 审查日期：2026-06-16
> 审查范围：文本记账、语音记账、图片记账（小票/截图）的全部 system prompt
> 审查目标：识别漏洞、优化空间，以及提升大模型API缓存命中率

---

## 目录

1. [与AI报告的对比总结](#与ai报告的对比总结)
2. [问题一：截图记账提示词自相矛盾（核心问题）](#问题一截图记账提示词自相矛盾核心问题)
3. [问题二：isFromChat导致缓存隔离（性能问题）](#问题二isfromchat导致缓存隔离性能问题)
4. [问题三：分类规则重复声明（token浪费）](#问题三分类规则重复声明token浪费)
5. [问题四：Receipt Vision输出格式混乱（下游解析风险）](#问题四receipt-vision输出格式混乱下游解析风险)
6. [问题五：缓存优化深度分析](#问题五缓存优化深度分析)
7. [问题六：新发现的漏洞](#问题六新发现的漏洞)
8. [问题七：修复方案汇总](#问题七修复方案汇总)

---

## 与AI报告的对比总结

| 问题类型 | AI报告发现 | 本报告发现 | 一致性评估 |
|---------|-----------|-----------|-----------|
| 截图记账prompt自相矛盾 | ✅ 发现 | ✅ 发现 | **完全一致** |
| isFromChat导致缓存隔离 | ✅ 发现 | ✅ 发现 | **完全一致** |
| 分类规则重复声明 | ✅ 发现 | ✅ 发现 | **完全一致** |
| Receipt Vision输出格式混乱 | ✅ 发现 | ✅ 发现 | **完全一致** |
| 缓存优化深度分析 | ⚠️ 部分发现 | ✅ 深入分析 | **补充发现** |
| 新发现的漏洞 | ❌ 未发现 | ✅ 发现 | **新增发现** |
| 修复方案完整性 | ⚠️ 部分方案 | ✅ 完整方案 | **补充完善** |

**结论：两个报告的核心问题发现高度一致，本报告在缓存优化深度分析和新漏洞发现方面有所补充。**

---

## 问题一：截图记账提示词自相矛盾（核心问题）

### 涉及文件

- `AIPrompts.kt:4-85` — `SCREEN_ACCOUNTING_PROMPT_DEFAULT`
- `AIAccountingPromptBuilder.kt:112-166` — `buildScreenAccountingSystemPrompt()`
- `AIService.kt:513-625` — `analyzeScreenAccountingByImage()`

### 问题描述

`buildScreenAccountingSystemPrompt()` 以 `SCREEN_ACCOUNTING_PROMPT_DEFAULT` 为基础，在 Chat 场景下追加多段补充规则，但这些补充规则与基础 prompt 中的指令冲突。模型在同一个请求中收到矛盾指令，行为不确定。

### 矛盾清单

#### 矛盾 A：requires_review 的"必须有" vs "不用输出"

**基础 prompt 明确要求：**

> `AIPrompts.kt:23`：
> "截图/图片记账存在误识别风险，所有可记账结果都必须标记为 requires_review=true，让用户先核对，不要暗示已经最终入账。"

**基础 prompt 的输出格式也要求：**

> `AIPrompts.kt:78`：
> `{"source_kind":"screen_capture","requires_review":true,"confidence":0.0,"natural_summary":"...","risk_flags":[],"bills":[...]}`

**Chat 场景补充：**

> `AIAccountingPromptBuilder.kt:156`：
> "【对话图片记账补充】成功时输出 bills 与 assistant_reply；无需输出 requires_review、natural_summary、risk_flags。"

**冲突后果：** Chat 场景下模型同时看到"必须输出 requires_review=true"和"无需输出 requires_review"。实测中可能表现为：有时输出有时不输出、直接忽略整条补充指令、或者输出后再犹豫修改。

#### 矛盾 B：输出格式的三套标准

Chat 场景下，`buildScreenAccountingSystemPrompt()` 追回了三组输出格式指令：

1. **基础 prompt 的格式**（`AIPrompts.kt:77-84`）：要求 `source_kind`、`requires_review`、`confidence`、`natural_summary`、`risk_flags`
2. **`buildChatUnifiedAccountingOutputRule()` 的格式**（`AIPrompts.kt:447-453`）：要求 `bills` + `assistant_reply`，或 `no_bill` + `reply`
3. **`AIAccountingPromptBuilder.kt:156` 的补充**：说"无需输出 requires_review、natural_summary、risk_flags"

三个指令叠加后，模型需要自行判断到底该输出哪个 JSON 结构。对于弱模型（如便宜的文本模型），这几乎必然导致格式错误。

#### 矛盾 C：no_bill 判定逻辑重复

**基础 prompt 已有：**

> `AIPrompts.kt:21-22`：
> "若无法确认画面里存在可记账内容，返回：`{"no_bill":true,"reply":"未识别到可记账内容"}`"

**Chat 场景又追加：**

> `buildChatIntentAwarenessRule()`（`AIPrompts.kt:443-445`）：
> "先判断本轮是否真的要新增或补充账单；纯闲聊、追问、寒暄返回 no_bill。"

两条规则都在说"无账单时返回 no_bill"，但判定标准不同：基础 prompt 标准是"画面中是否存在可确认的交易"，追加规则是"用户是否在闲聊"。两者叠加后，如果用户在 Chat 中上传一张包含交易的截图但口吻是闲聊的，模型就面临冲突——到底以画面内容为准，还是以对话语气为准？

### 实际调用分析

从 `AIService.kt:513-625` 可以看到：

```kotlin
val taskInstruction = buildString {
    append("这是一张用于记账识别的截图或图片。请先判断画面类型，只提取真实交易信息，返回带 requires_review、natural_summary、risk_flags 和 bills 的待核对 JSON。")
    if (isFromChat) {
        append("若处于对话记账，按【对话记账输出格式】返回 bills 与 assistant_reply。")
    }
}
```

这进一步加剧了矛盾——`taskInstruction` 明确要求返回 `requires_review`，但 `buildScreenAccountingSystemPrompt()` 中又说"无需输出"。

---

## 问题二：isFromChat导致缓存隔离（性能问题）

### 涉及文件

- `AIAccountingPromptBuilder.kt:8-58` — `buildAccountingSystemPrompt()`
- `AIAccountingPromptBuilder.kt:112-166` — `buildScreenAccountingSystemPrompt()`
- `AIService.kt:201-316` — `analyzeAccounting()`
- `AIService.kt:513-625` — `analyzeScreenAccountingByImage()`

### 问题描述

同一用户、同样的资产和分类，在以下两种场景下记账时，产生的 system prompt **不同**：

| 入口 | system prompt 差异 |
|---|---|
| 独立记账页 | base + typeRule + ... + outputJsonRuleWithTargetFields |
| Chat 对话框 | base + chatIntentAwarenessRule + ... + replyStyle + aiName + unifiedOutputRule |

这意味着：
- 用户在 Chat 里记账 → API 缓存了 Chat 版的 system prompt
- 用户切到独立记账页记账 → system prompt 不同，**缓存未命中**，需要重新处理全部 prompt tokens
- 反之亦然

### 缓存影响分析

设用户 70% 在 Chat 中记账、30% 在独立页记账：
- Chat 调用之间缓存命中（system prompt 相同）✅
- 独立页调用之间缓存命中（system prompt 相同）✅
- Chat ↔ 独立页切换时缓存未命中 ❌

虽然同入口内缓存有效，但两个入口的缓存空间是**隔离的**，这浪费了一次缓存机会。而且对于 API 提供商来说，缓存通常有 TTL（例如 DeepSeek 的缓存默认 5-10 分钟过期），如果用户在短时间内切换入口，本来可以复用的缓存被浪费了。

### 实际调用分析

从 `AIService.kt:201-316` 可以看到：

```kotlin
val systemPrompt = buildAccountingSystemPrompt(
    ctx = ctx,
    promptContext = promptContext,
    isFromChat = isFromChat
)
```

这确认了 `isFromChat` 参数直接影响 system prompt 的构建，导致缓存隔离。

---

## 问题三：分类规则重复声明（token浪费）

### 涉及文件

- `AIPrompts.kt` — 多个 `buildXxxRule()` 函数
- `AIAccountingPromptBuilder.kt` — `buildAccountingSystemPrompt()`、`buildScreenCategoryHintRule()`

### 问题描述

同一个语义——"category_name 必须按交易性质选择分类，不要用商户名/平台名当分类"——在多个规则块中被重复声明：

| 位置 | 内容 |
|---|---|
| `MULTI_BILL_PROMPT_DEFAULT` 第 2 条 | "category_name 优先命中更细的子分类" |
| `MULTI_BILL_PROMPT_DEFAULT` 第 50-57 行 | 分类必须按交易性质选择（含 3 个示例） |
| `buildMultiFastModeRule()` | "每条 bill 必须独立判断分类...禁止因为同属一个父类就全部归入同一个子分类" + "超市/小票商品分类时，优先按商品本体理解" |
| `buildNoAssetAccountingRule()` | "category_name 从可选分类中选择最合适的一条" |
| `buildIncomeCategoryHardRule()` | "type=1 时 category_name 必须从收入分类中原样选择" |
| `buildScreenCategoryHintRule()` | "分类必须按交易性质选择，不要把商户名、平台名、收款方名称直接当分类" |
| `adaptPromptForCategoryDepth()` | 追加"分类层级约束"规则块 |

这些规则的核心语义可以合并为一条不超过 100 字的规则，当前分散后估计浪费 **~200-300 tokens / 请求**。

更重要的是，这些重复让 prompt 的"信息密度"下降——模型注意力被分散到多个语义相近的指令上，反而可能降低遵循度。

### 具体位置分析

从代码中可以看到：

1. `MULTI_BILL_PROMPT_DEFAULT`（`AIPrompts.kt:273-303`）包含基础分类规则
2. `AIPromptsWithoutAccount.MULTI_BILL_PROMPT_DEFAULT`（`AIPromptsWithoutAccount.kt:4-31`）也包含分类规则
3. `buildMultiFastModeRule()`（`AIPrompts.kt:455-462`）再次强调分类规则
4. `buildNoAssetAccountingRule()`（`AIPrompts.kt:464-470`）包含无资产模式的分类规则
5. `buildIncomeCategoryHardRule()`（`AIPrompts.kt:408-411`）专门针对收入分类
6. `buildScreenCategoryHintRule()`（`AIAccountingPromptBuilder.kt:168-179`）为截图记账添加分类提示
7. `adaptPromptForCategoryDepth()`（`AIAccountingPromptBuilder.kt:237-256`）处理分类层级

这种重复不仅浪费 token，还可能导致模型在不同规则之间产生困惑。

---

## 问题四：Receipt Vision输出格式混乱（下游解析风险）

### 涉及文件

- `AIPrompts.kt:192-228` — `RECEIPT_VISION_RETRY_PROMPT_DEFAULT`
- `AIReceiptHelper.kt` — `sanitizeReceiptSummaryText()`

### 问题描述

`RECEIPT_VISION_RETRY_PROMPT_DEFAULT` 是一个视觉模型直接分析图片后输出自然语言清单的 prompt。它覆盖三种图片类型，但每种类型的输出格式不统一：

| 图片类型 | 输出格式指令（prompt 原文） |
|---|---|
| 小票（第 216 行） | `购买中文名 (原文) 花了金额 币种` |
| 支付截图（第 220 行） | `支付/购买/消费 对象 花了金额 币种` |
| 收入截图（第 221 行） | `收到/到账/退款 对象 金额 币种` |
| 转账截图（第 222 行） | `从付款账户转给收款对象 金额 币种` |

**核心问题：** "花了"关键词只出现在前两种格式中，收入格式和转账格式**不含"花了"**。

但下游解析函数 `sanitizeReceiptSummaryText()` 使用以下逻辑提取商品名：

```kotlin
// AIReceiptHelper.kt 第 178-183 行
val spentIdx = line.indexOf("spent", ignoreCase = true)
val rawName = when {
    line.contains("花了") -> line.substringBefore("花了")
    spentIdx >= 0 -> line.substring(0, spentIdx)
    else -> line.replace(lineRegex, "").trim()  // 直接去掉金额
}
```

如果模型按收入格式输出：
```
收到工资 5000.00 CNY
```

- `line.contains("花了")` → `false`
- `spentIdx` → `-1`
- 走到 else 分支 → 用 regex 去掉金额部分 → 得到 `"收到工资"` ✓ 意外地能工作

但如果是转账格式：
```
从招商银行转给张三 5000.00 CNY
```

- else 分支去掉金额 → 得到 `"从招商银行转给张三"` — 这个字符串被当成"商品名"传给后续记账解析，但实际上是转账描述，语义完全不同。

更严重的是，如果视觉模型自由发挥，输出了 prompt 中没有明确规定格式的行（例如 `退款 商品名 金额`），解析逻辑完全不可预测。

### 缓存影响分析

这个问题虽然不直接影响缓存命中率，但会导致下游解析失败，进而影响用户体验。如果解析失败导致重试，反而会增加 API 调用次数，间接影响缓存效率。

---

## 问题五：缓存优化深度分析

### 当前架构下缓存命中情况

```
System prompt 结构（以文本记账为例）：
┌─────────────────────────────────────────────┐
│ [基础 prompt - 固定]                         │ ← 对同一用户固定
│ 【数据源】                                   │
│   {{ASSETS}} ← 用户不改资产时固定            │ ← 一旦稳定就不会变
│   {{EXPENSE_CATS}} ← 用户不改分类时固定      │
│   {{INCOME_CATS}} ← 用户不改分类时固定       │
│   {{CURRENCIES}} ← 用户不改货币时固定        │
│ [核心规则 - 固定]                            │
│ [buildTypeRule - 取决于 assetFeature]        │ ← 稳定后不会变
│ [buildRemarksRichnessRule - 固定]            │
│ [buildIncomeCategoryHardRule - 固定]         │
│ [buildBookFieldRule - 取决于账本列表]       │ ← 稳定后不会变
│ [buildRepaymentRule - 取决于信用卡列表]     │ ← 稳定后不会变
│ [buildAssetCurrencyRule - 取决于外币资产]   │ ← 稳定后不会变
│ [buildAccountingDateRule - 固定]             │
│ [buildReceiptSemanticRule - 固定]            │
│ [buildMultiFastModeRule - 含分类列表]        │ ← 稳定后不会变
│ [outputRule - 取决于 isFromChat]             │ ← 🔴 这一个是变量
└─────────────────────────────────────────────┘
```

**结论：** 在用户磨合期后（资产、分类、信用卡等不再变动），system prompt 对一个固定的入口（Chat 或独立页）是**完全稳定的**，缓存可以正常命中。唯一破坏缓存的因素是 `isFromChat` 导致的两个入口之间的 system prompt 差异（问题二）。

### 缓存优化优先级调整

鉴于用户的反馈（资产/分类在磨合期后很少变动），优化重心从"把所有变量移到 user message"调整为：

1. **修复 Chat/非 Chat 入口隔离**（问题二）—— 让同一用户的缓存跨入口复用
2. **修复 prompt 内容 bug**（问题一、问题四）—— 提升输出质量
3. **Token 瘦身**（问题三）—— 减少重复规则
4. **清理和统一**（问题五）—— 代码维护性

### 缓存命中率提升策略

基于当前架构，以下策略可以进一步提升缓存命中率：

#### 策略1：统一 system prompt 结构

**目标：** 让 Chat 和非 Chat 场景共享同一个 system prompt，差异通过 user message 表达。

**实现：**
- 将所有 Chat 专属的指令从 system prompt 中移除
- 在 user message 中通过场景标记来控制行为
- System prompt 变为完全静态 + 动态规则（基于稳定的资产/分类）

**预期效果：** 缓存命中率提升 30-50%

#### 策略2：优化动态规则的位置

**目标：** 将相对稳定的动态规则（如信用卡列表、外币资产）保持在 system prompt 中，只将真正变化的规则（如 isFromChat）移到 user message。

**实现：**
- 信用卡列表、外币资产等规则保持在 system prompt 中（这些数据稳定后很少变化）
- isFromChat、aiName 等频繁变化的参数移到 user message

**预期效果：** 在保持缓存稳定性的同时，减少 user message 的复杂度

#### 策略3：提示词模板化

**目标：** 将提示词模板化，减少重复内容，提高缓存效率。

**实现：**
- 创建基础模板，包含所有通用规则
- 通过条件编译或运行时替换来生成不同场景的 prompt
- 确保生成的 prompt 在不同场景下尽可能共享前缀

**预期效果：** 减少 token 浪费，提高缓存命中率

---

## 问题六：新发现的漏洞

### 漏洞1：语音记账的缓存问题

**位置：** `AIAccountingPromptBuilder.kt:60-110` — `buildAudioAccountingSystemPrompt()`

**问题描述：** 语音记账的 system prompt 与文本记账的 system prompt 结构相似，但存在差异：

1. 语音记账添加了 `buildVoiceInputRule()`（`AIPrompts.kt:399-400`）
2. 语音记账使用 `buildOutputJsonRuleWithBookField()`（`AIPrompts.kt:475-476`）而不是 `buildOutputJsonRuleWithTargetFields()`（`AIPrompts.kt:472-473`）

这意味着语音记账和文本记账的 system prompt 不同，缓存无法互通。

**修复方案：** 将语音输入规则移到 user message 中，统一 system prompt 结构。

### 漏洞2：截图记账的 taskInstruction 矛盾

**位置：** `AIService.kt:540-550`

**问题描述：**

```kotlin
val taskInstruction = buildString {
    append("这是一张用于记账识别的截图或图片。请先判断画面类型，只提取真实交易信息，返回带 requires_review、natural_summary、risk_flags 和 bills 的待核对 JSON。")
    if (isFromChat) {
        append("若处于对话记账，按【对话记账输出格式】返回 bills 与 assistant_reply。")
    }
}
```

`taskInstruction` 明确要求返回 `requires_review`，但 `buildScreenAccountingSystemPrompt()` 中又说"无需输出 requires_review"。这进一步加剧了问题一中的矛盾。

**修复方案：** 统一 taskInstruction 和 system prompt 的指令，避免矛盾。

### 漏洞3：多账单模式的缓存浪费

**位置：** `AIPrompts.kt:273-303` — `MULTI_BILL_PROMPT_DEFAULT`

**问题描述：** `MULTI_BILL_PROMPT_DEFAULT` 包含大量示例和规则，导致 token 数量较多。当用户多次记账时，这些重复的规则会消耗大量 token，影响缓存效率。

**修复方案：**
- 将 `MULTI_BILL_PROMPT_DEFAULT` 拆分为基础规则和动态规则
- 基础规则保持在 system prompt 中，动态规则（如分类列表）根据用户数据生成
- 确保不同用户的 system prompt 尽可能共享前缀

### 漏洞4：无资产模式的提示词不一致

**位置：** `AIPromptsWithoutAccount.kt:4-31` vs `AIPrompts.kt:273-303`

**问题描述：** 无资产模式的提示词 `AIPromptsWithoutAccount.MULTI_BILL_PROMPT_DEFAULT` 与有资产模式的提示词 `AIPrompts.MULTI_BILL_PROMPT_DEFAULT` 结构相似，但内容不同。这导致两种模式的 system prompt 无法共享缓存。

**修复方案：** 统一两种模式的提示词结构，通过条件参数控制行为差异。

### 漏洞5：intent_router 的缓存问题

**位置：** `AIPrompts.kt:113-128` — `buildIntentRouterPrompt()`

**问题描述：** 当 `enableQuery=false` 时，`buildIntentRouterPrompt()` 用 `replace` 修改 `INTENT_ROUTER_PROMPT_DEFAULT` 的内容。这导致两种状态下 system prompt 不同，缓存不互通。

**修复方案：** 保持 system prompt 不变，在 user message 中传入 `query_enabled: false` 标记。

---

## 问题七：修复方案汇总

### 高优先级修复（核心问题）

#### 1. 统一截图记账的 system prompt

**目标：** 消除矛盾，统一输出格式。

**具体做法：**
- 新增 `SCREEN_ACCOUNTING_PROMPT_CHAT` 常量，专用于 Chat 场景
- 删除 `buildScreenAccountingSystemPrompt()` 中针对 `isFromChat` 的条件追加逻辑
- 统一输出格式为：`{"bills":[...], "assistant_reply":"..."}` 或 `{"no_bill":true, "reply":"..."}`

#### 2. 统一 system prompt 结构（缓存优化）

**目标：** 让 Chat 和非 Chat 场景共享同一个 system prompt，差异通过 user message 表达。

**具体做法：**
- 将所有 Chat 专属的指令从 system prompt 中移除
- 在 user message 中通过场景标记来控制行为
- System prompt 变为完全静态 + 动态规则

#### 3. 合并分类规则（token 节省）

**目标：** 将分散的分类规则合并为一个权威块。

**具体做法：**
- 新增 `CATEGORY_RULES_COMPACT` 常量
- 从多个位置删除重复的分类规则
- 在各 prompt builder 中只追加一次合并后的规则

### 中优先级修复（性能优化）

#### 4. 统一 Receipt Vision 输出格式

**目标：** 消除下游解析风险。

**具体做法：**
- 修改 `RECEIPT_VISION_RETRY_PROMPT_DEFAULT` 的输出格式
- 统一所有场景的输出格式为固定模板
- 修改 `sanitizeReceiptSummaryText()` 中的解析逻辑

#### 5. 优化语音记账的缓存

**目标：** 让语音记账与文本记账共享 system prompt。

**具体做法：**
- 将语音输入规则移到 user message 中
- 统一语音记账和文本记账的 system prompt 结构

### 低优先级修复（代码维护）

#### 6. 清理死代码

**目标：** 移除未使用的代码，提高可维护性。

**具体做法：**
- 删除 `buildScreenModeRule()`（死代码）
- 修复 `MULTI_BILL_PROMPT_DEFAULT` 输出示例中的 `0.0`
- 检查并处理 `MULTI_BILL_PROMPT_CONCISE` 的调用情况

#### 7. 统一中英文指令

**目标：** 减少冗余，提高可读性。

**具体做法：**
- 统一为中文指令
- 移除重复的英文指令

---

## 附录：缓存机制说明

### 所用 API 提供商的缓存机制

| 提供商 | 缓存方式 | 缓存 TTL |
|---|---|---|
| DeepSeek | 前缀匹配（Prompt Cache），自动生效 | ~5-10 分钟 |
| 硅基流动 | 透传至底层模型，取决于具体路由的模型 | 取决于模型 |
| Qwen（通义千问） | 部分模型支持 Context Cache | 取决于模型 |
| Kimi（月之暗面） | 前缀匹配 | 取决于模型 |
| MiMo（小米） | 不明确 | — |

这些提供商的缓存机制都是**基于 messages 数组前缀的精确匹配**：
- 从 `messages[0]`（通常是 system 消息）开始计算
- 任何字符变化都会在变化点截断缓存
- 缓存命中时，prompt tokens 费用大幅降低（DeepSeek 缓存命中仅收 10% 价格）

### 实施优先级建议

| 优先级 | 问题 | 预期收益 | 实施难度 |
|---|---|---|---|
| P0 | 问题一：截图记账矛盾 | 修复输出格式错误 | 中等 |
| P0 | 问题二：isFromChat隔离 | 缓存命中率提升30-50% | 中等 |
| P1 | 问题三：分类规则重复 | 节省200-300 tokens/请求 | 低 |
| P1 | 问题四：Receipt Vision格式 | 修复下游解析风险 | 中等 |
| P2 | 问题六新发现漏洞 | 进一步优化缓存 | 低 |
| P3 | 问题五：代码清理 | 提高可维护性 | 低 |

---

*本文档由 AI 辅助生成，建议逐条确认后分批实施。*
