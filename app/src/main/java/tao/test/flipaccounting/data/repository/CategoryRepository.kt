package tao.test.flipaccounting.data.repository

import kotlinx.coroutines.flow.Flow
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.CategoryNode
import tao.test.flipaccounting.data.local.dao.BillDao
import tao.test.flipaccounting.data.local.dao.CategoryDao
import tao.test.flipaccounting.data.local.entity.Category
import tao.test.flipaccounting.logic.BillDeleteHelper

class CategoryRepository(
    private val categoryDao: CategoryDao,
    private val billDao: BillDao? = null
) {

    val expenseCategories: Flow<List<Category>> = categoryDao.getCategoriesByType(0)
    val incomeCategories: Flow<List<Category>> = categoryDao.getCategoriesByType(1)

    /** 同步读取指定类型的分类列表（扁平，需在协程 IO 上下文中调用） */
    suspend fun getCategoriesListByType(type: Int): List<Category> =
        categoryDao.getCategoriesListByType(type)

    /** 把扁平 List<Category> 重建为父子嵌套的 List<CategoryNode>（兼容旧 UI） */
    fun buildCategoryTree(flatList: List<Category>): List<CategoryNode> {
        val roots = flatList.filter { it.parentId == null }
        val childrenByParent = flatList.filter { it.parentId != null }.groupBy { it.parentId }
        return roots.map { root ->
            val node = CategoryNode(root.name, root.iconId)
            node.id = root.id
            childrenByParent[root.id]?.forEach { child ->
                // 子分类 iconId 为空时，继承父分类的图标（避免子类显示红色占位块）
                val childIcon = if (child.iconId.isNotEmpty()) child.iconId else root.iconId
                val childNode = CategoryNode(child.name, childIcon)
                childNode.id = child.id
                node.subs.add(childNode)
            }
            node
        }
    }

    /** 按类型同步读取并返回 CategoryNode 树（需协程 IO 上下文） */
    suspend fun getCategoryTree(type: Int): List<CategoryNode> =
        buildCategoryTree(categoryDao.getCategoriesListByType(type))

    suspend fun findCategoryByDisplayName(type: Int, displayName: String): Category? {
        val parts = displayName
            .replace(" > ", "/::/")
            .replace("·", "/::/")
            .split("/::/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val categories = categoryDao.getCategoriesListByType(type)
        val leafName = parts.last()
        val parentName = parts.getOrNull(parts.size - 2)
        val parent = if (parentName.isNullOrBlank()) {
            null
        } else {
            categories.firstOrNull { it.parentId == null && it.name == parentName }
        }

        return when {
            parent != null -> categories.firstOrNull { it.parentId == parent.id && it.name == leafName }
            else -> categories.firstOrNull { it.parentId == null && it.name == leafName }
                ?: categories.firstOrNull { it.name == leafName }
        }
    }

    suspend fun addCategory(category: Category): Long {
        val maxOrder = categoryDao.getMaxSortOrder(category.type, category.parentId) ?: 0
        val categoryWithOrder = category.copy(sortOrder = maxOrder + 10)
        return categoryDao.insertCategory(categoryWithOrder)
    }

    suspend fun getCategoryByName(name: String): Category? {
        return categoryDao.getCategoryByName(name)
    }

    suspend fun updateCategory(category: Category) {
        categoryDao.updateCategory(category)
    }

    suspend fun saveOrderedCategories(categories: List<Category>) {
        categories.forEachIndexed { index, category ->
            categoryDao.updateCategory(category.copy(sortOrder = (index + 1) * 10))
        }
    }

    suspend fun saveOrderedCategoryTree(categories: List<Category>) {
        var rootOrder = 10
        var currentParentId: Long? = null
        val childOrders = mutableMapOf<Long, Int>()

        categories.forEach { category ->
            if (category.parentId == null) {
                categoryDao.updateCategory(category.copy(parentId = null, sortOrder = rootOrder))
                currentParentId = category.id
                childOrders[currentParentId] = 10
                rootOrder += 10
            } else {
                val resolvedParentId = currentParentId
                if (resolvedParentId == null) {
                    categoryDao.updateCategory(category.copy(parentId = null, sortOrder = rootOrder))
                    currentParentId = category.id
                    childOrders[currentParentId] = 10
                    rootOrder += 10
                } else {
                    val childOrder = childOrders[resolvedParentId] ?: 10
                    categoryDao.updateCategory(
                        category.copy(parentId = resolvedParentId, sortOrder = childOrder)
                    )
                    childOrders[resolvedParentId] = childOrder + 10
                }
            }
        }
    }

    /**
     * 删除分类（原逻辑：同时删除子分类），现在改为：
     * - 若该分类有子分类，禁止删除（调用方应先检查）
     * - 处理该分类下账单（迁移或删除）由外部决策后传入 billHandling
     */
    suspend fun deleteById(id: Long) {
        // 同时删除以该 id 为 parentId 的所有子分类
        val children = categoryDao.getAllCategoriesList().filter { it.parentId == id }
        children.forEach { categoryDao.deleteById(it.id) }
        categoryDao.deleteById(id)
    }

    /**
     * 统计指定分类（含所有子分类）下的账单数量
     */
    suspend fun countBillsUnderCategory(categoryId: Long): Int {
        val dao = billDao ?: return 0
        val self = categoryDao.getAllCategoriesList().find { it.id == categoryId }
        val children = categoryDao.getChildrenByParentId(categoryId)

        // 统计所有相关分类（自身 + 子分类）
        val allCats = listOfNotNull(self) + children
        var count = 0
        for (cat in allCats) {
            // 按 id 统计
            count += dao.countBillsByCategoryId(cat.id)
            // 同时按 categoryName 文本统计（兼容未设置 categoryId 的旧账单）
            count += dao.countBillsByCategoryName(cat.name)
        }
        return count
    }

    /**
     * 删除叶子分类，并将该分类下账单迁移到 targetCategoryId。
     * 若 targetCategoryId 为 null，则将账单的 categoryId 置 null。
     */
    suspend fun deleteCategoryAndMigrateBills(categoryId: Long, targetCategoryId: Long?) {
        val dao = billDao
        val self = categoryDao.getAllCategoriesList().find { it.id == categoryId }
        val children = categoryDao.getChildrenByParentId(categoryId)
        val allCats = listOfNotNull(self) + children

        if (dao != null) {
            for (cat in allCats) {
                if (targetCategoryId != null) {
                    // 按 id 迁移
                    dao.migrateCategoryId(cat.id, targetCategoryId)
                    // 按 categoryName 文本迁移（兼容旧账单）
                    dao.migrateCategoryByName(cat.name, targetCategoryId)
                } else {
                    dao.clearCategoryId(cat.id)
                    dao.clearCategoryByName(cat.name)
                }
            }
        }
        // 先删子分类，再删自身
        children.forEach { categoryDao.deleteById(it.id) }
        categoryDao.deleteById(categoryId)
    }

    /**
     * 删除叶子分类，并连同该分类下的账单一起删除。
     */
    suspend fun deleteCategoryAndBills(categoryId: Long, db: AppDatabase? = null) {
        val dao = billDao
        val self = categoryDao.getAllCategoriesList().find { it.id == categoryId }
        val children = categoryDao.getChildrenByParentId(categoryId)
        val allCats = listOfNotNull(self) + children

        if (dao != null) {
            for (cat in allCats) {
                // 按 id 查出并删除
                val billsById = dao.getBillsByCategoryIdList(cat.id)
                // 同时删除按 categoryName 关联但 categoryId 为 null 的旧账单
                // （通过先查出再删除，避免无 @Query DELETE by name 方法）
                val billsByName = dao.getBillsByCategoryNameList(cat.name)
                val billsToDelete = (billsById + billsByName)
                    .distinctBy { it.id }
                    .filter { it.id > 0L }
                if (billsToDelete.isNotEmpty()) {
                    if (db != null) {
                        BillDeleteHelper.deleteBillsAndRevertBalance(db, billsToDelete)
                    } else {
                        dao.delete(billsToDelete)
                    }
                }
            }
        }
        children.forEach { categoryDao.deleteById(it.id) }
        categoryDao.deleteById(categoryId)
    }

    /**
     * 将二级分类提升为一级分类（parentId 置 null）。
     */
    suspend fun promoteToParent(categoryId: Long) {
        val cat = categoryDao.getAllCategoriesList().find { it.id == categoryId } ?: return
        categoryDao.updateCategory(cat.copy(parentId = null))
    }

    /**
     * 将一级分类降级为指定父分类的二级分类。
     * 要求该一级分类没有子分类（调用方应先检查）。
     */
    suspend fun demoteToChild(categoryId: Long, newParentId: Long) {
        val cat = categoryDao.getAllCategoriesList().find { it.id == categoryId } ?: return
        categoryDao.updateCategory(cat.copy(parentId = newParentId))
    }

    /** 获取指定分类的子分类列表 */
    suspend fun getChildren(parentId: Long): List<Category> =
        categoryDao.getChildrenByParentId(parentId)
}
