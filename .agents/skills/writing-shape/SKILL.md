---
name: writing-shape
description: Take a markdown file of raw material and shape it into an article through a conversational session — drafting candidate openings, growing the piece paragraph by paragraph, arguing about format (lists, tables, callouts, quotes) at each step. Use when the user has a pile of notes, fragments, or a rough draft and wants help turning it into something publishable.
---

<what-to-do>

用户已经传入（或将传入）一份 raw material markdown 文件。把它当作 input pile：它可以是整齐的 fragments 列表、无结构 prose 墙、transcript，任何格式都可以。先完整读完，再做其他事。

然后运行一个 shaping session，产出一份独立的 article document。不要编辑 raw material 文件；对这个 skill 来说它是 read-only。

如果用户没有说明文章保存路径，只询问一次并记住路径。用户会在 session 中编辑 article file；每次写入前都要重新读取，保留他们的 edits。

</what-to-do>

<supporting-info>

## The loop

1. **Read the pile.** 完整读取 input file，形成对内容的整体感。
2. **Draft 2-3 candidate openings.** 每个 opening 都应暗示文章的不同 thesis 或 angle。全部展示出来，迫使用户选择或组合 hybrid。选定的 opening 定义整篇文章接下来必须完成什么。
3. **Grow paragraph by paragraph.** Opening 落地后，问 “given this opening, what does the reader need to hear next?” 从 pile 中抽取 material 回答。讨论下一 beat 应该是 paragraph、list、table、callout、quote 还是 code block。每个格式选择都应该 deliberate 且 defensible。
4. **Append to the article file as you go.** 不要 batch。每个达成一致的 paragraph 或 block 立即写入，让用户看到文章成形。
5. **Loop step 3 until the article is done.** 用户决定何时完成。

## Conversational feel

这是倒过来的 grilling session。Ideation 问的是 “what are you actually noticing?” 这里问的是 “what is this article actually arguing, and in what order does the reader need to hear it?” 要 push back。不要放过 weak transitions。如果一个段落没有赢得自己的位置，就删掉。

持续使用这些具体 moves：

- "What does this paragraph do for the reader that the previous one didn't?"
- "If I cut this, what breaks?"
- "Is this prose, or should it be a list? Why prose?"
- "This sentence is doing two jobs — split it or pick one."
- "The opening promised X. We've drifted to Y. Either re-thread it or change the opening."

## Pulling from the pile

把 raw material 当作 quarry，而不是 script。抽取 fragment，改写以适配周围段落，然后放进去。一个 fragment 可以被拆到多个段落、与另一个合并，或被 paraphrase。Pile 的工作是被挖掘；文章的工作是读起来像一个声音。

如果 pile 缺少文章需要的东西，明确指出 gap："We need an example here and the pile doesn't have one — give me one now or we cut this section."

## Format arguments to actually have

选择如何呈现一个 beat 时，把这些 tradeoffs 和用户大声讨论，而不是默默决定：

- **Prose vs. list.** Prose 承载 argument；lists 承载 parallel items。如果 items 不是真正 parallel，prose 更好。如果是，list 更容易扫描。
- **Inline vs. callout.** Tips、warnings 和 asides 放在 callouts（`> [!TIP]`、`> [!NOTE]`）里，但只有当它们 inline 会真正打断 main argument 时才这样做。否则保持 inline。
- **Table vs. repeated structure.** 如果同一形状以相同 fields 重复 3 次以上，用 table。否则用 prose 加 bold leads。
- **Quote vs. paraphrase.** 当原始措辞本身就是重点时 quote。只有 idea 重要时 paraphrase。
- **Code block vs. inline code.** Multi-line、runnable 或 illustrative → block。Single token 或 identifier → inline。

## Writing rhythm

每个 block 达成一致后就追加到 article file。每次写入前都从磁盘重新读取文件；用户可能在回合之间编辑。永远不要盲目 overwrite。如果用户想重写某个段落，就地编辑那个特定段落；其余部分保持不动。

## Out of scope

- 挖掘 pile 中不存在的新 fragments（pile 是 input；如果它不完整，指出 gap，并让用户补齐或删掉该 section）。
- 编辑 raw material 文件。
- 发布、为特定平台格式化，或添加用户没要求的 frontmatter。

</supporting-info>
