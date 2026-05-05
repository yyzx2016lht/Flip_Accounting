package tao.test.flipaccounting

import android.content.Context
import org.json.JSONObject

object PrefsChatSupport {
    private const val PREFS_NAME = "flip_prefs"
    private const val KEY_SHOW_AI_CHAT_ENTRY = "show_ai_chat_entry"
    private const val KEY_AI_ENTRY_MODE = "ai_entry_mode"
    private const val KEY_AI_CHAT_NAME = "ai_chat_name"
    private const val KEY_AI_CHAT_IDENTITY = "ai_chat_identity"
    private const val KEY_USER_CHAT_NAME = "user_chat_name"
    private const val KEY_USER_PROFILE_DESC = "user_profile_desc"
    private const val KEY_AI_CHAT_AVATAR_PATH = "ai_chat_avatar_path"
    private const val KEY_USER_CHAT_AVATAR_PATH = "user_chat_avatar_path"
    private const val KEY_AI_CHAT_BG_PATH = "ai_chat_bg_path"
    private const val KEY_AI_CHAT_MODEL = "ai_chat_model"
    private const val KEY_AI_CHAT_REPLY_STYLE = "ai_chat_reply_style"
    private const val KEY_AI_CHAT_REPLY_STYLE_CUSTOM = "ai_chat_reply_style_custom"
    private const val KEY_AI_CHAT_MODEL_AUDIO_SUPPORT = "ai_chat_model_audio_support"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isShowAiChatEntry(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHOW_AI_CHAT_ENTRY, false)
    fun setShowAiChatEntry(ctx: Context, show: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHOW_AI_CHAT_ENTRY, show).apply()

    fun getAiEntryMode(ctx: Context): Int =
        prefs(ctx).getInt(KEY_AI_ENTRY_MODE, Prefs.AI_ENTRY_MODE_TRADITIONAL)
    fun setAiEntryMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_AI_ENTRY_MODE, mode).apply()

    fun getAiChatName(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_CHAT_NAME, "小记") ?: "小记"
    fun setAiChatName(ctx: Context, name: String) =
        prefs(ctx).edit().putString(KEY_AI_CHAT_NAME, name.trim().ifBlank { "小记" }).apply()

    fun getAiChatIdentity(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_CHAT_IDENTITY, "") ?: ""
    fun setAiChatIdentity(ctx: Context, identity: String) =
        prefs(ctx).edit().putString(KEY_AI_CHAT_IDENTITY, identity.trim()).apply()

    fun getUserChatName(ctx: Context): String =
        prefs(ctx).getString(KEY_USER_CHAT_NAME, "我") ?: "我"
    fun setUserChatName(ctx: Context, name: String) =
        prefs(ctx).edit().putString(KEY_USER_CHAT_NAME, name.trim().ifBlank { "我" }).apply()

    fun getUserProfileDesc(ctx: Context): String =
        prefs(ctx).getString(KEY_USER_PROFILE_DESC, "点击设置名字和头像") ?: "点击设置名字和头像"
    fun setUserProfileDesc(ctx: Context, text: String) =
        prefs(ctx).edit().putString(KEY_USER_PROFILE_DESC, text.trim().ifBlank { "点击设置名字和头像" }).apply()

    fun getAiChatAvatarPath(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_CHAT_AVATAR_PATH, "") ?: ""
    fun setAiChatAvatarPath(ctx: Context, path: String) =
        prefs(ctx).edit().putString(KEY_AI_CHAT_AVATAR_PATH, path).apply()

    fun getUserChatAvatarPath(ctx: Context): String =
        prefs(ctx).getString(KEY_USER_CHAT_AVATAR_PATH, "") ?: ""
    fun setUserChatAvatarPath(ctx: Context, path: String) =
        prefs(ctx).edit().putString(KEY_USER_CHAT_AVATAR_PATH, path).apply()

    fun getAiChatBgPath(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_CHAT_BG_PATH, "") ?: ""
    fun setAiChatBgPath(ctx: Context, path: String) =
        prefs(ctx).edit().putString(KEY_AI_CHAT_BG_PATH, path).apply()

    fun getAiChatModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_CHAT_MODEL, "") ?: "").ifBlank { Prefs.getAiMultiModel(ctx) }
    fun setAiChatModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_CHAT_MODEL, value).apply()

    fun getAiChatReplyStyle(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_CHAT_REPLY_STYLE, "cute") ?: "cute").ifBlank { "cute" }
    fun setAiChatReplyStyle(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_CHAT_REPLY_STYLE, value).apply()

    fun getAiChatReplyStyleCustomPrompt(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_CHAT_REPLY_STYLE_CUSTOM, "") ?: ""
    fun setAiChatReplyStyleCustomPrompt(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_CHAT_REPLY_STYLE_CUSTOM, value.trim()).apply()

    fun getAiChatModelAudioSupport(ctx: Context, model: String): Boolean? {
        if (model.isBlank()) return null
        val raw = prefs(ctx).getString(KEY_AI_CHAT_MODEL_AUDIO_SUPPORT, "") ?: ""
        if (raw.isBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            if (!obj.has(model)) null else obj.optBoolean(model)
        }.getOrNull()
    }

    fun setAiChatModelAudioSupport(ctx: Context, model: String, supported: Boolean) {
        if (model.isBlank()) return
        val prefs = prefs(ctx)
        val obj = runCatching {
            JSONObject(prefs.getString(KEY_AI_CHAT_MODEL_AUDIO_SUPPORT, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        obj.put(model, supported)
        prefs.edit().putString(KEY_AI_CHAT_MODEL_AUDIO_SUPPORT, obj.toString()).apply()
    }

    private fun buildChatSessionTitleKey(bookName: String, conversationId: String): String =
        "ai_chat_session_title_${bookName}_${conversationId}"

    fun getAiChatSessionTitle(ctx: Context, bookName: String, conversationId: String): String =
        prefs(ctx).getString(buildChatSessionTitleKey(bookName, conversationId), "") ?: ""

    fun setAiChatSessionTitle(ctx: Context, bookName: String, conversationId: String, title: String) {
        val key = buildChatSessionTitleKey(bookName, conversationId)
        val editor = prefs(ctx).edit()
        if (title.isBlank()) editor.remove(key) else editor.putString(key, title.trim())
        editor.apply()
    }
}
