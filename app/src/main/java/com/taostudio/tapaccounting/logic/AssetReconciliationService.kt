package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * 资产对账助手服务。
 * 用户输入实际余额后，计算与账本余额的差异并分析可能原因。
 */
class AssetReconciliationService {

    enum class ReconciliationCauseType {
        LARGE_TRANSFER,      // 最近有大额转账
        RECENT_REFUND,       // 最近有退款
        INTEREST_SETTLE,     // 投资资产利息结算
        BALANCE_ADJUSTMENT,  // 最近余额调整记录
        UNRECORDED_BILL      // 可能漏记的账单
    }

    data class ReconciliationCause(
        val type: ReconciliationCauseType,
        val description: String,
        val amount: Double?
    )

    data class BillPreview(
        val billId: Long,
        val type: Int,
        val amount: Double,
        val categoryName: String,
        val accountName: String,
        val remark: String,
        val time: Long
    )

    enum class ReconciliationActionType {
        ADJUST_BALANCE,  // 余额调整
        ADD_BILL,        // 补记账单
        VIEW_BILLS,      // 查看流水
        LATER            // 稍后处理
    }

    data class ReconciliationAction(
        val type: ReconciliationActionType,
        val label: String
    )

    data class ReconciliationReport(
        val assetId: Long,
        val assetName: String,
        val ledgerBalance: Double,
        val actualBalance: Double,
        val diff: Double,
        val likelyCauses: List<ReconciliationCause>,
        val recentUnmatchedBills: List<BillPreview>,
        val suggestedActions: List<ReconciliationAction>
    )

    /**
     * 生成对账报告。
     * @param asset 资产
     * @param actualBalance 用户输入的实际余额
     * @param recentBills 最近 30 天相关账单
     */
    fun reconcile(
        asset: Asset,
        actualBalance: Double,
        recentBills: List<Bill>
    ): ReconciliationReport {
        val ledgerBalance = asset.balance
        val diff = actualBalance - ledgerBalance

        val relatedBills = recentBills.filter { bill ->
            bill.accountId == asset.id || bill.toAccountId == asset.id
                    || bill.accountName == asset.name || bill.toAccountName == asset.name
        }

        val causes = analyzeCauses(asset, diff, relatedBills)
        val unmatchedBills = findUnmatchedBills(relatedBills, diff)
        val actions = buildSuggestedActions(asset, diff)

        return ReconciliationReport(
            assetId = asset.id,
            assetName = asset.name,
            ledgerBalance = ledgerBalance,
            actualBalance = actualBalance,
            diff = diff,
            likelyCauses = causes,
            recentUnmatchedBills = unmatchedBills,
            suggestedActions = actions
        )
    }

    private fun analyzeCauses(
        asset: Asset,
        diff: Double,
        recentBills: List<Bill>
    ): List<ReconciliationCause> {
        val causes = mutableListOf<ReconciliationCause>()

        // 检查大额转账
        val transfers = recentBills.filter {
            (it.type == Bill.TYPE_TRANSFER || it.type == Bill.TYPE_REPAYMENT)
                    && (it.accountId == asset.id || it.toAccountId == asset.id)
        }
        if (transfers.isNotEmpty()) {
            val totalTransfer = transfers.sumOf { bill ->
                if (bill.toAccountId == asset.id) bill.amount else -bill.amount
            }
            causes.add(
                ReconciliationCause(
                    type = ReconciliationCauseType.LARGE_TRANSFER,
                    description = "最近有 ${transfers.size} 笔转账/还款",
                    amount = totalTransfer
                )
            )
        }

        // 检查退款
        val refunds = recentBills.filter { it.subType == Bill.SUBTYPE_REFUND }
        if (refunds.isNotEmpty()) {
            causes.add(
                ReconciliationCause(
                    type = ReconciliationCauseType.RECENT_REFUND,
                    description = "最近有 ${refunds.size} 笔退款",
                    amount = refunds.sumOf { it.amount }
                )
            )
        }

        // 检查余额调整
        val adjustments = recentBills.filter {
            it.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT
        }
        if (adjustments.isNotEmpty()) {
            causes.add(
                ReconciliationCause(
                    type = ReconciliationCauseType.BALANCE_ADJUSTMENT,
                    description = "最近有余额调整记录",
                    amount = adjustments.sumOf { it.amount }
                )
            )
        }

        // 如果差额较大且没有找到原因
        if (causes.isEmpty() && kotlin.math.abs(diff) > 1.0) {
            causes.add(
                ReconciliationCause(
                    type = ReconciliationCauseType.UNRECORDED_BILL,
                    description = "可能存在未记录的账单",
                    amount = diff
                )
            )
        }

        return causes
    }

    private fun findUnmatchedBills(
        recentBills: List<Bill>,
        diff: Double
    ): List<BillPreview> {
        // 返回最近 10 笔可能相关的账单
        return recentBills
            .sortedByDescending { it.time }
            .take(10)
            .map { bill ->
                BillPreview(
                    billId = bill.id,
                    type = bill.type,
                    amount = bill.amount,
                    categoryName = bill.categoryName,
                    accountName = bill.accountName,
                    remark = bill.remark,
                    time = bill.time
                )
            }
    }

    private fun buildSuggestedActions(
        asset: Asset,
        diff: Double
    ): List<ReconciliationAction> {
        val actions = mutableListOf<ReconciliationAction>()

        if (kotlin.math.abs(diff) > 0.01) {
            actions.add(
                ReconciliationAction(
                    type = ReconciliationActionType.ADJUST_BALANCE,
                    label = "余额调整"
                )
            )
        }

        actions.add(
            ReconciliationAction(
                type = ReconciliationActionType.ADD_BILL,
                label = "补记账单"
            )
        )

        actions.add(
            ReconciliationAction(
                type = ReconciliationActionType.VIEW_BILLS,
                label = "查看最近流水"
            )
        )

        actions.add(
            ReconciliationAction(
                type = ReconciliationActionType.LATER,
                label = "稍后处理"
            )
        )

        return actions
    }
}
