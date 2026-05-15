package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 对话消息实体
 * msgType: 0=用户文本, 1=用户图片, 2=用户语音(内容可为语音元数据JSON), 3=AI文本, 4=AI账单JSON
 */
@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 消息类型: 0=用户文本, 1=用户图片, 2=用户语音, 3=AI文本, 4=AI账单JSON */
    val msgType: Int,

    /** 消息内容（文本/JSON字符串） */
    val content: String = "",

    /** 图片URI路径（仅 msgType=1 时有效） */
    val imageUri: String = "",

    /** 时间戳（毫秒） */
    val timestamp: Long = System.currentTimeMillis(),

    /** 关联的账单ID列表（JSON格式），AI账单消息保存入库后的ID */
    val billIds: String = "",

    /** 使用的AI模型名称 */
    val modelName: String = "",

    /** 所属账本（用于按账本隔离聊天记录） */
    val bookName: String = "",

    /** 会话ID（同一账本可有多组对话） */
    val conversationId: String = ""
)

