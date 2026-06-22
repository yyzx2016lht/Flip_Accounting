# UI 层（Fragment / ViewModel / Dialog）Bug 审计

**共 17 个发现**: 🔴 0 Critical | 🟠 3 High | 🟡 9 Medium | 🟢 5 Low

## 🟠 High

### 1. StatsFragment: viewModel property delegate uses requireContext() before fragment is attached

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/stats/StatsFragment.kt`
- **行号**: 85-87
- **描述**: The StatsViewModel is initialized with: private val viewModel: StatsViewModel by viewModels { StatsViewModelFactory(AppDatabase.getDatabase(requireContext().applicationContext).billDao()) }. The viewModels delegate's factory lambda is called during fragment creation (when the ViewModel is first accessed). If the ViewModel is accessed before the fragment is attached to an activity (e.g., during fragment recreation by the system), requireContext() will throw IllegalStateException. This is a known pattern issue with by viewModels + requireContext() in the factory.
- **影响**: Crash during fragment recreation (e.g., configuration change) if the ViewModel provider triggers the factory before the fragment is attached.
- **建议修复**: Use activityViewModels with a shared ViewModel, or access the database lazily inside the ViewModel itself rather than passing it through the factory, or use a lazy initialization pattern.

### 2. AssetsFragment: db lazy property calls requireContext() which can crash before fragment is attached

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/assets/AssetsFragment.kt`
- **行号**: 89
- **描述**: private val db by lazy { AppDatabase.getDatabase(requireContext()) }. The lazy property is initialized on first access. If accessed before the fragment is attached (during fragment recreation), requireContext() will throw. This is accessed in onResume() via observeData() and also in setupAssetDrawer() during onCreateView. Since setupAssetDrawer is called from initViews which is called from onCreateView, and onCreateView is called after onAttach, this should be safe. However, the pattern is fragile.
- **影响**: Potential crash during fragment lifecycle edge cases.
- **建议修复**: Use requireContext().applicationContext or store the context reference after onAttach.

### 3. AssetsFragment: observeData() called from onCreateView sets up Flow collection without viewLifecycleOwner

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/assets/AssetsFragment.kt`
- **行号**: 355-369
- **描述**: observeData() is called from onCreateView (line 121), but it uses viewLifecycleOwner.lifecycleScope (line 356). This is correct. However, the function collects from a Room Flow (getAllAssets()) which emits on every database change. Every emission triggers buildCategoryCards() which calls containerCategoryCards.removeAllViews() and rebuilds all cards from scratch. This is very expensive for a Flow that fires on every single asset change.
- **影响**: Performance issue: full UI rebuild on every database emission, causing jank and wasted resources.
- **建议修复**: Use DiffUtil or compare the new list with the old one before rebuilding.

## 🟡 Medium

### 1. HomeViewModel.statsSnapshotCache is a companion object static field shared across all instances - potential stale data across process recreation

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/stats/StatsViewModel.kt`
- **行号**: 84
- **描述**: The statsSnapshotCache is declared as a companion object val (static). While this provides caching across Activity recreations, it uses linkedMapOf which is not thread-safe. Multiple coroutines in loadData() can read/write this map concurrently (one coroutine writes via putStatsCache, while another reads the cached value), risking ConcurrentModificationException or stale cache hits.
- **影响**: ConcurrentModificationException crash or returning stale data to the UI under rapid mode/date switching.
- **建议修复**: Replace linkedMapOf with a synchronized wrapper or use ConcurrentHashMap, or guard all access with a mutex.

### 2. HomeFragment: ensureChartController references homeAdapter before it may be initialized

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeFragment.kt`
- **行号**: 1001-1030
- **描述**: ensureChartController() is called from updateChartTitleLabel() which is called from onResume() (line 634). The chartController constructor receives homeAdapter via a direct reference. If ensureChartController() is called before uiListController.setupRecyclerView() sets the homeAdapter (which happens in onViewCreated), the homeAdapter lateinit property would not yet be initialized. However, since setupRecyclerView() is called before the StateFlow collection starts, and onResume comes after onViewCreated, this is safe in practice but fragile.
- **影响**: Potential UninitializedPropertyAccessException if calling order changes in future refactors.
- **建议修复**: Consider making chartController lazy and deriving homeAdapter from homeViewModel.adapter directly instead of relying on the lateinit field.

### 3. HomeBookDrawerController.refreshBookAccounts: uses fragment.requireContext() on IO thread after withContext(Main) guard, but calls it outside the guard

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeBookDrawerController.kt`
- **行号**: 271-330
- **描述**: In refreshBookAccounts(), the function starts with a fragment.isAdded check, then launches an IO coroutine. Inside the IO coroutine, it calls fragment.requireContext().applicationContext (line 274) which is safe since it uses applicationContext. However, the function also calls BookAccountManager operations that take context. If the fragment is destroyed between the IO launch and the completion, the coroutine may try to access fragment.requireContext() after detach. The function does guard the Main-thread callback with isAdded, but the IO work itself could theoretically fail.
- **影响**: Potential IllegalStateException if fragment is detached during IO work, though the use of applicationContext mitigates this in practice.
- **建议修复**: Capture applicationContext before launching the coroutine, or use fragment.context?.applicationContext with a null check.

### 4. HomeRefreshController.observeBillTableChanges: calls forceReload on every invalidation, potentially causing rapid redundant queries

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeRefreshController.kt`
- **行号**: 83-111
- **描述**: The InvalidationTracker observer calls homeViewModel.forceReload() on every bill table change. While there is a 260ms debounce, the debounce only applies per observer callback. If Room emits multiple invalidation callbacks in quick succession (e.g., during a batch insert that Room processes as multiple transactions), each will cancel the previous debounce job and restart the timer, effectively never debouncing. This can lead to many redundant forceReload calls.
- **影响**: Performance degradation during batch bill operations - multiple redundant DB queries and UI updates.
- **建议修复**: Use a single debounce timer that restarts on each invalidation, rather than cancel-and-recreate. The current implementation actually does cancel-and-recreate which IS a debounce, but the 260ms window may be too short for rapid invalidations. Consider increasing to 500ms+.

### 5. StatsFragment.showBookFilterDialog: uses requireContext() in IO coroutine without guarding fragment lifecycle

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/stats/StatsFragment.kt`
- **行号**: 948-979
- **描述**: showBookFilterDialog launches a lifecycleScope coroutine that calls requireContext() on the IO dispatcher (line 950). While the Main dispatcher callback guards with isAdded, the IO work itself calls requireContext().applicationContext which will crash if the fragment is detached. lifecycleScope should cancel on destroy, but there's a race window.
- **影响**: Potential IllegalStateException if fragment is destroyed between launch and the IO work completing.
- **建议修复**: Capture context before launching, or use fragment.context?.applicationContext with null guard.

### 6. AssetDetailActivity: TransactionAdapter uses adapterPosition (deprecated) instead of bindingAdapterPosition

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/assets/AssetDetailActivity.kt`
- **行号**: 1162-1164
- **描述**: In BillViewHolder.bind(), the click listener uses adapterPosition (line 1162) which is deprecated and may return NO_POSITION during layout animations. The code checks for NO_POSITION, but using bindingAdapterPosition is the recommended approach.
- **影响**: Potential stale position during item animations, though the NO_POSITION check mitigates crashes.
- **建议修复**: Replace adapterPosition with bindingAdapterPosition.

### 7. StatsFragment: syncHomeDateFromStatsIfNeeded calls homeViewModel.setMonth which can trigger recursive data loads

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/stats/StatsFragment.kt`
- **行号**: 257-268
- **描述**: syncHomeDateFromStatsIfNeeded() is called from within the uiState.collect block (line 488). It calls homeViewModel.setMonth() which updates the HomeViewModel's state and triggers a new data load. The HomeFragment also collects from homeViewModel.uiState and when it receives the new state, it calls syncDateFromSessionIfNeeded() which could trigger another update back. While there are guards (e.g., checking if values actually changed), this cross-ViewModel bidirectional sync pattern can cause oscillation.
- **影响**: Potential infinite loop or redundant data loads when switching between Home and Stats tabs with different dates.
- **建议修复**: Add a flag to prevent re-entrant sync calls, or use a shared event bus instead of direct ViewModel calls.

### 8. ProfileFragment: CoroutineScope(Dispatchers.IO).launch creates unmanaged coroutine that survives fragment destruction

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/profile/ProfileFragment.kt`
- **行号**: 347
- **描述**: btnSyncRemoteConfig click handler uses CoroutineScope(Dispatchers.IO).launch instead of lifecycleScope.launch. This creates a coroutine that is not tied to the fragment's lifecycle and will continue running even after the fragment is destroyed. If the fragment is destroyed before the coroutine completes, the withContext(Dispatchers.Main) callback may try to show a toast on a detached context.
- **影响**: Memory leak and potential crash when showing toast after fragment destruction.
- **建议修复**: Replace CoroutineScope(Dispatchers.IO) with viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO).

### 9. ProfileFragment: rootRef stored as strong reference can leak the view hierarchy

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/profile/ProfileFragment.kt`
- **行号**: 47
- **描述**: rootRef is set in onViewCreated and cleared in onDestroyView. However, it is accessed in onResume, onPause, and onHiddenChanged which can be called after onDestroyView in edge cases. While the null check (rootRef?.let) prevents crashes, holding a strong reference to the root view between view destruction and the next onViewCreated can prevent garbage collection of the old view hierarchy.
- **影响**: Temporary memory leak of the entire view hierarchy between onDestroyView and the next onCreateView.
- **建议修复**: This is already mitigated by setting rootRef = null in onDestroyView. Consider using WeakReference if leak becomes an issue.

## 🟢 Low

### 1. HomeFragment: barChart is assigned twice in onViewCreated - first assignment is wasted

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeFragment.kt`
- **行号**: 273-276
- **描述**: Line 273 assigns barChart from the main view, then line 276 re-assigns it from the inflated cvChartContainer. The first assignment at line 273 is dead code. While not a crash bug, it is confusing and suggests the layout XML still contains a barChart that is never used.
- **影响**: Wasted binding, potential confusion for future maintainers. No runtime crash.
- **建议修复**: Remove line 273 (the first barChart assignment from view.findViewById).

### 2. HomeMultiSelectController: billsToDelete snapshot captured at click time but deletion runs asynchronously - selection could change

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeMultiSelectController.kt`
- **行号**: 57-79
- **描述**: When the delete button is clicked, the code captures billsToDelete via getHomeAdapter().selectedBills.toList() (line 58). This is correct as it creates a snapshot. However, the deletion runs asynchronously via lifecycleScope.launch without Dispatchers.IO, meaning it runs on the Main thread. The BillDeleteHelper.deleteBillsAndRevertBalance call may do heavy IO work on the main thread.
- **影响**: Potential ANR if the delete operation involves heavy database work on the main thread.
- **建议修复**: Add Dispatchers.IO to the coroutine launch for the deletion work.

### 3. HomeBillSheetsController: showRefundSheet creates refundBill with id=0 for new refunds but the Bill data class may not handle this correctly

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeBillSheetsController.kt`
- **行号**: 728-739
- **描述**: When creating a new refund bill (editingRefund is null), the code sets id = editingRefund?.id ?: 0. If Bill's id column in Room is set to autoGenerate and 0 is treated as 'no id', this is fine. But if Room treats 0 as a valid id, it could cause conflicts.
- **影响**: Potential database conflict if id=0 is not handled as auto-generated.
- **建议修复**: Verify Room's @PrimaryKey(autoGenerate = true) behavior with id=0 - typically Room treats 0 as 'auto-generate', so this should be safe.

### 4. HomeFragment: crossfadeSummaryAmounts calls updateSummary which modifies TextViews, but the crossfade compares old/new text - race condition with rapid emissions

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeFragment.kt`
- **行号**: 1060-1086
- **描述**: crossfadeSummaryAmounts captures old text, calls updateSummary, then captures new text and compares. If the StateFlow emits rapidly (e.g., during fast data updates), a second emission could arrive between the old/new captures, causing the crossfade to animate to an intermediate state rather than the final state.
- **影响**: Visual glitch: summary amounts may briefly show incorrect values during rapid updates.
- **建议修复**: This is a minor UX issue and unlikely to cause data corruption. The animation duration is short (180ms) so it self-corrects quickly.

### 5. HomeChartController.updateChart: chart value formatter uses locale-specific formatting that may produce unexpected results

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeChartController.kt`
- **行号**: 306-319
- **描述**: The formatterK ValueFormatter has logic to shorten amounts (e.g., 1000 -> 1K). The string replacement logic on line 312 (s.replace('.00K', 'K').replace('0K', 'K')) is fragile - it would incorrectly transform '100K' into '1K' because '0K' is replaced globally. For example, 100000 would format as '100.00K' -> '100K' -> '1K' after the replace.
- **影响**: Incorrect chart label display for values >= 100,000 (showing 1K instead of 100K).
- **建议修复**: Fix the regex to only match trailing patterns: use replace(Regex("\.0+K$"), "K") instead of chained replace calls.

