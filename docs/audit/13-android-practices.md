# Android 最佳实践审计报告

**共 22 个发现**: 🔴 0 Critical | 🟠 7 High | 🟡 11 Medium | 🟢 4 Low

## 🟠 High

### 1. ChatActivity.aiWorkScope uses SupervisorJob without cancellation on destroy

- **分类**: Lifecycle awareness
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatActivity.kt`
- **行号**: 154-155
- **描述**: aiWorkScope is created with SupervisorJob() + Dispatchers.Main.immediate. While aiScopeJob.cancel() is called in onDestroy() at line 1688, the scope itself is also used by multiple lazy-initialized controllers (messagePipeline, billCorrectionService, etc.) that may schedule work after onDestroy if there is a race condition. The pattern of exposing the raw CoroutineScope to child controllers without lifecycle binding is fragile.
- **影响**: Coroutines may execute after Activity destruction, causing crashes from accessing destroyed views or leaked resources.
- **建议修复**: Instead of a raw CoroutineScope, pass lifecycleScope from the Activity to child controllers. For work that must survive configuration changes, use ViewModel scope. The current pattern works because cancel() is called in onDestroy, but add a defensive check in each controller to verify isActive before dispatching UI updates.

### 2. LocalAsrService creates unmanaged CoroutineScope for downloads and warmup

- **分类**: Lifecycle awareness
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/LocalAsrService.kt`
- **行号**: 221, 480, 600
- **描述**: LocalAsrService is a singleton object that creates bare CoroutineScope(Dispatchers.IO) in installLocalModelWithUI (line 221), warmUp (line 480), and downloadModelWithProgress (line 600). These scopes are fire-and-forget with no cancellation mechanism tied to any lifecycle. If the user navigates away from the settings page, the download continues in the background with no way to cancel it from the UI layer.
- **影响**: Network and CPU resources consumed after user leaves the screen. Dialog references (passed as closures) may reference destroyed Activities, causing window token leaks.
- **建议修复**: Accept a CoroutineScope parameter from the calling Activity/Fragment, or use a cancellableJob stored as a property that callers can cancel. The dialog-dismiss pattern already exists but should be tied to the Activity lifecycle via lifecycleScope.

### 3. Missing android:exported attribute on multiple Activity declarations

- **分类**: Android 12+ exported components
- **文件**: `app/src/main/AndroidManifest.xml`
- **行号**: 112-178
- **描述**: Android 12 (API 31+) requires all components with intent-filters to declare android:exported explicitly. The following activities are missing android:exported and have no intent-filter, which defaults to exported=false (safe but should be explicit): AppListActivity (112), AssetActivity (113), AddAssetActivity (114), BalanceAdjustmentActivity (115), SettingsActivity (116), CategorySortActivity (117), StorageCleanupActivity (129), LogViewerActivity (156), CurrencyManagerActivity (157), ExchangeRateActivity (158), BillDisplaySettingsActivity (159), SensitivityActivity (160), GesturePermissionGuideActivity (161), BillDetailActivity (162), RefundActivity (163), EditBillActivity (164), CalendarActivity (165), BillSearchActivity (166), BookOverviewActivity (167), AssetDetailActivity (173), AssetStatsActivity (174), AiRuleManageActivity (175), AiFeatureSettingsActivity (176), AiConfigActivity (177).
- **影响**: While these default to exported=false (no intent-filter), the Android lint warning will fire. More importantly, if any of these gain an intent-filter in the future without adding exported, the app will crash on Android 12+.
- **建议修复**: Add android:exported="false" to all activities that are not launched externally. This is a defensive best practice and eliminates lint warnings.

### 4. BackupPinCrypto uses 4-digit PIN with only 10,000 possible combinations

- **分类**: Storage access
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/BackupPinCrypto.kt`
- **行号**: 29
- **描述**: The backup encryption uses a 4-digit numeric PIN (regex ^\d{4}$). Despite using PBKDF2 with 60,000 iterations, the keyspace is only 10,000 combinations. A brute-force attack on a modern device could crack this in under a second. PBKDF2 iterations protect against rainbow tables but cannot compensate for a tiny keyspace.
- **影响**: An attacker who obtains the backup file can decrypt the AI API keys in negligible time. The encryption provides a false sense of security.
- **建议修复**: Increase minimum PIN length to 6 digits (1M combinations) or allow alphanumeric passphrases. Alternatively, use Android Keystore to wrap the encryption key, so the PIN only unlocks access to a hardware-backed key. At minimum, increase PBKDF2 iterations to 600,000+ to make brute-force slower (though still inadequate for 4 digits).

### 5. WebDAV credentials stored in plain SharedPreferences

- **分类**: Storage access
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/backup/WebDavClient.kt`
- **行号**: 143-146
- **描述**: WebDAV username and password are stored in plain SharedPreferences (tap_cloud_backup_prefs). On rooted devices or via backup extraction, these credentials are readable. The password field (KEY_WEBDAV_PASS) contains the raw WebDAV application password.
- **影响**: WebDAV credentials are exposed to any app with root access or backup read capability. Combined with android:allowBackup="true" in the manifest, the backup itself could contain these credentials.
- **建议修复**: Use EncryptedSharedPreferences from the AndroidX Security library, or encrypt the password with a key stored in Android Keystore before writing to SharedPreferences. The BackupPinCrypto pattern could be adapted for this.

### 6. android:allowBackup="true" combined with cleartext traffic and stored credentials

- **分类**: Storage access
- **文件**: `app/src/main/AndroidManifest.xml`
- **行号**: 33, 39
- **描述**: The manifest declares android:allowBackup="true" (line 33) and android:usesCleartextTraffic="true" (line 39). With allowBackup=true, adb backup can extract SharedPreferences and databases. With cleartext traffic, WebDAV credentials and backup data can be intercepted on the network. The WebDAV URL may use http:// (WebDavClient adds https:// if missing, but the user may configure http:// explicitly).
- **影响**: User data including API keys, WebDAV passwords, and financial records can be extracted via adb backup. Network traffic can be intercepted on untrusted networks.
- **建议修复**: Set android:allowBackup="false" or use android:fullBackupContent to exclude sensitive files. Remove android:usesCleartextTraffic="true" and enforce HTTPS in WebDavClient by rejecting http:// URLs.

### 7. Missing POST_NOTIFICATIONS runtime permission request in main flow

- **分类**: Permission handling
- **文件**: `app/src/main/AndroidManifest.xml`
- **行号**: 19
- **描述**: The manifest declares android.permission.POST_NOTIFICATIONS (required for Android 13+ API 33). However, in the scanned source files, there is no visible runtime permission request for POST_NOTIFICATIONS. The OverlayService starts as a foreground service with a notification, and on Android 13+, if the user has not granted this permission, the notification will be silently suppressed. On some OEM ROMs, the foreground service may be killed without a visible notification.
- **影响**: On Android 13+, the foreground service notification is invisible if permission is denied. Some OEMs may kill the service entirely. Users have no way to see that the service is running.
- **建议修复**: Request POST_NOTIFICATIONS permission during onboarding or when the user first enables flip/tap detection. Use ActivityResultContracts.RequestPermission() and show a rationale dialog explaining why the notification is needed for the background service.

## 🟡 Medium

### 1. Unmanaged CoroutineScope in Application.onCreate leaks coroutines

- **分类**: Lifecycle awareness
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/TapApplication.kt`
- **行号**: 43-51
- **描述**: TapApplication.onCreate() creates two bare CoroutineScope(Dispatchers.IO) instances for migration and icon preloading. These scopes are never cancelled and have no exception handler. If the Application is recreated (rare but possible) or if an exception occurs, it will be silently swallowed by the default handler. More critically, the migration coroutine has no structured concurrency -- if it fails partway, there is no retry or user feedback.
- **影响**: Potential silent failures in database migration and icon preloading. On low-memory devices, coroutines may outlive their useful window.
- **建议修复**: Use a properly scoped CoroutineScope with SupervisorJob() stored as a property on TapApplication, and attach a CoroutineExceptionHandler. Cancel it in onTerminate() for cleanliness. Example: private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Logger.e(...) })

### 2. QuickStartTileService uses deprecated ACTION_CLOSE_SYSTEM_DIALOGS

- **分类**: Target SDK compliance
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/QuickStartTileService.kt`
- **行号**: 33
- **描述**: On Android 12 (API 31+), sending ACTION_CLOSE_SYSTEM_DIALOGS broadcast throws a SecurityException for apps targeting API 31+. The code correctly guards this with Build.VERSION.SDK_INT < Build.VERSION_CODES.S, but the fallback for Android 12/13 is a no-op that leaves the QS panel open, degrading UX.
- **影响**: On Android 12/13, the Quick Settings panel remains open after the tile is clicked. On Android 14+, the workaround with PendingIntent.getActivity is a hack that may not reliably collapse the panel.
- **建议修复**: For Android 14+, use TileService.requestListeningState() or accept that the QS panel stays open. For Android 12-13, the no-op is unavoidable. Consider using a trampoline Activity (QuickStartActivity) as the tile onClick handler instead, which would naturally dismiss the QS panel.

### 3. BootReceiver starts foreground service without POST_NOTIFICATIONS permission check

- **分类**: Notification channel creation
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/BootReceiver.kt`
- **行号**: 28-32
- **描述**: BootReceiver calls OverlayService.startCompat() which calls ContextCompat.startForegroundService(). On Android 13+ (API 33), if the user has not granted POST_NOTIFICATIONS permission, the foreground service notification will be invisible, and on some OEM ROMs, the service may be killed. The manifest declares the permission (line 19) but there is no runtime check before starting the foreground service from the boot receiver.
- **影响**: On Android 13+ devices where the user denied notification permission, the foreground service may be silently killed by the system on some OEMs (Xiaomi, Huawei, Samsung).
- **建议修复**: Before calling startForegroundService in BootReceiver, check if POST_NOTIFICATIONS is granted. If not, still start the service (it works without visible notification on stock Android), but log a warning. The real fix is to ensure the permission is requested during onboarding.

### 4. ChatActivity.onBackPressed() is deprecated in API 33

- **分类**: Target SDK compliance
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatActivity.kt`
- **行号**: 1697-1707
- **描述**: ChatActivity overrides onBackPressed() which is deprecated starting in API 33. The correct approach is to use OnBackPressedDispatcher with addCallback(). HomeFragment already uses this pattern (line 257 of HomeFragment.kt), but ChatActivity does not.
- **影响**: On Android 13+ with predictive back gesture enabled, the deprecated callback may not integrate correctly with the back gesture animation. The app targets SDK 34, so this should be migrated.
- **建议修复**: Replace onBackPressed() override with onBackInvokedCallback registered via onBackInvokedDispatcher (API 33+) or OnBackPressedDispatcher in onCreate().

### 5. OverlayService KeepAliveManager BroadcastReceiver registration may fail on Android 14+

- **分类**: Target SDK compliance
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/OverlayService.kt`
- **行号**: 110-125
- **描述**: The screen receiver is registered with RECEIVER_EXPORTED on TIRAMISU+, but the catch block at line 124 falls back to registering without the flag. On Android 14+ (UPSIDE_DOWN_CAKE), registering a receiver that receives implicit broadcasts (SCREEN_ON/OFF/USER_PRESENT) without RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED will throw. The fallback at line 124 would throw again, and the second catch is empty, so the receiver is simply never registered.
- **影响**: On some Android 14 devices, if the first registration attempt fails for any reason other than the export flag, the receiver will not be registered, and the service will not respond to screen on/off events, breaking sensor lifecycle management.
- **建议修复**: Remove the fallback registration at line 124. The initial registration with RECEIVER_EXPORTED is correct for system broadcasts. If it fails, log the error and do not retry with a potentially invalid flag.

### 6. TapApplication uses static singleton pattern instead of proper DI

- **分类**: Lifecycle awareness
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/TapApplication.kt`
- **行号**: 19-24
- **描述**: TapApplication.app() returns a static singleton instance. This is a common pattern but makes testing difficult and can cause issues if the Application is recreated. The repositories (BillRepository, AssetRepository, CategoryRepository) are lazily initialized on the Application, which means they share the Application lifecycle and cannot be scoped to Activities or Fragments.
- **影响**: Repositories and database connections are never released. Testing requires a real Application instance. Not a runtime issue but a code quality concern.
- **建议修复**: Consider using Hilt or Koin for dependency injection. At minimum, document that app() will throw before Application.onCreate() completes.

### 7. HomeFragment uses deprecated onBackPressed pattern

- **分类**: Target SDK compliance
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ui/main/home/HomeFragment.kt`
- **行号**: 257-270
- **描述**: HomeFragment registers an OnBackPressedCallback but at line 266 calls requireActivity().onBackPressed() as a fallback, which is deprecated in API 33. The callback sets isEnabled=false, calls onBackPressed(), then re-enables -- this pattern bypasses the dispatcher and may cause issues with predictive back.
- **影响**: On Android 13+ with predictive back, this fallback will not show the correct back animation. The callback may be called twice in some edge cases.
- **建议修复**: Instead of calling requireActivity().onBackPressed(), remove the callback and let the default back behavior handle it. Use isEnabled = false and then the system will call the next handler in the chain.

### 8. Edge-to-edge display not fully enforced for Android 15 target

- **分类**: Edge-to-edge display
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/MainActivity.kt`
- **行号**: 223-421
- **描述**: Android 15 (API 35) enforces edge-to-edge by default and deprecates setDecorFitsSystemWindows(). MainActivity handles window insets via ViewCompat.setOnApplyWindowInsetsListener but does not explicitly call WindowCompat.setDecorFitsSystemWindows(window, false) in onCreate. While targetSdk is 34, when targeting SDK 35, the app will automatically go edge-to-edge and the current inset handling may produce unexpected padding.
- **影响**: When the app targets SDK 35, content will render behind system bars. The existing inset handling in MainActivity may partially compensate, but without explicit edge-to-edge setup, there may be visual glitches during the transition.
- **建议修复**: Add WindowCompat.setDecorFitsSystemWindows(window, false) in MainActivity.onCreate() and ensure all fragments handle WindowInsetsCompat.Type.systemBars() correctly. This prepares the app for SDK 35 migration.

### 9. OverlayService uses START_STICKY which may cause restart loops on some OEMs

- **分类**: Battery optimization
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/OverlayService.kt`
- **行号**: 469
- **描述**: The service returns START_STICKY from onStartCommand. On aggressive OEM ROMs (Xiaomi MIUI, Huawei EMUI, Samsung One UI), START_STICKY services are frequently killed and restarted, consuming battery. The KeepAliveManager and KeepAliveWorker add additional restart mechanisms, creating a potential restart loop where the service is killed, restarted by START_STICKY, then immediately killed again.
- **影响**: On aggressive OEM ROMs, this can cause battery drain from repeated service restarts. Some OEMs may flag the app as battery-draining and restrict it further.
- **建议修复**: Consider using START_REDELIVER_INTENT instead of START_STICKY, and implement exponential backoff in the restart mechanism. Also add a user-visible explanation of why the service needs to run persistently, and direct users to disable battery optimization for the app.

### 10. QuickStartActivity has no exported attribute protection

- **分类**: Android 12+ exported components
- **文件**: `app/src/main/AndroidManifest.xml`
- **行号**: 51-57
- **描述**: QuickStartActivity is declared with android:exported="true" but has no intent-filter and no permission guard. Any app can launch this Activity to start the OverlayService, which could be used to drain battery or interfere with the user's device by triggering flip/tap actions.
- **影响**: A malicious app could repeatedly launch QuickStartActivity to start the overlay service, consuming battery and sensor resources.
- **建议修复**: Add a custom permission or check the calling package in onCreate(). Since this is used for external integration, consider requiring a signature-level permission: android:permission="com.taostudio.tapaccounting.permission.QUICK_START".

### 11. ShizukuProvider uses INTERACT_ACROSS_USERS_FULL permission

- **分类**: Permission handling
- **文件**: `app/src/main/AndroidManifest.xml`
- **行号**: 219-224
- **描述**: The ShizukuProvider is declared with android:permission="android.permission.INTERACT_ACROSS_USERS_FULL". This is a system-level permission that third-party apps cannot hold. While this is required by Shizuku's architecture, it means the provider is effectively inaccessible to other apps, which is the intended behavior. However, on Android 14+, providers with system permissions may trigger additional security reviews.
- **影响**: No direct functional issue. The permission ensures only the system and Shizuku can interact with this provider.
- **建议修复**: No change needed. This is the standard Shizuku provider declaration. Verify this does not cause issues with Google Play review if publishing to the Play Store.

## 🟢 Low

### 1. LocalAsrService uses HttpURLConnection instead of OkHttp for model downloads

- **分类**: Background execution limits
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/LocalAsrService.kt`
- **行号**: 736-845
- **描述**: The model download uses raw HttpURLConnection with manual timeout handling. OkHttp is already in the dependency tree (line 94 of build.gradle.kts) and provides better connection pooling, automatic retries, interceptors, and cancellation support via Call.cancel().
- **影响**: No functional issue, but inconsistent with the rest of the codebase (WebDavClient uses OkHttp). Missing features: no connection reuse, no automatic redirect handling for mirror fallback, harder to cancel mid-download.
- **建议修复**: Replace HttpURLConnection with OkHttp Call. Use Call.cancel() for download cancellation instead of the manual cancelDownload flag.

### 2. OverlayService WakeLock tag does not follow naming convention

- **分类**: Battery optimization
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/OverlayService.kt`
- **行号**: 150
- **描述**: The WakeLock tag "TapAccount::BriefWL" uses the app package prefix correctly but the tag should include the class name for debugging. More importantly, the WakeLock is acquired with a timeout (4000ms default) which is good practice, and setReferenceCounted(false) is set. However, if the service is destroyed while the WakeLock is held, releaseAllWakeLocks() in detach() is called but wrapped in try-catch that silently swallows errors.
- **影响**: Minor. The timeout-based acquire means the WakeLock will auto-release even if release fails. Battery impact is bounded.
- **建议修复**: No critical change needed. Consider logging WakeLock acquisition/release for diagnostics.

### 3. OverlayServiceNotification uses system default icon instead of app icon

- **分类**: Notification channel creation
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/OverlayServiceNotifications.kt`
- **行号**: 24
- **描述**: The foreground service notification uses android.R.drawable.ic_menu_edit as the small icon. This is a system resource that looks generic and inconsistent across devices. Android guidelines require using a monochrome app icon for the notification small icon.
- **影响**: The notification appears generic and may confuse users about which app is running the foreground service. On some devices, system icons may render differently.
- **建议修复**: Replace with a custom monochrome vector drawable from the app's resources, such as @drawable/ic_notification or a simplified version of the app logo.

### 4. InvestmentInterestWorker has no battery constraint

- **分类**: Background execution limits
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/InvestmentInterestWorker.kt`
- **行号**: 31-37
- **描述**: InvestmentInterestWorker schedules a daily periodic work request with no constraints. Unlike AutoBackupWorker which sets setRequiresBatteryNotLow(true), this worker will run even on low battery. The work itself is lightweight (database query + update), so the impact is minimal, but consistency with AutoBackupWorker constraints would be better.
- **影响**: Negligible performance impact. The interest settlement is a lightweight database operation.
- **建议修复**: Add .setRequiresBatteryNotLow(true) constraint for consistency, or document why no constraints are needed.

