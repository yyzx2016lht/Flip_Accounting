# Preferences / 工具类 / 跨模块 Bug 审计

**共 13 个发现**: 🔴 0 Critical | 🟠 1 High | 🟡 5 Medium | 🟢 7 Low

## 🟠 High

### 1. SharedPreferences read-modify-write race condition in addBill/deleteBills

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/PrefsDataSupport.kt`
- **行号**: 16-28
- **描述**: addBill() reads the full bill list via getBills(), mutates it, then writes it back with apply(). deleteBills() follows the same pattern. If two threads (e.g. UI thread and background sync) call addBill concurrently, thread A can read the list, thread B can read the same list, both add their bill, and the last writer silently overwrites the other's bill. This is a classic TOCTOU (Time-Of-Check-Time-Of-Use) data loss race.
- **影响**: Bills can be silently lost when concurrent operations occur. Users may not notice data loss until they review their records.
- **建议修复**: Use synchronized blocks or SharedPreferences.Transaction (commit/apply with a lock) around the read-modify-write cycle. Alternatively, migrate bill storage to Room (already partially done via AppDatabase) and use database transactions for atomicity.

## 🟡 Medium

### 1. SharedPreferences race condition in addOcrDebugRecord

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/PrefsAiSupport.kt`
- **行号**: 382-404
- **描述**: addOcrDebugRecord() reads the full list of OCR debug records, prepends a new one, truncates, and writes back with apply(). The read-modify-write cycle is not atomic. Concurrent calls can lose records or corrupt the list ordering.
- **影响**: OCR debug records can be lost during concurrent writes. This primarily affects debugging workflows, not user data.
- **建议修复**: Synchronize access to the OCR debug records, or use a database table instead of SharedPreferences for this list.

### 2. Provider key migration uses two non-atomic apply() calls

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/PrefsAiSupport.kt`
- **行号**: 102-113
- **描述**: migrateLegacyProviderKeysIfNeeded() performs two separate p.edit().apply() calls: first to persist the provider keys map, then to set the migration flag. If the app crashes between the first and second apply(), the keys are written but the migration flag is not set. On next launch, the migration runs again, but putIfAbsent() prevents overwriting, so this is safe in practice but leaves a window for inconsistency.
- **影响**: Low probability of duplicate migration runs. No data loss due to putIfAbsent() guard, but the non-atomic pattern is fragile.
- **建议修复**: Combine both writes into a single editor commit: p.edit().putString(...).putBoolean(KEY_AI_PROVIDER_KEYS_MIGRATED, true).commit()

### 3. getAiChatModelAudioSupport silently converts non-boolean values to false

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/PrefsChatSupport.kt`
- **行号**: 100-108
- **描述**: When the JSON object contains the model key but its value is not a valid boolean (e.g. a string or number due to data corruption), JSONObject.optBoolean(model) returns false instead of null. The method returns Boolean? (nullable) to distinguish 'not cached' from 'cached as false', but this distinction is lost for corrupt entries. The caller cannot tell whether the model was explicitly marked as not supporting audio or whether the cached value is corrupt.
- **影响**: Corrupt cached audio support entries are treated as 'audio not supported' instead of 'unknown', potentially preventing audio features from being used for models that actually support them.
- **建议修复**: Check obj.has(model) AND the type before calling optBoolean. Use obj.get(model) and check if it is a Boolean type before casting.

### 4. importAll loses ai_thinking_multi_bill_v1 setting during restore

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/PrefsBackupSupport.kt`
- **行号**: 96-301
- **描述**: serializeSettings() exports 'ai_thinking_modify_bill_v1' and 'ai_thinking_category_refine_v1' (lines 376-377), and importAll() restores both (lines 173-174). However, serializeSettings() does NOT export 'ai_thinking_multi_bill' or 'ai_thinking_vision' settings, and importAll() does not restore them either. If a user backs up and restores, these two thinking settings are silently lost and revert to their defaults (false).
- **影响**: Users who have enabled AI thinking for multi-bill or vision modes will lose those settings after a backup/restore cycle.
- **建议修复**: Add export and import lines for 'ai_thinking_multi_bill_v1' and 'ai_thinking_vision_v1' in both serializeSettings() and importAll().

### 5. isQuickGestureEnabled getter has side effects and races with setters

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/PrefsGeneralSupport.kt`
- **行号**: 150-158
- **描述**: isQuickGestureEnabled() calls setQuickGestureEnabled() as a side effect when the key does not exist (migration path). This means a getter mutates state. Additionally, setFlipEnabled() and setDoubleTapEnabled() read isDoubleTapEnabled()/isFlipEnabled() inside an editor to compute the quick gesture state, creating a TOCTOU race. If a concurrent call changes the double-tap state between the read and the write, the computed value is stale.
- **影响**: The quick gesture master switch can end up in an inconsistent state when flip and double-tap are toggled concurrently from different threads.
- **建议修复**: Move the migration logic out of the getter into a one-time migration method called at startup. In the setters, avoid reading other preferences inside the editor chain; instead read all needed values before constructing the editor.

## 🟢 Low

### 1. importAll double-writes provider keys, second write may overwrite first

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/PrefsBackupSupport.kt`
- **行号**: 179-182 and 247-249
- **描述**: importAll() first writes 'ai_provider_keys_v1' into the main editor (line 180-181), then calls edit.apply() (line 245). Then it calls Prefs.importAiProviderKeysFromBackup() which opens a new editor, overwrites the same key, and calls commit() (line 248). The second call's commit() overwrites whatever was just applied. While functionally the data is the same string, this double-write is wasteful and confusing.
- **影响**: No data loss in practice, but the double-write pattern is a maintenance hazard and wastes I/O.
- **建议修复**: Remove the first write of ai_provider_keys_v1 from the main editor block (lines 180-181), since importAiProviderKeysFromBackup handles it separately.

### 2. TapApplication.onCreate installs crash handler after instance assignment

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/TapApplication.kt`
- **行号**: 33-52
- **描述**: installCrashHandler() is called after instance = this and after ProcessExitLogger.onAppCreate(). If ProcessExitLogger.onAppCreate() or SharedYearMonthSession.resetToCurrentMonth() throws, the crash handler is not yet installed and the crash won't be logged to the internal crash log file.
- **影响**: Crashes during very early app initialization (before installCrashHandler) won't be captured in the app's internal crash log.
- **建议修复**: Move installCrashHandler() to the very first line of onCreate(), before any other initialization.

### 3. TapApplication launches unscoped CoroutineScopes that leak or race

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/TapApplication.kt`
- **行号**: 43-51
- **描述**: Two CoroutineScope(Dispatchers.IO) instances are created inline in onCreate(). These are not tied to any lifecycle and cannot be cancelled. If the application is recreated (e.g. in tests or multi-process scenarios), the old coroutines continue running. Also, the migration coroutine has no error handling — if MigrationManager.migrateIfNecessary() throws, the exception is silently swallowed by the coroutine scope.
- **影响**: Silent failures during data migration. In test scenarios, leaked coroutines can cause flaky test results.
- **建议修复**: Store the CoroutineScope as a property of TapApplication and add try-catch with logging inside the coroutine. Consider using application-scoped coroutines with proper error handling.

### 4. ShizukuHelper.createProcess returns null silently on all exceptions

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ShizukuHelper.java`
- **行号**: 12-29
- **描述**: The method catches all exceptions with a bare e.printStackTrace() and returns null. This includes ClassNotFoundException (Shizuku not installed), NoSuchMethodException (API version mismatch), SecurityException (permission denied), and InvocationTargetException (actual process creation failure). Callers must null-check, but the root cause of failure is only visible in logcat, not reported to the user.
- **影响**: When Shizuku process creation fails, the user gets no actionable error message. Debugging requires checking logcat output.
- **建议修复**: Log the exception with the app's Logger utility instead of printStackTrace(). Consider returning a Result type or throwing a typed exception so callers can provide meaningful feedback.

### 5. Chat session title key generation allows collisions from underscores

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/PrefsChatSupport.kt`
- **行号**: 120-121
- **描述**: buildChatSessionTitleKey() constructs keys as 'ai_chat_session_title_{bookName}_{conversationId}'. If a bookName contains underscores (e.g. 'my_book'), the key becomes ambiguous — 'ai_chat_session_title_my_book_conv1' could refer to book='my_book', conversationId='conv1' OR book='my', conversationId='book_conv1'. This could cause session titles from different books to overwrite each other.
- **影响**: Users with underscore-containing book names may experience session title corruption where one conversation's title overwrites another's.
- **建议修复**: Use a separator that cannot appear in book names, or encode the components (e.g. JSON, or URL-encode, or use a fixed-width format).

### 6. setQuickGestureEnabled silently forces double-tap to enabled

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/PrefsGeneralSupport.kt`
- **行号**: 159-163
- **描述**: setQuickGestureEnabled(ctx, true) always sets KEY_DOUBLE_TAP_ENABLED to true as a side effect. setQuickGestureEnabled(ctx, false) always sets KEY_DOUBLE_TAP_ENABLED to false. This means disabling the quick gesture master switch also disables double-tap, even if the user only wanted to disable flip detection. There is no way to enable quick gestures without also enabling double-tap.
- **影响**: Users cannot independently control flip detection and double-tap detection through the quick gesture master switch. Disabling the master switch kills both sub-features.
- **建议修复**: Decouple the master switch from the individual feature toggles. The master switch should only control whether the gesture detection service is active, not override individual sub-feature states.

### 7. setFlipEnabled/setDoubleTapEnabled have asymmetric side effects on quick gesture state

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/PrefsGeneralSupport.kt`
- **行号**: 167-171 and 190-193
- **描述**: setFlipEnabled(ctx, false) sets quick gesture to (false || isDoubleTapEnabled), correctly preserving double-tap state. But setDoubleTapEnabled(ctx, false) sets quick gesture to (false || isFlipEnabled), correctly preserving flip state. However, setQuickGestureEnabled(ctx, false) unconditionally sets both sub-features to false, which is inconsistent. If a user has only flip enabled and calls setQuickGestureEnabled(false), the flip state is preserved but double-tap is forced to false (which it already was). But calling setQuickGestureEnabled(false) when both are enabled forces both off, losing the individual states.
- **影响**: Toggling the quick gesture master switch can unexpectedly change the individual flip/double-tap states, confusing users who expect the master switch to be a read-only aggregation.
- **建议修复**: Make the master switch a pure read-only aggregation (OR of sub-features). Removing the setter's side effects on sub-features, or at minimum only writing the master key without touching sub-feature keys.

