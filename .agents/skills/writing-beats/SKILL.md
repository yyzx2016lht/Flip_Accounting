---
name: writing-beats
description: Shape an article as a journey of beats, choose-your-own-adventure style. The user picks a starting beat from the raw material, you write only that beat, then offer options for where to pivot next, beat by beat, until the article reaches a natural end. Use when the user has raw material and wants to assemble it as a narrative rather than an argument.
---

<what-to-do>

用户已经传入（或将传入）一份 raw material markdown 文件。

如果用户没有说明文章保存路径，只询问一次并记住路径。

然后运行 beat-by-beat journey：

1. 从 raw material 中写出 2-3 个候选 **starting beats**。每个都是进入文章的不同入口。先展示给用户，用户选一个。预览写下这个 beat 后可能通向哪些 beats，就像让用户先看到路径前方一点点。
2. 用户选定 starting beat 后，只把**那个 beat**写入文章文件。一个 beat 可以是一句话，也可以是几段，按它自然需要的长度来。写完就停。
3. 从磁盘重新读取文章文件。然后提供 2-3 个候选 **next beats**，也就是文章当前可以 pivot 的不同方向。
4. 循环步骤 2-4，直到文章自然结束。

</what-to-do>

<supporting-info>

## What is a beat

Beat 是 journey 中的一步。它只做一件事：设定场景、落下一个观点、提出问题、插入 aside、扭转角度。然后停下，把读者留在一个下一个 beat 可以 pivot 的位置。

Beat 的大小由它需要完成的动作决定：

- 如果动作只有一句话，那就是一句话（"And then nothing happened for three weeks."）。
- 如果需要 setup，就是一个短段落。
- 如果 beat 是自洽的 vignette、argument 或 example，可以是多段。

如果一个 “beat” 需要五段和三个 subheadings，它就不是 beat，而是两个粘在一起的 beats。拆开。

## Writing one beat

一旦选定 beat，只把_那个 beat_写入文章文件。不要写下一个 beat。

从 raw pile 中抽取 material 来填充 beat。你可以 paraphrase、split、recombine 或 quote。这个 pile 是 quarry。

## Ending the journey

文章在 journey 完成时结束，不是在 pile 用完时结束。大多数 piles 都会剩下没有用进去的 fragments。这没关系；raw material 多于实际需要正是重点。

## Writing rhythm

- 一次追加一个 beat。永远不要提前写。
- 每次写入前都从磁盘重新读取文章文件。绝对保留用户 edits。
- 如果用户大幅编辑了之前的 beat，让它改变接下来要写什么。
- 如果用户说 "rewrite that beat" 或 "go back and try a different beat 3"，照做：就地编辑，别动其余部分。

</supporting-info>
