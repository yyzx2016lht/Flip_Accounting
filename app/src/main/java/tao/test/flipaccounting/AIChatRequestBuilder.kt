package tao.test.flipaccounting

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal fun buildTextChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    userText: String,
    jsonObjectResponse: Boolean = false,
    stream: Boolean = false,
    enableThinking: Boolean = false
): JsonObject {
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        add(buildTextMessage("user", userText))
    }
    return buildChatRequest(
        model = model,
        temperature = temperature,
        messages = messages,
        jsonObjectResponse = jsonObjectResponse,
        stream = stream,
        enableThinking = enableThinking
    )
}

internal fun buildAudioChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    leadText: String,
    audioBase64: String,
    audioFormat: String,
    enableThinking: Boolean = false
): JsonObject {
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        add(
            buildContentMessage(
                role = "user",
                parts = listOf(
                    buildTextPart(leadText),
                    buildAudioPart(audioBase64, audioFormat)
                )
            )
        )
    }
    return buildChatRequest(
        model = model,
        temperature = temperature,
        messages = messages,
        enableThinking = enableThinking
    )
}

internal fun buildVisionChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    dataUrl: String,
    userText: String,
    enableThinking: Boolean = false
): JsonObject {
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        add(
            buildContentMessage(
                role = "user",
                parts = listOf(
                    buildImagePart(dataUrl),
                    buildTextPart(userText)
                )
            )
        )
    }
    return buildChatRequest(
        model = model,
        temperature = temperature,
        messages = messages,
        enableThinking = enableThinking
    )
}

private fun buildChatRequest(
    model: String,
    temperature: Double,
    messages: JsonArray,
    jsonObjectResponse: Boolean = false,
    stream: Boolean = false,
    enableThinking: Boolean = false
): JsonObject {
    return JsonObject().apply {
        addProperty("model", model)
        addProperty("temperature", temperature)
        // Some providers treat a missing thinking flag as provider-default behavior.
        // Always send the boolean explicitly so "off" really means off.
        addProperty("enable_thinking", enableThinking)
        if (stream) addProperty("stream", true)
        add("messages", messages)
        if (jsonObjectResponse) {
            add("response_format", JsonObject().apply {
                addProperty("type", "json_object")
            })
        }
    }
}

private fun buildTextMessage(role: String, content: String): JsonObject =
    JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

private fun buildContentMessage(role: String, parts: List<JsonObject>): JsonObject =
    JsonObject().apply {
        addProperty("role", role)
        add("content", JsonArray().apply {
            parts.forEach(::add)
        })
    }

private fun buildTextPart(text: String): JsonObject =
    JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
    }

private fun buildImagePart(dataUrl: String): JsonObject =
    JsonObject().apply {
        addProperty("type", "image_url")
        add("image_url", JsonObject().apply {
            addProperty("url", dataUrl)
        })
    }

private fun buildAudioPart(audioBase64: String, audioFormat: String): JsonObject =
    JsonObject().apply {
        addProperty("type", "input_audio")
        add("input_audio", JsonObject().apply {
            addProperty("data", audioBase64)
            addProperty("format", audioFormat)
        })
    }
