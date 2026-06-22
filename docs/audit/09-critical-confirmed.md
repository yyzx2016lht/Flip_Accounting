# 经对抗验证确认的 Critical/High 级 Bug

以下 8 个 Bug 经过独立验证确认为真实问题：

### 1. Memory/coroutine leak: AiAssistant.scope never cancelled

- **严重程度**: high
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AiAssistant.kt`
- **行号**: 42
- **描述**: AiAssistant creates a CoroutineScope with SupervisorJob() + Dispatchers.IO that is never cancelled. The class holds a Context reference and launches network coroutines on this scope. dismiss() only cancels analyzeJob, not the scope itself, so any in-flight coroutines will leak the Context and continue running after the AiAssistant is no longer needed.
- **影响**: Context leak (Activity or Service), wasted network resources, potential crashes from callbacks on a dead context.
- **建议修复**: Add a destroy/cleanup method that calls scope.cancel(), or tie the scope to a lifecycle.
- **验证置信度**: 0.75
- **验证理由**: The bug is real but severity is overstated. The scope (SupervisorJob + Dispatchers.IO) at line 42 is never cancelled - confirmed zero calls to scope.cancel() anywhere in the file. dismiss() only cancels analyzeJob, not the scope. All callers (OverlayManager.removeOverlay, OverlayService.onDestroy, AddBillEntrySheetLauncher, AccountingFormController) fail to call dismiss() or cancel the scope during teardown. This means an in-flight coroutine (network call to AIService) can hold the Context alive via Dispatchers.IO GC root -> lambda closure -> AiAssistant -> ctx after the parent component is destroyed. However, severity should be medium not high because: (1) there is at most one in-flight coroutine at a time, (2) the leak is temporary (bounded by network timeout), (3) dismiss() is called in all normal user-driven flows (close button, result confirmation), (4) the coroutine guards UI access with isShowing checks. The fix is simple: add scope.cancel() to dismiss(), or accept a lifecycle-aware CoroutineScope from the caller.

### 2. Fragment commitNow() after state save causes IllegalStateException

- **严重程度**: critical
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/MainActivity.kt`
- **行号**: 332, 588, 646, 672, 684
- **描述**: Multiple calls to commitNow() inside swipe gesture callbacks and animation end listeners (onSwipeStart at line 332, commitSwipe animation end at line 588, snapBack animation end at line 646, switchTab at lines 672 and 684) will throw IllegalStateException if the activity's state has already been saved (e.g., during rotation or system-initiated process death). Unlike commitNowAllowingStateLoss() used elsewhere (lines 285, 530, 728), these calls do not protect against post-save state.
- **影响**: Crash (IllegalStateException) when the device rotates or the system kills the activity while a tab-switch animation or swipe gesture is in progress. The user sees a force-close dialog.
- **建议修复**: Replace commitNow() with commitNowAllowingStateLoss() in all animation-end listeners and gesture callbacks, or add isFinishing/isStateSaved guards before each transaction.
- **验证置信度**: 0.92
- **验证理由**: PARTIALLY CONFIRMED: The bug is real at 2 of the 5 claimed locations. The claim correctly identifies the animation-end-listener issue but overstates scope by including locations that are safe or already mitigated.

CONFIRMED at lines 591 and 648 (commitSwipe and snapBack onAnimationEnd):
- `commitSwipe()` (line 544) creates a ValueAnimator (duration 220ms, UiMotion.NORMAL) whose onAnimationEnd at line 584 calls `commitNow()` at line 591.
- `snapBack()` (line 613) creates a ValueAnimator (duration 300ms, UiMotion.SLOW) whose onAnimationEnd at line 643 calls `commitNow()` at line 648.
- The lifecycle crash path is deterministic: (1) onSaveInstanceState sets mStateSaved=true, (2) onDestroy at line 449 calls settleAnimator?.cancel(), (3) ValueAnimator.cancel() synchronously dispatches onAnimationEnd, (4) commitNow() checks isStateSaved() and throws IllegalStateException. This is not a race condition -- it is a guaranteed crash on any configuration change (rotation, locale change, dark mode toggle, etc.) that occurs while either animation is active. The animation durations (220-300ms) make this easily triggerable by a quick device rotation during or immediately after a swipe gesture.

REFUTED at the other 3 claimed locations:
- Line 332 (onSwipeStart): This is a synchronous touch-event callback inside onInterceptTouchEvent. Touch events are only dispatched while the Activity is in RESUMED state. State cannot be saved during synchronous touch dispatch. By the time onSaveInstanceState is called, touch dispatch has ceased. Safe by lifecycle contract.
- Lines 672-675 (switchTab peekFragment cleanup): Already wrapped in `try { ... } catch (_: Exception) {}`. The developer anticipated this could throw. Not a latent bug.
- Line 684 (switchTab show): Called exclusively from bottomNavigationView.setOnItemSelectedListener (user click, RESUMED state) and onNewIntent (activity re-entering foreground). Both are pre-save-state contexts. Safe.

The contrast with commitNowAllowingStateLoss() at lines 285, 530, 728 is real and telling -- those call sites are in lifecycle contexts where state-save is expected, while the animation-end call sites are in an overlooked async context where state-save is equally possible but was not guarded.

### 3. Unmanaged CoroutineScope leaks in AppListActivity

- **严重程度**: high
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AppListActivity.kt`
- **行号**: 49
- **描述**: MainScope().launch { ... } creates a CoroutineScope that is never cancelled. If the activity is destroyed before the IO-bound getInstalledApplications call completes, the withContext(Dispatchers.Main) block at line 64 will try to update UI on a destroyed activity.
- **影响**: Memory leak (activity held in memory) and potential crash when updating UI on destroyed activity.
- **建议修复**: Replace MainScope() with lifecycleScope. The import and availability come from AppCompatActivity.
- **验证置信度**: 0.85
- **验证理由**: **Bug Confirmed, but severity is overstated.**

**The problematic code (line 49):**
```kotlin
MainScope().launch {
    val allApps = withContext(Dispatchers.IO) { ... }
    // UI updates follow...
}
```

**Why this is a real issue:**

1. **CoroutineScope leak**: `MainScope()` creates a new scope with `SupervisorJob() + Dispatchers.Main`. This scope is not stored anywhere and never cancelled. The `launch` coroutine holds implicit references to the activity through captured member variables (`allFilteredApps`, `pbLoading`, `listView`, `adapter`, `displayNames`).

2. **Activity lifecycle mismatch**: The coroutine is not tied to the activity's lifecycle. If the activity is destroyed (user presses back quickly, configuration change, system kills app), the coroutine continues running.

3. **Post-destruction UI updates**: Lines 64-67 attempt to update UI (`pbLoading.visibility`, `listView.visibility`, `adapter.notifyDataSetChanged()`, `listView.setItemChecked()`) on potentially destroyed views.

**Why severity is overstated:**

1. **Short-lived leak**: `getInstalledApplications()` is typically fast (milliseconds), so the leak window is small.

2. **Android resilience**: Setting visibility on detached views is generally safe (no crash). `notifyDataSetChanged()` on an unattached adapter is a no-op. Android's view system handles this gracefully in most cases.

3. **No demonstrated crash**: While theoretically possible, actual crashes from this pattern are rare in practice.

**Correct fix**: Replace `MainScope().launch` with `lifecycleScope.launch` (from `androidx.lifecycle:lifecycle-runtime-ktx`), which automatically cancels the coroutine when the activity is destroyed.

**Conclusion**: Bug exists but severity should be "low" not "high". It's a code quality issue that should be fixed, not a high-severity crash-inducing bug.

### 4. Unmanaged CoroutineScope leak in LocalAsrService download/install

- **严重程度**: high
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/LocalAsrService.kt`
- **行号**: 221, 600
- **描述**: installLocalModelWithUI and downloadModelWithProgress both create CoroutineScope(Dispatchers.IO).launch { ... } that are not tied to any lifecycle. The dialogs reference Activity contexts (ctx parameter). If the calling activity is destroyed, the withContext(Dispatchers.Main) calls will try to show/dismiss dialogs on a dead activity.
- **影响**: Memory leak and potential BadTokenException when showing dialogs on a destroyed activity. Download operations (potentially minutes long) keep the activity alive.
- **建议修复**: Accept a LifecycleOwner parameter and use lifecycleScope.launch(Dispatchers.IO), or add isContextAlive checks before each withContext(Dispatchers.Main) UI update.
- **验证置信度**: 0.82
- **验证理由**: The bug claim is VALID, though the specific crash mechanism (BadTokenException) is largely mitigated by existing protections. Here is the evidence from the actual code:

**Confirmed: Unmanaged CoroutineScope**

1. `installLocalModelWithUI` (line 221): `CoroutineScope(Dispatchers.IO).launch { ... }` — standalone scope, no parent Job, no lifecycle binding.
2. `downloadModelWithProgress` (line 600): `CoroutineScope(Dispatchers.IO).launch { ... }` — same pattern.
3. `warmUp` (line 480): `CoroutineScope(Dispatchers.IO).launch { ... }` — same pattern.

These scopes have no mechanism to be cancelled when the calling Activity is destroyed. The `cancelDownload` flag only fires when the user presses the cancel button inside the dialog — if the Activity is destroyed by the system (rotation, low memory, back navigation during download), the coroutine keeps running.

**Confirmed: Activity reference retention (memory leak)**

Each coroutine captures `ctx` (the Activity), `dialog` (AlertDialog built from Activity context), and other references. A download can take minutes (the model is ~75MB compressed from GitHub). During that entire time, the Activity and its entire view hierarchy cannot be garbage collected.

**Mitigated: BadTokenException crash**

The claim's crash scenario is partially overstated because:
- New dialog shows go through `OverlayDialogs.showStyledDialog()` which checks `isContextAlive(ctx)` (line 216 of OverlayDialogs.kt) and catches `BadTokenException` / `IllegalStateException` (lines 220-223).
- `dialog.setMessage()` only updates internal CharSequence state and does not interact with the WindowManager, so it is safe on a dead Activity.
- `dialog.dismiss()` in modern Android has a `mWindow.isDestroyed()` guard that logs an error but does not throw.
- `Utils.toast()` wraps `Toast.makeText` in a try-catch (Utils.kt lines 22+).

**Remaining real concern: Callback execution on destroyed Activity**

The `onComplete` callbacks from the callers in `AiFeatureSettingsActivity` (lines 166-168, 463-465) call `runOnUiThread { updateAsrUi() }` and `recreate()` respectively. These execute inside `withContext(Dispatchers.Main)` after the download completes, potentially on a destroyed Activity. `updateAsrUi()` would operate on dead views; `recreate()` on a destroyed Activity throws `IllegalStateException`. This is a genuine crash vector, though it originates from the unmanaged scope design rather than a BadTokenException.

**Verdict**: The core issue (unmanaged CoroutineScope with no lifecycle binding, retaining Activity references during long operations) is real and provable from the code. The specific crash mechanism described (BadTokenException) is largely mitigated by existing protections, but the unmanaged scope does enable real crashes through caller callbacks on destroyed Activities. The memory retention during potentially-minutes-long downloads is a genuine concern. Severity is real but the specific failure mode is different from what the claim describes.

### 5. BillDao.clearCategoryByName is a no-op query

- **严重程度**: high
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/dao/BillDao.kt`
- **行号**: 330-341
- **描述**: The clearCategoryByName query has WHERE categoryId IS NULL and then SET categoryId = NULL. Since it only matches rows where categoryId is already NULL, the UPDATE changes nothing. Bills that have categoryId=NULL but still carry the old categoryName are never cleaned up when a category is deleted.
- **影响**: When a user deletes a category without migrating bills, the old bills retain the deleted category's name in the categoryName field. If a new category with the same name is created later, those orphaned bills will incorrectly appear to belong to the new category.
- **建议修复**: Change the query to clear the categoryName instead of (or in addition to) categoryId. For example: SET categoryName = '' WHERE categoryId IS NULL AND (categoryName = :name OR categoryName LIKE '% - ' || :name OR ...). Alternatively, if the intent is to just be a guard, remove the method entirely since it does nothing.
- **验证置信度**: 0.97
- **验证理由**: The bug is confirmed. The clearCategoryByName query at lines 330-341 of BillDao.kt has SET categoryId = NULL with WHERE categoryId IS NULL, making it a guaranteed no-op. The WHERE clause restricts to rows where categoryId is already NULL, and then the SET writes NULL to that same column. Zero rows are meaningfully updated. The calling code in CategoryRepository.deleteCategoryAndMigrateBills (line 185) relies on this method to clean up legacy bills that only carry categoryName (not categoryId), but the method fails to do so. The migration-path counterpart migrateCategoryByName correctly uses the same WHERE pattern but with a meaningful SET (assigning a new categoryId), confirming the WHERE is correct and only the SET is wrong. The asset-side analog markDeletedAccountName demonstrates the intended pattern by actually modifying the text field. Severity is medium (not high) because the triggering scenario requires deleting a category without migration, then creating a same-named category and triggering name-based migration.

### 6. mergeRestoreFullData does not remap chat message bill references

- **严重程度**: high
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/BackupRepository.kt`
- **行号**: 298-303
- **描述**: In mergeRestoreFullData, chat messages are inserted with id=0 but their billIds and content fields still reference the old bill IDs from the backup. Unlike restoreFullData which calls remapChatBillReferences(), mergeRestoreFullData skips this step entirely.
- **影响**: After a merge restore, chat messages that reference bills (msgType=4) will have stale bill IDs that point to non-existent bills. This breaks the bill-reference chain in AI chat history, causing bill links in chat to fail or point to wrong bills.
- **建议修复**: Apply remapChatBillReferences(msg, billIdMap) before inserting each chat message in mergeRestoreFullData, matching the pattern used in restoreFullData.
- **验证置信度**: 0.98
- **验证理由**: The bug is confirmed by direct code inspection. restoreFullData builds a billIdMap and calls remapChatBillReferences(msg, billIdMap) on each chat message before insertion (line 149). mergeRestoreFullData has no billIdMap variable and inserts chat messages with only msg.copy(id = 0) at line 300, completely skipping bill ID remapping. Additionally, the deduplication logic in mergeRestoreFullData skips duplicate bills without recording any old-to-new ID mapping, so even if remapping were attempted, some IDs would have no mapping. The remapChatBillReferences private method at line 314 correctly handles both billIds and content fields and is ready to use but simply not called from mergeRestoreFullData.

### 7. Gson deserialization ignores Kotlin default values, causing potential NPE

- **严重程度**: high
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/DataExportManager.kt`
- **行号**: 12-19
- **描述**: DataExportManager uses Gson for deserialization of all entity types. Gson bypasses Kotlin constructors and default parameter values, using Java reflection to set fields directly. When a JSON field is missing (e.g., an older backup without 'bookName'), Gson sets non-nullable String fields to null instead of the Kotlin default (''). This violates Kotlin's null-safety guarantees.
- **影响**: Restoring a backup from an older app version that lacks newer fields will produce entities with null values in non-nullable fields. Subsequent database operations or property accesses will crash with NullPointerException or insert invalid data into the database.
- **建议修复**: Either switch to a Kotlin-aware JSON library (e.g., kotlinx.serialization or Moshi with Kotlin adapter), or add a post-deserialization validation/sanitization step that fills in defaults for null fields on each entity type.
- **验证置信度**: 0.85
- **验证理由**: The bug claim is TECHNICALLY REAL but severity is overstated. Here is the detailed analysis:

**What is true:**
1. DataExportManager (line 8) uses plain `Gson()` with no Kotlin-aware type adapter -- confirmed in the source.
2. Gson does bypass Kotlin constructors via `Unsafe.allocateInstance()`, setting fields via reflection. This means Kotlin default parameter values are never applied during deserialization.
3. The entity classes have many non-nullable `String` fields with non-empty defaults that would become `null` if absent from JSON: `Asset.currency = "CNY"`, `Asset.assetCategory = CATEGORY_FUND`, `Bill.bookName = "日常账本"`, `Bill.currency = "CNY"`, `DeletedBill.bookName = "日常账本"`, `ChatMessage.bookName = ""`, etc.
4. There is NO version field in the backup format and NO migration logic for old backups (confirmed in BackupManager/BackupRepository).
5. The restore path passes deserialized objects directly into database DAOs with no null-sanitization. If `bill.categoryName` is null, `CategoryNameNormalizer.normalizeForStorage(bill.categoryName)` at BackupRepository.kt line 90 would NPE.

**Mitigating factors that reduce severity from HIGH to MEDIUM:**
1. The scenario ONLY manifests when restoring a backup from an OLDER app version. Same-version backup/restore is safe because `Gson.toJson()` serializes ALL fields.
2. Both restore paths wrap operations in try-catch (BackupActivity lines 1076, 1146), catching Exception and showing a toast. No silent corruption -- but the restore still fails.
3. Primitive fields (Double, Int, Long) get Java defaults (0.0, 0, 0L) which often match Kotlin defaults. The real danger is String fields becoming null and Boolean fields defaulting `true` to `false`.

**Confirmed vulnerable code path:** BackupRepository.restoreFullData accesses `bill.categoryName` (line 90), `bill.accountName` (line 80), `bill.bookName` (line 98 in catch block) -- all non-nullable Strings that Gson could set to null from an older backup lacking these fields.

### 8. handleSave has no re-entrancy guard -- double-tap or concurrent async callbacks create duplicate bills

- **严重程度**: critical
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt`
- **行号**: 1425-1863
- **描述**: handleSave() is called from the save button click listener (with animation) and also recursively from async callbacks (exchange rate confirmation at line 1465, currency exchange confirmation at line 1484, investment schedule confirmation at line 1520). There is no boolean guard (e.g. isSaving) to prevent concurrent execution. If the user taps save during the animation's withEndAction delay, or if multiple async coroutines complete near-simultaneously, two or more bill insertions can proceed in parallel on the IO dispatcher, creating duplicate bills and applying balance impact twice.
- **影响**: Duplicate bills and double balance impact. User sees two identical entries; asset balance is debited/credited twice for a single transaction.
- **建议修复**: Add a `private var isSaving = false` flag. Set it true at the top of handleSave and false at every exit point (return, toast, onCloseRequest). Wrap the flag check in a synchronized block or ensure all paths run on Main thread.
- **验证置信度**: 0.75
- **验证理由**: The bug is VALID but severity is overstated (medium, not critical). The code at lines 1425-1862 of AccountingFormController.kt has no re-entrancy guard on handleSave(). However, the specific attack vectors in the claim are partially incorrect: (1) double-tapping during the animation is mitigated by v.animate().cancel() at line 764 which prevents the cancelled animation's withEndAction from firing, and (2) async callbacks all serialize on Dispatchers.Main via withContext(), and dialog-based callbacks are modal. The actual race window is: after handleSave() launches an IO coroutine at line 1531 and returns, the button remains clickable. If the user taps save again before the IO coroutine completes and calls onCloseRequest(true), a second IO coroutine is launched, creating a duplicate bill. The window is roughly 100-400ms depending on IO speed. The fix is a simple isSaving boolean guard or disabling the button.


## 被驳回的发现（7 个）

1. ~~Race condition: AudioRecord null between check and read in recording thread~~ — The claimed race condition is theoretically possible but practically unexploitable. AudioRecord.read() is a blocking call — in the normal flow, the background thread is already inside read() when stopVoiceRecording() is called, and stop() unblocks it. The dangerous window (between the null-safe dereference and the actual read() call) is essentially zero CPU cycles. Even if triggered, it would NOT crash the app (uncaught exceptions on background threads only terminate the thread, not the process). The only real impact would be a potentially corrupt WAV file with a zeroed header, but the file size check provides a guard. The code could be improved by joining the thread before releasing the AudioRecord, but the current implementation works correctly in all realistic scenarios. Severity is overstated as "high".
2. ~~Race condition: displayMessages list accessed from multiple threads~~ — The claim is REFUTED. After reading all source files that access `displayMessages`, every single access occurs on the Main thread. Here is the evidence:

1. `aiWorkScope` (ChatActivity.kt line 155) is `CoroutineScope(aiScopeJob + Dispatchers.Main.immediate)` — all coroutines launched in this scope run on Main.

2. `ChatMessagePersistenceController`: All methods (`appendUserMessage`, `appendAiTextMessage`, `removeLoadingMessage`, `updateLoadingMessage`, `finalizeLoadingMessage`) access `displayMessages` either directly from the Main-thread caller, or inside explicit `withContext(Dispatchers.Main)` blocks after IO work completes. The `lifecycleScope.launch(Dispatchers.IO) { ... withContext(Dispatchers.Main) { displayMessages... } }` pattern at lines 61-87, 116-146, 178-207, and 255-278 explicitly dispatches back to Main before touching the list.

3. `ChatBillCorrectionService.processBillResult`: The `displayMessages.add()` at line 324 executes after the `withContext(Dispatchers.IO)` block at line 299 completes, returning execution to the calling coroutine's dispatcher (Main). This is correct — after a `withContext(Dispatchers.IO)` block, the coroutine resumes on its original dispatcher.

4. `ChatHistoryController.loadHistoryMessages`: Launched via `lifecycleScope.launch` (Main dispatcher). All `displayMessages` accesses at lines 61-183 occur after `withContext(Dispatchers.IO)` blocks return, so they are on Main.

5. `ChatVoiceController.deleteVoiceMessages`: Inside `lifecycleScope.launch`, with `displayMessages.removeAll` at line 255 occurring after `withContext(Dispatchers.IO)` at line 249, returning to Main.

6. IO-thread callbacks (e.g., `onDelta` in `ChatMessagePipeline`) that touch `displayMessages` indirectly via `updateLoadingMessage` are wrapped in `runOnUiIfAlive { ... }` which calls `context.runOnUiThread { ... }`.

7. The `ChatAdapter` constructor at line 222-259 receives `displayMessages` but adapter methods (`onBindViewHolder`, etc.) are always called by RecyclerView on the Main thread.

The claim misunderstands the Kotlin coroutine dispatching model. `withContext(Dispatchers.Main)` blocks launched from IO are NOT "IO accessing the list" — they are explicit switches back to the Main thread, which is the CORRECT pattern for thread-safe access. The `Dispatchers.Main.immediate` scope ensures all top-level coroutine code starts on Main, and `withContext(Dispatchers.Main)` ensures return from IO is on Main.

Additionally, `rvMessages.itemAnimator = null` (line 668) disables RecyclerView item animations, reducing edge-case layout consistency risks.
3. ~~Unmanaged CoroutineScope leaks and potential crash on destroyed Activity~~ — The bug claim is REFUTED. While the code does use an unmanaged CoroutineScope (a code quality issue), the claimed high-severity crash scenario does not hold up. Setting View properties (isEnabled, text) on a detached Activity does not crash on Android -- these are simple Java object mutations that are harmless on views not attached to a window. getString() on a destroyed Activity also works because the object is still alive (held by the lambda). Utils.toast() wraps all window operations in try-catch and falls back safely. The memory leak is real but bounded and temporary (the Activity reference is released once the coroutine completes, typically seconds to low tens of seconds). The unmanaged scope is a style issue (should use lifecycleScope), but calling this a 'high severity potential crash' is overstated. No crash will occur in the described scenario.
4. ~~Unmanaged CoroutineScope leaks in OverlayDialogs category/refund pickers~~ — The bug claim is technically correct that unmanaged CoroutineScope instances are used (lines 426, 688, 704, 802, 1286, 1298, 1479), but the severity is significantly overstated ("high") and the described crash scenario is largely mitigated by the actual code. Key refuting evidence:

1. **isContextAlive() guard at line 216 inside showStyledDialog()**: Every dialog show path goes through `showStyledDialog`, which checks `isContextAlive(ctx)` (line 216) and returns early if the activity is finishing/destroyed. This prevents the most dangerous operation (showing a dialog on a dead activity) and catches both `isFinishing` and `isDestroyed` states (lines 107-108).

2. **BadTokenException catch at lines 220-224**: Even if `isContextAlive` passes due to a race, the `dialog.show()` call is wrapped in try/catch for `BadTokenException` and `IllegalStateException`, logging and swallowing the error gracefully.

3. **The coroutines are extremely short-lived**: Each coroutine does a single local Room database query (typically sub-millisecond to low milliseconds) and then renders UI. This is not a long-running operation that would hold activity references for meaningful durations. The "memory leak" window is negligible.

4. **Intermediate UI operations don't crash on dead contexts**: The `render()`, `notifyDataSetChanged()`, `addView()`, `removeAllViews()`, `LayoutInflater.inflate()` calls that happen before `showStyledDialog` operate on view objects that are detached but don't throw exceptions on dead contexts. They are no-ops in terms of user-visible impact.

5. **The actual described crash scenario requires**: (a) user opens picker, (b) activity is destroyed during the DB query (sub-second window), (c) the coroutine resumes and tries to show the dialog -- but `isContextAlive` check and `BadTokenException` catch both protect against this.

6. **showRefundBillPickerDialog wraps everything in try/catch** (line 1753/1810), providing additional protection.

While it would be better engineering practice to use lifecycle-bound scopes, the claim of "high severity" with "memory leaks and potential crashes" is not supported by the actual code, which has adequate defensive measures.
5. ~~Thread-unsafe mutable list in LocalAsrService streaming~~ — The claimed bug is REFUTED. While `streamSamples` is indeed a plain `mutableListOf<Float>()` (ArrayList), the claim that it is accessed from multiple threads "without synchronization" is incorrect. Both callers synchronize access via `Thread.join()`:

1. **VoiceInputHandler.stopRecording()** (line 447-476): Sets `isRecording = false`, calls `audioRecord?.stop()` and `audioRecord?.release()`, then calls `recordingThread?.join(500)` BEFORE invoking the `onFileReady` callback that ultimately calls `finishStreaming()`. The recording thread runs `writeAudioDataToFile()` which is the only place `acceptStreamingData()` and `startStreaming()` are called. After `join()`, the recording thread is guaranteed to have finished.

2. **ChatAudioRecordController.stopVoiceRecording()** (line 75-103): Same pattern - sets `isRecording = false`, stops/releases AudioRecord, calls `recordingThread?.join(1200)`, then invokes the `onFileReady` callback. The `resetStreamingBuffer()` at line 197 in ChatVoiceInputController runs inside this callback, after the join.

3. **resetStreamingBuffer() on ACTION_DOWN** (line 159): Called on the UI thread BEFORE `startVoiceRecording()` at line 165, so the recording thread hasn't started yet. No concurrency.

4. **The two code paths** (VoiceInputHandler vs ChatVoiceInputController/ChatAudioRecordController) are for different UI contexts and don't overlap in practice. Even if they did, the `ChatAudioRecordController.writeAudioDataToFile()` does NOT call `acceptStreamingData()` at all - it only writes to file.

The `join()` call establishes a happens-before relationship: all writes by the recording thread (including `streamSamples.add()`) are visible to the thread that calls `join()`, and the recording thread cannot make further writes after `join()` returns. This is standard Java memory model synchronization.

The only theoretical edge case is if `join(500)` times out (only in VoiceInputHandler; ChatAudioRecordController uses 1200ms). But after `audioRecord.stop()` and `release()`, the `read()` call returns immediately, and the `while (isRecording)` loop exits. A 500ms timeout for thread exit after AudioRecord is stopped is extremely generous. Even in this edge case, the recording thread would be blocked in `read()` (not touching `streamSamples`), not in `add()`.

The claimed "ConcurrentModificationException or silent data corruption" cannot happen in the actual execution flow because the critical sections are properly ordered by thread lifecycle management (join).
6. ~~targetDeltaInCurrency ignores target currency parameter~~ — The bug claim is REFUTED. The claim's central premise -- that bill.exchangeRate is always the rate from bill.currency to CNY -- is factually incorrect for transfer bills. In AccountingFormController.kt lines 1642-1658, the transfer branch computes finalRate = targetDelta / money, which is the rate from bill.currency to the TARGET asset's currency (not CNY). The comment on line 1680 about "exchangeRate always stores bill.currency -> CNY" applies only to the else branch for expense/income bills (lines 1673-1683), not to transfers. Therefore targetDeltaInCurrency returning bill.amount * bill.exchangeRate correctly gives the amount in the target currency. The _targetCurrency parameter is unused because the target conversion is already baked into bill.exchangeRate at bill creation time. The existing audit doc (11-logic-correctness.md) confirms this by only flagging a rounding issue with this function, not a wrong-currency issue.
7. ~~targetDeltaInCurrency uses bill.exchangeRate without accounting for bill.currency mismatch with target asset currency~~ — The claimed bug is refuted. For transfers created through the UI, bill.currency is always forced to the source asset's currency (effectiveCurrency = asset1.currency), bill.amount is always in that same currency (the spinner is synced), and bill.exchangeRate is always targetDelta/bill.amount. Therefore bill.amount * bill.exchangeRate correctly produces the target amount. The claim's scenario of bill.currency=USD with a CNY source account cannot happen through the normal form flow. The _targetCurrency parameter is intentionally unused because bill.exchangeRate already encodes the full conversion.
