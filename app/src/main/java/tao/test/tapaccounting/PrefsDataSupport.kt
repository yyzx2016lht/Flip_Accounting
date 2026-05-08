package tao.test.tapaccounting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PrefsDataSupport {
    private const val PREFS_NAME = "flip_prefs"
    private const val KEY_ASSETS = "assets_v1"
    private const val KEY_CAT_EXPENSE = "cat_expense_v1"
    private const val KEY_CAT_INCOME = "cat_income_v1"
    private const val KEY_BILLS = "bills_list"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addBill(ctx: Context, bill: Bill) {
        val list = getBills(ctx).toMutableList()
        val existingIndex = if (bill.recordTime.isNotEmpty()) {
            list.indexOfFirst { it.recordTime == bill.recordTime }
        } else -1

        if (existingIndex >= 0) {
            list[existingIndex] = bill
        } else {
            list.add(bill)
        }
        prefs(ctx).edit().putString(KEY_BILLS, serializeBills(list).toString()).apply()
    }

    fun deleteBills(ctx: Context, billsToDelete: Set<Bill>) {
        val list = getBills(ctx).toMutableList()
        val toDeleteRecordTimes = billsToDelete
            .mapNotNull { if (it.recordTime.isNotEmpty()) it.recordTime else null }
            .toSet()

        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.recordTime.isNotEmpty() && toDeleteRecordTimes.contains(item.recordTime)) {
                iterator.remove()
                continue
            }
            val matchedInToDelete = billsToDelete.any { bill ->
                bill.recordTime.isEmpty() &&
                    item.time == bill.time &&
                    item.amount == bill.amount &&
                    item.categoryName == bill.categoryName &&
                    item.assetName == bill.assetName
            }
            if (matchedInToDelete) {
                iterator.remove()
            }
        }

        prefs(ctx).edit().putString(KEY_BILLS, serializeBills(list).toString()).apply()
    }

    fun getBills(ctx: Context): List<Bill> {
        val str = prefs(ctx).getString(KEY_BILLS, null) ?: return emptyList()
        val list = mutableListOf<Bill>()
        runCatching {
            val json = JSONArray(str)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                list.add(
                    Bill(
                        obj.getDouble("amount"),
                        obj.getInt("type"),
                        obj.getString("assetName"),
                        obj.getString("categoryName"),
                        obj.getString("time"),
                        obj.optString("remarks", ""),
                        obj.optString("iconUrl", ""),
                        obj.optString("recordTime", "")
                    )
                )
            }
        }.onFailure { it.printStackTrace() }
        return list
    }

    fun getAssets(ctx: Context): List<Asset> {
        val json = prefs(ctx).getString(KEY_ASSETS, "")
        if (json.isNullOrEmpty()) return emptyList()
        val list = mutableListOf<Asset>()
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Asset(
                        obj.getString("name"),
                        obj.getString("type"),
                        obj.getString("currency"),
                        obj.optString("icon", "")
                    )
                )
            }
        }.onFailure { it.printStackTrace() }
        return list
    }

    fun saveAssets(ctx: Context, assets: List<Asset>) {
        prefs(ctx).edit().putString(KEY_ASSETS, serializeAssetList(assets).toString()).apply()
    }

    fun getCategories(ctx: Context, type: Int): MutableList<CategoryNode> {
        val key = if (type == Prefs.TYPE_INCOME) KEY_CAT_INCOME else KEY_CAT_EXPENSE
        val json = prefs(ctx).getString(key, "")
        if (json.isNullOrEmpty()) return loadDefaultFromRaw(ctx, type)
        val list = mutableListOf<CategoryNode>()
        return runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                list.add(parseNode(array.getJSONObject(i)))
            }
            list
        }.getOrElse { loadDefaultFromRaw(ctx, type) }
    }

    fun loadDefaultFromRaw(ctx: Context, type: Int): MutableList<CategoryNode> {
        return runCatching {
            val stream = ctx.resources.openRawResource(R.raw.default_category)
            val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(content)
            val list = mutableListOf<CategoryNode>()

            val key = if (type == Prefs.TYPE_INCOME) "收入" else "支出"
            var array = root.optJSONArray(key)
            if (array == null) {
                array = root.optJSONArray(if (type == Prefs.TYPE_INCOME) "支出" else "收入")
            }

            if (array != null) {
                for (i in 0 until array.length()) {
                    list.add(parseNode(array.getJSONObject(i)))
                }
            }
            list
        }.getOrElse {
            it.printStackTrace()
            mutableListOf()
        }
    }

    fun loadAssetsFromRaw(ctx: Context): List<BuiltInCategory> {
        val list = mutableListOf<BuiltInCategory>()
        runCatching {
            val stream = ctx.resources.openRawResource(R.raw.assets)
            val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(content)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BuiltInCategory(
                        name = obj.getString("name"),
                        icon = obj.getString("icon"),
                        type = obj.optString("type")
                    )
                )
            }
        }.onFailure { it.printStackTrace() }
        return list
    }

    fun saveCategories(ctx: Context, type: Int, list: List<CategoryNode>) {
        val key = if (type == Prefs.TYPE_INCOME) KEY_CAT_INCOME else KEY_CAT_EXPENSE
        prefs(ctx).edit().putString(key, serializeCategoryList(list).toString()).apply()
    }

    fun deleteCategory(ctx: Context, type: Int, name: String) {
        val list = getCategories(ctx, type)
        if (recursiveDelete(list, name)) {
            saveCategories(ctx, type, list)
        }
    }

    fun serializeAssetList(assets: List<Asset>): JSONArray {
        val array = JSONArray()
        assets.forEach {
            val obj = JSONObject()
            obj.put("name", it.name)
            obj.put("type", it.type)
            obj.put("currency", it.currency)
            obj.put("icon", it.icon)
            array.put(obj)
        }
        return array
    }

    fun serializeCategoryList(list: List<CategoryNode>): JSONArray {
        val array = JSONArray()
        list.forEach { array.put(serializeNode(it)) }
        return array
    }

    private fun parseNode(obj: JSONObject): CategoryNode {
        val node = CategoryNode(obj.getString("name"), obj.getString("icon"))
        val subArray = obj.optJSONArray("subs") ?: obj.optJSONArray("sub")
        if (subArray != null) {
            for (i in 0 until subArray.length()) {
                node.subs.add(parseNode(subArray.getJSONObject(i)))
            }
        }
        return node
    }

    private fun serializeNode(node: CategoryNode): JSONObject {
        val obj = JSONObject()
        obj.put("name", node.name)
        obj.put("icon", node.icon)
        val subArray = JSONArray()
        node.subs.forEach { subArray.put(serializeNode(it)) }
        obj.put("subs", subArray)
        return obj
    }

    private fun recursiveDelete(list: MutableList<CategoryNode>, targetName: String): Boolean {
        val removed = list.removeIf { it.name == targetName }
        if (removed) return true
        for (node in list) {
            if (recursiveDelete(node.subs, targetName)) return true
        }
        return false
    }

    private fun serializeBills(list: List<Bill>): JSONArray {
        val json = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("amount", it.amount)
            obj.put("type", it.type)
            obj.put("assetName", it.assetName)
            obj.put("categoryName", it.categoryName)
            obj.put("time", it.time)
            if (it.remarks.isNotEmpty()) obj.put("remarks", it.remarks)
            if (it.iconUrl.isNotEmpty()) obj.put("iconUrl", it.iconUrl)
            if (it.recordTime.isNotEmpty()) obj.put("recordTime", it.recordTime)
            json.put(obj)
        }
        return json
    }
}
