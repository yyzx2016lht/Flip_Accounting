package tao.test.tapaccounting.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import tao.test.tapaccounting.data.local.entity.Category

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: Category): Long

    @Query("SELECT * FROM categories")
    suspend fun getAllCategoriesList(): List<Category>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder ASC, id ASC")
    fun getCategoriesByType(type: Int): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder ASC, id ASC")
    suspend fun getCategoriesListByType(type: Int): List<Category>

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getCategoryByName(name: String): Category?

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getCategoryById(id: Long): Category?

    @Query("SELECT * FROM categories WHERE name = :name AND type = :type LIMIT 1")
    suspend fun getCategoryByNameAndType(name: String, type: Int): Category?

    @Query("SELECT MAX(sortOrder) FROM categories WHERE type = :type AND IFNULL(parentId, 0) = IFNULL(:parentId, 0)")
    suspend fun getMaxSortOrder(type: Int, parentId: Long?): Int?

    @Update
    suspend fun updateCategory(category: Category)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 查询某一级分类下的所有子分类 */
    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY sortOrder ASC, id ASC")
    suspend fun getChildrenByParentId(parentId: Long): List<Category>

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}
