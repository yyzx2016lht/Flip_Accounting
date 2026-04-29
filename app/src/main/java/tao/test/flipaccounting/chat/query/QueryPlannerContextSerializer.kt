package tao.test.flipaccounting.chat.query

import org.json.JSONArray
import org.json.JSONObject

object QueryPlannerContextSerializer {
    fun toCompactJson(context: QueryContext): String {
        val root = JSONObject()
        root.put("nowMillis", context.nowMillis)
        root.put("timezoneId", context.timezoneId)
        root.put("currentBookName", context.currentBookName)
        root.put("availableBooks", JSONArray(context.availableBooks))
        root.put(
            "assets",
            JSONArray().apply {
                context.assets.take(80).forEach { asset ->
                    put(
                        JSONObject().apply {
                            put("id", asset.id)
                            put("name", asset.name)
                            put("currency", asset.currency)
                        }
                    )
                }
            }
        )
        root.put(
            "categories",
            JSONArray().apply {
                context.categories.take(120).forEach { category ->
                    put(
                        JSONObject().apply {
                            put("id", category.id)
                            put("name", category.name)
                            put("type", category.type)
                        }
                    )
                }
            }
        )
        root.put("currencies", JSONArray(context.currencies))
        root.put(
            "capabilities",
            JSONObject().apply {
                put("canOpenStatsPage", context.capabilities.canOpenStatsPage)
                put("canOpenAssetStatsPage", context.capabilities.canOpenAssetStatsPage)
                put("supportsStatsExternalFilter", context.capabilities.supportsStatsExternalFilter)
                put("supportsAssetStatsTimeRange", context.capabilities.supportsAssetStatsTimeRange)
                put("supportsAssetStatsBillType", context.capabilities.supportsAssetStatsBillType)
            }
        )
        root.put("recentBillHints", JSONArray(context.recentBillHints.take(12)))
        return root.toString()
    }
}
