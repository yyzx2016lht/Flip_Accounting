package tao.test.tapaccounting.tap

import android.content.Context
import android.content.Intent
import android.os.Build
import tao.test.tapaccounting.OverlayService

interface TapAction {
    val id: String
    val displayName: String
    val description: String get() = ""
    fun execute(context: Context)
}

object TapActionRegistry {
    private val actions = mutableListOf<TapAction>()

    fun register(action: TapAction) { actions.add(action) }
    fun getAll(): List<TapAction> = actions
    fun findById(id: String): TapAction? = actions.find { it.id == id }
    fun getDisplayNames(): Array<String> = arrayOf("无") + actions.map { it.displayName }
    fun getIds(): Array<String> = arrayOf("") + actions.map { it.id }

    init {
        register(ShowOverlayAction())
        register(OpenAiChatAction())
    }
}

class ShowOverlayAction : TapAction {
    override val id = "show_overlay"
    override val displayName = "弹出悬浮窗"
    override val description = "快速呼出记账悬浮窗"
    override fun execute(context: Context) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

class OpenAiChatAction : TapAction {
    override val id = "open_ai_chat"
    override val displayName = "AI 智能记账助手"
    override val description = "打开 AI 对话记账界面"
    override fun execute(context: Context) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_AI_INPUT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
