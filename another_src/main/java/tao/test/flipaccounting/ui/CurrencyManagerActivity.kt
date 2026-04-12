package tao.test.flipaccounting.ui

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tao.test.flipaccounting.CurrencyData
import tao.test.flipaccounting.CurrencyInfo
import tao.test.flipaccounting.R
import tao.test.flipaccounting.logic.CurrencyManager

class CurrencyManagerActivity : AppCompatActivity() {

    private lateinit var rvList: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var adapter: CurrencyAdapter
    
    // Memory cache of enabled currencies code
    private val enabledSet = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency_manager)
        
        // Load initial state
        enabledSet.addAll(CurrencyManager.getEnabledCurrencies(this))
        // Ensure CNY is always there
        enabledSet.add("CNY")

        initViews()
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btn_settings).setOnClickListener {
            val intent = android.content.Intent(this, ExchangeRateActivity::class.java)
            startActivity(intent)
        }
        etSearch = findViewById(R.id.et_search)
        rvList = findViewById(R.id.rv_currency_list)
        
        adapter = CurrencyAdapter(CurrencyData.ALL_CURRENCIES)
        rvList.layoutManager = LinearLayoutManager(this)
        rvList.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onPause() {
        super.onPause()
        // Save whenever leaving this screen
        CurrencyManager.setEnabledCurrencies(this, enabledSet.toList())
    }

    inner class CurrencyAdapter(private val originalList: List<CurrencyInfo>) : RecyclerView.Adapter<CurrencyAdapter.ViewHolder>() {
        
        // We want to show:
        // 1. Enabled items at the top (sorted roughly)
        // 2. Disabled items below
        // But the user requested search. Search usually filters the whole list.
        // Let's implement this: 
        // If search is empty: Show "My Currencies" section (Implicitly at top) then others
        // Actually, a single list with Checked state is simplest. 
        // Let's just sort: Enabled first, then others. Both alphabetical.
        
        private var displayedList: List<CurrencyInfo> = sortList(originalList)

        private fun sortList(list: List<CurrencyInfo>): List<CurrencyInfo> {
            return list.sortedWith(compareByDescending<CurrencyInfo> { enabledSet.contains(it.code) }
                .thenBy { it.code })
        }

        fun filter(query: String) {
            val q = query.trim()
            displayedList = if (q.isEmpty()) {
                sortList(originalList)
            } else {
                // When searching, we just show matches, but keeping checked ones visually checked
                originalList.filter { it.matches(q) }
                    .sortedWith(compareByDescending<CurrencyInfo> { enabledSet.contains(it.code) }.thenBy { it.code })
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_currency_select, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = displayedList.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = displayedList[position]
            holder.bind(item)
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvFlag: TextView = itemView.findViewById(R.id.tv_currency_flag)
            val tvCode: TextView = itemView.findViewById(R.id.tv_currency_code)
            val tvSymbol: TextView = itemView.findViewById(R.id.tv_currency_symbol)
            val tvName: TextView = itemView.findViewById(R.id.tv_currency_name)
            val cbSelect: CheckBox = itemView.findViewById(R.id.cb_select)

            fun bind(item: CurrencyInfo) {
                tvFlag.text = item.flagEmoji
                tvCode.text = item.code
                tvSymbol.text = item.symbol
                tvName.text = "${item.nameZh} (${item.countryZh})" // e.g. 美元 (美国)

                val isChecked = enabledSet.contains(item.code)
                cbSelect.isChecked = isChecked
                
                // CNY is mandatory
                if (item.code == "CNY") {
                    cbSelect.isEnabled = false
                    itemView.alpha = 0.5f // Dim slightly to show immutable
                    itemView.setOnClickListener(null)
                } else {
                    cbSelect.isEnabled = true
                    itemView.alpha = 1.0f
                    itemView.setOnClickListener {
                        toggleSelection(item)
                    }
                    // Allow clicking checkbox directly too, but better just item click
                    cbSelect.setOnClickListener { 
                        toggleSelection(item) 
                    }
                }
            }

            private fun toggleSelection(item: CurrencyInfo) {
                if (enabledSet.contains(item.code)) {
                    enabledSet.remove(item.code)
                } else {
                    enabledSet.add(item.code)
                }
                notifyItemChanged(adapterPosition)
                // Re-sort if search is empty to move checked to top dynamically? 
                // Maybe too jumpy. Let's keep position for now, or re-sort on search clear.
            }
        }
    }
}
