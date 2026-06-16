# AI记账提示词修改指南

> 基于《AI记账提示词审查报告》和《AI记账提示词深度审查报告》
> 编写日期：2026-06-16

---

## 一、问题汇总

| 优先级 | 问题 | 影响 | 涉及文件 |
|--------|------|------|----------|
| P0 | 截图记账prompt自相矛盾 | 输出格式错误，模型行为不确定 | AIPrompts.kt, AIAccountingPromptBuilder.kt, AIService.kt |
| P0 | isFromChat导致缓存隔离 | 缓存命中率降低30-50% | AIAccountingPromptBuilder.kt, AIService.kt |
| P1 | 分类规则重复声明 | 浪费200-300 tokens/请求 | AIPrompts.kt, AIAccountingPromptBuilder.kt |
| P1 | Receipt Vision输出格式混乱 | 下游解析风险，转账描述被当商品名 | AIPrompts.kt, AIReceiptHelper.kt |
| P2 | 语音记账缓存问题 | 与文本记账缓存不互通 | AIAccountingPromptBuilder.kt, AIPrompts.kt |
| P2 | intent_router缓存问题 | 不同状态缓存不互通 | AIPrompts.kt |
| P3 | 死代码、示例数字误导、中英混杂 | 代码维护性差 | AIPrompts.kt |

---

## 二、修改优先级与实施顺序

### 第一阶段：核心问题修复（P0）

#### 1. 统一截图记账的system prompt

**目标：** 消除矛盾，让Chat和非Chat场景共享同一个system prompt

**修改步骤：**

1. **新增`SCREEN_ACCOUNTING_PROMPT_CHAT`常量**
   - 文件：`AIPrompts.kt`
   - 位置：在`SCREEN_ACCOUNTING_PROMPT_DEFAULT`之后
   - 内容：基于DEFAULT版本，移除requires_review、natural_summary、risk_flags等字段，内联Chat场景的输出格式

2. **修改`buildScreenAccountingSystemPrompt()`**
   - 文件：`AIAccountingPromptBuilder.kt:112-166`
   - 删除针对`isFromChat`的条件追加逻辑（第120-122行、第147-157行）
   - 改为根据`isFromChat`选择不同的基础prompt

3. **修改`AIService.kt:540-550`的taskInstruction**
   - 删除对`requires_review`的要求
   - 统一为Chat场景的输出格式

**修改后效果：**
- Chat场景：使用`SCREEN_ACCOUNTING_PROMPT_CHAT`
- 非Chat场景：使用`SCREEN_ACCOUNTING_PROMPT_DEFAULT`
- 两个场景的输出格式统一，不再矛盾

#### 2. 统一system prompt结构（缓存优化）

**目标：** 让Chat和非Chat场景共享同一个system prompt，差异通过user message表达

**修改步骤：**

1. **修改`buildAccountingUserPrompt()`**
   - 文件：`AIAccountingPromptBuilder.kt`
   - 增加场景标记：`isFromChat`、`aiName`
   - 在user message中添加：
     ```
     【场景】对话记账模式/独立记账模式
     【你的名字】AI名称
     ```

2. **从`buildAccountingSystemPrompt()`中移除所有`if (isFromChat)`条件分支**
   - 文件：`AIAccountingPromptBuilder.kt:8-58`
   - System prompt变为完全静态+动态规则

3. **统一输出格式**
   - System prompt中统一输出格式为：
     ```
     成功记账：{"bills":[...]}
     无法记账：{"no_bill":true,"reply":"..."}
     （对话模式下额外输出assistant_reply；独立模式下不输出）
     ```

**修改后效果：**
- 同一用户的system prompt在任何入口下完全相同 → 缓存100%互通
- Chat特有的行为指令通过user message传递
- aiName不再影响system prompt

### 第二阶段：性能优化（P1）

#### 3. 合并分类规则

**目标：** 将分散的分类规则合并为一个权威块，节省200-300 tokens/请求

**修改步骤：**

1. **新增`CATEGORY_RULES_COMPACT`常量**
   - 文件：`AIPrompts.kt`
   - 内容：
     ```
     【分类规则（高优先）】
     1. category_name 只从可用分类列表中原样选择。
     2. 支出从支出分类中选，收入从收入分类中选。
     3. 分类必须基于交易"性质/用途"，不是商户名/平台名。
        示例：酒店→住宿，外卖→餐饮，API服务→软件/服务
     4. 优先命中子分类，格式为"一级 - 二级"。
     5. 多条账单必须逐条独立判断子分类，不得因同属一个父类而合并。
     6. 超市商品按商品本体分类：水果→水果类，蔬菜→蔬菜类，饼干→零食类。
     7. 无法判断时选"其他/其它"，无兜底类目时才留空。
     8. 收入分类禁止使用"收入""入账"等泛词。
     ```

2. **从以下位置删除分类相关规则**
   - `MULTI_BILL_PROMPT_DEFAULT` 第2条和第50-57行
   - `MULTI_BILL_PROMPT_DEFAULT`（无账户版）第3条和第8条
   - `SCREEN_ACCOUNTING_PROMPT_DEFAULT` 第50-57行
   - `buildMultiFastModeRule()` 中关于分类的3段
   - `buildNoAssetAccountingRule()` 中关于分类的部分
   - `buildIncomeCategoryHardRule()`（整体可删除）
   - `buildScreenCategoryHintRule()`（整体可删除）

3. **在各prompt builder中只追加一次`CATEGORY_RULES_COMPACT`**

#### 4. 统一Receipt Vision输出格式

**目标：** 消除下游解析风险

**修改步骤：**

1. **修改`RECEIPT_VISION_RETRY_PROMPT_DEFAULT`**
   - 文件：`AIPrompts.kt:192-228`
   - 统一所有场景的输出格式为固定模板：
     ```
     【输出格式（硬约束）】
     每行一条真实交易，格式固定为：
     方向 | 商品/对象 | 金额 | 币种 | 时间(可选) | 支付方式(可选)

     方向仅允许：支出、收入、转账、还款
     示例：
     - 支出 | 维也纳酒店 | 292.41 | CNY | 2026-06-15 14:30:00 | 招商银行
     - 收入 | 工资 | 15000.00 | CNY
     - 转账 | 从招商银行到工商银行 | 5000.00 | CNY
     - 还款 | 招商银行信用卡 | 2000.00 | CNY
     ```

2. **修改`sanitizeReceiptSummaryText()`**
   - 文件：`AIReceiptHelper.kt:178-183`
   - 新的解析逻辑：
     ```kotlin
     val parts = line.split("|").map { it.trim() }
     if (parts.size >= 4) {
         val direction = parts[0]  // 支出/收入/转账/还款
         val item = parts[1]       // 商品/对象
         val amount = parseReceiptPrice(parts[2])
         val currency = parts[3]
         // ...
     }
     ```

3. **同步修改OCR文本prompt**
   - 修改`RECEIPT_BILL_PROMPT`、`RECEIPT_BILL_PROMPT_CN`、`RECEIPT_BILL_PROMPT_FOREIGN`
   - 使用相同的`|`分隔格式

### 第三阶段：进一步优化（P2）

#### 5. 优化语音记账的缓存

**目标：** 让语音记账与文本记账共享system prompt

**修改步骤：**

1. **将语音输入规则移到user message中**
   - 文件：`AIAccountingPromptBuilder.kt:60-110`
   - 从system prompt中移除`buildVoiceInputRule()`
   - 在user message中添加语音输入相关的场景标记

2. **统一语音记账和文本记账的system prompt结构**
   - 使用`buildOutputJsonRuleWithTargetFields()`替代`buildOutputJsonRuleWithBookField()`

#### 6. 修复intent_router缓存问题

**目标：** 让intent_router在不同状态下共享缓存

**修改步骤：**

1. **保持system prompt不变**
   - 文件：`AIPrompts.kt:113-128`
   - 不再用`replace`修改`INTENT_ROUTER_PROMPT_DEFAULT`

2. **在user message中传入状态标记**
   ```
   【系统状态】
   Query 功能: 已禁用（查询类请求请走 GENERAL_CHAT）
   ```

### 第四阶段：代码清理（P3）

#### 7. 清理死代码和冗余

**修改步骤：**

1. **删除`buildScreenModeRule()`**
   - 文件：`AIPrompts.kt:478-492`
   - 全项目搜索确认无调用后删除

2. **修复`MULTI_BILL_PROMPT_DEFAULT`输出示例**
   - 文件：`AIPrompts.kt:302`
   - 将`0.0`改为有意义的示例数字`12.34`

3. **检查并处理`MULTI_BILL_PROMPT_CONCISE`**
   - 文件：`AIPrompts.kt:338-347`
   - 确认是否有调用路径，如果没有则标记为deprecated或删除

4. **统一中英文指令**
   - 文件：`AIPrompts.kt:472-496`
   - 统一为中文指令，移除重复的英文指令

---

## 三、修改验证

### 验证方法

1. **单元测试**
   - 为每个修改点编写单元测试
   - 验证prompt生成结果符合预期

2. **集成测试**
   - 测试Chat和非Chat场景的记账功能
   - 验证输出格式统一

3. **缓存测试**
   - 测试同一用户在不同入口的缓存命中情况
   - 验证缓存命中率提升

4. **回归测试**
   - 测试所有记账场景（文本、语音、截图）
   - 确保修改不引入新问题

### 验证指标

| 指标 | 目标值 | 测量方法 |
|------|--------|----------|
| 缓存命中率 | 提升30-50% | 对比修改前后的API调用日志 |
| Token消耗 | 减少200-300 tokens/请求 | 对比修改前后的token统计 |
| 输出格式正确率 | 100% | 抽样检查AI输出 |
| 下游解析成功率 | 100% | 检查AIReceiptHelper日志 |

---

## 四、实施注意事项

### 1. 分批实施

建议按优先级分批实施，每批完成后进行测试验证：
- 第一批：P0问题（截图记账矛盾、缓存隔离）
- 第二批：P1问题（分类规则重复、Receipt Vision格式）
- 第三批：P2问题（语音记账、intent_router）
- 第四批：P3问题（代码清理）

### 2. 向后兼容

- 修改输出格式时，确保旧版本的解析逻辑仍能处理新格式
- 在`AIReceiptHelper.kt`中添加兼容逻辑，支持新旧两种格式

### 3. 监控与回滚

- 在修改前后添加监控日志
- 准备回滚方案，如果发现问题可以快速回滚

### 4. 文档更新

- 修改完成后，更新相关文档
- 记录修改内容和验证结果

---

## 五、修改文件清单

| 文件 | 修改类型 | 优先级 |
|------|----------|--------|
| `AIPrompts.kt` | 新增常量、修改prompt、删除死代码 | P0-P3 |
| `AIAccountingPromptBuilder.kt` | 修改函数逻辑、移除条件分支 | P0-P2 |
| `AIService.kt` | 修改taskInstruction | P0 |
| `AIReceiptHelper.kt` | 修改解析逻辑 | P1 |

---

## 六、风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 修改后AI输出格式错误 | 中 | 高 | 充分测试，准备回滚方案 |
| 缓存命中率未提升 | 低 | 中 | 分析原因，调整优化策略 |
| 下游解析失败 | 低 | 高 | 添加兼容逻辑，支持新旧格式 |
| 引入新bug | 中 | 中 | 完善测试用例，分批实施 |

---

*本文档基于两份审查报告编写，建议逐条确认后分批实施。*
