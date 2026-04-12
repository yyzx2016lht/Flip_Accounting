package tao.test.flipaccounting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object BackupManager {
    const val REQUEST_CODE_EXPORT = 1001
    const val REQUEST_CODE_IMPORT = 1002

    fun startExport(activity: Activity) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "FlipAccounting_Backup.json")
        }
        activity.startActivityForResult(intent, REQUEST_CODE_EXPORT)
    }

    fun handleExportResult(context: Context, uri: Uri) {
        try {
            val root = JSONObject().apply {
                // 1. 资产
                put("assets_v1", Prefs.serializeAssetList(Prefs.getAssets(context)))
                
                // 2. 分类
                put("cat_expense_v1", Prefs.serializeCategoryList(Prefs.getCategories(context, Prefs.TYPE_EXPENSE)))
                put("cat_income_v1", Prefs.serializeCategoryList(Prefs.getCategories(context, Prefs.TYPE_INCOME)))
                
                // 3. 账单历史
                val billsArr = org.json.JSONArray()
                Prefs.getBills(context).forEach { bill ->
                    val obj = JSONObject()
                    obj.put("amount", bill.amount)
                    obj.put("type", bill.type)
                    obj.put("assetName", bill.assetName)
                    obj.put("categoryName", bill.categoryName)
                    obj.put("time", bill.time)
                    obj.put("remarks", bill.remarks)
                    obj.put("iconUrl", bill.iconUrl)
                    obj.put("recordTime", bill.recordTime)
                    billsArr.put(obj)
                }
                put("bills_v1", billsArr)

                // 4. 白名单
                put("app_white_list_v1", Prefs.serializeWhiteList(Prefs.getAppWhiteList(context)))
                
                // 5. 多币种
                put("active_currencies_v1", Prefs.getActiveCurrencies(context).joinToString(","))
                put("exchange_refresh_interval_v1", Prefs.getExchangeRefreshInterval(context))
                
                // 6. 灵敏度/翻转设置
                put("flip_enabled_v1", Prefs.isFlipEnabled(context))
                put("flip_always_v1", Prefs.isFlipAlways(context))
                put("flip_sensitivity_v1", Prefs.getFlipSensitivity(context))
                put("flip_debounce_v1", Prefs.getFlipDuration(context))
                put("use_custom_sensitivity_v1", Prefs.isUseCustomSensitivity(context))
                put("custom_g_threshold_v1", Prefs.getCustomGThreshold(context))
                put("custom_max_duration_v1", Prefs.getCustomMaxDuration(context))
                
                // 7. 其他偏好
                put("hide_recents_v1", Prefs.isHideRecents(context))
                put("back_tap_enabled_v1", Prefs.isBackTapEnabled(context))

                // 8. 显示控制 (新增)
                put("show_ai_text_v1", Prefs.isShowAiText(context))
                put("show_ai_voice_v1", Prefs.isShowAiVoice(context))
                put("show_multi_cur_v1", Prefs.isShowMultiCurrency(context))

                // 9. AI 配置 (建议加上，方便换设备)
                put("ai_api_key_v1", Prefs.getAiKey(context))
                put("ai_api_url_v1", Prefs.getAiUrl(context))
                put("ai_model_id_v1", Prefs.getAiModel(context))
                put("ai_system_prompt_v1", Prefs.getAiPrompt(context))
            }
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(root.toString(4).toByteArray())
            }
            Utils.toast(context, "备份已成功导出")
        } catch (e: Exception) {
            e.printStackTrace()
            Utils.toast(context, "导出失败: ${e.message}")
        }
    }

    fun startImport(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        activity.startActivityForResult(intent, REQUEST_CODE_IMPORT)
    }

    fun handleImportResult(context: Context, uri: Uri) {
        try {
            val sb = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).forEachLine { sb.append(it) }
            }
            val root = JSONObject(sb.toString())

            // 使用 Prefs 统一导入
            Prefs.importAll(context, root)

            Utils.toast(context, "备份已恢复，部分设置可能需要重启应用生效")
        } catch (e: Exception) {
            e.printStackTrace()
            Utils.toast(context, "导入失败: ${e.message}")
        }
    }
}