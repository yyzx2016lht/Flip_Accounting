package tao.test.tapaccounting.data.repository

import kotlinx.coroutines.flow.Flow
import tao.test.tapaccounting.data.local.dao.BillDao
import tao.test.tapaccounting.data.local.entity.Bill
import tao.test.tapaccounting.logic.CategoryNameNormalizer

class BillRepository(private val billDao: BillDao) {

    suspend fun addBill(bill: Bill): Long {
        return billDao.insertBill(
            bill.copy(categoryName = CategoryNameNormalizer.normalizeForStorage(bill.categoryName))
        )
    }

    suspend fun addBills(bills: List<Bill>) {
        billDao.insertBills(
            bills.map { it.copy(categoryName = CategoryNameNormalizer.normalizeForStorage(it.categoryName)) }
        )
    }

    fun getBillsBetweenTimes(startTime: Long, endTime: Long): Flow<List<Bill>> {
        return billDao.getBillsBetweenTimes(startTime, endTime)
    }

    fun getBillsByAssetId(assetId: Long): Flow<List<Bill>> {
        return billDao.getBillsByAssetId(assetId)
    }

    suspend fun getUnsyncedBills(): List<Bill> {
        return billDao.getUnsyncedBills()
    }

    suspend fun markAsSynced(billIds: List<Long>) {
        billDao.markAsSynced(billIds)
    }

    suspend fun getBillById(id: Long): Bill? {
        return billDao.getBillById(id)
    }

    suspend fun updateBill(bill: Bill) {
        billDao.updateBill(
            bill.copy(categoryName = CategoryNameNormalizer.normalizeForStorage(bill.categoryName))
        )
    }
}
