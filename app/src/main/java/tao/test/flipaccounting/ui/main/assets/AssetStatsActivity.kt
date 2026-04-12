package tao.test.flipaccounting.ui.main.assets

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import java.util.*

class AssetStatsActivity : AppCompatActivity() {

    private lateinit var barChart: BarChart
    private lateinit var pieChart: PieChart
    private var assetId: Long = -1
    private val db by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_stats)

        assetId = intent.getLongExtra("ASSET_ID", -1)
        if (assetId == -1L) {
            finish()
            return
        }

        initViews()
        loadData()
    }

    private fun initViews() {
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        barChart = findViewById(R.id.bar_chart)
        pieChart = findViewById(R.id.pie_chart)
        
        setupCharts()
    }

    private fun setupCharts() {
        barChart.description.isEnabled = false
        barChart.xAxis.position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
        barChart.axisRight.isEnabled = false
        barChart.legend.isEnabled = false
        barChart.setNoDataText("暂无图表数据")
        barChart.setNoDataTextColor(Color.parseColor("#9AA0A6"))

        pieChart.description.isEnabled = false
        pieChart.isDrawHoleEnabled = true
        pieChart.setHoleColor(Color.TRANSPARENT)
        pieChart.setNoDataText("暂无图表数据")
        pieChart.setNoDataTextColor(Color.parseColor("#9AA0A6"))
    }

    private fun loadData() {
        lifecycleScope.launch {
            val bills = db.billDao().getBillsByAssetId(assetId).first()
            val asset = db.assetDao().getAssetById(assetId)
            
            findViewById<TextView>(R.id.tv_toolbar_title).text = "${asset?.name ?: ""} 统计"
            
            updateTrendChart(bills)
            updatePieChart(bills)
        }
    }

    private fun updateTrendChart(bills: List<Bill>) {
        // Group by month for simplicity in asset stats
        val calendar = Calendar.getInstance()
        val monthlyStats = mutableMapOf<Int, Double>()
        
        bills.filter { it.type == Bill.TYPE_EXPENSE }.forEach {
            calendar.timeInMillis = it.time
            val month = calendar.get(Calendar.MONTH)
            monthlyStats[month] = (monthlyStats[month] ?: 0.0) + it.amount
        }

        val entries = (0..11).map { month ->
            BarEntry(month.toFloat(), (monthlyStats[month] ?: 0.0).toFloat())
        }

        val dataSet = BarDataSet(entries, "月度支出")
        dataSet.color = Color.parseColor("#F44336")
        barChart.data = BarData(dataSet)
        barChart.invalidate()
    }

    private fun updatePieChart(bills: List<Bill>) {
        val categoryStats = mutableMapOf<String, Double>()
        var total = 0.0
        
        bills.filter { it.type == Bill.TYPE_EXPENSE }.forEach {
            val cat = it.categoryName.ifEmpty { "其它" }
            categoryStats[cat] = (categoryStats[cat] ?: 0.0) + it.amount
            total += it.amount
        }

        if (total == 0.0) {
            pieChart.clear()
            return
        }

        val entries = categoryStats.map { (name, amount) ->
            PieEntry(amount.toFloat(), name)
        }

        val dataSet = PieDataSet(entries, "")
        dataSet.colors = listOf(
            Color.parseColor("#F44336"), Color.parseColor("#E91E63"), Color.parseColor("#9C27B0"),
            Color.parseColor("#673AB7"), Color.parseColor("#3F51B5"), Color.parseColor("#2196F3")
        )
        pieChart.data = PieData(dataSet)
        pieChart.centerText = "总支出\n¥${String.format("%.2f", total)}"
        pieChart.invalidate()
    }
}
