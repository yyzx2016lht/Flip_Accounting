package tao.test.tapaccounting.logic

import tao.test.tapaccounting.BookAccountManager
import tao.test.tapaccounting.TapApplication
import tao.test.tapaccounting.data.local.AppDatabase
import tao.test.tapaccounting.data.local.entity.Asset
import tao.test.tapaccounting.data.local.entity.Bill
import tao.test.tapaccounting.data.local.entity.Category
import tao.test.tapaccounting.data.local.entity.InvestmentLot
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

object InvestmentInterestService {
    const val CATEGORY_NAME = "理财产品"
    const val CATEGORY_ICON = "http://res3.qianjiapp.com/cateic_licai.png"
    private const val DAYS_IN_YEAR = 365.0
    private const val MIN_INTEREST_AMOUNT = 0.01

    data class InvestmentSchedule(
        val startEarningAt: Long,
        val firstPayoutAt: Long
    )

    suspend fun ensureInvestmentCategories(db: AppDatabase) {
        ensureCategory(db, Bill.TYPE_EXPENSE)
        ensureCategory(db, Bill.TYPE_INCOME)
    }

    suspend fun createOrReplaceLotForTransfer(
        db: AppDatabase,
        bill: Bill,
        targetAsset: Asset,
        schedule: InvestmentSchedule
    ) {
        if (bill.type != Bill.TYPE_TRANSFER || bill.id <= 0L) return
        val principal = BillAssetImpactService.roundMoney(bill.amount * bill.exchangeRate)
        val normalizedStart = startOfDay(schedule.startEarningAt)
        val normalizedPayout = startOfDay(schedule.firstPayoutAt).coerceAtLeast(normalizedStart + MILLIS_PER_DAY)
        val existing = db.investmentLotDao().getLotBySourceBillId(bill.id)
        val lot = InvestmentLot(
            id = existing?.id ?: 0L,
            assetId = targetAsset.id,
            sourceBillId = bill.id,
            principalAmount = principal,
            remainingPrincipal = principal,
            currency = targetAsset.currency,
            startEarningAt = normalizedStart,
            firstPayoutAt = normalizedPayout,
            lastSettledAt = normalizedStart,
            createTime = existing?.createTime ?: System.currentTimeMillis()
        )
        db.investmentLotDao().insertLot(lot)
    }

    suspend fun createLotForAssetBalance(
        db: AppDatabase,
        asset: Asset,
        schedule: InvestmentSchedule
    ) {
        val principal = BillAssetImpactService.roundMoney(asset.balance)
        if (principal <= 0.0) return
        val normalizedStart = startOfDay(schedule.startEarningAt)
        val normalizedPayout = startOfDay(schedule.firstPayoutAt).coerceAtLeast(normalizedStart + MILLIS_PER_DAY)
        val lot = InvestmentLot(
            assetId = asset.id,
            sourceBillId = null,
            principalAmount = principal,
            remainingPrincipal = principal,
            currency = asset.currency,
            startEarningAt = normalizedStart,
            firstPayoutAt = normalizedPayout,
            lastSettledAt = normalizedStart
        )
        db.investmentLotDao().insertLot(lot)
    }

    suspend fun settleDueInterest(db: AppDatabase, now: Long = System.currentTimeMillis()) {
        val lots = db.investmentLotDao().getOpenLots()
        if (lots.isEmpty()) return

        ensureInvestmentCategories(db)
        val todayStart = startOfDay(now)
        lots.forEach { lot ->
            val asset = db.assetDao().getAssetById(lot.assetId) ?: return@forEach
            if (asset.assetCategory != Asset.CATEGORY_INVESTMENT || asset.annualInterestRate == 0.0) return@forEach
            settleLotInterest(db, asset, lot, todayStart)
        }
    }

    private suspend fun settleLotInterest(
        db: AppDatabase,
        asset: Asset,
        lot: InvestmentLot,
        todayStart: Long
    ) {
        val payoutDelay = (startOfDay(lot.firstPayoutAt) - startOfDay(lot.startEarningAt))
            .coerceAtLeast(MILLIS_PER_DAY)
        val dailyRate = asset.annualInterestRate / 100.0 / DAYS_IN_YEAR
        val incomeCategory = ensureCategory(db, Bill.TYPE_INCOME)
        val expenseCategory = ensureCategory(db, Bill.TYPE_EXPENSE)
        val bookName = BookAccountManager.getDefaultBook(TapApplication.app())

        var workingLot = lot
        var earningDay = startOfDay(workingLot.lastSettledAt).coerceAtLeast(startOfDay(workingLot.startEarningAt))
        while (true) {
            val payoutDay = earningDay + payoutDelay
            if (payoutDay > todayStart) break

            val interest = BillAssetImpactService.roundMoney(workingLot.remainingPrincipal * dailyRate)
            val nextSettledAt = earningDay + MILLIS_PER_DAY
            if (abs(interest) >= MIN_INTEREST_AMOUNT) {
                val bill = Bill(
                    type = if (interest >= 0.0) Bill.TYPE_INCOME else Bill.TYPE_EXPENSE,
                    subType = Bill.SUBTYPE_NORMAL,
                    amount = abs(interest),
                    originalAmount = abs(interest),
                    currency = asset.currency,
                    exchangeRate = 1.0,
                    categoryId = if (interest >= 0.0) incomeCategory.id else expenseCategory.id,
                    accountId = asset.id,
                    accountName = asset.name,
                    categoryName = CATEGORY_NAME,
                    time = payoutDay,
                    remark = "年利率 ${formatCompactDecimal(asset.annualInterestRate)}% 自动结息，收益为估算值；起息 ${formatDate(earningDay)}，到账 ${formatDate(payoutDay)}",
                    bookName = bookName
                )
                BillMutationService.insertBillAndApplyImpact(db, bill, applyAssetImpact = true)
                workingLot = workingLot.copy(
                    remainingPrincipal = BillAssetImpactService.roundMoney(workingLot.remainingPrincipal + interest),
                    lastSettledAt = nextSettledAt
                )
            } else {
                workingLot = workingLot.copy(lastSettledAt = nextSettledAt)
            }
            db.investmentLotDao().updateLot(workingLot)
            earningDay = nextSettledAt
        }
    }

    private suspend fun ensureCategory(db: AppDatabase, type: Int): Category {
        db.categoryDao().getCategoryByNameAndType(CATEGORY_NAME, type)?.let { return it }
        val maxOrder = db.categoryDao().getMaxSortOrder(type, null) ?: 0
        val category = Category(
            name = CATEGORY_NAME,
            type = type,
            iconId = CATEGORY_ICON,
            sortOrder = maxOrder + 10
        )
        return category.copy(id = db.categoryDao().insertCategory(category))
    }

    fun startOfDay(timeMillis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun plusDays(dayStartMillis: Long, days: Int): Long = startOfDay(dayStartMillis) + days * MILLIS_PER_DAY

    private fun formatCompactDecimal(value: Double): String {
        return String.format(Locale.getDefault(), "%.4f", value)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun formatDate(timeMillis: Long): String {
        return java.text.SimpleDateFormat("MM-dd", Locale.getDefault()).format(java.util.Date(timeMillis))
    }

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
}
