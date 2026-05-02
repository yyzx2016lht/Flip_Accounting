package tao.test.flipaccounting.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale
import java.util.concurrent.Executors
import tao.test.flipaccounting.Logger
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.data.local.dao.AiRuleDao
import tao.test.flipaccounting.data.local.dao.AssetDao
import tao.test.flipaccounting.data.local.dao.BillDao
import tao.test.flipaccounting.data.local.dao.CategoryDao
import tao.test.flipaccounting.data.local.dao.ChatMessageDao
import tao.test.flipaccounting.data.local.entity.AiRule
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.Category
import tao.test.flipaccounting.data.local.entity.ChatMessage

@Database(
    entities = [Bill::class, Asset::class, Category::class, AiRule::class, ChatMessage::class],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao
    abstract fun assetDao(): AssetDao
    abstract fun categoryDao(): CategoryDao
    abstract fun aiRuleDao(): AiRuleDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private val ROOM_LOG_EXECUTOR = Executors.newSingleThreadExecutor()

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE Bill ADD COLUMN fee REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE assets ADD COLUMN creditLimit REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE bills ADD COLUMN relatedBillId INTEGER")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bills_relatedBillId ON bills(relatedBillId)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE assets ADD COLUMN billingDay INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE assets ADD COLUMN pickerSortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `msgType` INTEGER NOT NULL,
                        `content` TEXT NOT NULL DEFAULT '',
                        `imageUri` TEXT NOT NULL DEFAULT '',
                        `timestamp` INTEGER NOT NULL,
                        `billIds` TEXT NOT NULL DEFAULT '',
                        `modelName` TEXT NOT NULL DEFAULT ''
                    )"""
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Rebuild table to match Room schema exactly:
                // 1) no SQL DEFAULT on text columns
                // 2) no extra indices
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chat_messages_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `msgType` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `imageUri` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `billIds` TEXT NOT NULL,
                        `modelName` TEXT NOT NULL,
                        `bookName` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL
                    )"""
                )
                database.execSQL(
                    """INSERT INTO `chat_messages_new`
                       (`id`,`msgType`,`content`,`imageUri`,`timestamp`,`billIds`,`modelName`,`bookName`,`conversationId`)
                       SELECT
                         `id`,
                         `msgType`,
                         `content`,
                         `imageUri`,
                         `timestamp`,
                         `billIds`,
                         `modelName`,
                         '日常账本',
                         'legacy'
                       FROM `chat_messages`"""
                )
                database.execSQL("DROP TABLE `chat_messages`")
                database.execSQL("ALTER TABLE `chat_messages_new` RENAME TO `chat_messages`")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appCtx = context.applicationContext
                val instance = Room.databaseBuilder(
                    appCtx,
                    AppDatabase::class.java,
                    "flipaccounting_database"
                )
                    .setQueryCallback({ sql, args ->
                        if (!Prefs.isDeveloperFullLoggingEnabled(appCtx)) return@setQueryCallback
                        val normalized = sql.trim().uppercase(Locale.US)
                        val isWrite = normalized.startsWith("INSERT") ||
                            normalized.startsWith("UPDATE") ||
                            normalized.startsWith("DELETE") ||
                            normalized.startsWith("REPLACE")
                        if (!isWrite) return@setQueryCallback
                        Logger.d(
                            appCtx,
                            "DB_SQL",
                            "sql=${sql.replace("\n", " ").take(600)}; args=${args.joinToString(prefix = "[", postfix = "]").take(400)}"
                        )
                    }, ROOM_LOG_EXECUTOR)
                    .addMigrations(
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
