---
name: request-refactor-plan
description: 通过 user interview 创建带 tiny commits 的详细 refactor plan，然后 file as a GitHub issue。Use when user wants to plan a refactor, create a refactoring RFC, or break a refactor into safe incremental steps.
---

当用户想创建 refactor request 时调用此 skill。按以下步骤执行。你可以跳过你认为不必要的步骤。

1. 让用户详细描述他们想解决的问题，以及可能的 solution ideas。

2. 探索 repo，验证他们的 assertions，并理解 codebase 当前状态。

3. 询问他们是否考虑过其他 options，并向他们展示其他 options。

4. 围绕 implementation 访谈用户。要非常详细、彻底。

5. 敲定 implementation 的 exact scope。弄清你计划改什么，以及不改什么。

6. 在 codebase 中检查该区域的 test coverage。如果 coverage 不足，询问用户的 testing plans。

7. 把 implementation 拆成 tiny commits 的计划。记住 Martin Fowler 的建议：“make each refactoring step as small as possible, so that you can always see the program working.”

8. 使用 refactor plan 创建 GitHub issue。issue description 使用下面模板：

<refactor-plan-template>

## Problem Statement

developer 面对的问题，从 developer 视角描述。

## Solution

问题的 solution，从 developer 视角描述。

## Commits

一份很长、详细的 implementation plan。用 plain English 写，把 implementation 拆到尽可能小的 commits。每个 commit 都应让 codebase 保持 working state。

## Decision Document

已作出的 implementation decisions 列表。可以包括：

- 将 build/modify 的 modules
- 将 modify 的 module interfaces
- 来自 developer 的 technical clarifications
- Architectural decisions
- Schema changes
- API contracts
- Specific interactions

不要包含具体 file paths 或 code snippets。它们可能很快过时。

## Testing Decisions

已作出的 testing decisions 列表。包括：

- 什么是好测试的描述（只测试 external behavior，不测试 implementation details）
- 哪些 modules 会被测试
- 测试的 prior art（即 codebase 中类似类型的 tests）

## Out of Scope

本 refactor 范围外事项的描述。

## Further Notes (optional)

关于 refactor 的其他 notes。

</refactor-plan-template>
