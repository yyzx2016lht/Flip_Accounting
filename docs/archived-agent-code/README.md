# 归档代码：Agent 模式 & 账单修改

> **状态：暂时未使用，后续可能恢复**
>
> 归档日期：2026-06-16
>
> 这批代码是从 commit `477a71d` 中提取的，包含完整的 Agent 模式和账单修改功能。
> 当前版本已从源码中移除，保留在此处供后续参考或恢复使用。

---

## 目录结构

```
archived-agent-code/
├── README.md                          ← 本文件
├── agent-src/                         ← Agent 核心源码（原 app/src/main/java/.../chat/agent/）
│   ├── AgentConfirmationController.kt
│   ├── AgentConversationState.kt
│   ├── AgentEffect.kt
│   ├── AgentErrorType.kt
│   ├── AgentLlmMessageBuilder.kt
│   ├── AgentPromptBuilder.kt
│   ├── AgentSessionContext.kt
│   ├── AgentTool.kt
│   ├── AgentToolRegistrar.kt
│   ├── AgentToolRegistry.kt
│   ├── AgentToolResult.kt
│   ├── AgentValidationResult.kt
│   ├── ChatAgentOrchestrator.kt       ← Agent 主编排器
│   ├── ChatConversationMode.kt
│   ├── PendingAgentAction.kt
│   ├── RiskLevel.kt
│   ├── UiAction.kt
│   ├── skill/                         ← Agent 技能路由
│   │   ├── AgentSkill.kt
│   │   ├── AgentSkillRegistry.kt
│   │   ├── AgentSkillRouter.kt
│   │   └── BuiltInAgentSkills.kt
│   └── tool/                          ← 50+ Agent 工具实现
│       ├── BillCreateFromTextTool.kt
│       ├── BillModifyByInstructionTool.kt
│       ├── BillDeleteTool.kt
│       ├── ...（共 50+ 个工具文件）
│       └── PrefSetTool.kt
├── agent-test/                        ← Agent 单元测试
│   ├── AgentOrchestratorBehaviorTest.kt
│   ├── AgentConfirmationTest.kt
│   ├── ...（共 11 个测试文件）
│   └── skill/
│       └── AgentSkillRouterTest.kt
├── agent-docs/                        ← Agent 设计文档
│   ├── AGENT_IMPLEMENTATION_PLAN.md
│   ├── AGENT_IMPLEMENTATION_PROMPT.md
│   ├── AGENT_SKILL_IMPLEMENTATION_PROMPT.md
│   └── AGENT_TOOLS.md
└── *.patch                            ← 从其他文件中移除的代码 diff
    ├── ChatMessagePipeline-removed-code.patch
    ├── ChatActivity-removed-code.patch
    └── ChatBillCorrectionService-removed-code.patch
```

## 包含的功能

### 1. Agent 模式
- 用户可以在聊天中以自然语言指令操作账单、查统计、管理资产等
- 通过 `ChatAgentOrchestrator` 编排 LLM 工具调用
- 支持 50+ 工具（账单CRUD、统计查询、资产管理、账本管理、分类管理等）
- 支持多步骤工具调用和确认机制

### 2. 账单修改
- 用户在聊天中说"把上一笔改成40元"等自然语言，AI 自动找到目标账单并修改
- 包含候选账单排序算法（`rankModifyCandidates`）
- 包含修改预览和确认交互

## 恢复方法

如需恢复这些功能：

1. 将 `agent-src/` 下的文件复制回 `app/src/main/java/com/taostudio/tapaccounting/chat/agent/`
2. 将 `agent-test/` 下的文件复制回 `app/src/test/java/com/taostudio/tapaccounting/chat/agent/`
3. 参考 `.patch` 文件恢复其他文件中的 agent/modify 相关代码
4. 恢复布局文件中的 agent 相关 UI（`activity_chat.xml` 的 `layout_agent_empty_state`，`activity_ai_feature_settings.xml` 的 agent 入口）
5. 恢复 `strings.xml` 中的 agent 相关字符串

或者直接 `git checkout 477a71d -- app/src/main/java/com/taostudio/tapaccounting/chat/agent/`
