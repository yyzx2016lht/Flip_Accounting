package tao.test.tapaccounting.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import tao.test.tapaccounting.data.local.dao.AiRuleDao
import tao.test.tapaccounting.data.local.dao.AssetDao
import tao.test.tapaccounting.data.local.dao.BillDao
import tao.test.tapaccounting.data.local.dao.CategoryDao
import tao.test.tapaccounting.data.local.dao.ChatMessageDao
import tao.test.tapaccounting.data.local.dao.DeletedBillDao
import tao.test.tapaccounting.data.local.dao.InvestmentLotDao
import tao.test.tapaccounting.data.local.entity.AiRule
import tao.test.tapaccounting.data.local.entity.Asset
import tao.test.tapaccounting.data.local.entity.Bill
import tao.test.tapaccounting.data.local.entity.Category
import tao.test.tapaccounting.data.local.entity.ChatMessage
import tao.test.tapaccounting.data.local.entity.DeletedBill
import tao.test.tapaccounting.data.local.entity.InvestmentLot

@Database(
    entities = [Bill::class, Asset::class, Category::class, AiRule::class, ChatMessage::class, InvestmentLot::class, DeletedBill::class],
    version = 17,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao
    abstract fun assetDao(): AssetDao
    abstract fun categoryDao(): CategoryDao
    abstract fun aiRuleDao(): AiRuleDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun investmentLotDao(): InvestmentLotDao
    abstract fun deletedBillDao(): DeletedBillDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

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

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                database.execSQL("ALTER TABLE assets ADD COLUMN annualInterestRate REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE assets ADD COLUMN interestLastSettledAt INTEGER NOT NULL DEFAULT $now")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `investment_lots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `assetId` INTEGER NOT NULL,
                        `sourceBillId` INTEGER,
                        `principalAmount` REAL NOT NULL,
                        `remainingPrincipal` REAL NOT NULL,
                        `currency` TEXT NOT NULL,
                        `startEarningAt` INTEGER NOT NULL,
                        `firstPayoutAt` INTEGER NOT NULL,
                        `lastSettledAt` INTEGER NOT NULL,
                        `createTime` INTEGER NOT NULL,
                        FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`sourceBillId`) REFERENCES `bills`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )"""
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_lots_assetId` ON `investment_lots` (`assetId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_investment_lots_sourceBillId` ON `investment_lots` (`sourceBillId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_lots_lastSettledAt` ON `investment_lots` (`lastSettledAt`)")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP INDEX IF EXISTS `index_investment_lots_assetId`")
                database.execSQL("DROP INDEX IF EXISTS `index_investment_lots_sourceBillId`")
                database.execSQL("DROP INDEX IF EXISTS `index_investment_lots_lastSettledAt`")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `investment_lots_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `assetId` INTEGER NOT NULL,
                        `sourceBillId` INTEGER,
                        `principalAmount` REAL NOT NULL,
                        `remainingPrincipal` REAL NOT NULL,
                        `currency` TEXT NOT NULL,
                        `startEarningAt` INTEGER NOT NULL,
                        `firstPayoutAt` INTEGER NOT NULL,
                        `lastSettledAt` INTEGER NOT NULL,
                        `createTime` INTEGER NOT NULL,
                        FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`sourceBillId`) REFERENCES `bills`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )"""
                )
                database.execSQL(
                    """INSERT INTO `investment_lots_new`
                       (`id`,`assetId`,`sourceBillId`,`principalAmount`,`remainingPrincipal`,`currency`,`startEarningAt`,`firstPayoutAt`,`lastSettledAt`,`createTime`)
                       SELECT `id`,`assetId`,`sourceBillId`,`principalAmount`,`remainingPrincipal`,`currency`,`startEarningAt`,`firstPayoutAt`,`lastSettledAt`,`createTime`
                       FROM `investment_lots`"""
                )
                database.execSQL("DROP TABLE `investment_lots`")
                database.execSQL("ALTER TABLE `investment_lots_new` RENAME TO `investment_lots`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_lots_assetId` ON `investment_lots` (`assetId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_investment_lots_sourceBillId` ON `investment_lots` (`sourceBillId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_lots_lastSettledAt` ON `investment_lots` (`lastSettledAt`)")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE bills ADD COLUMN excludeFromStats INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `deleted_bills` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `originalBillId` INTEGER NOT NULL,
                        `type` INTEGER NOT NULL,
                        `subType` INTEGER NOT NULL DEFAULT 0,
                        `amount` REAL NOT NULL,
                        `originalAmount` REAL NOT NULL,
                        `currency` TEXT NOT NULL DEFAULT 'CNY',
                        `exchangeRate` REAL NOT NULL DEFAULT 1.0,
                        `categoryId` INTEGER,
                        `accountId` INTEGER,
                        `toAccountId` INTEGER,
                        `categoryName` TEXT NOT NULL DEFAULT '',
                        `accountName` TEXT NOT NULL DEFAULT '',
                        `toAccountName` TEXT NOT NULL DEFAULT '',
                        `time` INTEGER NOT NULL,
                        `remark` TEXT NOT NULL DEFAULT '',
                        `fee` REAL NOT NULL DEFAULT 0.0,
                        `bookName` TEXT NOT NULL DEFAULT '日常账本',
                        `relatedBillId` INTEGER,
                        `excludeFromStats` INTEGER NOT NULL DEFAULT 0,
                        `deletedAt` INTEGER NOT NULL
                    )"""
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appCtx = context.applicationContext
                val instance = Room.databaseBuilder(
                    appCtx,
                    AppDatabase::class.java,
                    "TapAccount_database"
                )
                    .addMigrations(
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
