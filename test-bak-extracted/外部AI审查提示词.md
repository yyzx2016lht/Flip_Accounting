# 外部 AI 审查指南

## 发送顺序

分 3 次发给 AI，每次一个主题。不要一次全发（太大会丢失上下文）。

---

## 第一次：记账流程完整性审查

### 提示词

```
你是一个记账 App 的产品经理 + 高级 Android 开发。

我开发了一个 Android 记账 App，核心功能是 AI 记账——用户用文字、语音或图片描述消费，AI 自动提取账单。

以下是 AI 记账的完整代码。请你从"记账功能是否完整"的角度审查，重点回答：

1. 功能覆盖：以下记账场景是否都有正确处理？
   - 文字记账（单笔、多笔、收入、转账、还款、外币）
   - 语音记账（ASR 转文字后记账）
   - 图片记账（单图直出、单图草稿确认、多图）
   - 意图路由（记账 vs 闲聊分流）
   - 聊天回复（闲聊时的自然对话）
   - 本地修正规则（用户自定义关键词→分类映射）

2. 数据完整性：账单的每个字段（amount, type, category_name, asset_name, to_asset_name, time, remarks, currency, fee）在各种场景下是否都能正确填充？

3. 错误处理：网络失败、AI 返回异常、用户取消等情况下，是否有合理的兜底？

4. 有没有什么记账场景是你觉得应该支持但代码里没有的？

请逐项分析，给出明确的"✅ 正常"或"⚠️ 问题"结论。
```

### 需要发送的文件（按顺序）

1. `AIService.kt` — 核心 AI 调用（analyzeAccounting, classifyIntent, generateGeneralChatReply）
2. `AIAccountingPromptBuilder.kt` — 提示词组装
3. `AIPrompts.kt` — 提示词常量
4. `ChatMessagePipeline.kt` — 聊天入口的完整流程
5. `AIAccountingSupport.kt` — 结果规范化（normalizeAccountingResult）
6. `ChatBillCorrectionService.kt` — 账单保存逻辑

---

## 第二次：提示词质量审查

### 提示词

```
你是一个 prompt engineering 专家，专注于结构化数据提取。

以下是我的 AI 记账系统使用的全部提示词。用户输入一段文字或图片，AI 需要输出结构化的账单 JSON。

请你评估每个提示词的质量：

1. 指令是否清晰无歧义？有没有会让 AI 理解错的表述？
2. 规则之间有没有矛盾？
3. 输出格式约束够强吗？AI 会不会输出不符合格式的结果？
4. 有没有遗漏的边界情况？
5. 如果你来写，你会怎么改？

请逐个提示词点评，给出具体的改进建议。
```

### 需要发送的文件

1. `AIPrompts.kt` — 所有提示词常量
2. `AIPromptsWithoutAccount.kt` — 无资产模式提示词
3. `AIAssistantPromptBuilder.kt` — 聊天助手提示词组装
4. `RuleDialogHelper.kt` — 关键词提取提示词

---

## 第三次：改进建议

### 提示词

```
你是一个有 10 年经验的 Android 架构师，同时对 AI 应用有深入理解。

以下是一个 AI 记账 App 的核心代码。我已经实现了：
- 文字/语音/图片记账
- 意图路由（记账 vs 闲聊）
- 多图一次性识别
- 本地修正规则
- 多平台 AI 支持（MiMo、DeepSeek 等）

请你从以下角度给出改进建议：

1. 架构：代码组织、职责划分、可维护性
2. 用户体验：记账流程是否顺畅？有没有卡顿或等待过长的环节？
3. AI 效果：提示词和后处理是否能让 AI 准确记账？
4. 性能：有没有可以优化的地方（API 调用次数、token 消耗、响应速度）？
5. 功能缺失：作为一个记账 App，还有什么功能是你觉得用户会需要但目前没有的？

请给出具体的、可执行的建议，不要泛泛而谈。
```

### 需要发送的文件

1. `AIService.kt`
2. `ChatMessagePipeline.kt`
3. `AiAssistant.kt`
4. `AIAccountingPromptBuilder.kt`
5. `AIPrompts.kt`
6. `ChatActivity.kt`（关键部分：sendText、语音处理、图片处理）

---

## 注意事项

- 每次对话开始新会话，不要混在一起
- 如果 AI 回复太长被截断，发"继续"让它补完
- 如果 AI 建议太笼统，追问"具体怎么改？给代码示例"
- 把 AI 的建议保存下来，我们一起评估哪些值得做
