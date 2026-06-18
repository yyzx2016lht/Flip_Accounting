---
name: review
description: Review the changes since a fixed point (commit, branch, tag, or merge-base) along two axes — Standards (does the code follow this repo's documented coding standards?) and Spec (does the code match what the originating issue/PRD asked for?). Runs both reviews in parallel sub-agents and reports them side by side. Use when the user wants to review a branch, a PR, work-in-progress changes, or asks to "review since X".
---

# Review

对用户提供的固定点与 `HEAD` 之间的 diff 做双轴 review：

- **Standards** — 代码是否符合这个 repo 记录下来的 coding standards？
- **Spec** — 代码是否忠实实现来源 issue / PRD / spec？

两个轴线都作为**并行 sub-agents**运行，避免互相污染 context；然后这个 skill 聚合它们的 findings。

Issue tracker 应该已经提供给你；如果缺少 `docs/agents/issue-tracker.md`，运行 `/setup-matt-pocock-skills`。

## Process

### 1. Pin the fixed point

用户说的任何内容都是 fixed point：commit SHA、branch name、tag、`main`、`HEAD~5` 等。不要自作主张；原样传入。如果用户没有指定，询问：“Review against what — a branch, a commit, or `main`?” 在拿到 fixed point 前不要继续。

先捕获一次 diff command：`git diff <fixed-point>...HEAD`（three-dot，因此比较对象是 merge-base）。同时用 `git log <fixed-point>..HEAD --oneline` 记录 commits 列表。

### 2. Identify the spec source

按以下顺序寻找来源 spec：

1. Commit messages 中的 issue references（`#123`、`Closes #45`、GitLab `!67` 等）— 按 `docs/agents/issue-tracker.md` 中的 workflow 获取。
2. 用户作为 argument 传入的 path。
3. `docs/`、`specs/` 或 `.scratch/` 下与 branch name 或 feature 匹配的 PRD/spec 文件。
4. 如果什么都找不到，询问用户 spec 在哪里。如果用户说没有 spec，**Spec** sub-agent 跳过并报告 “no spec available”。

### 3. Identify the standards sources

Repo 中任何记录代码应该如何写的内容。常见位置：

- `CLAUDE.md`、`AGENTS.md`
- `CONTRIBUTING.md`
- `CONTEXT.md`、`CONTEXT-MAP.md`、per-context `CONTEXT.md` files
- `docs/adr/`（architectural decisions 也是 standards）
- `.editorconfig`、`eslint.config.*`、`biome.json`、`prettier.config.*`、`tsconfig.json`（machine-enforced standards；记录它们，但不要重复检查 tooling 已经检查的内容）
- Repo root 或 `docs/` 下任何 `STYLE.md`、`STANDARDS.md`、`STYLEGUIDE.md` 或类似文件

收集文件列表。**Standards** sub-agent 会读取它们。

### 4. Spawn both sub-agents in parallel

发送一条包含两个 `Agent` tool calls 的消息。两个都使用 `general-purpose` subagent。

**Standards sub-agent prompt** — 包含：

- 完整 diff command 和 commit list。
- Step 3 中找到的 standards-source files 列表。
- Brief："Read the standards docs. Then read the diff. Report — per file/hunk where relevant — every place the diff violates a documented standard. Cite the standard (file + the rule). Distinguish hard violations from judgement calls. Skip anything tooling enforces. Under 400 words."

**Spec sub-agent prompt** — 包含：

- Diff command 和 commit list。
- Spec 的 path 或已获取内容。
- Brief："Read the spec. Then read the diff. Report: (a) requirements the spec asked for that are missing or partial; (b) behaviour in the diff that wasn't asked for (scope creep); (c) requirements that look implemented but where the implementation looks wrong. Quote the spec line for each finding. Under 400 words."

如果缺少 spec，跳过 Spec sub-agent，并在最终报告中说明。

### 5. Aggregate

在 `## Standards` 和 `## Spec` headings 下展示两个 reports，可原样或轻微清理。**不要**合并或重新排序 findings；这两个轴线刻意保持分离，方便用户独立查看。

最后用一行总结：每个轴线的 findings 总数，以及被标记的最严重单个问题（如果有）。

## Why two axes

一个变更可能通过其中一个轴线，但失败在另一个轴线：

- 代码符合所有 standard，但实现了错误的东西 → **Standards pass, Spec fail.**
- 代码完全符合 issue 要求，但破坏了项目约定 → **Spec pass, Standards fail.**

分开报告能避免一个轴线掩盖另一个轴线。
