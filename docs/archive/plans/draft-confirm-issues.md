# 待确认账单流程优化 — 问题记录与困惑

## 背景

项目是 Android 记账 App（敲敲记账），有一个「图片记账草稿确认」功能：

1. 用户在聊天页发小票图片
2. AI 识别图片返回结果
3. 弹窗展示给用户核对
4. 用户可以「确认入账」或「稍后再确认」
5. 「稍后再确认」保存到草稿箱（Inbox），用户以后再确认

## 原始流程（两次 AI 调用）

```
图片 → analyzeReceiptByImage（专门的票据 OCR 提示词）→ 自然语言文本
     → 弹窗展示文本
     → 用户点「确认入账」→ analyzeAccounting（记账提示词）→ 结构化 JSON → 入库
     → 用户点「稍后再确认」→ 存纯文本到 draft（不调 AI）
     → [以后] Inbox 确认 → analyzeAccounting → JSON → 入库
```

**问题**：AI 被调了两次。第一次只返回文本，第二次把同样的文本重新解析为 JSON。

## 尝试一：用 `analyzeScreenAccountingByImage` 替换第一次调用

**思路**：`analyzeScreenAccountingByImage` 直接返回 JSON，一次调用搞定。弹窗展示从 JSON 格式化的文本，后台持有 JSON。

**结果**：失败。

**原因**：
- `analyzeScreenAccountingByImage` 使用的是**通用记账提示词**（`IMAGE_ACCOUNTING_PROMPT`），针对截图/屏幕记账优化
- `analyzeReceiptByImage` 使用的是**专门的票据 OCR 提示词**（`RECEIPT_VISION_RETRY_PROMPT_DEFAULT`），针对小票/票据优化
- 用通用提示词识别小票，效果明显变差 — 漏读商品、金额错误、分类不准

**教训**：不能为了省一次调用而换掉专门优化过的提示词。

## 尝试二：修改 `analyzeReceiptByImage` 的提示词，让它同时返回文本和 JSON

**思路**：保持原来的票据 OCR 提示词，但在 prompt 里追加要求 AI 在回复末尾附上 ` ```json ``` ` 代码块。一次调用同时拿到文本和 JSON。

**结果**：失败。

**AI 返回的实际内容**：
```
根据图片内容，识别到以下可记账交易： 用了visa卡支付
1. 购买开胃菜（Kebab）：实付 4.59 PLN。 用了visa卡支付
2. 购买散装番茄：实付 0.52 PLN。 用了visa卡支付
```json 用了visa卡支付
{ 用了visa卡支付
"bills": [ 用了visa卡支付
"amount": 4.59, 用了visa卡支付
"type": 0, 用了visa卡支付
...
```

**原因**：
- 提示词里要求「每条交易写出用了xxx支付」
- AI 理解过度，在**每一行**都插入了「用了visa卡支付」
- 包括 JSON 代码块内部也被插入了额外文字
- JSON 解析失败

**尝试修复**：
1. 在 system prompt 加硬规则：「JSON 块内必须是纯 JSON，严禁插入额外文字」
2. 在 user prompt 加说明：「支付方式写在句子中，不要单独重复」
3. 给出完整的 JSON 示例

**修复后仍然失败** — AI 仍然在 JSON 块内插入额外文字。可能是模型对「不要做某事」的指令遵循不够强，尤其是在已经被告知「每条交易要写支付方式」的情况下。

**教训**：
- 让 AI 同时做两件事（生成自然语言 + 生成结构化 JSON）比只做一件更容易出错
- 「不要做X」的负向指令不如「只做Y」的正向指令可靠
- JSON 格式对额外文字零容忍，即使多一个字也会解析失败

## 最终方案：回到两次调用

```
图片 → analyzeReceiptByImage（票据 OCR 提示词）→ 自然语言文本
     → 弹窗展示文本
     → 用户点「确认入账」→ analyzeAccounting → JSON → 入库
     → 用户点「稍后再确认」→ 存文本到 draft
     → [以后] Inbox 确认 → analyzeAccounting → JSON → 入库
```

**改进点**（不改变调用次数，只改进体验）：
- Inbox 每笔账单单独一条 draft（不再合并）
- Inbox 展示为账单卡片（分类/金额/账户/时间）
- 点击草稿 → 预览确认 → 入库 → 打开账单详情页
- 首页待确认账单卡片自动刷新
- 批量确认支持动态文案

## 核心困惑

1. **为什么一次调用不行？** — 票据 OCR 提示词和记账 JSON 输出格式天然矛盾。OCR 需要灵活的自然语言输出，JSON 需要严格的结构化输出。让同一个 prompt 同时做好两件事，比分开做两次更容易出错。

2. **为什么 AI 会在 JSON 里插额外文字？** — 当 prompt 同时要求「每条交易写支付方式」和「输出纯 JSON」时，AI 倾向于遵循更具体的指令（写支付方式）而忽略更抽象的指令（保持 JSON 纯净）。

3. **有没有可能一次调用成功？** — 理论上可以，但需要：
   - 更强的模型（对负向指令遵循更好）
   - 或者改变策略：先拿 JSON，再从 JSON 格式化文本（不用 AI 生成文本）
   - 但第二种策略的 OCR 效果不如专门的 OCR 提示词

## 相关代码

| 文件 | 作用 |
|------|------|
| `AIPrompts.kt` → `RECEIPT_VISION_RETRY_PROMPT_DEFAULT` | 票据 OCR 提示词（专门优化） |
| `AIPrompts.kt` → `IMAGE_ACCOUNTING_PROMPT` | 通用记账提示词（截图记账用） |
| `AIService.kt` → `analyzeReceiptByImage` | 第一次调用：图片 → 文本 |
| `AIService.kt` → `analyzeAccounting` | 第二次调用：文本 → JSON |
| `ChatMessagePipeline.kt` | 草稿确认流程编排 |
| `AccountingDraftInboxActivity.kt` | Inbox 确认流程 |
