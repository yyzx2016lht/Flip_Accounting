# Activity / Service / 广播 Bug 审计

**共 17 个发现**: 🔴 1 Critical | 🟠 5 High | 🟡 7 Medium | 🟢 4 Low

## 🔴 Critical

### 1. Fragment commitNow() after state save causes IllegalStateException

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/MainActivity.kt`
- **行号**: 332, 588, 646, 672, 684
- **描述**: Multiple calls to commitNow() inside swipe gesture callbacks and animation end listeners (onSwipeStart at line 332, commitSwipe animation end at line 588, snapBack animation end at line 646, switchTab at lines 672 and 684) will throw IllegalStateException if the activity's state has already been saved (e.g., during rotation or system-initiated process death). Unlike commitNowAllowingStateLoss() used elsewhere (lines 285, 530, 728), these calls do not protect against post-save state.
- **影响**: Crash (IllegalStateException) when the device rotates or the system kills the activity while a tab-switch animation or swipe gesture is in progress. The user sees a force-close dialog.
- **建议修复**: Replace commitNow() with commitNowAllowingStateLoss() in all animation-end listeners and gesture callbacks, or add isFinishing/isStateSaved guards before each transaction.

## 🟠 High

### 1. Unmanaged CoroutineScope leaks and potential crash on destroyed Activity

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AiConfigActivity.kt`
- **行号**: 344
- **描述**: The test-connection button handler uses CoroutineScope(Dispatchers.IO).launch { ... } which is not bound to the activity lifecycle. If the user navigates away while the network request is in flight, the withContext(Dispatchers.Main) block at line 347 will attempt to update UI (btnTest.isEnabled, btnTest.text) on a destroyed activity.
- **影响**: Potential crash or memory leak. The coroutine keeps the activity reference alive until the network call completes or times out (potentially 20+ seconds).
- **建议修复**: Replace CoroutineScope(Dispatchers.IO) with lifecycleScope (available from AppCompatActivity). Change to: lifecycleScope.launch(Dispatchers.IO) { ... }

### 2. Unmanaged CoroutineScope leaks in AppListActivity

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AppListActivity.kt`
- **行号**: 49
- **描述**: MainScope().launch { ... } creates a CoroutineScope that is never cancelled. If the activity is destroyed before the IO-bound getInstalledApplications call completes, the withContext(Dispatchers.Main) block at line 64 will try to update UI on a destroyed activity.
- **影响**: Memory leak (activity held in memory) and potential crash when updating UI on destroyed activity.
- **建议修复**: Replace MainScope() with lifecycleScope. The import and availability come from AppCompatActivity.

### 3. Unmanaged CoroutineScope leaks in OverlayDialogs category/refund pickers

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/dialog/OverlayDialogs.kt`
- **行号**: 426, 688, 802, 1479
- **描述**: Multiple methods (showGridCategoryPicker, showCategorySortDialog, showMigrationTargetPicker, showRefundBillPickerDialog) use CoroutineScope(Dispatchers.Main).launch { ... } to load data from the database. These coroutines are not bound to any lifecycle. If the calling activity is destroyed before the database query completes, the UI update in withContext(Dispatchers.Main) will operate on a dead context.
- **影响**: Memory leaks and potential crashes when UI is updated on a destroyed activity context. The activity is held alive by the coroutine until the database query completes.
- **建议修复**: Accept a LifecycleOwner parameter and use its lifecycleScope, or catch IllegalStateException in the UI update paths.

### 4. Unmanaged CoroutineScope leak in LocalAsrService download/install

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/LocalAsrService.kt`
- **行号**: 221, 600
- **描述**: installLocalModelWithUI and downloadModelWithProgress both create CoroutineScope(Dispatchers.IO).launch { ... } that are not tied to any lifecycle. The dialogs reference Activity contexts (ctx parameter). If the calling activity is destroyed, the withContext(Dispatchers.Main) calls will try to show/dismiss dialogs on a dead activity.
- **影响**: Memory leak and potential BadTokenException when showing dialogs on a destroyed activity. Download operations (potentially minutes long) keep the activity alive.
- **建议修复**: Accept a LifecycleOwner parameter and use lifecycleScope.launch(Dispatchers.IO), or add isContextAlive checks before each withContext(Dispatchers.Main) UI update.

### 5. Thread-unsafe mutable list in LocalAsrService streaming

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/LocalAsrService.kt`
- **行号**: 471, 501, 511, 515
- **描述**: streamSamples is a plain mutableListOf<Float>() accessed from multiple threads without synchronization. acceptStreamingData() is called from the audio recording thread, while finishStreaming() and resetStreamingBuffer() are called from other threads. Concurrent read/write to ArrayList causes ConcurrentModificationException or silent data corruption.
- **影响**: Crash (ConcurrentModificationException) or corrupted audio data leading to garbled/empty speech recognition results. Intermittent and hard to reproduce.
- **建议修复**: Use a thread-safe collection such as Collections.synchronizedList(mutableListOf<Float>()) or protect access with a Mutex/ReentrantLock. Alternatively, use a CopyOnWriteArrayList.

## 🟡 Medium

### 1. Hardcoded currency symbol in AssetActivity disregards multi-currency

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AssetActivity.kt`
- **行号**: 97-99, 169
- **描述**: updateHeader() formats net asset, total asset, and total debt with hardcoded '¥' prefix (e.g., String.format(Locale.getDefault(), '¥%.2f', netAsset)), and the adapter also uses '¥%.2f' for individual asset balances. When assets are stored in non-CNY currencies, the display shows wrong currency symbols.
- **影响**: Users with non-CNY assets see incorrect currency symbols (always ¥) in the asset manager, which is misleading and can cause accounting errors.
- **建议修复**: Use CurrencyManager.getSymbol(asset.currency) or CurrencyUtils.formatAmount() instead of hardcoding '¥'. In updateHeader(), the conversion to CNY is already done, but the header could still note the display currency. In the adapter, use the asset's actual currency.

### 2. BuiltInPickerActivity selection never returned to caller

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/BuiltInPickerActivity.kt`
- **行号**: 24-30
- **描述**: The selection handler only shows a Toast with the selected name but never calls setResult(RESULT_OK, intent) or finish(). The code to do this is commented out (lines 27-29). Any activity launching BuiltInPickerActivity for a result will never receive a selection.
- **影响**: The built-in category picker is non-functional for result-based flows. Users select a category but nothing happens.
- **建议修复**: Uncomment and activate the setResult/finish logic, or remove the activity if it is unused.

### 3. Hardcoded Chinese strings bypass resource system

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/SettingsActivity.kt`
- **行号**: 226, 229
- **描述**: Two TextView text assignments use hardcoded Chinese strings: '拖动一级分类进行排序' and '拖动子分类进行排序' instead of getString(R.string.xxx). These will appear in Chinese regardless of the device locale.
- **影响**: Non-Chinese users see untranslated Chinese text in the category sort mode tip.
- **建议修复**: Create string resources for these two strings and use getString() to fetch them.

### 4. AccessibilityService subscribes to all event types for keep-alive

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/KeepAliveAccessibilityService.kt`
- **行号**: 77
- **描述**: eventTypes is set to AccessibilityEvent.TYPES_ALL_MASK, but onAccessibilityEvent only uses events as a periodic timer (every 60 seconds) to call ensureOverlayService. Subscribing to all event types forces the system to deliver every accessibility event to this service, consuming CPU and battery unnecessarily.
- **影响**: Increased battery drain and CPU usage. The service processes every UI event on the device just to check a 60-second timer.
- **建议修复**: Set eventTypes to a minimal type (e.g., AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) or use a Handler/AlarmManager for periodic checks instead of piggy-backing on accessibility events.

### 5. Potential crash when swipe gesture callback executes after activity destruction

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/MainActivity.kt`
- **行号**: 301-345
- **描述**: The onSwipeStart lambda captures tabFragments and supportFragmentManager. If called during or after activity destruction (e.g., quick rotation during a swipe), the fragment transaction at line 332 (commitNow) will crash. Additionally, the onHorizontalDrag and onHorizontalSettle lambdas reference peekFragment and swipeContainer views that may be in a destroyed state.
- **影响**: Crash during rapid rotation while swiping between tabs.
- **建议修复**: Add an isFinishing check at the start of each swipe callback lambda. Use commitNowAllowingStateLoss() for fragment transactions in these callbacks.

### 6. PromptPinSetupForBackup allows empty confirmation after validation failure

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/BackupActivity.kt`
- **行号**: 1373-1380
- **描述**: In promptPinSetupForBackup, the positive button's onClick performs validation (4-digit check, mismatch check) and shows a Toast on failure, but does NOT prevent the dialog from dismissing. The AlertDialog's default behavior dismisses on button click regardless of validation. This means an invalid PIN triggers a toast but the backup proceeds with the invalid pin via the onPinConfirmed callback.
- **影响**: Backup may proceed with an invalid/empty PIN when the user enters mismatched or non-4-digit PINs and taps confirm. The Toast warns but does not block.
- **建议修复**: Use dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { ... } instead of the builder's setPositiveButton, so the dialog stays open on validation failure (same pattern used in promptPinVerifyForOverwrite at line 1501).

### 7. Plaintext PIN stored in SharedPreferences

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/BackupActivity.kt`
- **行号**: 762-763
- **描述**: saveLastBackupPin stores the 4-digit backup encryption PIN in plaintext SharedPreferences. Any app with root access, backup extraction, or device compromise can read this PIN and decrypt backup files.
- **影响**: Security weakness. The PIN that protects API keys in backups is stored in plaintext on the device filesystem.
- **建议修复**: Store the PIN using Android Keystore-encrypted SharedPreferences (EncryptedSharedPreferences from the security-crypto library), or store a hashed version and verify by re-encrypting a known value.

## 🟢 Low

### 1. FlipDetector uses System.currentTimeMillis() which can jump due to NTP

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/FlipDetector.kt`
- **行号**: 36, 79
- **描述**: lastSensorEventTimeMillis is set using System.currentTimeMillis(), and the watchdog in OverlayService also compares using System.currentTimeMillis(). NTP time adjustments can cause sudden jumps, leading to false 'sensor dead' detection or missed watchdog checks.
- **影响**: Intermittent false watchdog restarts of sensor detection, or brief lapses in detection after NTP adjustments.
- **建议修复**: Use SystemClock.elapsedRealtime() instead of System.currentTimeMillis() for all elapsed-time measurements.

### 2. BillDisplaySettingsActivity loads external HTTP URL for preview

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/BillDisplaySettingsActivity.kt`
- **行号**: 28
- **描述**: sampleIconUrl uses HTTP (not HTTPS) to an external domain: 'http://res3.qianjiapp.com/ic_cate2_wancan.png'. On Android 9+ cleartext traffic is blocked by default (unless explicitly allowed in network security config). If blocked, the preview icon silently fails to load.
- **影响**: Preview icon may not display on devices with default network security configuration. Also a minor security concern (cleartext HTTP).
- **建议修复**: Use a local drawable resource for the preview icon instead of a remote URL, or change to HTTPS.

### 3. SettingsActivity uses deprecated onBackPressed()

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/SettingsActivity.kt`
- **行号**: 948
- **描述**: onBackPressed() is overridden directly instead of using the OnBackPressedDispatcher with OnBackPressedCallback (the modern approach). This can cause inconsistent behavior with gesture navigation on newer Android versions.
- **影响**: Potential inconsistency with predictive back gesture on Android 13+. Low practical impact currently.
- **建议修复**: Use onBackPressedDispatcher.addCallback(this, ...) in onCreate instead of overriding onBackPressed().

### 4. AiFeatureSettingsActivity uses deprecated startActivityForResult

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AiFeatureSettingsActivity.kt`
- **行号**: 172, 460
- **描述**: Uses the deprecated startActivityForResult/onActivityResult pattern for importing a local ASR model file. The modern Activity Result API (registerForActivityResult) is not used.
- **影响**: Deprecation warning. No functional issue currently, but may break in future Android versions.
- **建议修复**: Migrate to registerForActivityResult(ActivityResultContracts.StartActivityForResult()) pattern.

