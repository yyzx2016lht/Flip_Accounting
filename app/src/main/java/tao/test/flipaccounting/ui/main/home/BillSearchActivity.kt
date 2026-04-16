package tao.test.flipaccounting.ui.main.home

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.logic.BillDisplayFormatter
import tao.test.flipaccounting.ui.activity.EditBillActivity
import java.util.Locale

class BillSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_BOOK = "extra_source_book"
    }

    private lateinit var etSearch: EditText
    private lateinit var rvResults: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvScope: TextView

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val adapter = HomeAdapter().apply { showChart = false }
    private var allBills: List<Bill> = emptyList()
    private var searchJob: Job? = null

    private val sourceBookName by lazy {
        BookAccountManager.normalizeBookName(
            intent.getStringExtra(EXTRA_SOURCE_BOOK).orEmpty().ifBlank { BookAccountManager.ALL_BOOK }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_search)

        etSearch = findViewById(R.id.et_bill_search)
        rvResults = findViewById(R.id.rv_bill_search_results)
        tvEmpty = findViewById(R.id.tv_bill_search_empty)
        tvScope = findViewById(R.id.tv_bill_search_scope)

        findViewById<View>(R.id.btn_bill_search_back).setOnClickListener { finish() }

        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter

        adapter.onBillItemClick = { bill ->
            startActivity(Intent(this, EditBillActivity::class.java).apply {
                putExtra("BILL_ID", bill.id)
            })
        }
        adapter.detailSuffixProvider = { bill ->
            if (sourceBookName == BookAccountManager.ALL_BOOK) {
                "账本: ${BookAccountManager.normalizeBookName(bill.bookName)}"
            } else {
                null
            }
        }

        tvScope.text = if (sourceBookName == BookAccountManager.ALL_BOOK) {
            "范围：全部账本"
        } else {
            "范围：$sourceBookName"
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty().trim()
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(120)
                    applyFilter(keyword)
                }
            }
        })

        loadAllBills()
    }

    private fun loadAllBills() {
        lifecycleScope.launch {
            val bills = withContext(Dispatchers.IO) {
                db.billDao().getAllBillsList()
            }
            allBills = bills
                .asSequence()
                .filter { BookAccountManager.isBillInBook(it.bookName, sourceBookName) }
                .sortedWith(compareByDescending<Bill> { it.time }.thenByDescending { it.id })
                .toList()
            applyFilter(etSearch.text?.toString().orEmpty().trim())
        }
    }

    private fun applyFilter(keyword: String) {
        val filtered = filterBillsByKeyword(allBills, keyword)
        adapter.submitList(filtered)
        val empty = filtered.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rvResults.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun filterBillsByKeyword(source: List<Bill>, keywordRaw: String): List<Bill> {
        val keyword = keywordRaw.trim().lowercase(Locale.getDefault())
        if (keyword.isBlank()) return source
        val normalizedKeyword = keyword.replace(',', '.')

        return source.filter { bill ->
            val textMatched = listOf(
                bill.remark,
                bill.categoryName,
                BillDisplayFormatter.stripRefundPrefix(bill.categoryName),
                bill.accountName,
                bill.toAccountName,
                BookAccountManager.normalizeBookName(bill.bookName)
            ).any { value ->
                value.isNotBlank() && value.lowercase(Locale.getDefault()).contains(keyword)
            }
            if (textMatched) return@filter true

            val numericCandidates = listOf(
                String.format(Locale.US, "%.2f", bill.amount),
                bill.amount.toString(),
                String.format(Locale.US, "%.2f", bill.originalAmount),
                bill.originalAmount.toString(),
                String.format(Locale.US, "%.2f", bill.amount * bill.exchangeRate)
            )
            numericCandidates.any { candidate ->
                val value = candidate.lowercase(Locale.getDefault())
                value.contains(keyword) || value.contains(normalizedKeyword)
            }
        }
    }
}
