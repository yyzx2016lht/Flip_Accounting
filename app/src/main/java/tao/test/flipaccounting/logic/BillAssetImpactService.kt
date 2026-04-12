package tao.test.flipaccounting.logic

import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.Bill

object BillAssetImpactService {

    private fun baseOriginalAmount(bill: Bill): Double {
        return if (bill.originalAmount > 0.0) {
            kotlin.math.max(bill.originalAmount, bill.amount)
        } else {
            bill.amount
        }
    }

    suspend fun applyBillBalanceImpact(db: AppDatabase, bill: Bill) {
        when {
            bill.subType == Bill.SUBTYPE_REFUND -> return
            bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT || bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED -> return
            bill.type == Bill.TYPE_EXPENSE -> {
                val asset = resolveSourceAsset(db, bill) ?: return
                ensureRatesForImpact(bill, sourceAsset = asset, targetAsset = null)
                val sourceDelta = convertAmountBetweenCurrencies(baseOriginalAmount(bill), bill.currency, asset.currency)
                db.assetDao().updateBalance(asset.id, asset.balance - sourceDelta)
            }
            bill.type == Bill.TYPE_INCOME -> {
                val asset = resolveSourceAsset(db, bill) ?: return
                ensureRatesForImpact(bill, sourceAsset = asset, targetAsset = null)
                val sourceDelta = convertAmountBetweenCurrencies(bill.amount, bill.currency, asset.currency)
                db.assetDao().updateBalance(asset.id, asset.balance + sourceDelta)
            }
            bill.type == Bill.TYPE_TRANSFER -> {
                val sourceAsset = resolveSourceAsset(db, bill)
                val targetAsset = resolveTargetAsset(db, bill)
                ensureRatesForImpact(bill, sourceAsset = sourceAsset, targetAsset = targetAsset)

                if (sourceAsset != null) {
                    val sourceDelta = convertAmountBetweenCurrencies(bill.amount, bill.currency, sourceAsset.currency)
                    db.assetDao().updateBalance(sourceAsset.id, sourceAsset.balance - sourceDelta)
                }

                if (targetAsset != null) {
                    db.assetDao().updateBalance(targetAsset.id, targetAsset.balance + targetDeltaInCurrency(bill, targetAsset.currency))
                }
            }
        }
    }

    suspend fun revertBillBalanceImpact(db: AppDatabase, bill: Bill) {
        when {
            bill.subType == Bill.SUBTYPE_REFUND -> return
            bill.type == Bill.TYPE_EXPENSE -> {
                val asset = resolveSourceAsset(db, bill) ?: return
                ensureRatesForImpact(bill, sourceAsset = asset, targetAsset = null)
                val sourceDelta = convertAmountBetweenCurrencies(baseOriginalAmount(bill), bill.currency, asset.currency)
                db.assetDao().updateBalance(asset.id, asset.balance + sourceDelta)
            }
            bill.type == Bill.TYPE_INCOME -> {
                val asset = resolveSourceAsset(db, bill) ?: return
                ensureRatesForImpact(bill, sourceAsset = asset, targetAsset = null)
                val sourceDelta = convertAmountBetweenCurrencies(bill.amount, bill.currency, asset.currency)
                db.assetDao().updateBalance(asset.id, asset.balance - sourceDelta)
            }
            bill.type == Bill.TYPE_TRANSFER -> {
                val sourceAsset = resolveSourceAsset(db, bill)
                val targetAsset = resolveTargetAsset(db, bill)
                ensureRatesForImpact(bill, sourceAsset = sourceAsset, targetAsset = targetAsset)

                if (sourceAsset != null) {
                    val sourceDelta = convertAmountBetweenCurrencies(bill.amount, bill.currency, sourceAsset.currency)
                    db.assetDao().updateBalance(sourceAsset.id, sourceAsset.balance + sourceDelta)
                }

                if (targetAsset != null) {
                    db.assetDao().updateBalance(targetAsset.id, targetAsset.balance - targetDeltaInCurrency(bill, targetAsset.currency))
                }
            }
        }
    }

    fun convertAmountBetweenCurrencies(amount: Double, fromCurrency: String, toCurrency: String): Double {
        return MoneyConversionService.convertAmountBetweenCurrencies(
            amount = amount,
            fromCurrency = fromCurrency,
            toCurrency = toCurrency,
            rateProvider = { code -> CurrencyManager.getRate(code) }
        )
    }

    fun estimateExchangeRateToTarget(amount: Double, sourceCurrency: String, targetCurrency: String): Double {
        return MoneyConversionService.estimateExchangeRateToTarget(
            amount = amount,
            sourceCurrency = sourceCurrency,
            targetCurrency = targetCurrency,
            rateProvider = { code -> CurrencyManager.getRate(code) }
        )
    }

    fun estimateExchangeRateToCny(currency: String): Double {
        return MoneyConversionService.estimateExchangeRateToCny(
            currency = currency,
            rateProvider = { code -> CurrencyManager.getRate(code) }
        )
    }

    fun roundMoney(amount: Double): Double {
        return MoneyConversionService.roundMoney(amount)
    }

    fun roundRate(rate: Double): Double {
        return MoneyConversionService.roundRate(rate)
    }

    suspend fun ensureRatesForImpact(
        bill: Bill,
        sourceAsset: Asset?,
        targetAsset: Asset?
    ) {
        val requiredCurrencies = linkedSetOf<String>()
        requiredCurrencies.add(bill.currency)
        sourceAsset?.currency?.takeIf { it.isNotBlank() }?.let { requiredCurrencies.add(it) }
        if (bill.type == Bill.TYPE_TRANSFER) {
            targetAsset?.currency?.takeIf { it.isNotBlank() }?.let { requiredCurrencies.add(it) }
        }
        MoneyConversionService.requireCurrenciesAvailable(
            currencies = requiredCurrencies,
            rateProvider = { code -> CurrencyManager.getRate(code) }
        )
    }

    private suspend fun resolveSourceAsset(db: AppDatabase, bill: Bill): Asset? {
        return resolveAssetByReference(db, bill.accountId, bill.accountName)
    }

    private suspend fun resolveTargetAsset(db: AppDatabase, bill: Bill): Asset? {
        return resolveAssetByReference(db, bill.toAccountId, bill.toAccountName)
    }

    private suspend fun resolveAssetByReference(
        db: AppDatabase,
        assetId: Long?,
        assetName: String
    ): Asset? {
        assetId?.let { db.assetDao().getAssetById(it) }?.let { return it }
        if (assetName.isBlank()) return null

        db.assetDao().getAssetByName(assetName)?.let { return it }

        val target = normalizeAssetName(assetName)
        if (target.isBlank()) return null

        return db.assetDao().getAllAssetsList()
            .firstOrNull { asset ->
                val candidate = normalizeAssetName(asset.name)
                candidate.isNotBlank() && candidate == target
            }
    }

    private fun normalizeAssetName(name: String): String {
        return name
            .trim()
            .lowercase()
            .replace("银行卡", "")
            .replace("信用卡", "")
            .replace("银行", "")
            .replace("账户", "")
            .replace("账本", "")
            .replace("卡", "")
            .replace("\\s+".toRegex(), "")
    }

    private fun targetDeltaInCurrency(bill: Bill, targetCurrency: String): Double {
        val rawTargetDelta = bill.amount * bill.exchangeRate
        val feeInTarget = if (bill.fee > 0.0) convertAmountBetweenCurrencies(bill.fee, bill.currency, targetCurrency) else 0.0
        return rawTargetDelta - feeInTarget
    }
}
