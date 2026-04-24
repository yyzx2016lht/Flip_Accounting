package tao.test.flipaccounting.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import tao.test.flipaccounting.data.local.entity.AiRule

@Dao
interface AiRuleDao {
    @Query("SELECT * FROM ai_rule ORDER BY id DESC")
    fun getAllRules(): Flow<List<AiRule>>
    
    @Query("SELECT * FROM ai_rule")
    suspend fun getAllRulesList(): List<AiRule>
    
    @Query("SELECT * FROM ai_rule WHERE isEnabled = 1")
    suspend fun getEnabledRulesList(): List<AiRule>

    @Query("SELECT * FROM ai_rule WHERE keyword = :keyword")
    suspend fun getRulesByKeyword(keyword: String): List<AiRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: AiRule)

    @Update
    suspend fun updateRule(rule: AiRule)

    @Delete
    suspend fun deleteRule(rule: AiRule)

    @Query("DELETE FROM ai_rule WHERE id = :id")
    suspend fun deleteRuleById(id: Int)

    @Query("DELETE FROM ai_rule")
    suspend fun deleteAll()
}
