package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import java.util.Calendar

/**
 * 信用卡账单周期计算服务。
 */
class CreditCardCycleService {

    data class CreditCardCycleSnapshot(
        val assetId: Long,
        val cardName: String,
        val statementDay: Int,
        val dueDay: Int,
        val creditLimit: Double,
        val statementStart: Long,
        val statementEnd: Long,
        val billedSpend: Double,
        val unbilledSpend: Double,
        val paymentsInCycle: Double,
        val amountDue: Double,
        val availableLimit: Double?,
        val daysToDue: Int?
    )

    /**
     * 计算信用卡周期快照。
     * @param asset 信用卡资产
     * @param bills 该卡相关的所有账单
     * @return 周期快照，如果未设置账单日返回 null
     */
    fun calculateSnapshot(
        asset: Asset,
        bills: List<Bill>,
        now: Long = System.currentTimeMillis()
    ): CreditCardCycleSnapshot? {
        val statementDay = getStatementDay(asset)
        if (statementDay <= 0) return null

        val dueDay = getDueDay(asset)

        // 计算已出账周期：上个账单日次日 00:00 到本次账单日 23:59
        val (statementStart, statementEnd) = calculateStatementPeriod(statementDay, now)

        // 筛选相关账单
        val relatedBills = bills.filter { bill ->
            bill.accountId == asset.id || bill.toAccountId == asset.id
                    || bill.accountName == asset.name || bill.toAccountName == asset.name
        }

        // 已出账周期内的消费/退款
        val billedBills = relatedBills.filter { it.time in statementStart..statementEnd }
        val billedSpend = billedBills
            .filter { it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND }
            .sumOf { it.amount }
        val billedRefund = billedBills
            .filter { it.subType == Bill.SUBTYPE_REFUND }
            .sumOf { it.amount }

        // 未出账消费（本次账单日之后到今天）
        val unbilledSpend = relatedBills
            .filter { it.time > statementEnd && it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND }
            .sumOf { it.amount }

        // 还款（本期出账后到现在）。出账后还款才会抵扣本期待还。
        val paymentsInCycle = relatedBills
            .filter { it.time > statementEnd && it.time <= now }
            .filter { it.type == Bill.TYPE_REPAYMENT || (it.type == Bill.TYPE_TRANSFER && it.subType == Bill.SUBTYPE_REPAYMENT) }
            .sumOf { it.amount }

        val amountDue = (billedSpend - billedRefund - paymentsInCycle).coerceAtLeast(0.0)

        val availableLimit = if (asset.creditLimit > 0) {
            val currentDebt = (-asset.balance).coerceAtLeast(0.0)
            asset.creditLimit - currentDebt
        } else null

        val daysToDue = if (dueDay > 0) {
            calculateDaysToDue(dueDay, statementEnd, now)
        } else null

        return CreditCardCycleSnapshot(
            assetId = asset.id,
            cardName = asset.name,
            statementDay = statementDay,
            dueDay = dueDay,
            creditLimit = asset.creditLimit,
            statementStart = statementStart,
            statementEnd = statementEnd,
            billedSpend = billedSpend - billedRefund,
            unbilledSpend = unbilledSpend,
            paymentsInCycle = paymentsInCycle,
            amountDue = amountDue,
            availableLimit = availableLimit,
            daysToDue = daysToDue
        )
    }

    /**
     * 获取账单日（兼容旧 billingDay 字段）。
     */
    fun getStatementDay(asset: Asset): Int {
        // 新字段 statementDay 优先，回退到 billingDay
        return if (asset.statementDay > 0) asset.statementDay
        else if (asset.billingDay > 0) asset.billingDay
        else 0
    }

    /**
     * 获取还款日。
     */
    fun getDueDay(asset: Asset): Int {
        return if (asset.dueDay > 0) asset.dueDay
        else if (asset.billingDay > 0) asset.billingDay
        else 0
    }

    /**
     * 计算已出账周期。
     */
    private fun calculateStatementPeriod(statementDay: Int, now: Long): Pair<Long, Long> {
        // 本次账单日
        val thisMonthStatement = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, statementDay.coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }

        val statementEnd: Long
        val statementStart: Long

        if (now < thisMonthStatement.timeInMillis) {
            // 今天未到账单日：已出账周期 = 上上月账单日次日 ~ 上月账单日
            val lastMonthStatement = Calendar.getInstance().apply {
                timeInMillis = thisMonthStatement.timeInMillis
                add(Calendar.MONTH, -1)
            }
            statementEnd = lastMonthStatement.timeInMillis
            val prevPrevStatement = Calendar.getInstance().apply {
                timeInMillis = lastMonthStatement.timeInMillis
                add(Calendar.MONTH, -1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, 1)
            }
            statementStart = prevPrevStatement.timeInMillis
        } else {
            // 今天已过账单日：已出账周期 = 上月账单日次日 ~ 本月账单日
            statementEnd = thisMonthStatement.timeInMillis
            val lastMonthStatement = Calendar.getInstance().apply {
                timeInMillis = thisMonthStatement.timeInMillis
                add(Calendar.MONTH, -1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, 1)
            }
            statementStart = lastMonthStatement.timeInMillis
        }

        return statementStart to statementEnd
    }

    /**
     * 计算距离还款日还有几天。
     */
    private fun calculateDaysToDue(dueDay: Int, statementEnd: Long, now: Long): Int {
        val dueCal = Calendar.getInstance().apply {
            timeInMillis = statementEnd
            set(Calendar.DAY_OF_MONTH, dueDay.coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }

        if (dueCal.timeInMillis <= statementEnd) {
            dueCal.add(Calendar.MONTH, 1)
        }

        val diffMs = dueCal.timeInMillis - now
        return (diffMs / (24 * 3600_000)).toInt().coerceAtLeast(0)
    }
}
