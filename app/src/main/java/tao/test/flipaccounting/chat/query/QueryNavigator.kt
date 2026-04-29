package tao.test.flipaccounting.chat.query

import android.content.Intent
import tao.test.flipaccounting.MainActivity
import tao.test.flipaccounting.ui.main.assets.AssetStatsActivity
import tao.test.flipaccounting.ui.main.stats.StatsExternalQueryBridge
import tao.test.flipaccounting.ui.main.stats.StatsExternalQueryFilter

class QueryNavigator(
    private val host: android.app.Activity
) {
    fun openStatsPage(slots: QuerySlots): String {
        StatsExternalQueryBridge.publish(
            StatsExternalQueryFilter(
                startMillis = slots.timeRange?.startMillis,
                endMillis = slots.timeRange?.endMillis,
                label = slots.timeRange?.label,
                bookName = slots.bookName,
                currency = slots.currency
            )
        )
        host.startActivity(
            Intent(host, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TAB_INDEX, 1)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        return if (!slots.accountName.isNullOrBlank()) {
            "已打开统计页，并应用筛选。"
        } else {
            "已打开统计页，并按你的查询条件筛选。"
        }
    }

    fun openAssetStatsPage(slots: QuerySlots): String {
        val assetId = slots.assetId ?: return "暂时无法打开资产统计：还没确定具体资产。"
        val intent = Intent(host, AssetStatsActivity::class.java).apply {
            putExtra(AssetStatsActivity.EXTRA_ASSET_ID, assetId)
            slots.timeRange?.startMillis?.let { putExtra(AssetStatsActivity.EXTRA_FILTER_START_TIME, it) }
            slots.timeRange?.endMillis?.let { putExtra(AssetStatsActivity.EXTRA_FILTER_END_TIME, it) }
            slots.timeRange?.label?.let { putExtra(AssetStatsActivity.EXTRA_FILTER_LABEL, it) }
            putExtra(AssetStatsActivity.EXTRA_BILL_TYPE, slots.billType.name)
        }
        host.startActivity(intent)
        val assetName = slots.accountName?.ifBlank { null } ?: "该资产"
        val range = slots.timeRange?.label?.ifBlank { null }
        return if (range != null) {
            "已打开 $assetName 的资产统计，并筛选为$range。"
        } else {
            "已打开 $assetName 的资产统计。"
        }
    }
}
