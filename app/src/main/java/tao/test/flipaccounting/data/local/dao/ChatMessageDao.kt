package tao.test.flipaccounting.data.local.dao

import androidx.room.*
import tao.test.flipaccounting.data.local.entity.ChatMessage

@Dao
interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: ChatMessage): Long

    @Delete
    suspend fun delete(msg: ChatMessage)

    @Query("DELETE FROM chat_messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE bookName = :bookName ORDER BY timestamp ASC")
    suspend fun getAllByBook(bookName: String): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE bookName = :bookName AND conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getAllByBookAndConversation(bookName: String, conversationId: String): List<ChatMessage>

    @Query("SELECT * FROM (SELECT * FROM chat_messages WHERE bookName = :bookName AND conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    suspend fun getRecentMessages(bookName: String, conversationId: String, limit: Int): List<ChatMessage>

    @Query("SELECT conversationId FROM chat_messages WHERE bookName = :bookName ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestConversationIdByBook(bookName: String): String?

    @Query("SELECT * FROM chat_messages WHERE content LIKE :query OR imageUri LIKE :query ORDER BY timestamp DESC")
    suspend fun search(query: String): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE bookName = :bookName AND (content LIKE :query OR imageUri LIKE :query) ORDER BY timestamp DESC")
    suspend fun searchByBook(bookName: String, query: String): List<ChatMessage>

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun count(): Int

    @Query("DELETE FROM chat_messages WHERE bookName = :bookName AND conversationId = :conversationId")
    suspend fun deleteByBookAndConversation(bookName: String, conversationId: String)

    @Query("UPDATE chat_messages SET bookName = :newBookName WHERE bookName = :oldBookName")
    suspend fun renameBookName(oldBookName: String, newBookName: String)

    @Query("DELETE FROM chat_messages WHERE bookName = :bookName")
    suspend fun deleteAllByBookName(bookName: String)

    @Query("UPDATE chat_messages SET bookName = :bookName, conversationId = :conversationId WHERE (bookName IS NULL OR bookName = '' OR conversationId IS NULL OR conversationId = '')")
    suspend fun migrateLegacyBookAndConversation(bookName: String, conversationId: String)

    @Query("UPDATE chat_messages SET conversationId = :conversationId WHERE conversationId IS NULL OR conversationId = ''")
    suspend fun migrateLegacyConversationId(conversationId: String)

    @Query("UPDATE chat_messages SET bookName = :bookName WHERE bookName IS NULL OR bookName = ''")
    suspend fun migrateLegacyBookName(bookName: String)

    @Update
    suspend fun update(msg: ChatMessage)

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getById(id: Long): ChatMessage?

    @Query("SELECT * FROM chat_messages WHERE bookName = :bookName AND conversationId = :conversationId AND msgType = :msgType ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestMessageByType(bookName: String, conversationId: String, msgType: Int): ChatMessage?
}
