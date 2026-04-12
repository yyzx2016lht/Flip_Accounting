package tao.test.flipaccounting

import android.content.Context

object CategoryIconHelper {

    fun findCategoryIcon(ctx: Context, name: String, type: Int): String {
        // 如果是转账/还款类型或名称包含关键字，返回转账图标
        if (type == 2 || type == 3 || name.contains("转账到") || name.contains("还款到")) return "http://res3.qianjiapp.com/catev2/cate_icon_zhuanzhang.png"
        
        // 1. 标准化名称：去除层级，只取最后一段 (例如 "餐饮 > 早餐" -> "早餐")
        val leafName = if (name.contains(" > ")) name.split(" > ").last().trim() else name.trim()
        
        // 转换 Prefs 的类型 (Bill.type 1->支出, 2->收入)
        // 注意：Bill 对象中 type: 1->收入, 2->转账, 0/其他->支出 (但在 Prefs 中 1->支出, 2->收入，这里定义比较混乱，需小心对应)
        // 修正逻辑：BillAdapter 中 type=1 是收入(绿色)，type=0 是支出(红色)
        // Prefs.getCategories 中 type=1 是支出，type=2 是收入
        val categoriesType = if (type == 1) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE

        // 辅助函数：递归搜索图标
        fun searchRecursive(list: List<CategoryNode>, target: String): String? {
            for (node in list) {
                // A. 精确匹配
                if (node.name.trim() == target && node.icon.isNotEmpty()) return node.icon
                // B. 递归子类
                val subResult = searchRecursive(node.subs, target)
                if (!subResult.isNullOrEmpty()) return subResult
            }
            return null
        }

        // --- 第一阶段：在用户现有分类中精确查找 ---
        val userCats = Prefs.getCategories(ctx, categoriesType)
        var icon = searchRecursive(userCats, leafName)
        if (!icon.isNullOrEmpty()) return icon

        // --- 第二阶段：在系统内置全量库中查找 (包含收入和支出，防止 AI 判错类型) ---
        val rawIncome = Prefs.loadDefaultFromRaw(ctx, Prefs.TYPE_INCOME)
        val rawExpense = Prefs.loadDefaultFromRaw(ctx, Prefs.TYPE_EXPENSE)
        val allBuiltin = rawIncome + rawExpense

        // 2.1 尝试在内置库中精确查找 "早餐"
        icon = searchRecursive(allBuiltin, leafName)
        if (!icon.isNullOrEmpty()) return icon

        // --- 第三阶段：模糊匹配 / 猜测 (解决 AI 返回非标准名称的问题) ---
        // 将所有内置分类展开成平铺列表
        val flattenedNodes = mutableListOf<CategoryNode>()
        fun flatten(list: List<CategoryNode>) {
            list.forEach { 
                flattenedNodes.add(it)
                flatten(it.subs)
            }
        }
        flatten(allBuiltin)

        // 3.1 包含匹配：如果 AI 返回 "交通费"，匹配内置的 "交通"
        // 优先匹配名字更长的分类（越长越精确），比如 "交通" vs "公共交通"，优先匹配后者
        val containsMatch = flattenedNodes
            .filter { leafName.contains(it.name) || it.name.contains(leafName) }
            .sortedByDescending { it.name.length }
            .firstOrNull()
        
        if (containsMatch != null && containsMatch.icon.isNotEmpty()) {
            return containsMatch.icon
        }

        // 3.2 (兜底) 如果是“早餐/午餐/晚餐”，强行映射到“三餐”或“餐饮”
        if (leafName.contains("早") || leafName.contains("午") || leafName.contains("晚") || leafName.contains("饭")) {
            val meal = flattenedNodes.find { it.name.contains("三餐") || it.name.contains("餐饮") }
            if (meal != null) return meal.icon
        }

        return ""
    }
}