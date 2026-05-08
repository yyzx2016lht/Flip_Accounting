package tao.test.tapaccounting

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object RemoteConfigManager {

    // TODO: 替换为你的 Gist raw URL
    private const val CONFIG_URL = "https://gist.githubusercontent.com/yyzx2016lht/410018a849271da1a0e39efaa0978c2a/raw/fba84488cf611ede2640edb73e41c17367230d71/flipaccounting-config.json"

    data class RemoteConfig(
        @SerializedName("apiKey") val apiKey: String = "",
        @SerializedName("apiUrl") val apiUrl: String = "https://api.siliconflow.cn",
        @SerializedName("provider") val provider: String = "硅基流动",
        @SerializedName("modelId") val modelId: String = "Qwen/Qwen3-14B",
        @SerializedName("singleModelId") val singleModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("multiModelId") val multiModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("modifyModelId") val modifyModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("categoryRefineModelId") val categoryRefineModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("routerModelId") val routerModelId: String = "Qwen/Qwen3-8B",
        @SerializedName("queryModelId") val queryModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("ruleModelId") val ruleModelId: String = "Qwen/Qwen3-8B",
        @SerializedName("receiptModelId") val receiptModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("receiptVisionModelId") val receiptVisionModelId: String = "Qwen/Qwen3-VL-30B-A3B-Instruct",
        @SerializedName("ocrRefineModelId") val ocrRefineModelId: String = "Qwen/Qwen3-8B",
        @SerializedName("speechModelId") val speechModelId: String = "FunAudioLLM/SenseVoiceSmall",
        @SerializedName("chatModelId") val chatModelId: String = "Qwen/Qwen3-14B",
        @SerializedName("llmRouterEnabled") val llmRouterEnabled: Boolean = false,
        @SerializedName("queryEnabled") val queryEnabled: Boolean = true,
        @SerializedName("thinkingEnabled") val thinkingEnabled: Boolean = true,
        @SerializedName("ocrRefineEnabled") val ocrRefineEnabled: Boolean = true
    )

    fun isConfigUrlConfigured(): Boolean = CONFIG_URL.isNotBlank()

    suspend fun syncIfConfigured(context: Context): Boolean {
        val config = fetchConfig() ?: return false
        applyConfig(context, config)
        return true
    }

    suspend fun fetchConfig(): RemoteConfig? {
        if (CONFIG_URL.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(CONFIG_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    Gson().fromJson(response, RemoteConfig::class.java)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun applyConfig(context: Context, config: RemoteConfig) {
        if (config.apiKey.isNotBlank()) {
            Prefs.setAiKey(context, config.apiKey)
        }
        if (config.apiUrl.isNotBlank()) {
            Prefs.setAiUrl(context, config.apiUrl)
        }
        if (config.provider.isNotBlank()) {
            Prefs.setAiProvider(context, config.provider)
        }
        if (config.modelId.isNotBlank()) {
            Prefs.setAiModel(context, config.modelId)
        }
        if (config.multiModelId.isNotBlank()) {
            Prefs.setAiMultiModel(context, config.multiModelId)
        }
        if (config.modifyModelId.isNotBlank()) {
            Prefs.setAiModifyModel(context, config.modifyModelId)
        }
        if (config.categoryRefineModelId.isNotBlank()) {
            Prefs.setAiCategoryRefineModel(context, config.categoryRefineModelId)
        }
        if (config.routerModelId.isNotBlank()) {
            Prefs.setAiRouterModel(context, config.routerModelId)
        }
        if (config.queryModelId.isNotBlank()) {
            Prefs.setAiQueryModel(context, config.queryModelId)
        }
        if (config.ruleModelId.isNotBlank()) {
            Prefs.setAiRuleModel(context, config.ruleModelId)
        }
        if (config.receiptModelId.isNotBlank()) {
            Prefs.setAiReceiptModel(context, config.receiptModelId)
        }
        if (config.receiptVisionModelId.isNotBlank()) {
            Prefs.setAiReceiptVisionModel(context, config.receiptVisionModelId)
        }
        if (config.ocrRefineModelId.isNotBlank()) {
            Prefs.setAiReceiptOcrRefineModel(context, config.ocrRefineModelId)
        }
        if (config.speechModelId.isNotBlank()) {
            Prefs.setAiSpeechModel(context, config.speechModelId)
        }
        if (config.chatModelId.isNotBlank()) {
            Prefs.setAiChatModel(context, config.chatModelId)
        }

        Prefs.setAiLlmRouterEnabled(context, config.llmRouterEnabled)
        Prefs.setAiQueryEnabled(context, config.queryEnabled)
        Prefs.setReceiptOcrRefineEnabled(context, config.ocrRefineEnabled)
    }
}
