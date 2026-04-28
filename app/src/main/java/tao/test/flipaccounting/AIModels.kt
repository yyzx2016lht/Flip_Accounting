package tao.test.flipaccounting

import retrofit2.http.*
import okhttp3.ResponseBody
import retrofit2.http.Streaming

// ─────────────────────────────────────────────
// 数据模型
// ─────────────────────────────────────────────

data class ChatRequest(
    val model: String,
    val messages: List<MessageUnion>,
    val temperature: Double = 0.3,
    val enable_thinking: Boolean = false,
    val response_format: ResponseFormat? = ResponseFormat("json_object")
)

/** 纯文本消息（兼容旧逻辑）*/
data class Message(val role: String, val content: String)

/** 多模态消息（文本 + 图片）*/
data class MultimodalMessage(val role: String, val content: List<ContentPart>)
data class ContentPart(val type: String, val text: String? = null, val image_url: ImageUrl? = null)
data class ImageUrl(val url: String)

/** 统一联合类型，序列化时决定实际结构 */
sealed class MessageUnion {
    data class Text(val msg: Message) : MessageUnion()
    data class Multimodal(val msg: MultimodalMessage) : MessageUnion()
}

/** 自定义 Gson 序列化，确保 MessageUnion 正确序列化 */
class MessageUnionSerializer : com.google.gson.JsonSerializer<MessageUnion> {
    override fun serialize(
        src: MessageUnion,
        typeOfSrc: java.lang.reflect.Type,
        context: com.google.gson.JsonSerializationContext
    ): com.google.gson.JsonElement {
        return when (src) {
            is MessageUnion.Text -> context.serialize(src.msg)
            is MessageUnion.Multimodal -> context.serialize(src.msg)
        }
    }
}

data class ResponseFormat(val type: String)
data class ChatResponse(val choices: List<Choice>)
data class Choice(val message: Message)
data class AudioResponse(
    val text: String? = null,
    val result: String? = null,
    val transcript: String? = null
)

data class ModelsResponse(val data: List<ModelItem>)
data class ModelItem(val id: String)

// ─────────────────────────────────────────────
// API 接口
// ─────────────────────────────────────────────

interface SiliconFlowApi {
    @GET("v1/models")
    suspend fun getModels(@Header("Authorization") auth: String): ModelsResponse

    @POST("v1/chat/completions")
    suspend fun chat(@Header("Authorization") auth: String, @Body body: ChatRequest): ChatResponse

    @POST("v1/chat/completions")
    suspend fun chatRaw(
        @Header("Authorization") auth: String,
        @Body body: com.google.gson.JsonObject
    ): ChatResponse

    @Streaming
    @POST("v1/chat/completions")
    suspend fun chatStreamRaw(
        @Header("Authorization") auth: String,
        @Body body: com.google.gson.JsonObject
    ): ResponseBody

    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribe(
        @Header("Authorization") auth: String,
        @Part model: okhttp3.MultipartBody.Part,
        @Part file: okhttp3.MultipartBody.Part
    ): AudioResponse
}
