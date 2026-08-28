# 数据层（Room DB / Repository / 备份）Bug 审计

**共 16 个发现**: 🔴 0 Critical | 🟠 4 High | 🟡 8 Medium | 🟢 4 Low

## 🟠 High

### 1. BillDao.clearCategoryByName is a no-op query

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/dao/BillDao.kt`
- **行号**: 330-341
- **描述**: The clearCategoryByName query has WHERE categoryId IS NULL and then SET categoryId = NULL. Since it only matches rows where categoryId is already NULL, the UPDATE changes nothing. Bills that have categoryId=NULL but still carry the old categoryName are never cleaned up when a category is deleted.
- **影响**: When a user deletes a category without migrating bills, the old bills retain the deleted category's name in the categoryName field. If a new category with the same name is created later, those orphaned bills will incorrectly appear to belong to the new category.
- **建议修复**: Change the query to clear the categoryName instead of (or in addition to) categoryId. For example: SET categoryName = '' WHERE categoryId IS NULL AND (categoryName = :name OR categoryName LIKE '% - ' || :name OR ...). Alternatively, if the intent is to just be a guard, remove the method entirely since it does nothing.

### 2. targetDeltaInCurrency ignores target currency parameter

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillAssetImpactService.kt`
- **行号**: 292-294
- **描述**: targetDeltaInCurrency always returns bill.amount * bill.exchangeRate regardless of the _targetCurrency parameter (note the underscore prefix indicating it is intentionally unused). bill.exchangeRate is the rate from bill.currency to CNY, so this function always returns the CNY amount. When the target asset's currency is not CNY, the wrong delta is applied.
- **影响**: For cross-currency transfers where the target asset is not denominated in CNY (e.g., USD to EUR transfer), the target asset's balance is incorrectly updated with the CNY amount instead of the properly converted amount. This causes asset balance corruption.
- **建议修复**: Replace the body with: return BillAssetImpactService.convertAmountBetweenCurrencies(bill.amount, bill.currency, _targetCurrency). This ensures proper multi-hop currency conversion to the target asset's actual currency.

### 3. mergeRestoreFullData does not remap chat message bill references

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/BackupRepository.kt`
- **行号**: 298-303
- **描述**: In mergeRestoreFullData, chat messages are inserted with id=0 but their billIds and content fields still reference the old bill IDs from the backup. Unlike restoreFullData which calls remapChatBillReferences(), mergeRestoreFullData skips this step entirely.
- **影响**: After a merge restore, chat messages that reference bills (msgType=4) will have stale bill IDs that point to non-existent bills. This breaks the bill-reference chain in AI chat history, causing bill links in chat to fail or point to wrong bills.
- **建议修复**: Apply remapChatBillReferences(msg, billIdMap) before inserting each chat message in mergeRestoreFullData, matching the pattern used in restoreFullData.

### 4. Gson deserialization ignores Kotlin default values, causing potential NPE

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/DataExportManager.kt`
- **行号**: 12-19
- **描述**: DataExportManager uses Gson for deserialization of all entity types. Gson bypasses Kotlin constructors and default parameter values, using Java reflection to set fields directly. When a JSON field is missing (e.g., an older backup without 'bookName'), Gson sets non-nullable String fields to null instead of the Kotlin default (''). This violates Kotlin's null-safety guarantees.
- **影响**: Restoring a backup from an older app version that lacks newer fields will produce entities with null values in non-nullable fields. Subsequent database operations or property accesses will crash with NullPointerException or insert invalid data into the database.
- **建议修复**: Either switch to a Kotlin-aware JSON library (e.g., kotlinx.serialization or Moshi with Kotlin adapter), or add a post-deserialization validation/sanitization step that fills in defaults for null fields on each entity type.

## 🟡 Medium

### 1. mergeRestoreFullData silently drops deletedBills and investmentLots

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/BackupRepository.kt`
- **行号**: 161-167
- **描述**: The mergeRestoreFullData method does not accept deletedBills or investmentLots parameters. Its signature only includes assets, bills, categories, rules, and chatMessages. Compare with restoreFullData which processes all 7 data types.
- **影响**: Users performing a merge restore lose their deleted bills history and investment lot data permanently. The merge restore silently succeeds without these datasets, giving no indication that data was discarded.
- **建议修复**: Add deletedBills and investmentLots parameters to mergeRestoreFullData and implement deduplication/merge logic for them, or at minimum log a warning that these data types were not processed.

### 2. isRefundLikeRemark is overly broad, matching any remark containing the word refund

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/CsvManager.kt`
- **行号**: 279-286
- **描述**: The isRefundLikeRemark function has text.contains('退款') as its last check, making the three preceding prefix checks redundant. More importantly, ANY income bill whose remark contains the substring '退款' anywhere will be reclassified as SUBTYPE_REFUND, regardless of context.
- **影响**: Bills with remarks like '咨询退款政策', '这笔不是退款', or '已退款处理' that are genuine income transactions will be incorrectly classified as refunds during CSV import. This changes their statistical behavior and can cause incorrect reporting.
- **建议修复**: Remove the text.contains('退款') check. Keep only the prefix-based checks (startsWith) which are more precise. If broader matching is needed, require the remark to start with '退款' or contain '[退款]' or '【退款】' markers.

### 3. CSV import preserves original bill IDs, risking overwrite with REPLACE strategy

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/CsvManager.kt`
- **行号**: 163-180
- **描述**: importFlipCsv sets id = importedId from the CSV, preserving the original bill ID. When these bills are subsequently inserted via insertBill with OnConflictStrategy.REPLACE, any existing bill with the same ID is silently overwritten.
- **影响**: If a user imports a CSV that was exported from a different database (or a different book), bills with conflicting IDs will overwrite the user's existing bills without any warning. This is a silent data loss scenario.
- **建议修复**: Set id = 0L for imported bills (like importQianJi already does), forcing auto-generation of new IDs. Or add deduplication logic that checks for existing bills by content before inserting.

### 4. No fallbackToDestructiveMigration configured; versions < 5 crash on upgrade

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/AppDatabase.kt`
- **行号**: 330-363
- **描述**: The database builder registers migrations starting from version 5 (MIGRATION_5_6). There is no fallbackToDestructiveMigration() or fallbackToDestructiveMigrationFrom() call. If a user has a database at version 1, 2, 3, or 4, Room cannot find a migration path and throws IllegalStateException.
- **影响**: Extremely old users upgrading directly to the current version will experience a crash loop with 'Room cannot verify the data integrity' error. The only recovery is clearing app data, which loses all data.
- **建议修复**: Add .fallbackToDestructiveMigrationFrom(1, 2, 3, 4) to the database builder to handle early version upgrades gracefully. This destroys and recreates the database for those ancient versions.

### 5. AssetRepository.deleteAssetWithCleanup non-transaction path has no atomicity

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/AssetRepository.kt`
- **行号**: 55-63
- **描述**: When appDatabase is null, the deleteAssetWithCleanup method executes 5 sequential database operations without a transaction: backfillAssetLinksByName, clearAccountId, clearToAccountId, markDeletedAccountName, markDeletedToAccountName, and deleteAsset. If the app crashes mid-way, the database is left in an inconsistent state.
- **影响**: Partial execution can leave bills with dangling asset references (accountId pointing to a deleted asset) or assets that should have been deleted but remain. The severity depends on how often appDatabase is null in practice.
- **建议修复**: Always require a non-null AppDatabase reference and remove the non-transaction fallback path. If a fallback is truly needed, at minimum ensure deleteAsset is called first so that FK cascades handle the cleanup atomically.

### 6. mergeRestoreFullData duplicates all rules on every restore

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/BackupRepository.kt`
- **行号**: 289-295
- **描述**: Rules are inserted with id = 0 (forcing new auto-generated IDs) and OnConflictStrategy.REPLACE. There is no deduplication by keyword or content. Each merge restore appends all rules as new entries.
- **影响**: After multiple merge restores, the AI rules list accumulates duplicate entries. For example, 3 merge restores of the same backup would triple the number of rules. Users must manually identify and delete duplicates.
- **建议修复**: Add deduplication logic before inserting rules: check for existing rules with the same keyword (or keyword + targetType + targetCategory combination) and skip or update existing ones instead of always inserting new ones.

### 7. WebDAV uploadBackup loads entire backup file into memory

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/WebDavClient.kt`
- **行号**: 54-69
- **描述**: uploadBackup accepts a ByteArray parameter and AutoBackupWorker calls tempFile.readBytes() which loads the entire backup ZIP file into memory. For full-mode backups with chat media, the file can be tens or hundreds of megabytes.
- **影响**: On memory-constrained devices or when backup files are large (especially full mode with chat media), this can cause OutOfMemoryError crashes in the background worker.
- **建议修复**: Change uploadBackup to accept a File or InputStream parameter instead of ByteArray. Use OkHttp's streaming RequestBody that reads from the file on-demand rather than buffering the entire content in memory.

### 8. Income bill revert does not use baseOriginalAmount (inconsistent with expense)

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillAssetImpactService.kt`
- **行号**: 143-153
- **描述**: When reverting an income bill, the code uses bill.amount directly: convertAmountBetweenCurrencies(bill.amount, bill.currency, asset.currency). But when reverting an expense, it uses baseOriginalAmount(bill) which returns max(originalAmount, amount). This means if an income bill's amount was edited after creation, the revert uses the edited amount instead of the original amount.
- **影响**: Editing an income bill's amount (e.g., from 500 to 300) and then deleting it would revert 300 from the balance instead of the original 500, leaving the balance 200 higher than it should be. The asset balance becomes incorrect.
- **建议修复**: Change the income revert to use baseOriginalAmount(bill) consistently with the expense revert: val sourceDelta = convertAmountBetweenCurrencies(baseOriginalAmount(bill), bill.currency, asset.currency).

## 🟢 Low

### 1. Migration MIGRATION_5_6 references table 'Bill' but later migrations use 'bills'

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/AppDatabase.kt`
- **行号**: 42-46
- **描述**: MIGRATION_5_6 uses 'ALTER TABLE Bill ADD COLUMN ...' (PascalCase singular) while MIGRATION_7_8 and all subsequent migrations use 'ALTER TABLE bills ...' (lowercase plural). The entity definition has tableName = 'bills'. There is no migration that renames the table from 'Bill' to 'bills'.
- **影响**: If a user has a database at version 5 with the table actually named 'Bill', MIGRATION_5_6 would succeed but MIGRATION_7_8 would fail with 'no such table: bills'. Conversely, if the table was always 'bills', MIGRATION_5_6 would fail. In practice, this only affects users upgrading from very old versions (v5), which is unlikely in current usage.
- **建议修复**: Verify the actual table name at version 5 and fix the migration accordingly. If the table was renamed at some point, add a rename migration. If MIGRATION_5_6 has a typo, change 'Bill' to 'bills'.

### 2. MIGRATION_20_21 silently swallows all exceptions during ALTER TABLE

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/AppDatabase.kt`
- **行号**: 264-269
- **描述**: MIGRATION_20_21 wraps ALTER TABLE statements in try-catch that catches Exception (all exceptions) and silently ignores them with the comment 'column already exists'. This catches not just duplicate-column errors but also disk-full, permission, corruption, and other critical errors.
- **影响**: If a real error occurs during migration (e.g., disk full, database corruption), it would be silently swallowed. The migration would appear to succeed, but the columns might not actually be added, leading to crashes when the app tries to use those columns.
- **建议修复**: Catch only the specific SQLiteException for duplicate column errors, or verify the column exists before attempting the ALTER. Re-throw all other exceptions to prevent silent data corruption.

### 3. CategoryRepository.deleteById and deleteCategoryAndMigrateBills do not handle deep category trees

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/CategoryRepository.kt`
- **行号**: 134-139, 170-192
- **描述**: Both deleteById and deleteCategoryAndMigrateBills use getChildrenByParentId which only fetches direct children (one level deep). If a category tree has 3+ levels, grandchildren are not included in the migration or deletion logic. The entity schema allows arbitrary depth.
- **影响**: If the app ever allows 3-level category nesting, deleting a grandparent category would leave grandchildren as orphaned root categories with their bills still referencing them. The grandchildren's bills would not be migrated or cleaned up.
- **建议修复**: Implement recursive descendant collection, or enforce a maximum depth of 2 in the category tree and add a check before deletion.

### 4. saveOrderedCategoryTree silently orphans children if parent comes after child in input

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/CategoryRepository.kt`
- **行号**: 100-127
- **描述**: saveOrderedCategoryTree tracks the current parent using currentParentId, updated when a root category is encountered. If a child category appears before its parent in the input list, currentParentId is null and the child is silently promoted to a root (parentId set to null).
- **影响**: If the input list is not properly ordered (root before children), category hierarchy is silently corrupted. Children that should be nested under a parent become root-level categories, losing their hierarchical relationship.
- **建议修复**: Validate the input ordering, or build a parent map first. Throw an IllegalArgumentException if a child is encountered before its parent.

