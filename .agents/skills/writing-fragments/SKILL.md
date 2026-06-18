---
name: writing-fragments
description: Grilling session that mines the user for fragments — heterogeneous nuggets of writing (claims, vignettes, sharp sentences, half-thoughts) — and appends them to a single document as raw material for a future article. Use when the user wants to develop ideas before imposing structure, or mentions "fragments", "ideate", or "raw material" for writing.
---

<what-to-do>

运行一个会产出 fragments 的 grilling session。围绕用户想写的主题持续访谈。不要强加 phases、outlines 或 structure；这些明确不在范围内。

当对话双方产生 fragments 时，把它们追加到单一 markdown 文件。用户会在 session 中编辑这个文件；每次写入前都要重新读取，保留他们的 edits。

如果用户没有传入路径，只询问一次文档保存到哪里，然后在 session 剩余部分记住它。

从用户说的第一句话开始捕获 fragments，包括 initial prompt。

第一次写入时，在顶部放一个带 working title 的单一 H1（之后可以改），除此之外什么都不要放：不要 metadata、TOC 或 date。

</what-to-do>

<supporting-info>

## What is a fragment

Fragment 是任何可能进入最终文章的文本片段。它必须_对作者可读_，也就是作者能知道它是什么意思；但它不需要定义术语，也不需要让冷读者立即理解。标准是 “这是不是一段好写作素材？”，不是 “这是不是自洽论证？”

Fragments 刻意保持异质。可能成为 fragment 的例子：

- 一句 sharp sentence，你想在某处使用但还不知道放哪里。
- 一个 claim 和一句 justification。
- 一个 vignette：发生过的事、code snippet、scenario、analogy。
- 一个 half-thought："something about how X feels like Y, work this out later."
- 一句 quote、一段 dialogue、一句 overheard line。
- 一组凭感觉属于同一簇的 observations。
- 一个 complaint、confession 或 punchline。

Novelist's diary 是模型：多年无结构的 noticings，之后被挖掘成 raw material。Fragments 就是 noticings。

## File format

```markdown
# Working title

A first fragment lives here.

It can be multiple paragraphs. It can include lists, code, quotes — whatever
shape the fragment naturally takes.

---

A second fragment.

---

> A quoted line that the user wants to keep around.

A reaction to it.

---

- A cluster of related observations
- That hang together by feel
- And want to be near each other
```

Fragments 用 horizontal rule（`\n---\n`）分隔。正文内不要 headings。不要 tags。除了加入顺序之外，不做排序。

## Writing rhythm

安静追加。不要为每个 fragment 请求许可。可以顺带说你添加了什么（"adding that"），但不要用 save dialogs 打断对话。

每次写入前：从磁盘重新读取文件。用户可能在回合之间编辑、重排或删除 fragments；保留他们的变化。永远不要 overwrite 文件；只 append（或者在用户要求时，就地编辑特定 fragment）。

用户随时可以说 "cut the last one"、"rewrite that one sharper"、"merge those two"。把它们当成一等指令。

</supporting-info>
