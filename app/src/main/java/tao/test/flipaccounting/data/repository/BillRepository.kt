package tao.test.flipaccounting.data.repository

import kotlinx.coroutines.flow.Flow
import tao.test.flipaccounting.data.local.dao.BillDao
import tao.test.flipaccounting.data.local.entity.Bill

class BillRepository(private val billDao: BillDao) {

    suspend fun addBill(bill: Bill): Long {
        return billDao.insertBill(bill)
    }

    suspend fun addBills(bills: List<Bill>) {
        billDao.insertBills(bills)
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
}
