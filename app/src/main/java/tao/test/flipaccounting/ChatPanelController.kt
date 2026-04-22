package tao.test.flipaccounting

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ChatPanelController(
    private val context: ChatActivity,
    private val onConversationSubtitleChanged: () -> Unit,
    private val refreshVoiceSupportHint: () -> Unit,
    private val showStyledBottomDialog: (AlertDialog) -> Unit
) {
    fun showReplyStyleDialog() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_reply_style, null)
        val optionContainer = view.findViewById<LinearLayout>(R.id.layout_style_options)
        val btnCancel = view.findViewById<TextView>(R.id.btn_style_cancel)
        val current = Prefs.getAiChatReplyStyle(context)
        val options = listOf(
            Triple("cute", "可爱俏皮", "更活一点，允许少量颜文字和小俏皮话"),
            Triple("gentle", "温柔陪伴", "更轻一点，像在旁边慢慢接住你"),
            Triple("concise", "简洁克制", "只说重点，语气干净，不拖泥带水"),
            Triple("playful", "活泼碎碎念", "更有聊天感，适合想要热闹一点的反馈"),
            Triple("custom", "自定义", "按你自己的提示词来定义语气、人设和长度"),
            Triple("off", "关闭", "只保留账单卡片，不再补自然回复")
        )

        lateinit var dialog: AlertDialog
        options.forEach { (value, title, desc) ->
            val itemView = LayoutInflater.from(context).inflate(R.layout.item_reply_style_option, optionContainer, false)
            val titleView = itemView.findViewById<TextView>(R.id.tv_style_title)
            val descView = itemView.findViewById<TextView>(R.id.tv_style_desc)
            val stateView = itemView.findViewById<TextView>(R.id.tv_style_state)
            titleView.text = title
            descView.text = desc
            val selected = current == value
            itemView.background = context.getDrawable(
                if (selected) R.drawable.bg_reply_style_option_selected else R.drawable.bg_dialog_action_item
            )
            stateView.visibility = if (selected) View.VISIBLE else View.GONE
            itemView.setOnClickListener {
                if (value == "custom") {
                    dialog.dismiss()
                    showCustomReplyStyleDialog()
                } else {
                    Prefs.setAiChatReplyStyle(context, value)
                    Utils.toast(context, if (value == "off") "已关闭自然回复" else "已切换为$title")
                    dialog.dismiss()
                }
            }
            optionContainer.addView(itemView)
        }

        dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_FlipAccounting))
            .setView(view)
            .create()
        styleChatPanelWindow(dialog)
        btnCancel.setOnClickListener { dialog.dismiss() }
        showStyledBottomDialog(dialog)
    }

    fun showModelSwitchDialog() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_model_picker, null)
        val etSearch = view.findViewById<android.widget.EditText>(R.id.et_model_search)
        val rv = view.findViewById<RecyclerView>(R.id.rv_model_list)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_model_empty)
        val btnCancel = view.findViewById<TextView>(R.id.btn_model_cancel)
        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_FlipAccounting))
            .setView(view)
            .create()

        val allModels = Prefs.getAiModelsCache(context).ifEmpty {
            listOf(Prefs.getAiModel(context), Prefs.getAiSingleModel(context), Prefs.getAiMultiModel(context)).distinct()
        }
        val modelAdapter = ModelOptionAdapter(
            current = Prefs.getAiChatModel(context),
            onSelect = { model ->
                Prefs.setAiChatModel(context, model)
                onConversationSubtitleChanged()
                refreshVoiceSupportHint()
                dialog.dismiss()
            }
        )
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = modelAdapter

        fun filter(kw: String) {
            val list = if (kw.isBlank()) allModels else allModels.filter { it.contains(kw, ignoreCase = true) }
            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            modelAdapter.submit(list)
        }
        filter("")

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                filter(s?.toString().orEmpty().trim())
            }
        })
        btnCancel.setOnClickListener { dialog.dismiss() }
        showStyledBottomDialog(dialog)
    }

    private fun showCustomReplyStyleDialog() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_reply_style_custom, null)
        val input = view.findViewById<android.widget.EditText>(R.id.et_custom_reply_style)
        val btnSave = view.findViewById<TextView>(R.id.btn_custom_save)
        val btnTurnOff = view.findViewById<TextView>(R.id.btn_custom_turn_off)
        val btnCancel = view.findViewById<TextView>(R.id.btn_custom_cancel)
        input.setText(Prefs.getAiChatReplyStyleCustomPrompt(context))
        input.setSelection(input.text?.length ?: 0)

        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_FlipAccounting))
            .setView(view)
            .create()
        styleChatPanelWindow(dialog)

        btnSave.setOnClickListener {
            val prompt = input.text?.toString().orEmpty().trim()
            Prefs.setAiChatReplyStyleCustomPrompt(context, prompt)
            Prefs.setAiChatReplyStyle(context, "custom")
            Utils.toast(context, "已切换为自定义风格")
            dialog.dismiss()
        }
        btnTurnOff.setOnClickListener {
            Prefs.setAiChatReplyStyle(context, "off")
            Utils.toast(context, "已关闭自然回复")
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        showStyledBottomDialog(dialog)
    }

    private fun styleChatPanelWindow(dialog: AlertDialog) {
        dialog.window?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            win.setWindowAnimations(R.style.Animation_FlipAccounting_DialogSoft)
            win.setBackgroundDrawableResource(R.drawable.shape_dialog_bg)
            win.setDimAmount(0.34f)
            win.setGravity(android.view.Gravity.BOTTOM)
            val margin = (12 * context.resources.displayMetrics.density).toInt()
            win.setLayout(context.resources.displayMetrics.widthPixels - margin * 2, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }
}
