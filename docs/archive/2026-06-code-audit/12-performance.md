# 性能优化审计报告

**共 21 个发现**: 🔴 0 Critical | 🟠 5 High | 🟡 11 Medium | 🟢 5 Low

## 🟠 High

### 1. N+1 update loop in BillBalanceSnapshotService.rebuildSnapshotsForAsset

- **分类**: N+1 query patterns
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillBalanceSnapshotService.kt`
- **行号**: 32-38
- **描述**: For each bill returned by getBillsByAssetIdOrNameList(), an individual UPDATE query is issued via db.billDao().updateBill(patched). With hundreds or thousands of bills per asset, this generates one UPDATE per bill instead of a single batched operation.
- **影响**: Rebuilding balance snapshots for an asset with 500 bills executes 500 separate UPDATE statements inside a loop, causing hundreds of milliseconds of DB I/O and potential ANR if called on a tight path.
- **建议修复**: Add a batch update method to BillDao (e.g. UPDATE bills SET accountBalanceAfter = CASE WHEN id=? THEN ? ... END WHERE id IN (...)) or use a @Transaction wrapper with a single SQL statement that updates all bill rows at once.

### 2. N+1 loop in BillBalanceSnapshotService.rebuildAllAssetSnapshots

- **分类**: N+1 query patterns
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillBalanceSnapshotService.kt`
- **行号**: 13-16
- **描述**: rebuildAllAssetSnapshots iterates all assets and calls rebuildSnapshotsForAsset() for each one. Each inner call fetches all bills for that asset and then updates them one by one. Total queries = assets + sum(bills_per_asset) + sum(bills_per_asset UPDATEs).
- **影响**: With 10 assets each having 200 bills, this produces 10 + 2000 + 2000 = 4010 queries in sequence. This is O(N*M) complexity that scales linearly with data volume.
- **建议修复**: Use a single JOIN-based query that computes and writes all balance snapshots in one pass, or at minimum batch the updates per asset using a single multi-row UPDATE.

### 3. Full table scan fallback in resolveAssetByReference

- **分类**: Unnecessary full table scans
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillAssetImpactService.kt`
- **行号**: 243-260
- **描述**: When assetId is null and getAssetByName() fails, the code calls getAllAssetsList() to load the entire assets table, then iterates in Kotlin to find a normalized name match. This is a full table scan performed on every bill insertion where the asset name doesn't exactly match.
- **影响**: Every time a bill is inserted with a loosely-matching asset name, the entire assets table is loaded into memory and scanned linearly. For users with many assets, this adds unnecessary memory allocation and CPU time on the critical bill-save path.
- **建议修复**: Add a normalized name column to the assets table with an index, or create a database view/function that handles the normalization. Alternatively, maintain an in-memory cache of normalized asset names in BillAssetImpactService.

### 4. No composite index on (bookName, time) for the primary query pattern

- **分类**: Missing database indexes
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/entity/Bill.kt`
- **行号**: 15-22
- **描述**: The most frequently executed query getBillsByBookNamesBetweenTimes filters by bookName IN (...) AND time BETWEEN ... and sorts by time DESC. There are individual indexes on bookName and time, but no composite index. SQLite can only use one index per table scan, so it picks the more selective one and filters the rest in-memory.
- **影响**: The main home screen query executes every time the user switches months, books, or pulls to refresh. Without a composite index, SQLite performs a range scan on one column and applies the other filter to every matching row, which is slower than a composite B-tree lookup.
- **建议修复**: Add a composite index: Index(value = ["bookName", "time"]) to the @Entity annotation on Bill. This covers the most common query pattern and eliminates the need for SQLite to intersect single-column indexes.

### 5. Prefs read on every sensor event in TapDetector.onSensorChanged

- **分类**: Main thread blocking
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapDetector.kt`
- **行号**: 171
- **描述**: Prefs.isTapTripleEnabled(context) reads from SharedPreferences on every onSensorChanged() callback. Sensor events fire at 400Hz (every 2.5ms). SharedPreferences.getString() involves a synchronized read from an in-memory map, but the overhead accumulates rapidly at this rate.
- **影响**: At 400Hz, this adds ~400 SharedPreferences reads per second in the sensor callback path. While individually fast, the cumulative CPU cost and GC pressure from String allocations is significant for a battery-sensitive always-on sensor listener.
- **建议修复**: Cache the tripleEnabled value once when TapDetector is started or when settings change. Use a volatile boolean field that is set during start()/restart() and updated only when preferences actually change.

## 🟡 Medium

### 1. Calendar.getInstance() called per bill in isBillInSelectedMonth/isBillInSelectedYear

- **分类**: Redundant computations that could be cached
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeViewModel.kt`
- **行号**: 277-287
- **描述**: isBillInSelectedMonth() and isBillInSelectedYear() create a new Calendar.getInstance() for every single bill during filtering. Calendar.getInstance() involves timezone lookup, locale resolution, and object allocation. This runs on Dispatchers.Default for every DB emission.
- **影响**: With 1000 bills per month, 1000 Calendar objects are allocated and immediately discarded. This generates GC pressure on the Default dispatcher thread pool. Calendar.getInstance() alone is ~2-5 microseconds per call.
- **建议修复**: Pre-compute the target year/month range as epoch milliseconds (which is already done for the DB query) and use simple arithmetic comparison: bill.time >= monthStart && bill.time <= monthEnd. No Calendar needed.

### 2. Heavy updateChart work runs entirely on main thread

- **分类**: UI thread work that should be on background thread
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeChartController.kt`
- **行号**: 237-378
- **描述**: updateChart() performs date iteration with SimpleDateFormat.format(), builds expense/income maps, iterates all transactions, creates BarEntry lists, and configures the chart -- all on the main thread. The method itself logs [Xms on main thread].
- **影响**: With many transactions in the chart time range (up to 30 days of data), the date formatting and grouping loop blocks the main thread. The explicit log message confirms the developer is aware this runs on the UI thread.
- **建议修复**: Move the data aggregation loop (lines 244-281) to Dispatchers.Default, then post only the BarData configuration and barChart.notifyDataSetChanged() to the main thread.

### 3. Memory churn: ArrayList allocation in TapRT.recognizeTapML feature vector pipeline

- **分类**: Memory churn in signal processing pipeline
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapRT.kt`
- **行号**: 167, 192-194, 202-206
- **描述**: Multiple hot-path allocations: (1) recognizeTapHeuristic() creates new ArrayList(_zsAcc) every time a peak is detected (line 167). (2) recognizeTapML() calls _tflite.predict() which internally allocates 4D input arrays and output HashMaps on every call. (3) reset() creates a new ArrayList with 300 Float entries. (4) TfClassifier.predict12() creates a 4D array via reflection on every inference.
- **影响**: These allocations occur on the sensor processing thread at up to 400Hz. Each ML inference creates multiple temporary arrays (300 floats * 4 dimensions) plus output arrays. This generates 10s of KB of garbage per inference cycle, causing GC pauses that can delay sensor processing.
- **建议修复**: Pre-allocate the feature vector, input arrays, and output arrays once during initialization and reuse them by clearing/resetting values in place. Use FloatArray instead of ArrayList<Float> for the feature vector to avoid boxing.

### 4. Autoboxing Long.valueOf in sensor callback hot path

- **分类**: Unnecessary object allocation in hot paths
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/tap/TapRT.kt`
- **行号**: 287
- **描述**: In updateML(), the line _tBackTapTimestamps.addLast(java.lang.Long.valueOf(lastT)) explicitly boxes a primitive long into a java.lang.Long object. This runs every time a back tap is detected in the ML path.
- **影响**: Each call allocates a Long object on the heap. While ArrayDeque<Long> requires boxed types in Kotlin/JVM, using the explicit java.lang.Long.valueOf() is worse than the implicit boxing because it bypasses the JVM's Long cache for values outside -128..127. Timestamp values are always outside this range.
- **建议修复**: Replace with _tBackTapTimestamps.addLast(lastT) and let Kotlin handle the boxing. For a more fundamental fix, use a primitive long ring buffer instead of ArrayDeque<Long> to avoid boxing entirely.

### 5. Chat image Glide loads disable all caching (DiskCacheStrategy.NONE + skipMemoryCache)

- **分类**: Large image loading without proper sizing/caching
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatAdapters.kt`
- **行号**: 165-175
- **描述**: In UserVH.bind() for MSG_TYPE_USER_IMAGE, Glide is configured with .diskCacheStrategy(DiskCacheStrategy.NONE) and .skipMemoryCache(true). This means every bind re-decodes the full image from the content URI, performing the complete decode pipeline (read bytes, decompress, transform) on every scroll.
- **影响**: Scrolling through a chat with images triggers full image decode on every visible bind. For a 4MB photo, this means 4MB of I/O + decompression per bind. With RecyclerView item cache size of 36, this can cause visible jank during fast scrolling.
- **建议修复**: Use DiskCacheStrategy.RESOURCE with skipMemoryCache(false) to allow both memory and disk caching. If the concern is stale content after edits, use a cache key based on the URI's last-modified timestamp.

### 6. Sequential Glide downloads in CategoryIconPreloader

- **分类**: Missing pagination for large datasets
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/CategoryIconPreloader.kt`
- **行号**: 56-67
- **描述**: The preload loop iterates all URLs and calls .submit().get() for each one sequentially. Each .get() blocks the current coroutine until that single download completes before starting the next.
- **影响**: With 50+ category icons, the preloader blocks the IO dispatcher for 50+ sequential HTTP requests. If each takes 200ms, the entire preload takes 10+ seconds, tying up an IO thread and delaying other IO work.
- **建议修复**: Use coroutine async/await to parallelize downloads (e.g. allUrls.map { async { download(it) } }.awaitAll()), or use Glide's preload() method which returns a future that can be composed.

### 7. Regex compiled on every call in normalizeCategoryDisplayName

- **分类**: Redundant computations that could be cached
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillDisplayFormatter.kt`
- **行号**: 17-20
- **描述**: normalizeCategoryDisplayName() creates two new Regex objects on every invocation: Regex("\\s*(/:::/|/::/|[>]|::|·|-)\\s*") and Regex("\\s+"). This method is called from formatCategoryByPreference which is called during every onBindViewHolder for bill list items.
- **影响**: Each Regex() constructor compiles the pattern to a Java Pattern object. With 20 visible bill items, this is 40 pattern compilations per scroll frame. Regex compilation is non-trivial (~10-50 microseconds each).
- **建议修复**: Hoist the Regex objects to companion object constants: private val SEPARATOR_REGEX = Regex(...); private val WHITESPACE_REGEX = Regex(...). Then use them as SEPARATOR_REGEX.replace(...) in the method.

### 8. No index on Asset.name for getAssetByName query

- **分类**: Missing database indexes
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/entity/Asset.kt`
- **行号**: 6
- **描述**: AssetDao.getAssetByName() queries WHERE name = :name. The assets table has no index on the name column. This query is called from BillAssetImpactService.resolveAssetByReference() on every bill save where assetId is null.
- **影响**: Each bill insertion with a name-based asset lookup performs a full table scan on assets. With 20 assets this is negligible, but the lack of index means SQLite must compare every row.
- **建议修复**: Add Index(value = ["name"]) to the @Entity annotation on Asset. This enables O(log n) lookups instead of O(n) scans.

### 9. Backfill subquery per row in BillDao.backfillAccountIdByName/backfillToAccountIdByName

- **分类**: N+1 query patterns
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/dao/BillDao.kt`
- **行号**: 63-88
- **描述**: backfillAccountIdByName uses a correlated subquery (SELECT assets.id FROM assets WHERE assets.name = bills.accountName LIMIT 1) inside an UPDATE statement. SQLite executes this subquery once per qualifying bill row. backfillToAccountIdByName does the same for toAccountId.
- **影响**: For N unlinked bills, SQLite executes N subqueries against the assets table. If assets has no index on name (which it does not), each subquery is an O(M) scan. Total cost: O(N*M) where N = unlinked bills, M = assets.
- **建议修复**: Add an index on assets.name, or use a JOIN-based UPDATE: UPDATE bills SET accountId = assets.id FROM assets WHERE assets.name = bills.accountName AND bills.accountId IS NULL.

### 10. notifyItemRangeChanged(0, adapter.itemCount) refreshes all items on currency cache update

- **分类**: UI thread work that should be on background thread
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeDataController.kt`
- **行号**: 29-31
- **描述**: refreshAccountCurrencyCache() calls adapter.notifyItemRangeChanged(0, adapter.itemCount) after updating the currency cache. This triggers a full rebind of every visible item in the RecyclerView, even if the currency data hasn't changed for those items.
- **影响**: With 20+ visible bill items, every item is rebound including Glide image loads, text formatting, and SpannableString creation. This causes visible jank especially when combined with the main thread chart updates.
- **建议修复**: Use DiffUtil or targeted payload-based updates that only rebind items whose currency display actually changed. Alternatively, check if the currency maps actually changed before triggering the full refresh.

### 11. CategoryIconHelper.findCategoryIcon called per bill card in AiBillVH.bind

- **分类**: N+1 query patterns
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatAdapters.kt`
- **行号**: 658-668
- **描述**: In AiBillVH.bind(), for each bill card in the container, a coroutine is launched that calls CategoryIconHelper.findCategoryIcon() on Dispatchers.IO, which likely involves a database lookup per bill. With multiple bills per AI message, this spawns multiple concurrent DB queries.
- **影响**: An AI message containing 5 bills spawns 5 coroutines each querying the category database. This creates database contention and unnecessary parallel queries for what could be a single batch lookup.
- **建议修复**: Batch all category icon lookups before the forEachIndexed loop: collect all unique (categoryName, type) pairs, resolve them in one pass, then use the results during card inflation.

## 🟢 Low

### 1. SimpleDateFormat created per-invoke in HomeChartController instead of reused

- **分类**: Redundant computations that could be cached
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeChartController.kt`
- **行号**: 251-252, 272
- **描述**: dfChartKey.format() is called once per date in the while loop and once per transaction in the for loop. While the SimpleDateFormat instances are injected (lines 49-51), the Date objects are created inline: Date(t.time) for each transaction.
- **影响**: With 200+ transactions, 200+ Date objects are allocated for the chart formatting loop. Each Date allocation is small but contributes to GC pressure on the main thread.
- **建议修复**: Use a single Calendar instance and set its timeInMillis before formatting, or switch to java.time.Instant/LocalDate APIs that avoid the Date allocation. For the date iteration loop, reuse a single Calendar object.

### 2. Duplicate time range calculation logic between HomeViewModel and HomeChartController

- **分类**: Redundant computations that could be cached
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeChartController.kt`
- **行号**: 205-235
- **描述**: getStartTimeFromRange() and getEndTimeFromRange() are duplicated almost identically in HomeViewModel (lines 312-342) and HomeChartController (lines 205-235). Both create Calendar instances and compute the same date ranges.
- **影响**: Not a performance issue per se, but the duplicated code means Calendar allocation happens twice when it only needs to happen once. More importantly, any optimization applied to one copy may be missed in the other.
- **建议修复**: Extract these utility functions into a shared DateRangeUtils object. This eliminates duplication and ensures any performance improvements (like caching Calendar instances) apply everywhere.

### 3. No index on Bill.isSynced for getUnsyncedBills query

- **分类**: Missing database indexes
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/entity/Bill.kt`
- **行号**: 15-22
- **描述**: BillDao.getUnsyncedBills() queries WHERE isSynced = 0. There is no index on isSynced. This query is used for cloud sync operations.
- **影响**: Cloud sync must scan the entire bills table to find unsynced rows. With 10000+ bills, most of which are synced, this is wasteful. However, if sync runs infrequently and most bills are unsynced, the impact is minimal.
- **建议修复**: Add Index(value = ["isSynced"]) to the Bill entity. For a boolean column with low cardinality, the index helps most when the minority value (0) is queried.

### 4. Regex compiled on each call in normalizeAssetName

- **分类**: Redundant computations that could be cached
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillAssetImpactService.kt`
- **行号**: 273
- **描述**: normalizeAssetName() creates a new Regex("\\s+") on every call. This method is called inside the full table scan fallback path, potentially multiple times per asset during name matching.
- **影响**: Regex compilation on each call in a loop over all assets adds unnecessary overhead. The impact is amplified because this code path already runs inside a full table scan.
- **建议修复**: Hoist the regex to a companion val: private val WHITESPACE_REGEX = Regex("\\s+") and reuse it.

### 5. buildRefreshSnapshot iterates all bills computing hash on main thread

- **分类**: UI thread work that should be on background thread
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeRefreshController.kt`
- **行号**: 128-145
- **描述**: buildRefreshSnapshot() iterates every bill computing a hash signature using Double.doubleToLongBits, String.hashCode(), and multiplications. This runs on the main thread during pull-to-refresh and again when the new data arrives.
- **影响**: With 1000 bills, this performs 9000+ hash operations on the main thread. While individually fast, it contributes to the overall frame budget during refresh animations.
- **建议修复**: Move the snapshot computation to Dispatchers.Default. The result is only used for a string comparison, so it can be computed off the UI thread.

