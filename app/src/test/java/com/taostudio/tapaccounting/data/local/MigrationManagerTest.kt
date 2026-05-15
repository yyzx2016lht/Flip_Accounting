package com.taostudio.tapaccounting.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import com.taostudio.tapaccounting.data.local.entity.Bill

class MigrationManagerTest {

    @Test
    fun normalizeLegacyBillTypeAndSubtype_mapsRepaymentToTransferSubtype() {
        val normalized = MigrationManager.normalizeLegacyBillTypeAndSubtype(Bill.TYPE_REPAYMENT)

        assertEquals(Bill.TYPE_TRANSFER, normalized.first)
        assertEquals(Bill.SUBTYPE_REPAYMENT, normalized.second)
    }

    @Test
    fun normalizeLegacyBillTypeAndSubtype_keepsNormalExpense() {
        val normalized = MigrationManager.normalizeLegacyBillTypeAndSubtype(Bill.TYPE_EXPENSE)

        assertEquals(Bill.TYPE_EXPENSE, normalized.first)
        assertEquals(Bill.SUBTYPE_NORMAL, normalized.second)
    }
}

