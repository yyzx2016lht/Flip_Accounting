package com.taostudio.tapaccounting

internal class BuiltInCategorySelection {
    private val selectedItemsByName = linkedMapOf<String, BuiltInCategory>()

    fun clear() {
        selectedItemsByName.clear()
    }

    fun isSelected(item: BuiltInCategory): Boolean {
        val selected = selectedItemsByName[item.name.trim()] ?: return false
        return selected.selectionKey() == item.selectionKey()
    }

    fun toggle(item: BuiltInCategory) {
        val name = item.name.trim()
        val selected = selectedItemsByName[name]
        if (selected?.selectionKey() == item.selectionKey()) {
            selectedItemsByName.remove(name)
        } else {
            selectedItemsByName[name] = item
        }
    }

    fun selectedItems(): List<BuiltInCategory> = selectedItemsByName.values.toList()

    private fun BuiltInCategory.selectionKey() = SelectionKey(
        name = name.trim(),
        icon = icon.trim()
    )

    private data class SelectionKey(
        val name: String,
        val icon: String
    )
}
