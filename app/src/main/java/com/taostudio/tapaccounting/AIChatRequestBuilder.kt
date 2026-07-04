package com.taostudio.tapaccounting

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

internal fun buildMultiTurnChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    historyTurns: List<ChatTurn> = emptyList(),
    userText: String,
    jsonObjectResponse: Boolean = false,
    stream: Boolean = false,
    enableThinking: Boolean = false
): JsonObject {
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        historyTurns.forEach { turn ->
            add(buildTextMessage(turn.role, turn.content))
        }
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

internal fun buildMultiTurnAudioChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    historyTurns: List<ChatTurn> = emptyList(),
    audioBase64: String,
    audioFormat: String,
    userText: String = "",
    stream: Boolean = false,
    enableThinking: Boolean = false
): JsonObject {
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        historyTurns.forEach { turn ->
            add(buildTextMessage(turn.role, turn.content))
        }
        add(
            buildContentMessage(
                role = "user",
                parts = buildList {
                    add(buildAudioPart(audioBase64, audioFormat))
                    if (userText.isNotBlank()) add(buildTextPart(userText))
                }
            )
        )
    }
    return buildChatRequest(
        model = model,
        temperature = temperature,
        messages = messages,
        stream = stream,
        enableThinking = enableThinking
    )
}

internal fun buildMultiTurnVideoChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    historyTurns: List<ChatTurn> = emptyList(),
    videoDataUrl: String,
    userText: String,
    stream: Boolean = false,
    enableThinking: Boolean = false
): JsonObject {
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        historyTurns.forEach { turn ->
            add(buildTextMessage(turn.role, turn.content))
        }
        add(
            buildContentMessage(
                role = "user",
                parts = listOf(
                    buildVideoPart(videoDataUrl),
                    buildTextPart(userText)
                )
            )
        )
    }
    return buildChatRequest(
        model = model,
        temperature = temperature,
        messages = messages,
        stream = stream,
        enableThinking = enableThinking
    )
}

internal fun buildMultiTurnVisionChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    historyTurns: List<ChatTurn> = emptyList(),
    dataUrl: String,
    userText: String,
    stream: Boolean = false,
    enableThinking: Boolean = false
): JsonObject {
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        historyTurns.forEach { turn ->
            add(buildTextMessage(turn.role, turn.content))
        }
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
        stream = stream,
        enableThinking = enableThinking
    )
}

internal fun buildVisionChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    dataUrl: String,
    userText: String,
    jsonObjectResponse: Boolean = false,
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
        jsonObjectResponse = jsonObjectResponse,
        enableThinking = enableThinking
    )
}

internal fun buildMultiImageVisionChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    dataUrls: List<String>,
    userText: String,
    jsonObjectResponse: Boolean = false,
    enableThinking: Boolean = false
): JsonObject {
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        add(
            buildContentMessage(
                role = "user",
                parts = dataUrls.map { buildImagePart(it) } + listOf(buildTextPart(userText))
            )
        )
    }
    return buildChatRequest(
        model = model,
        temperature = temperature,
        messages = messages,
        jsonObjectResponse = jsonObjectResponse,
        enableThinking = enableThinking
    )
}

internal fun buildMultiTurnMultiImageVisionChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    historyTurns: List<ChatTurn> = emptyList(),
    dataUrls: List<String>,
    userText: String,
    stream: Boolean = false,
    enableThinking: Boolean = false
): JsonObject {
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        historyTurns.forEach { turn ->
            add(buildTextMessage(turn.role, turn.content))
        }
        add(
            buildContentMessage(
                role = "user",
                parts = dataUrls.map { buildImagePart(it) } + listOf(buildTextPart(userText))
            )
        )
    }
    return buildChatRequest(
        model = model,
        temperature = temperature,
        messages = messages,
        stream = stream,
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
        // Provider-specific request adaptation removes or converts this field before sending.
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

private fun buildVideoPart(dataUrl: String): JsonObject =
    JsonObject().apply {
        addProperty("type", "video_url")
        add("video_url", JsonObject().apply {
            addProperty("url", dataUrl)
        })
    }

private fun buildFilePart(fileName: String, mime: String, base64: String): JsonObject =
    JsonObject().apply {
        addProperty("type", "file")
        add("file", JsonObject().apply {
            addProperty("filename", fileName.ifBlank { "file" })
            addProperty("file_data", "data:$mime;base64,$base64")
        })
    }

internal data class MultimodalAttachmentPart(
    val base64: String,
    val mime: String,
    val fileName: String = ""
)

internal fun buildContentPartsForAttachments(
    attachments: List<MultimodalAttachmentPart>
): List<JsonObject> = attachments.map { buildContentPartForAttachment(it) }

internal fun buildContentPartForAttachment(attachment: MultimodalAttachmentPart): JsonObject {
    val mime = attachment.mime
    val base64 = attachment.base64
    val dataUrl = "data:$mime;base64,$base64"
    return when {
        ChatAttachmentHelper.isImageMime(mime) -> buildImagePart(dataUrl)
        ChatAttachmentHelper.isVideoMime(mime) -> buildVideoPart(dataUrl)
        ChatAttachmentHelper.isAudioMime(mime) -> buildAudioPart(
            audioBase64 = dataUrl,
            audioFormat = ChatAttachmentHelper.audioFormatForMime(mime)
        )
        mime.equals("application/pdf", ignoreCase = true) ||
            ChatAttachmentHelper.isDocxMime(mime, attachment.fileName) -> buildFilePart(
            fileName = attachment.fileName.ifBlank { defaultFileNameForMime(mime) },
            mime = mime,
            base64 = base64
        )
        else -> buildFilePart(
            fileName = attachment.fileName.ifBlank { defaultFileNameForMime(mime) },
            mime = mime,
            base64 = base64
        )
    }
}

private fun defaultFileNameForMime(mime: String): String = when {
    mime.equals("application/pdf", ignoreCase = true) -> "document.pdf"
    mime.contains("wordprocessingml", ignoreCase = true) -> "document.docx"
    mime.startsWith("text/", ignoreCase = true) -> "document.txt"
    else -> "file"
}

internal fun buildMultimodalChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    attachments: List<MultimodalAttachmentPart>,
    userText: String,
    jsonObjectResponse: Boolean = false,
    stream: Boolean = false,
    enableThinking: Boolean = false
): JsonObject {
    val attachmentParts = buildContentPartsForAttachments(attachments)
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        add(
            buildContentMessage(
                role = "user",
                parts = attachmentParts + listOf(buildTextPart(userText))
            )
        )
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

internal fun buildMultiTurnMultimodalChatRequest(
    model: String,
    temperature: Double,
    systemPrompt: String? = null,
    historyTurns: List<ChatTurn> = emptyList(),
    attachments: List<MultimodalAttachmentPart>,
    userText: String,
    jsonObjectResponse: Boolean = false,
    stream: Boolean = false,
    enableThinking: Boolean = false
): JsonObject {
    val attachmentParts = buildContentPartsForAttachments(attachments)
    val messages = JsonArray().apply {
        systemPrompt?.let { add(buildTextMessage("system", it)) }
        historyTurns.forEach { turn ->
            add(buildTextMessage(turn.role, turn.content))
        }
        add(
            buildContentMessage(
                role = "user",
                parts = attachmentParts + listOf(buildTextPart(userText))
            )
        )
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
