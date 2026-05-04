package tao.test.flipaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "deleted_bills")
data class DeletedBill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val originalBillId: Long,
    val type: Int,
    val subType: Int = 0,
    val amount: Double,
    val originalAmount: Double = amount,
    val currency: String = "CNY",
    val exchangeRate: Double = 1.0,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val categoryName: String = "",
    val accountName: String = "",
    val toAccountName: String = "",
    val time: Long,
    val remark: String = "",
    val fee: Double = 0.0,
    val bookName: String = "日常账本",
    val relatedBillId: Long? = null,
    val excludeFromStats: Boolean = false,

    val deletedAt: Long = System.currentTimeMillis()
)
