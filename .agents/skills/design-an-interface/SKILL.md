---
name: design-an-interface
description: 使用 parallel sub-agents 为 module 生成多个 radically different interface designs。Use when user wants to design an API, explore interface options, compare module shapes, or mentions "design it twice".
---

# Design an Interface

基于 “A Philosophy of Software Design” 中的 “Design It Twice”：你的第一个想法很可能不是最好的。生成多个根本不同的 designs，然后比较。

## Workflow

### 1. Gather Requirements

设计前先理解：

- [ ] 这个 module 解决什么问题？
- [ ] callers 是谁？（other modules、external users、tests）
- [ ] key operations 是什么？
- [ ] 有哪些 constraints？（performance、compatibility、existing patterns）
- [ ] 什么应该隐藏在内部，什么应该暴露？

询问：“这个 module 需要做什么？谁会使用它？”

### 2. Generate Designs (Parallel Sub-Agents)

使用 Task tool 同时生成 3+ 个 sub-agents。每个都必须产出**根本不同**的 approach。

```
Prompt template for each sub-agent:

Design an interface for: [module description]

Requirements: [gathered requirements]

Constraints for this design: [assign a different constraint to each agent]
- Agent 1: "Minimize method count - aim for 1-3 methods max"
- Agent 2: "Maximize flexibility - support many use cases"
- Agent 3: "Optimize for the most common case"
- Agent 4: "Take inspiration from [specific paradigm/library]"

Output format:
1. Interface signature (types/methods)
2. Usage example (how caller uses it)
3. What this design hides internally
4. Trade-offs of this approach
```

### 3. Present Designs

每个 design 展示：

1. **Interface signature** — types、methods、params
2. **Usage examples** — callers 在实践中如何使用
3. **What it hides** — 保持在内部的 complexity

顺序展示 designs，让用户能在比较前吸收每个 approach。

### 4. Compare Designs

展示所有 designs 后，按以下维度比较：

- **Interface simplicity**：更少 methods、更简单 params
- **General-purpose vs specialized**：flexibility vs focus
- **Implementation efficiency**：shape 是否允许高效 internals？
- **Depth**：小 interface 隐藏大量 complexity（好）vs 大 interface 配薄 implementation（坏）
- **Ease of correct use** vs **ease of misuse**

用 prose 讨论 trade-offs，不用 tables。突出 designs 分歧最大的地方。

### 5. Synthesize

最好的 design 往往结合多个 options 的 insights。询问：

- “哪个 design 最适合你的 primary use case？”
- “其他 designs 中是否有值得合并的 elements？”

## Evaluation Criteria

来自 “A Philosophy of Software Design”：

**Interface simplicity**：更少 methods、更简单 params = 更容易学习和正确使用。

**General-purpose**：能不改动就处理未来 use cases。但要警惕 over-generalization。

**Implementation efficiency**：interface shape 是否允许高效 implementation？还是迫使 internals 变别扭？

**Depth**：小 interface 隐藏大量 complexity = deep module（好）。大 interface 配薄 implementation = shallow module（避免）。

## Anti-Patterns

- 不要让 sub-agents 产出相似 designs；强制 radical difference
- 不要跳过 comparison；价值在 contrast
- 不要 implement；这里只讨论 interface shape
- 不要基于 implementation effort 评价
