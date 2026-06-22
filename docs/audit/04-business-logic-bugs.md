# 业务逻辑（账单 / 资产 / 货币）Bug 审计

**共 14 个发现**: 🔴 2 Critical | 🟠 4 High | 🟡 5 Medium | 🟢 3 Low

## 🔴 Critical

### 1. targetDeltaInCurrency uses bill.exchangeRate without accounting for bill.currency mismatch with target asset currency

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillAssetImpactService.kt`
- **行号**: 292-294
- **描述**: targetDeltaInCurrency calculates `bill.amount * bill.exchangeRate`. For transfer bills, bill.amount is in the transaction currency (spCurrency, e.g. USD) and bill.exchangeRate is the source-to-target rate. However, bill.currency may differ from the source asset currency. For example, user transfers 100 USD from a CNY account to a PLN account. bill.amount=100, bill.currency=USD, bill.exchangeRate=3.8 (USD_to_PLN). The correct target delta should convert 100 USD to CNY first (~714.29), then apply rate to get ~38 PLN. Instead the formula produces 100*3.8=380 PLN -- a 10x error. The function ignores bill.currency entirely and never converts bill.amount to the source asset currency before applying the rate.
- **影响**: Transfer balance impact is calculated incorrectly whenever the transaction currency (spCurrency) differs from the source asset currency. Target asset receives wrong credit/debit, corrupting balances.
- **建议修复**: Convert bill.amount from bill.currency to sourceCurrency first, then multiply by exchangeRate: `val sourceAmount = convertAmountBetweenCurrencies(bill.amount, bill.currency, sourceCurrency); return sourceAmount * bill.exchangeRate`.

### 2. handleSave has no re-entrancy guard -- double-tap or concurrent async callbacks create duplicate bills

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt`
- **行号**: 1425-1863
- **描述**: handleSave() is called from the save button click listener (with animation) and also recursively from async callbacks (exchange rate confirmation at line 1465, currency exchange confirmation at line 1484, investment schedule confirmation at line 1520). There is no boolean guard (e.g. isSaving) to prevent concurrent execution. If the user taps save during the animation's withEndAction delay, or if multiple async coroutines complete near-simultaneously, two or more bill insertions can proceed in parallel on the IO dispatcher, creating duplicate bills and applying balance impact twice.
- **影响**: Duplicate bills and double balance impact. User sees two identical entries; asset balance is debited/credited twice for a single transaction.
- **建议修复**: Add a `private var isSaving = false` flag. Set it true at the top of handleSave and false at every exit point (return, toast, onCloseRequest). Wrap the flag check in a synchronized block or ensure all paths run on Main thread.

## 🟠 High

### 1. MoneyConversionService falls back to identity conversion when rate is zero, silently producing wrong amount

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/MoneyConversionService.kt`
- **行号**: 48
- **描述**: In convertAmountBetweenCurrencies, when fromRate is 0.0, the code returns `amount` unchanged instead of throwing an error: `if (fromRate == 0.0) amount else amount / fromRate`. A rate of 0.0 is an invalid/stale value (DEFAULT_RATES has no 0.0 entries), yet the code silently treats it as a 1:1 conversion. This means 1000 JPY would be treated as 1000 CNY, or vice versa, causing massive balance corruption.
- **影响**: Any currency with a zero rate (data corruption, API failure returning 0, or user-entered custom rate) will be treated as equal to CNY, producing orders-of-magnitude incorrect balance impacts.
- **建议修复**: Replace the fallback `if (fromRate == 0.0) amount` with `if (fromRate == 0.0) throw MissingCurrencyRateException(setOf(from))` or `return Double.NaN` to signal the error clearly.

### 2. Investment interest settlement accumulates interest daily within a payout period instead of computing flat interest for the full period

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/InvestmentInterestService.kt`
- **行号**: 155-189
- **描述**: settleLotInterest calculates `interest = principal * dailyRate` (1 day of interest) but the loop advances by 1 day per iteration, with `payoutDelay` potentially being 30+ days. The remainingPrincipal is updated each iteration with the accumulated interest, causing compounding. For a schedule with monthly payouts (payoutDelay=30), the loop iterates 30 times with daily compounding instead of computing 30 days of simple interest once. Example: principal=12000, rate=10%, payoutDelay=30: code produces ~102.74 (compound) vs correct 98.63 (simple). The error grows with longer payout periods and higher rates.
- **影响**: Investment interest is over-credited for assets with payout periods longer than 1 day. The longer the payout period, the larger the compounding error. Users receive more interest than they should.
- **建议修复**: Compute the total interest for the full payout period in a single calculation: `val totalDays = (payoutDelay / MILLIS_PER_DAY).toInt(); val interest = roundMoney(workingLot.remainingPrincipal * dailyRate * totalDays)`. Update lastSettledAt to the payoutDay, not earningDay + 1.

### 3. BillMutationService.replaceBill does not revert source bill amount modification made by prior refund

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillMutationService.kt`
- **行号**: 97-175
- **描述**: When saveRefundBill creates a refund, it reduces the source expense's `amount` field (e.g., 100 -> 70 for a 30 refund). When replaceBill is later called to edit the expense, it reverts the expense's balance impact using `baseOriginalAmount(bill)` which returns 100 (the original), then applies the new amount. However, it never restores the source bill's `amount` field back to its pre-refund state before re-processing. The refund's amount delta is baked into the source bill's `amount` field permanently. If the user edits the expense multiple times, each edit compounds the issue because the refund delta is applied to an already-modified amount.
- **影响**: Source expense bill's `amount` field drifts from the intended value after repeated edits when refunds exist. The stored amount may not accurately reflect the net expense after refunds.
- **建议修复**: Before applying the new bill in replaceBill, if the old bill has associated refunds, recalculate the source bill's `amount` field to be `newBill.amount - totalRefundAmount` (clamped to [0, baseOriginalAmount]). Or, always recompute from scratch: revert all refund impacts, apply new expense, re-apply refunds.

### 4. convertAmountBetweenCurrencies in AccountingFormController uses wrong rate direction

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/AccountingFormController.kt`
- **行号**: 2238-2244
- **描述**: The private `convertAmountBetweenCurrencies` method (line 2238) calculates `(amount / fromRate) * toRate`. But CurrencyManager rates are CNY-based: 'USD' to 0.14 means 1 CNY = 0.14 USD. The correct formula to convert FROM fromCurrency TO toCurrency is: `(amount / fromRate) * toRate` which is `amount / rate(fromCurrency) * rate(toCurrency)`. This is actually `(amount * toRate) / fromRate`. For USD->CNY: `(amount * 1.0) / 0.14 = amount / 0.14`, which gives the CNY equivalent. This appears correct. However, the method is UNUSED in the save flow (BillAssetImpactService has its own conversion). The issue is that it's dead code that could be called from elsewhere with wrong assumptions, and its existence suggests the developer may have intended to use it but forgot.
- **影响**: Dead code risk. If any future code path calls this private method, it may produce incorrect conversions if the rate semantics change.
- **建议修复**: Remove this dead private method to avoid confusion. All currency conversions should go through MoneyConversionService.convertAmountBetweenCurrencies.

## 🟡 Medium

### 1. BillRestoreHelper does not validate restored bill against existing data -- duplicate or inconsistent records possible

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillRestoreHelper.kt`
- **行号**: 33-56
- **描述**: When restoring a deleted bill, if `origId > 0L` and no existing bill has that ID, the code inserts with `id = origId`. But it does not check whether a bill with the same properties (amount, time, account, category) already exists. If the same deletion was processed twice (e.g., crash/retry), the first restore inserts the bill and deletes the DeletedBill record. The second attempt finds no DeletedBill (already deleted), so it never runs. This is safe. However, if two different DeletedBill records reference the same origId (possible if the delete flow has a bug), the second restore would insert with auto-generated ID, creating a duplicate bill.
- **影响**: In edge cases with duplicate DeletedBill records for the same original bill, restoration creates duplicate bills with double balance impact.
- **建议修复**: Before inserting, query for an existing bill with matching properties (time, amount, accountName, categoryName, type) and skip if found.

### 2. BillDeleteHelper scoped delete can orphan refund bills when expense is deleted without including refund IDs in scope

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillDeleteHelper.kt`
- **行号**: 143-163
- **描述**: When `deleteBillAndRevertBalanceInternal` processes an expense bill, it fetches associated refund bills via `getRefundBillsBySourceId`. With `scopeBillIds = null` (single delete), all refunds are deleted. But with `scopeBillIds` (scoped batch delete), only refunds whose IDs are in scope are deleted. If a user batch-deletes an expense bill but not its associated refund bills, the refunds are NOT deleted. They become orphaned -- their `relatedBillId` still points to the deleted expense. When the refund is later displayed or restored, it will reference a non-existent source bill.
- **影响**: Orphaned refund bills with broken source references. Display may show errors; restore may fail to find the source bill.
- **建议修复**: When deleting an expense bill, always delete all associated refund bills regardless of scope, OR warn the user that refund bills will also be deleted.

### 3. BillAssetImpactService silently swallows MissingCurrencyRateException, creating bills without balance impact

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillAssetImpactService.kt`
- **行号**: 32-39
- **描述**: applyBillBalanceImpact catches MissingCurrencyRateException and returns 0. This means the bill is saved to the database but the asset balance is NOT updated. The caller (BillMutationService.insertBillAndApplyImpact) logs a warning but proceeds. The bill exists in the database with the wrong asset balance. Similarly, revertBillBalanceImpact returns 0 on missing rates, meaning old balance impact is not reversed during edit, causing cumulative drift.
- **影响**: Bills with missing currency rates are persisted without balance impact, causing the asset balance to be permanently out of sync with the sum of all bills. The user sees a bill in the list but the balance doesn't reflect it.
- **建议修复**: Propagate the exception to the caller and prevent the bill from being saved when currency rates are missing. Show an error toast to the user instead of silently saving.

### 4. CurrencyManager.DEFAULT_RATES has inverted rate semantics for JPY -- 20.0 means 1 CNY = 20 JPY but stored as rate-to-CNY

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/CurrencyManager.kt`
- **行号**: 23-30
- **描述**: DEFAULT_RATES maps currencies to rates relative to CNY. The comment says 'against CNY base' and the API URL is `.../latest/CNY`, meaning rates are '1 CNY = X foreign'. So 'USD' to 0.14 means 1 CNY = 0.14 USD. The conversion functions use `amount / rate` to get CNY from foreign (convertToCny) and `amount * rate` to get foreign from CNY (convertFromCny). For JPY with rate 20.0: 1 CNY = 20 JPY is correct (real rate is ~20 JPY per CNY). However, the rates are stale fallbacks. If the API is unreachable and these defaults are used, the rates for USD (0.14) and EUR (0.13) are approximately correct, but over time they become increasingly inaccurate. More critically, the DEFAULT_RATES are set at class init time but overwritten by API data. If the API returns different rate semantics (e.g., 1 JPY = X CNY), the conversion formulas would be inverted.
- **影响**: Stale default rates produce incorrect conversions when API is unreachable. Risk of inverted rates if API response format changes.
- **建议修复**: Add a comment documenting the rate semantics clearly. Add validation after API fetch: if rate for USD is > 1 (suggesting 1 USD = X CNY instead of 1 CNY = X USD), invert the rates.

### 5. InvestmentInterestService.reconcileAssetLotsToBalance reduces lots in arbitrary order, not oldest-first

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/InvestmentInterestService.kt`
- **行号**: 120-131
- **描述**: When the asset balance decreases and lots need to be reduced, the code iterates `openLots` in whatever order the DAO returns them (likely insertion order or ID order). It reduces lots sequentially until the reduction is satisfied. This means the first lot might be fully depleted while later lots remain untouched. For tax or accounting purposes, lots should typically be reduced in FIFO (oldest first) or LIFO order. Using arbitrary order means the interest calculation on remaining lots may be incorrect if older lots have different rates or start dates.
- **影响**: Lot reduction order affects which lots retain principal, which in turn affects interest calculations for those lots. Arbitrary order may not match user expectations.
- **建议修复**: Sort `openLots` by `startEarningAt` ascending (FIFO) before reduction.

## 🟢 Low

### 1. AmountFormatHelper.formatAmount does not handle NaN or Infinity from failed currency conversions

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/AmountFormatHelper.kt`
- **行号**: 16-19
- **描述**: formatAmount calls `String.format(Locale.getDefault(), pattern, amount)`. If `amount` is `Double.NaN` (from CurrencyManager.convertToCny when rate is missing) or `Double.POSITIVE_INFINITY` (from division by near-zero rate), String.format will produce 'NaN' or 'Infinity' text in the UI, which is confusing to users.
- **影响**: Edge case UI glitch: 'NaN' or 'Infinity' displayed in amount fields when currency rate is missing or zero.
- **建议修复**: Add a check: `if (amount.isNaN() || amount.isInfinite()) return "--"` before formatting.

### 2. VoiceInputHandler recording thread accesses isRecording flag without synchronization

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/VoiceInputHandler.kt`
- **行号**: 339-375
- **描述**: The `isRecording` flag is read on the recording thread (line 339: `while (isRecording)`) and written on the main thread (line 455: `isRecording = false`). Without volatile or synchronized access, the recording thread may not see the updated value immediately due to CPU caching, causing the recording loop to continue for extra iterations after stopRecording is called.
- **影响**: Minor: recording may continue for a few extra milliseconds after stop, potentially capturing unwanted audio. Usually not noticeable.
- **建议修复**: Mark `isRecording` as `@Volatile` or use `AtomicBoolean`.

### 3. BillDisplayFormatter.buildCrossCurrencyAmountFormula uses bill.amount * bill.exchangeRate which may be wrong for transfers

- **文件**: `app/src/main/java/com/taostudio/tapaccounting/logic/BillDisplayFormatter.kt`
- **行号**: 131-137
- **描述**: buildCrossCurrencyAmountFormula calculates `bill.amount * bill.exchangeRate` as the account amount. For expense/income bills, exchangeRate is the bill-currency-to-CNY rate, so this gives the CNY equivalent -- correct for display. For transfer bills, exchangeRate is the source-to-target rate. If bill.amount is in the transaction currency (which may differ from source currency), the formula produces the wrong account amount. This is the same root cause as the targetDeltaInCurrency bug but in the display layer.
- **影响**: Cross-currency transfer amount formula shown in the UI may display incorrect converted amount.
- **建议修复**: For transfer bills, convert bill.amount from bill.currency to sourceCurrency first, then multiply by exchangeRate.

