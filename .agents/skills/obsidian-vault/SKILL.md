---
name: obsidian-vault
description: 在 Obsidian vault 中使用 wikilinks 和 index notes 搜索、创建并管理 notes。Use when user wants to find, create, or organize notes in Obsidian.
---

# Obsidian Vault

## Vault location

`/mnt/d/Obsidian Vault/AI Research/`

root level 基本保持扁平。

## Naming conventions

- **Index notes**：聚合相关 topics（例如 `Ralph Wiggum Index.md`、`Skills Index.md`、`RAG Index.md`）
- 所有 note names 使用 **Title case**
- 不用 folders 做组织；改用 links 和 index notes

## Linking

- 使用 Obsidian `[[wikilinks]]` syntax：`[[Note Title]]`
- Notes 在底部链接 dependencies/related notes
- Index notes 只是 `[[wikilinks]]` 列表

## Workflows

### Search for notes

```bash
# Search by filename
find "/mnt/d/Obsidian Vault/AI Research/" -name "*.md" | grep -i "keyword"

# Search by content
grep -rl "keyword" "/mnt/d/Obsidian Vault/AI Research/" --include="*.md"
```

或直接在 vault path 上使用 Grep/Glob tools。

### Create a new note

1. filename 使用 **Title Case**
2. 按 vault rules，把内容写成一个 learning unit
3. 在底部添加指向 related notes 的 `[[wikilinks]]`
4. 如果属于 numbered sequence，使用 hierarchical numbering scheme

### Find related notes

在 vault 中搜索 `[[Note Title]]` 来找 backlinks：

```bash
grep -rl "\\[\\[Note Title\\]\\]" "/mnt/d/Obsidian Vault/AI Research/"
```

### Find index notes

```bash
find "/mnt/d/Obsidian Vault/AI Research/" -name "*Index*"
```
