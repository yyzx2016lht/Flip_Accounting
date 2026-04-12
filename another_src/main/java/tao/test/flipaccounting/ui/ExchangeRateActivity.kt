package tao.test.flipaccounting.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tao.test.flipaccounting.CurrencyData
import tao.test.flipaccounting.CurrencyInfo
import tao.test.flipaccounting.R
import tao.test.flipaccounting.logic.CurrencyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExchangeRateActivity : AppCompatActivity() {

    private lateinit var rvRates: RecyclerView
    private lateinit var spInterval: Spinner
    private lateinit var tvLastUpdate: TextView
    private lateinit var btnRefresh: ImageView
    
    private val intervals = listOf(
        "15 分钟" to 15,
        "30 分钟" to 30,
        "1 小时" to 60,
        "2 小时" to 120,
        "6 小时" to 360,
        "12 小时" to 720,
        "1 天" to 1440
    )

    private lateinit var adapter: RateAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exchange_rate)
        
        initViews()
        loadData()
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        rvRates = findViewById(R.id.rv_rates)
        spInterval = findViewById(R.id.spinner_interval)
        tvLastUpdate = findViewById(R.id.tv_last_update)
        btnRefresh = findViewById(R.id.btn_refresh)

        btnRefresh.setOnClickListener {
            btnRefresh.animate().rotationBy(360f).setDuration(500).start()
            CurrencyManager.updateRates(this) { success ->
                if (success) {
                    loadData()
                    Toast.makeText(this, "汇率已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "更新失败，请检查网络", Toast.LENGTH_SHORT).show()
                }
            }
        }

        setupIntervalSpinner()
    }

    private fun setupIntervalSpinner() {
        val adapterStr = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervals.map { it.first })
        adapterStr.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spInterval.adapter = adapterStr

        val currentInterval = CurrencyManager.getRefreshInterval(this)
        val index = intervals.indexOfFirst { it.second == currentInterval }
        if (index >= 0) {
            spInterval.setSelection(index)
        }

        spInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = intervals[position].second
                CurrencyManager.setRefreshInterval(this@ExchangeRateActivity, selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadData() {
        val lastUpdate = CurrencyManager.getLastUpdateTime(this)
        if (lastUpdate > 0) {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            tvLastUpdate.text = "上次自动更新: ${df.format(Date(lastUpdate))}"
        } else {
            tvLastUpdate.text = "上次自动更新: 尚未更新"
        }

        // Only show rates for selected currencies (excluding CNY which is base 1:1)
        val enabledCodes = CurrencyManager.getEnabledCurrencies(this).filter { it != "CNY" }
        val items = enabledCodes.mapNotNull { code ->
            val info = CurrencyData.getInfo(code)
            val rate = CurrencyManager.getRate(code)
            if (info != null && rate != null) {
                RateItem(info, rate)
            } else null
        }

        adapter = RateAdapter(items)
        rvRates.layoutManager = LinearLayoutManager(this)
        rvRates.adapter = adapter
    }

    data class RateItem(val info: CurrencyInfo, val rate: Double)

    inner class RateAdapter(private val items: List<RateItem>) : RecyclerView.Adapter<RateAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exchange_rate, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvFlag.text = item.info.flagEmoji
            holder.tvCode.text = "${item.info.code} ${item.info.nameZh}"
            
            // 1 CNY = X Currency
            holder.tvInfo.text = "1 CNY = ${String.format(Locale.US, "%.4f", item.rate)} ${item.info.code}"
            
            // 1 Currency = 1/X CNY
            val reverseRate = if (item.rate != 0.0) 1.0 / item.rate else 0.0
            holder.tvReverseInfo.text = "1 ${item.info.code} ≈ ${String.format(Locale.US, "%.2f", reverseRate)} CNY"
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFlag: TextView = view.findViewById(R.id.tv_flag)
            val tvCode: TextView = view.findViewById(R.id.tv_code)
            val tvInfo: TextView = view.findViewById(R.id.tv_info)
            val tvReverseInfo: TextView = view.findViewById(R.id.tv_reverse_info)
        }
    }
}
