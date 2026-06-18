---
name: qa
description: 交互式 QA session，用户以对话方式报告 bugs 或 issues，agent 创建 GitHub issues。后台探索 codebase 以获取 context 和 domain language。Use when user wants to report bugs, do QA, file issues conversationally, or mentions "QA session".
---

# QA Session

运行交互式 QA session。用户描述遇到的问题。你负责澄清、探索 codebase 获取 context，并创建 durable、user-focused 且使用项目 domain language 的 GitHub issues。

## For each issue the user raises

### 1. Listen and lightly clarify

让用户用自己的话描述问题。最多问 **2-3 个简短 clarifying questions**，聚焦：

- 他们期望什么，实际发生了什么
- Steps to reproduce（如果不明显）
- 是否稳定复现，还是 intermittent

不要过度访谈。如果描述足够清楚，可以直接 file。

### 2. Explore the codebase in the background

与用户对话时，在后台启动 Agent（subagent_type=Explore）理解相关区域。目标不是找 fix，而是：

- 学习该区域使用的 domain language（检查 UBIQUITOUS_LANGUAGE.md）
- 理解 feature 本应做什么
- 识别 user-facing behavior boundary

这些 context 帮助你写出更好的 issue，但 issue 本身不应引用具体 files、line numbers 或 internal implementation details。

### 3. Assess scope: single issue or breakdown?

file 前判断这是**单个 issue**，还是需要**拆成多个 issues**。

拆分条件：

- fix 跨多个 independent areas（例如 “form validation is wrong AND success message is missing AND redirect is broken”）
- 存在清晰可分离 concerns，不同人可以并行处理
- 用户描述了多个不同 failure modes 或 symptoms

保持单个 issue 的条件：

- 一个地方的一个 behavior 错了
- symptoms 都来自同一个 root behavior

### 4. File the GitHub issue(s)

使用 `gh issue create` 创建 issues。不要先要求用户 review；直接 file 并分享 URLs。

Issues 必须 **durable**，即 major refactors 后仍有意义。从用户视角写。

#### For a single issue

使用这个模板：

```
## What happened

[用普通语言描述用户经历的实际行为]

## What I expected

[描述期望行为]

## Steps to reproduce

1. [developer 可执行的具体编号步骤]
2. [使用 codebase 的 domain terms，不用 internal module names]
3. [包含相关 inputs、flags 或 configuration]

## Additional context

[来自用户或 codebase exploration 的额外观察，用来帮助 framing；使用 domain language，但不引用 files]
```

#### For a breakdown (multiple issues)

按 dependency order 创建 issues（blockers first），这样可以引用真实 issue numbers。

每个 sub-issue 使用这个模板：

```
## Parent issue

#<parent-issue-number>（如果你创建了 tracking issue）或 "Reported during QA session"

## What's wrong

[描述这个 specific behavior problem，只描述这个 slice]

## What I expected

[这个 slice 的 expected behavior]

## Steps to reproduce

1. [只针对这个 issue 的步骤]

## Blocked by

- #<issue-number>（如果必须等另一个 issue 解决）

如果没有 blockers，写 "None — can start immediately"。

## Additional context

[与这个 slice 相关的额外观察]
```

创建 breakdown 时：

- **Prefer many thin issues over few thick ones** — 每个都应能独立 fix 和 verify
- **Mark blocking relationships honestly** — 如果 B 确实必须等 A 才能测试，就说明。如果独立，两个都写 “None — can start immediately”
- **Create issues in dependency order**，这样可以在 “Blocked by” 中引用真实 issue numbers
- **Maximize parallelism** — 目标是让多人（或 agents）能同时领取不同 issues

#### Rules for all issue bodies

- **No file paths or line numbers** — 它们会过时
- **Use the project's domain language**（如果存在，检查 UBIQUITOUS_LANGUAGE.md）
- **Describe behaviors, not code** — 写 “the sync service fails to apply the patch”，不要写 “applyPatch() throws on line 42”
- **Reproduction steps are mandatory** — 如果无法确定，询问用户
- **Keep it concise** — developer 应能 30 秒内读完 issue

file 后，打印所有 issue URLs（并总结 blocking relationships），然后问：“Next issue, or are we done?”

### 5. Continue the session

持续进行，直到用户说结束。每个 issue 都独立处理，不要 batch。
