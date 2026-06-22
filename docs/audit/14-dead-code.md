# 死代码 & 代码重复审计报告

**共 17 个发现**: 🔴 0 Critical | 🟠 3 High | 🟡 9 Medium | 🟢 5 Low

## 🟠 High

### 1. baseOriginalAmount() duplicated in 4 files

- **分类**: duplication
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillAssetImpactService.kt, BillMutationService.kt, ui/main/assets/AssetDetailActivity.kt, AssetStatsActivity.kt`
- **行号**: BillAssetImpactService:12, BillMutationService:14, AssetDetailActivity:1245, AssetStatsActivity:2098
- **描述**: The function `baseOriginalAmount(bill: Bill): Double` is identically copy-pasted in 4 files. Each implementation does exactly `if (bill.originalAmount > 0.0) max(bill.originalAmount, bill.amount) else bill.amount`. BillDisplayFormatter already has `originalAmountOfExpenseBill()` which does the same thing but with slightly different wording.
- **影响**: Any change to the rounding/max logic requires editing 4+ files; high risk of inconsistency.
- **建议修复**: Keep only `BillDisplayFormatter.originalAmountOfExpenseBill()` and have all call sites use it. Remove the private copies from BillAssetImpactService, BillMutationService, AssetDetailActivity, and AssetStatsActivity.

### 2. stripRefundPrefix() duplicated in 8 files

- **分类**: duplication
- **文件**: `Multiple files across logic/ and ui/ packages`
- **行号**: CategoryNameNormalizer:27, BillDisplayFormatter:59, BillMutationService:22, AssetDetailActivity:1192, HomeAdapter:116, HomeBillFormatHelper:12, CalendarActivity:442, StatsPopups:533
- **描述**: The `stripRefundPrefix()` function is copy-pasted in 8 different locations. The canonical implementation is in `CategoryNameNormalizer.stripRefundPrefix()`. BillDisplayFormatter and HomeBillFormatHelper correctly delegate to it, but AssetDetailActivity, StatsPopups, and others have inline re-implementations with incomplete logic (e.g., StatsPopups only strips the `退款：` prefix but not the `退款·` alternative prefix).
- **影响**: StatsPopups and other inline implementations miss the alternate prefix `退款·`, causing bugs when encountering old-format refund data.
- **建议修复**: Remove all private copies. Have every call site use `CategoryNameNormalizer.stripRefundPrefix()` directly or through `BillDisplayFormatter.stripRefundPrefix()`.

### 3. ChatUiHelperController duplicates ChatTimeFormatter entirely

- **分类**: duplication
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/ChatUiHelperController.kt`
- **行号**: 136-180
- **描述**: ChatUiHelperController contains a complete copy of `formatTime()`, `formatChatMessageTime()`, `dayDiffFromToday()`, and `weekdayLabel()` that is identical to `chat/time/ChatTimeFormatter`. Both have the same logic for relative time formatting (just now, X minutes ago, yesterday, weekday, etc.).
- **影响**: Both files must be kept in sync. The ChatTimeFormatter was likely extracted but the old copy in ChatUiHelperController was never removed.
- **建议修复**: Remove the duplicated methods from ChatUiHelperController and have it call `ChatTimeFormatter.formatChatMessageTime()`.

## 🟡 Medium

### 1. formatMoney() duplicated in 6 files

- **分类**: duplication
- **文件**: `Multiple files`
- **行号**: BillDisplayFormatter:93, HomeBillFormatHelper:26, CalendarActivity:456, AssetDetailActivity:1200, StatsPopups:576, RefundActivity:267
- **描述**: The `formatMoney(amount, currency)` pattern is repeated across 6 files. BillDisplayFormatter.formatMoney() is the canonical version. HomeBillFormatHelper, CalendarActivity, and others have their own implementations that vary in behavior (CalendarActivity uses `String.format` directly without `AmountFormatHelper` grouping support).
- **影响**: Inconsistent money formatting across UI surfaces; grouping settings not applied uniformly.
- **建议修复**: All files should call `BillDisplayFormatter.formatMoney()` or `CurrencyUtils.formatAmount()`.

### 2. logFull() boilerplate duplicated in 3 logic files

- **分类**: duplication
- **文件**: `BillAssetImpactService.kt, BillDeleteHelper.kt, BillMutationService.kt`
- **行号**: BillAssetImpactService:20, BillDeleteHelper:12, BillMutationService:30
- **描述**: The identical `logFull(tag, message)` pattern -- `val ctx = runCatching { TapApplication.app() }.getOrNull() ?: return; if (!Prefs.isDeveloperFullLoggingEnabled(ctx)) return; Logger.d(ctx, tag, message)` -- is copy-pasted in 3 files.
- **影响**: Low risk of bug, but if the logging pattern changes (e.g., adding thread info), all 3 must be updated.
- **建议修复**: Extract to a shared utility, e.g. `Logger.logFull(tag, message)` or a top-level function in a shared file.

### 3. convertAmountBetweenCurrencies() duplicated in AccountingFormController

- **分类**: duplication
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt`
- **行号**: 2238
- **描述**: AccountingFormController has a private `convertAmountBetweenCurrencies()` that uses a simplified rate conversion formula (`amount / fromRate * toRate`) without rounding, while `BillAssetImpactService.convertAmountBetweenCurrencies()` delegates to `MoneyConversionService` which properly rounds. The form controller version bypasses the missing-rate exception safety and produces unrounded results.
- **影响**: Form controller may silently produce slightly different amounts than what gets stored in the bill, due to lack of rounding and missing-rate validation.
- **建议修复**: Remove the private copy and call `BillAssetImpactService.convertAmountBetweenCurrencies()` or `MoneyConversionService.convertAmountBetweenCurrencies()` directly.

### 4. originalAmountOfExpenseBill() duplicated in 3 files

- **分类**: duplication
- **文件**: `HomeBillFormatHelper.kt, CalendarActivity.kt, BillDisplayFormatter.kt`
- **行号**: HomeBillFormatHelper:16, CalendarActivity:446, BillDisplayFormatter:102
- **描述**: The function that computes `max(originalAmount, amount)` for expense bills is implemented in 3 places. HomeBillFormatHelper duplicates the logic inline instead of calling `BillDisplayFormatter.originalAmountOfExpenseBill()`.
- **影响**: If the logic for handling original amounts changes, HomeBillFormatHelper and CalendarActivity will be missed.
- **建议修复**: HomeBillFormatHelper and CalendarActivity should call `BillDisplayFormatter.originalAmountOfExpenseBill()`.

### 5. refundAmountOfExpenseBill() duplicated in 4 files

- **分类**: duplication
- **文件**: `BillDisplayFormatter.kt, HomeBillFormatHelper.kt, HomeAdapter.kt, CalendarActivity.kt`
- **行号**: BillDisplayFormatter:110, HomeBillFormatHelper:21, HomeAdapter:112, CalendarActivity:451
- **描述**: The refund amount calculation is duplicated 4 times. Some call BillDisplayFormatter, others implement inline. CalendarActivity re-implements the full logic inline.
- **影响**: Risk of inconsistency in refund amount display across different screens.
- **建议修复**: All files should delegate to `BillDisplayFormatter.refundAmountOfExpenseBill()`.

### 6. buildCrossCurrencyAmountFormula() duplicated in 4 files

- **分类**: duplication
- **文件**: `BillDisplayFormatter.kt, HomeBillFormatHelper.kt, AssetDetailActivity.kt, StatsPopups.kt`
- **行号**: BillDisplayFormatter:131, HomeBillFormatHelper:35, AssetDetailActivity:1285, StatsPopups:579
- **描述**: The cross-currency amount formula builder is duplicated across 4 files. AssetDetailActivity and StatsPopups correctly delegate to BillDisplayFormatter, but HomeBillFormatHelper has a full re-implementation.
- **影响**: If the formula format changes, HomeBillFormatHelper will not be updated.
- **建议修复**: HomeBillFormatHelper should delegate to `BillDisplayFormatter.buildCrossCurrencyAmountFormula()`.

### 7. Bill.TYPE_REPAYMENT is a dead constant (never used for storage)

- **分类**: dead-code
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/local/entity/Bill.kt`
- **行号**: 72
- **描述**: Bill defines `TYPE_REPAYMENT = 3` and it's used extensively in BillDetailActivity for UI display purposes. However, the data model stores repayment as `type=TYPE_TRANSFER(2) + subType=SUBTYPE_REPAYMENT(1)`. The `TYPE_REPAYMENT` constant is only used as a UI-side pseudo-type that gets converted back to TYPE_TRANSFER before storage. This creates confusion about what the actual data model is.
- **影响**: Developers reading the Bill entity may assume TYPE_REPAYMENT=3 is a valid stored type, leading to incorrect queries or logic.
- **建议修复**: Consider removing TYPE_REPAYMENT from the entity companion and defining it in a UI-layer enum or constant set, with clear documentation that it is never persisted.

### 8. CurrencyUtils.formatAmount and AmountFormatHelper.formatCurrency do the same thing differently

- **分类**: redundancy
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/CurrencyUtils.kt, AmountFormatHelper.kt`
- **行号**: CurrencyUtils:8, AmountFormatHelper:16
- **描述**: Two separate formatting approaches exist: `CurrencyUtils.formatAmount()` uses `NumberFormat` with per-currency decimal places and gets the symbol from CurrencyData. `AmountFormatHelper.formatCurrency()` uses `String.format` with always 2 decimal places and takes the symbol as a parameter. They produce different results for zero-decimal currencies (JPY, KRW, etc.) -- CurrencyUtils correctly shows no decimals, while AmountFormatHelper always shows .00.
- **影响**: JPY amounts show as `¥100.00` in some places and `¥100` in others, depending on which formatter is used.
- **建议修复**: Consolidate into a single formatter that handles currency-aware decimal places. Use `CurrencyUtils.formatAmount` as the canonical version.

### 9. BillRepository is largely unused -- direct DAO access used instead

- **分类**: dead-code
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/data/repository/BillRepository.kt`
- **行号**: 1-48
- **描述**: BillRepository provides wrapped DAO methods with CategoryNameNormalizer normalization. However, most of the codebase accesses `db.billDao()` directly, bypassing the repository. The repository's `addBill`, `updateBill`, `getBillById` etc. are rarely called. BillMutationService, BillDeleteHelper, and most UI code all use the DAO directly.
- **影响**: The repository exists but provides no value since callers bypass it. Its normalization logic is duplicated in BillMutationService.
- **建议修复**: Either enforce repository usage by making DAO access package-private, or remove the repository and ensure normalization happens at the service layer (BillMutationService).

## 🟢 Low

### 1. normalizeCurrency() duplicated in 2 logic files

- **分类**: duplication
- **文件**: `CurrencyManager.kt, MoneyConversionService.kt`
- **行号**: CurrencyManager:39, MoneyConversionService:9
- **描述**: Both `CurrencyManager` and `MoneyConversionService` have identical `normalizeCurrency()` functions that do `code.trim().uppercase()`. MoneyConversionService uses `Locale.ROOT` while CurrencyManager does not, creating a subtle locale-dependent difference.
- **影响**: Minimal functional impact, but inconsistent locale handling could cause issues in Turkish locale (where `I` lowercases to a dotless-i).
- **建议修复**: Extract to a shared utility or have CurrencyManager delegate to MoneyConversionService's version.

### 2. Duplicate import: kotlin.coroutines.resume imported twice

- **分类**: dead-code
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt`
- **行号**: 37-38
- **描述**: Line 37 and 38 both contain `import kotlin.coroutines.resume`. This is a duplicate import statement.
- **影响**: No functional impact, but indicates sloppy code hygiene.
- **建议修复**: Remove the duplicate import on line 38.

### 3. Prefs.kt is a pure delegation facade with 100+ pass-through methods

- **分类**: redundancy
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/Prefs.kt`
- **行号**: 1-502
- **描述**: The `Prefs` object has over 100 methods that are pure one-line delegations to PrefsGeneralSupport, PrefsAiSupport, PrefsDisplaySupport, PrefsChatSupport, PrefsBackupSupport, and PrefsDataSupport. Every single method just calls the corresponding method on the support class.
- **影响**: Adds 500 lines of boilerplate. Every new preference requires adding a method in both Prefs and the support class. The facade provides no additional logic or validation.
- **建议修复**: Consider having callers import the specific support class directly, or use Kotlin extension functions. Keep Prefs only for backward compatibility aliases if needed.

### 4. GlideLocalFiles utility may be dead code

- **分类**: dead-code
- **文件**: `app/src/main/java/com/taostudio/tapaccounting/GlideLocalFiles.kt`
- **行号**: 1-65
- **描述**: GlideLocalFiles provides a convenience wrapper for loading local files into ImageViews with caching options. However, a search of the codebase shows it is not referenced by any other file -- all Glide calls use `Glide.with(ctx).load(url).into(iv)` directly.
- **影响**: Dead code that adds maintenance burden.
- **建议修复**: Verify no usage via a project-wide search and remove if confirmed unused.

### 5. Multiple SharedPreferences instances for the same logical domain

- **分类**: redundancy
- **文件**: `CurrencyManager.kt, PrefsDataSupport.kt, Prefs.kt`
- **行号**: CurrencyManager:38, PrefsDataSupport:14
- **描述**: CurrencyManager uses `getSharedPreferences("flip_currency_prefs")`, PrefsDataSupport uses `getSharedPreferences("flip_prefs")`, and Prefs.kt delegates to support classes that also use "flip_prefs". The currency prefs are a separate store for no clear reason, as they could be part of the main prefs.
- **影响**: Fragmented preference storage makes backup/restore more complex and increases the chance of orphaned data.
- **建议修复**: Consider consolidating currency prefs into the main "flip_prefs" store.

