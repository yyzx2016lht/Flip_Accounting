package tao.test.flipaccounting

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.ChatMessage
import tao.test.flipaccounting.data.repository.CategoryRepository
import tao.test.flipaccounting.logic.BillMutationService
import tao.test.flipaccounting.logic.BillDisplayFormatter
import tao.test.flipaccounting.ui.common.UiMotion
import tao.test.flipaccounting.ui.common.UiMotion.pressFeedback
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class ChatAdapter(
    private val context: ChatActivity,
    private val displayMessages: MutableList<ChatDisplayItem>,
    private val db: AppDatabase,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val isMessageSelected: (ChatDisplayItem) -> Boolean,
    private val pendingVoiceBubbleAnimations: MutableSet<String>,
    private val pendingTranscriptRevealAnimations: MutableSet<String>,
    private val visibleTranscriptPaths: MutableSet<String>,
    private val transcribingPaths: MutableSet<String>,
    private val isVoiceSelectionMode: () -> Boolean,
    private val currentPlayingPath: () -> String?,
    private val isMediaPlaying: () -> Boolean,
    private val onToggleVoiceSelection: (ChatDisplayItem) -> Unit,
    private val onPlayVoiceMessage: (ChatDisplayItem) -> Unit,
    private val onShowVoiceMessageMenu: (View, ChatDisplayItem) -> Unit,
    private val onShowTranscriptMenu: (View, ChatDisplayItem) -> Unit,
    private val onShowTextMessageMenu: (View, ChatDisplayItem) -> Unit,
    private val parseVoicePayload: (String) -> VoicePayload,
    private val copyToClipboard: (String, String, String) -> Unit,
    private val loadUserAvatar: (ImageView) -> Unit,
    private val loadAiAvatar: (ImageView) -> Unit,
    private val formatChatMessageTime: (Long) -> String,
    private val shouldShowTimestamp: (Int, Long) -> Boolean,
    private val formatTime: (Long) -> String,
    private val showSoftKeyboard: (View) -> Unit,
    private val hideSoftKeyboard: (View) -> Unit,
    private val getInlineAmountEditingBillId: () -> Long?,
    private val setInlineAmountEditingBillId: (Long?) -> Unit,
    private val onMaybeShowRuleDialogForChatBillCategoryEdit: (ChatDisplayItem, Bill, Bill) -> Unit,
    private val showCustomConfirmDialog: (String, String, String, Boolean, () -> Unit) -> Unit,
    private val onInteractiveBillAction: (ChatDisplayItem, Bill, Int) -> Unit,
    private val onInterruptAiLoading: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount(): Int = displayMessages.size

    override fun getItemViewType(position: Int): Int = displayMessages[position].msgType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ChatActivity.MSG_TYPE_USER_TEXT, ChatActivity.MSG_TYPE_USER_IMAGE, ChatActivity.MSG_TYPE_USER_VOICE ->
                UserVH(inflater.inflate(R.layout.item_chat_user, parent, false))

            ChatActivity.MSG_TYPE_AI_BILL ->
                AiBillVH(inflater.inflate(R.layout.item_chat_bill, parent, false))

            else ->
                AiTextVH(inflater.inflate(R.layout.item_chat_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = displayMessages[position]
        when (holder) {
            is UserVH -> holder.bind(item)
            is AiTextVH -> holder.bind(item)
            is AiBillVH -> holder.bind(item)
        }
    }

    inner class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvText: TextView = v.findViewById(R.id.tv_user_text)
        private val tvTime: TextView = v.findViewById(R.id.tv_user_time)
        private val ivImage: ImageView = v.findViewById(R.id.iv_user_image)
        private val layoutVoice: LinearLayout = v.findViewById(R.id.layout_user_voice)
        private val tvVoice: TextView = v.findViewById(R.id.tv_voice_text)
        private val ivVoicePlay: ImageView = v.findViewById(R.id.iv_voice_play)
        private val layoutVoiceWave: LinearLayout = v.findViewById(R.id.layout_voice_wave)
        private val layoutVoiceTranscript: LinearLayout = v.findViewById(R.id.layout_voice_transcript)
        private val tvVoiceTranscript: TextView = v.findViewById(R.id.tv_voice_transcript)
        private val ivVoiceTranscriptCopy: ImageView = v.findViewById(R.id.iv_voice_transcript_copy)
        private var waveBars: List<View> = emptyList()
        private val cbSelect: android.widget.CheckBox = v.findViewById(R.id.cb_user_select)
        private val ivUserAvatar: ImageView = v.findViewById(R.id.iv_user_avatar)

        fun bind(item: ChatDisplayItem) {
            tvTime.text = formatChatMessageTime(item.timestamp)
            tvTime.visibility = if (shouldShowTimestamp(adapterPosition, item.timestamp)) View.VISIBLE else View.GONE
            loadUserAvatar(ivUserAvatar)
            val supportsSelection = item.msgType == ChatActivity.MSG_TYPE_USER_TEXT || item.msgType == ChatActivity.MSG_TYPE_USER_VOICE
            cbSelect.visibility = if (isVoiceSelectionMode() && supportsSelection) View.VISIBLE else View.GONE
            cbSelect.isChecked = isMessageSelected(item)
            when (item.msgType) {
                ChatActivity.MSG_TYPE_USER_IMAGE -> {
                    tvText.visibility = View.GONE
                    layoutVoice.visibility = View.GONE
                    layoutVoiceTranscript.visibility = View.GONE
                    ivImage.visibility = View.VISIBLE
                    Glide.with(itemView.context)
                        .load(Uri.parse(item.imageUri))
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .into(ivImage)
                }

                ChatActivity.MSG_TYPE_USER_VOICE -> {
                    tvText.visibility = View.GONE
                    ivImage.visibility = View.GONE
                    layoutVoice.visibility = View.VISIBLE
                    val voice = item.voice ?: parseVoicePayload(item.content)
                    tvVoice.text = "${voice.durationSec}\""
                    val width = (44 + voice.durationSec.coerceAtMost(45) * 3.4f) * itemView.resources.displayMetrics.density
                    val waveWidth = width.toInt().coerceAtLeast(0)
                    layoutVoiceWave.layoutParams = layoutVoiceWave.layoutParams.apply {
                        this.width = waveWidth
                    }
                    updateWaveBarsForVoice(voice.durationSec, waveWidth)
                    val isPlaying = currentPlayingPath() == voice.audioPath && isMediaPlaying()
                    ivVoicePlay.setImageResource(
                        if (isPlaying) R.drawable.ic_voice_pause_telegram else R.drawable.ic_voice_play_telegram
                    )
                    ivVoicePlay.setColorFilter(Color.WHITE)
                    bindWaveBars(voice.audioPath, isPlaying)
                    bindVoiceTranscript(item, voice)
                    maybeAnimateFreshVoiceBubble(voice.audioPath)
                    maybeAnimateTranscriptReveal(voice.audioPath)
                    layoutVoice.alpha = if (isVoiceSelectionMode() && isMessageSelected(item)) 0.8f else 1f
                    layoutVoice.setOnClickListener {
                        if (isVoiceSelectionMode()) onToggleVoiceSelection(item) else onPlayVoiceMessage(item)
                    }
                    layoutVoice.setOnLongClickListener {
                        onShowVoiceMessageMenu(layoutVoice, item)
                        true
                    }
                }

                else -> {
                    tvText.visibility = View.VISIBLE
                    ivImage.visibility = View.GONE
                    layoutVoice.visibility = View.GONE
                    layoutVoiceTranscript.visibility = View.GONE
                    tvText.text = item.content
                    tvText.alpha = if (isVoiceSelectionMode() && isMessageSelected(item)) 0.8f else 1f
                    itemView.setOnClickListener {
                        if (isVoiceSelectionMode()) onToggleVoiceSelection(item)
                    }
                    itemView.setOnLongClickListener {
                        onShowTextMessageMenu(tvText, item)
                        true
                    }
                }
            }
            if (item.msgType != ChatActivity.MSG_TYPE_USER_VOICE) {
                layoutVoice.setOnClickListener(null)
                layoutVoice.setOnLongClickListener(null)
                layoutVoiceTranscript.setOnLongClickListener(null)
                tvVoiceTranscript.setOnLongClickListener(null)
                ivVoiceTranscriptCopy.setOnClickListener(null)
            }
            ivUserAvatar.setOnClickListener(null)
            itemView.setOnClickListener {
                if (isVoiceSelectionMode() && supportsSelection) onToggleVoiceSelection(item)
            }
        }

        private fun updateWaveBarsForVoice(durationSec: Int, waveWidthPx: Int) {
            val density = itemView.resources.displayMetrics.density
            val barCount = calculateWaveBarCount(durationSec, waveWidthPx, density)
            val barWidth = (2.4f * density).roundToInt().coerceAtLeast(2)
            val marginEnd = (1.9f * density).roundToInt().coerceAtLeast(1)
            if (layoutVoiceWave.childCount != barCount) {
                layoutVoiceWave.removeAllViews()
                repeat(barCount) {
                    val bar = View(itemView.context).apply {
                        setBackgroundResource(R.drawable.bg_chat_voice_wave_bar)
                    }
                    layoutVoiceWave.addView(bar)
                }
            }
            waveBars = (0 until layoutVoiceWave.childCount).map { layoutVoiceWave.getChildAt(it) }
            waveBars.forEachIndexed { index, bar ->
                val heightDp = 5 + ((index * 5 + durationSec * 3) % 9)
                val params = (bar.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(barWidth, (heightDp * density).toInt())
                params.width = barWidth
                params.height = (heightDp * density).roundToInt()
                params.marginEnd = if (index == waveBars.lastIndex) 0 else marginEnd
                bar.layoutParams = params
                bar.alpha = 0.6f + ((index + durationSec) % 5) * 0.07f
            }
        }

        private fun bindVoiceTranscript(item: ChatDisplayItem, voice: VoicePayload) {
            val transcript = voice.transcript.trim()
            val isVisible = visibleTranscriptPaths.contains(voice.audioPath)
            val isTranscribing = transcribingPaths.contains(voice.audioPath)

            if ((!isVisible || transcript.isBlank()) && !isTranscribing) {
                layoutVoiceTranscript.visibility = View.GONE
                layoutVoiceTranscript.alpha = 1f
                layoutVoiceTranscript.translationY = 0f
                ivVoiceTranscriptCopy.setOnClickListener(null)
                layoutVoiceTranscript.setOnLongClickListener(null)
                tvVoiceTranscript.setOnLongClickListener(null)
                return
            }

            layoutVoiceTranscript.visibility = View.VISIBLE
            tvVoiceTranscript.text = if (isTranscribing) "正在转换文字，请稍候..." else transcript
            ivVoiceTranscriptCopy.visibility = if (isTranscribing) View.GONE else View.VISIBLE

            if (isTranscribing) {
                ivVoiceTranscriptCopy.setOnClickListener(null)
                layoutVoiceTranscript.setOnLongClickListener(null)
                tvVoiceTranscript.setOnLongClickListener(null)
            } else {
                ivVoiceTranscriptCopy.setOnClickListener {
                    copyToClipboard("voice_transcript", transcript, "已复制转写文本")
                }
                val transcriptLongClick = View.OnLongClickListener {
                    if (isVoiceSelectionMode()) return@OnLongClickListener false
                    onShowTranscriptMenu(layoutVoiceTranscript, item)
                    true
                }
                layoutVoiceTranscript.setOnLongClickListener(transcriptLongClick)
                tvVoiceTranscript.setOnLongClickListener(transcriptLongClick)
            }
        }

        private fun calculateWaveBarCount(durationSec: Int, waveWidthPx: Int, density: Float): Int {
            val slotPx = (4.3f * density).coerceAtLeast(1f)
            val maxByWidth = (waveWidthPx / slotPx).toInt().coerceIn(8, 36)
            val fillRatio = when {
                durationSec <= 3 -> 0.58f
                durationSec <= 8 -> 0.72f
                durationSec <= 20 -> 0.86f
                else -> 0.92f
            }
            val byWidth = (maxByWidth * fillRatio).roundToInt()
            val byDuration = 6 + durationSec.coerceAtMost(60) / 2
            return maxOf(byWidth, byDuration).coerceIn(8, maxByWidth)
        }

        private fun bindWaveBars(audioPath: String, isPlaying: Boolean) {
            waveBars.forEachIndexed { index, bar ->
                bar.animate().cancel()
                if (isPlaying) {
                    val minScale = 0.45f + (index % 5) * 0.08f
                    bar.scaleY = minScale
                    bar.alpha = 0.55f + (index % 4) * 0.1f
                    animateWaveBar(bar, audioPath, index)
                } else {
                    bar.scaleY = 1f
                    if (bar.alpha < 0.62f) bar.alpha = 0.7f
                }
            }
        }

        private fun maybeAnimateFreshVoiceBubble(audioPath: String) {
            if (!pendingVoiceBubbleAnimations.remove(audioPath)) return
            layoutVoice.animate().cancel()
            layoutVoice.alpha = 0f
            layoutVoice.translationX = 20f
            layoutVoice.scaleX = 0.97f
            layoutVoice.scaleY = 0.97f
            layoutVoice.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(UiMotion.NORMAL)
                .setInterpolator(UiMotion.STANDARD_EASING)
                .start()
        }

        private fun maybeAnimateTranscriptReveal(audioPath: String) {
            if (layoutVoiceTranscript.visibility != View.VISIBLE) return
            if (!pendingTranscriptRevealAnimations.remove(audioPath)) return
            layoutVoiceTranscript.animate().cancel()
            layoutVoiceTranscript.alpha = 0f
            layoutVoiceTranscript.translationY = 10f
            layoutVoiceTranscript.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(UiMotion.FAST)
                .setInterpolator(UiMotion.STANDARD_EASING)
                .start()
        }

        private fun animateWaveBar(bar: View, audioPath: String, index: Int) {
            val targetScale = 1.4f - (index % 5) * 0.08f
            val duration = 170L + (index % 6) * 40L
            bar.animate()
                .scaleY(targetScale)
                .alpha(1f)
                .setDuration(duration)
                .withEndAction {
                    if (currentPlayingPath() == audioPath) {
                        bar.animate()
                            .scaleY(0.45f + index * 0.08f)
                            .alpha(0.55f + index * 0.08f)
                            .setDuration(duration)
                            .withEndAction {
                                if (currentPlayingPath() == audioPath) {
                                    animateWaveBar(bar, audioPath, index)
                                }
                            }
                            .start()
                    }
                }
                .start()
        }
    }

    inner class AiTextVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvText: TextView = v.findViewById(R.id.tv_ai_text)
        private val tvTime: TextView = v.findViewById(R.id.tv_ai_time)
        private val loadingRow: LinearLayout = v.findViewById(R.id.layout_ai_loading_row)
        private val loading: LinearLayout = v.findViewById(R.id.layout_ai_loading)
        private val tvLoading: TextView = v.findViewById(R.id.tv_ai_loading_text)
        private val ivInterrupt: ImageView = v.findViewById(R.id.iv_ai_interrupt)
        private val ivAvatar: ImageView = v.findViewById(R.id.iv_ai_avatar_msg)
        private val cbSelect: android.widget.CheckBox = v.findViewById(R.id.cb_ai_text_select)

        fun bind(item: ChatDisplayItem) {
            tvTime.text = formatChatMessageTime(item.timestamp)
            tvTime.visibility = if (shouldShowTimestamp(adapterPosition, item.timestamp)) View.VISIBLE else View.GONE
            loadAiAvatar(ivAvatar)
            cbSelect.visibility = if (isVoiceSelectionMode() && !item.isLoading && item.content.isNotBlank()) View.VISIBLE else View.GONE
            cbSelect.isChecked = isMessageSelected(item)
            if (item.isLoading) {
                loadingRow.visibility = View.VISIBLE
                tvText.visibility = View.GONE
                tvLoading.text = item.content.ifBlank { "分析中..." }
                ivInterrupt.setOnClickListener {
                    it.pressFeedback()
                    onInterruptAiLoading()
                }
            } else {
                loadingRow.visibility = View.GONE
                tvText.visibility = View.VISIBLE
                tvText.text = item.content
                ivInterrupt.setOnClickListener(null)
            }
            tvText.alpha = if (isVoiceSelectionMode() && isMessageSelected(item)) 0.8f else 1f
            itemView.setOnClickListener {
                if (isVoiceSelectionMode() && !item.isLoading && item.content.isNotBlank()) {
                    onToggleVoiceSelection(item)
                }
            }
            itemView.setOnLongClickListener {
                if (item.isLoading || item.content.isBlank()) return@setOnLongClickListener false
                onShowTextMessageMenu(tvText, item)
                true
            }
        }
    }

    inner class AiBillVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvTime: TextView = v.findViewById(R.id.tv_ai_bill_time)
        private val container: LinearLayout = v.findViewById(R.id.container_bills)
        private val ivAvatar: ImageView = v.findViewById(R.id.iv_ai_avatar_bill)
        private val tvHint: TextView = v.findViewById(R.id.tv_ai_bill_hint)

        fun bind(item: ChatDisplayItem) {
            tvTime.text = formatChatMessageTime(item.timestamp)
            tvTime.visibility = if (shouldShowTimestamp(adapterPosition, item.timestamp)) View.VISIBLE else View.GONE
            loadAiAvatar(ivAvatar)
            tvHint.text = item.billHint
            tvHint.visibility = if (item.billHint.isBlank()) View.GONE else View.VISIBLE
            container.removeAllViews()

            item.bills.forEachIndexed { index, bill ->
                val isPreviewBeforeBill =
                    item.billInteractionMode == ChatActivity.BILL_INTERACTION_CONFIRM_MODIFICATION &&
                        item.bills.size >= 2 &&
                        index == 0
                val deprecated = item.isDeprecated || item.deprecatedBillIds.contains(bill.id) || isPreviewBeforeBill
                val card = LayoutInflater.from(itemView.context)
                    .inflate(R.layout.item_chat_bill_card, container, false)
                val tvCat = card.findViewById<TextView>(R.id.tv_chat_bill_category)
                val tvDetail = card.findViewById<TextView>(R.id.tv_chat_bill_detail)
                val tvAmount = card.findViewById<TextView>(R.id.tv_chat_bill_amount)
                val etAmount = card.findViewById<android.widget.EditText>(R.id.et_chat_bill_amount)
                val tvBillTime = card.findViewById<TextView>(R.id.tv_chat_bill_time)
                val ivIcon = card.findViewById<ImageView>(R.id.iv_chat_bill_icon)
                val iconContainer = card.findViewById<View>(R.id.layout_chat_bill_icon_container)
                val btnEdit = card.findViewById<TextView>(R.id.btn_chat_bill_edit_category)
                val btnDelete = card.findViewById<TextView>(R.id.btn_chat_bill_delete)
                val tvEditedTag = card.findViewById<TextView>(R.id.tv_chat_bill_edited_tag)
                val isEdited = item.editedBillIds.contains(bill.id)
                val isTransfer = bill.type == Bill.TYPE_TRANSFER
                val showCategoryIcon = Prefs.isShowBillCategoryIcon(context)
                val showFullCategory = Prefs.isShowBillFullCategory(context)
                val remarkPriority = Prefs.isBillRemarkPriority(context)

                val categoryText = when (bill.type) {
                    Bill.TYPE_TRANSFER -> "转账"
                    else -> BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, showFullCategory).ifBlank { "未分类" }
                }
                val (primaryText, secondaryText) = BillDisplayFormatter.resolvePrimarySecondaryText(
                    categoryText = categoryText,
                    remarkText = bill.remark,
                    suffixText = bill.accountName,
                    remarkPriority = remarkPriority
                )
                tvCat.text = primaryText
                tvDetail.text = secondaryText
                tvDetail.visibility = if (secondaryText.isBlank()) View.GONE else View.VISIBLE
                val sign = when (bill.type) {
                    Bill.TYPE_INCOME -> "+"
                    Bill.TYPE_TRANSFER -> ""
                    else -> "-"
                }
                val amountText = String.format(Locale.getDefault(), "%.2f", bill.amount)
                tvAmount.text = "$sign$amountText"
                val amountColor = when (bill.type) {
                    Bill.TYPE_INCOME -> Color.parseColor("#2E7D32")
                    Bill.TYPE_TRANSFER -> Color.parseColor("#7A8598")
                    else -> Color.parseColor("#D32F2F")
                }
                val amountBgRes = when (bill.type) {
                    Bill.TYPE_INCOME -> R.drawable.bg_chat_bill_amount_tag_income
                    Bill.TYPE_TRANSFER -> R.drawable.bg_chat_bill_amount_tag_transfer
                    else -> R.drawable.bg_chat_bill_amount_tag_expense
                }
                val amountBgActiveRes = when (bill.type) {
                    Bill.TYPE_INCOME -> R.drawable.bg_chat_bill_amount_tag_income_active
                    Bill.TYPE_TRANSFER -> R.drawable.bg_chat_bill_amount_tag_transfer_active
                    else -> R.drawable.bg_chat_bill_amount_tag_expense_active
                }
                tvAmount.setTextColor(amountColor)
                etAmount.setTextColor(amountColor)
                tvAmount.setBackgroundResource(amountBgRes)
                etAmount.setBackgroundResource(amountBgActiveRes)
                etAmount.setText(amountText)
                etAmount.setSelection(etAmount.text?.length ?: 0)
                tvAmount.visibility = View.VISIBLE
                etAmount.visibility = View.GONE
                tvBillTime.text = formatTime(bill.time)
                if (deprecated) {
                    val strike = android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    tvCat.paintFlags = tvCat.paintFlags or strike
                    tvDetail.paintFlags = tvDetail.paintFlags or strike
                    tvAmount.paintFlags = tvAmount.paintFlags or strike
                    tvBillTime.paintFlags = tvBillTime.paintFlags or strike
                    card.alpha = 0.55f
                    btnEdit.visibility = View.GONE
                    btnDelete.visibility = View.GONE
                    etAmount.isEnabled = false
                } else if (isEdited) {
                    // 已编辑状态：显示标签，保留正常卡片信息，但隐藏操作入口
                    tvEditedTag.visibility = View.VISIBLE
                    card.alpha = 1f
                    btnEdit.visibility = View.GONE
                    btnDelete.visibility = View.GONE
                    etAmount.isEnabled = true
                } else {
                    card.alpha = 1f
                    tvEditedTag.visibility = View.GONE
                    btnEdit.visibility = if (isTransfer) View.GONE else View.VISIBLE
                    btnDelete.visibility = View.VISIBLE
                    etAmount.isEnabled = true
                }
                val iconTint = when (bill.type) {
                    0 -> Color.parseColor("#D32F2F")
                    1 -> Color.parseColor("#2E7D32")
                    else -> Color.parseColor("#7A8598")
                }
                if (!showCategoryIcon) {
                    iconContainer.setBackgroundColor(Color.TRANSPARENT)
                    iconContainer.layoutParams = iconContainer.layoutParams.apply {
                        val widthPx = (iconContainer.resources.displayMetrics.density * 10).toInt()
                        val heightPx = (iconContainer.resources.displayMetrics.density * 38).toInt()
                        width = widthPx
                        height = heightPx
                    }
                    ivIcon.clearColorFilter()
                    ivIcon.layoutParams = ivIcon.layoutParams.apply {
                        val px = (ivIcon.resources.displayMetrics.density * 6).toInt()
                        width = px
                        height = px
                    }
                    val dotRes = when (bill.type) {
                        Bill.TYPE_EXPENSE -> R.drawable.bg_bill_dot_expense
                        Bill.TYPE_INCOME -> R.drawable.bg_bill_dot_income
                        else -> R.drawable.bg_bill_dot_neutral
                    }
                    ivIcon.setImageResource(dotRes)
                } else {
                    iconContainer.setBackgroundResource(R.drawable.bg_chat_bill_icon_container)
                    iconContainer.layoutParams = iconContainer.layoutParams.apply {
                        val widthPx = (iconContainer.resources.displayMetrics.density * 38).toInt()
                        val heightPx = (iconContainer.resources.displayMetrics.density * 38).toInt()
                        width = widthPx
                        height = heightPx
                    }
                    ivIcon.layoutParams = ivIcon.layoutParams.apply {
                        val px = (ivIcon.resources.displayMetrics.density * 26).toInt()
                        width = px
                        height = px
                    }
                    ivIcon.setImageResource(android.R.drawable.ic_menu_info_details)
                    ivIcon.setColorFilter(iconTint)
                    lifecycleScope.launch {
                        val iconUrl = withContext(Dispatchers.IO) {
                            CategoryIconHelper.findCategoryIcon(context, bill.categoryName, bill.type)
                        }
                        if (iconUrl.isNotBlank()) {
                            Glide.with(ivIcon.context)
                                .load(iconUrl)
                                .diskCacheStrategy(DiskCacheStrategy.DATA)
                                .into(ivIcon)
                        }
                    }
                }

                if (item.billInteractionMode != ChatActivity.BILL_INTERACTION_NONE) {
                    if (deprecated) {
                        btnEdit.visibility = View.GONE
                        btnDelete.visibility = View.GONE
                    } else {
                        val allowActionOnThisCard =
                            item.billInteractionMode != ChatActivity.BILL_INTERACTION_CONFIRM_MODIFICATION ||
                                index == item.bills.lastIndex
                        if (!allowActionOnThisCard) {
                            btnEdit.visibility = View.GONE
                            btnDelete.visibility = View.GONE
                        } else {
                            btnEdit.visibility = View.VISIBLE
                            btnDelete.visibility = View.VISIBLE
                            when (item.billInteractionMode) {
                                ChatActivity.BILL_INTERACTION_SELECT_TARGET -> {
                                    btnEdit.text = "选这笔"
                                    btnDelete.text = "取消"
                                }
                                ChatActivity.BILL_INTERACTION_CONFIRM_MODIFICATION -> {
                                    btnEdit.text = "确认修改"
                                    btnDelete.text = "取消"
                                }
                            }
                            btnEdit.setOnClickListener {
                                onInteractiveBillAction(item, bill, ChatActivity.BILL_INTERACTIVE_ACTION_PRIMARY)
                            }
                            btnDelete.setOnClickListener {
                                onInteractiveBillAction(item, bill, ChatActivity.BILL_INTERACTIVE_ACTION_SECONDARY)
                            }
                        }
                    }
                    etAmount.visibility = View.GONE
                    tvAmount.visibility = View.VISIBLE
                    tvAmount.setOnClickListener(null)
                    etAmount.setOnEditorActionListener(null)
                    etAmount.setOnFocusChangeListener(null)
                    container.addView(card)
                    return@forEachIndexed
                }

                btnEdit.setOnClickListener {
                    if (deprecated) return@setOnClickListener
                    val pickerType = if (bill.type == Bill.TYPE_INCOME) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
                    val categoryDbType = if (bill.type == Bill.TYPE_INCOME) 1 else 0
                    OverlayDialogs.showGridCategoryPicker(context, bill.categoryName, pickerType) { selected ->
                        val originalBill = bill.copy()
                        lifecycleScope.launch {
                            val updated = withContext(Dispatchers.IO) {
                                val categoryEntity = CategoryRepository(db.categoryDao()).findCategoryByDisplayName(
                                    categoryDbType,
                                    selected
                                )
                                BillMutationService.replaceBill(
                                    db = db,
                                    oldBill = bill,
                                    newBill = bill.copy(categoryName = selected, categoryId = categoryEntity?.id)
                                )
                            }
                            val msgIdx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                            if (msgIdx >= 0) {
                                val rowIdx = displayMessages[msgIdx].bills.indexOfFirst { it.id == bill.id }
                                if (rowIdx >= 0) {
                                    displayMessages[msgIdx].bills[rowIdx] = updated
                                    this@ChatAdapter.notifyItemChanged(msgIdx)
                                }
                            }
                            onMaybeShowRuleDialogForChatBillCategoryEdit(item, originalBill, updated)
                        }
                    }
                }
                var savingAmount = false
                fun closeInlineAmountEdit(hideKeyboardNow: Boolean) {
                    if (hideKeyboardNow) hideSoftKeyboard(etAmount)
                    etAmount.clearFocus()
                    etAmount.visibility = View.GONE
                    tvAmount.visibility = View.VISIBLE
                    tvAmount.setBackgroundResource(amountBgRes)
                    if (getInlineAmountEditingBillId() == bill.id) {
                        setInlineAmountEditingBillId(null)
                    }
                }
                fun startInlineAmountEdit() {
                    if (deprecated || savingAmount) return
                    if (getInlineAmountEditingBillId() != null && getInlineAmountEditingBillId() != bill.id) {
                        Utils.toast(context, "请先完成当前金额编辑")
                        return
                    }
                    setInlineAmountEditingBillId(bill.id)
                    tvAmount.visibility = View.GONE
                    etAmount.visibility = View.VISIBLE
                    tvAmount.setBackgroundResource(amountBgRes)
                    etAmount.setBackgroundResource(amountBgActiveRes)
                    etAmount.setText(String.format(Locale.getDefault(), "%.2f", bill.amount))
                    etAmount.setSelection(etAmount.text?.length ?: 0)
                    etAmount.requestFocus()
                    showSoftKeyboard(etAmount)
                }
                fun commitInlineAmountEdit() {
                    if (deprecated || savingAmount || etAmount.visibility != View.VISIBLE) return
                    val value = etAmount.text?.toString().orEmpty().trim()
                    val editedAmount = value.toDoubleOrNull()
                    if (editedAmount == null || !editedAmount.isFinite() || editedAmount <= 0.0) {
                        Utils.toast(context, "请输入有效金额")
                        etAmount.requestFocus()
                        return
                    }
                    val changed = kotlin.math.abs(editedAmount - bill.amount) > 0.000001
                    if (!changed) {
                        closeInlineAmountEdit(hideKeyboardNow = true)
                        return
                    }
                    savingAmount = true
                    lifecycleScope.launch {
                        val updated = withContext(Dispatchers.IO) {
                            val updatedBill = when {
                                bill.subType == Bill.SUBTYPE_REFUND -> bill.copy(amount = editedAmount)
                                bill.type == Bill.TYPE_EXPENSE -> bill.copy(amount = editedAmount, originalAmount = editedAmount)
                                else -> bill.copy(amount = editedAmount)
                            }
                            BillMutationService.replaceBill(
                                db = db,
                                oldBill = bill,
                                newBill = updatedBill
                            )
                        }
                        savingAmount = false
                        closeInlineAmountEdit(hideKeyboardNow = true)
                        val msgIdx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                        if (msgIdx >= 0) {
                            val rowIdx = displayMessages[msgIdx].bills.indexOfFirst { it.id == bill.id }
                            if (rowIdx >= 0) {
                                displayMessages[msgIdx].bills[rowIdx] = updated
                                this@ChatAdapter.notifyItemChanged(msgIdx)
                            }
                        }
                    }
                }
                tvAmount.setOnClickListener { startInlineAmountEdit() }
                etAmount.setOnEditorActionListener { _, actionId, event ->
                    val imeDone = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    val enterDown = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        event.action == android.view.KeyEvent.ACTION_DOWN
                    if (imeDone || enterDown) {
                        commitInlineAmountEdit()
                        true
                    } else {
                        false
                    }
                }
                etAmount.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && !savingAmount && etAmount.visibility == View.VISIBLE) {
                        closeInlineAmountEdit(hideKeyboardNow = false)
                    }
                }
                btnDelete.setOnClickListener {
                    if (deprecated) return@setOnClickListener
                    val finalDelete: () -> Unit = {
                        lifecycleScope.launch {
                            val deletedBillSnapshot = bill.copy()
                            withContext(Dispatchers.IO) {
                                if (bill.id > 0L) tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
                            }
                            val msgIdx = displayMessages.indexOfFirst { it.dbId == item.dbId }
                            if (msgIdx >= 0) {
                                if (displayMessages[msgIdx].bills.size <= 1) {
                                    displayMessages[msgIdx] = displayMessages[msgIdx].copy(
                                        bills = mutableListOf(deletedBillSnapshot),
                                        isDeprecated = true,
                                        deprecatedBillIds = mutableSetOf(deletedBillSnapshot.id)
                                    )
                                    val msgId = displayMessages[msgIdx].dbId
                                    if (msgId > 0L) {
                                        withContext(Dispatchers.IO) {
                                            db.chatMessageDao().getById(msgId)?.let { oldMsg ->
                                                db.chatMessageDao().update(
                                                    oldMsg.copy(
                                                        billIds = ChatBillMessageParser.markBillIdsAsDeprecated(oldMsg.billIds),
                                                        content = ChatBillMessageParser.buildBillMessageContent(
                                                            bills = listOf(deletedBillSnapshot),
                                                            formatTime = formatTime,
                                                            deprecatedBillIds = setOf(deletedBillSnapshot.id)
                                                        )
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    this@ChatAdapter.notifyItemChanged(msgIdx)
                                } else {
                                    val currentItem = displayMessages[msgIdx]
                                    val updatedBills = currentItem.bills.toMutableList()
                                    updatedBills[index] = deletedBillSnapshot
                                    val updatedDeprecatedIds = currentItem.deprecatedBillIds.toMutableSet().apply {
                                        add(deletedBillSnapshot.id)
                                    }
                                    displayMessages[msgIdx] = currentItem.copy(
                                        bills = updatedBills,
                                        deprecatedBillIds = updatedDeprecatedIds
                                    )
                                    val msgId = currentItem.dbId
                                    if (msgId > 0L) {
                                        withContext(Dispatchers.IO) {
                                            db.chatMessageDao().getById(msgId)?.let { oldMsg ->
                                                db.chatMessageDao().update(
                                                    oldMsg.copy(
                                                        content = ChatBillMessageParser.buildBillMessageContent(
                                                            bills = updatedBills,
                                                            formatTime = formatTime,
                                                            deprecatedBillIds = updatedDeprecatedIds
                                                        )
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    this@ChatAdapter.notifyItemChanged(msgIdx)
                                }
                            }
                        }
                    }
                    showCustomConfirmDialog(
                        "确认删除",
                        "删除后不可恢复，是否继续？\n删除聊天记录不会删除附带账单。",
                        "确认删除",
                        true
                    ) {
                        finalDelete()
                    }
                }

                container.addView(card)
            }
            container.alpha = if (item.isDeprecated) 0.55f else 1f
        }
    }
}

class ModelOptionAdapter(
    private val current: String,
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<ModelOptionAdapter.VH>() {
    private val list = mutableListOf<String>()

    fun submit(data: List<String>) {
        list.clear()
        list.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_option, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(list[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvName: TextView = v.findViewById(R.id.tv_model_name)
        private val tvTag: TextView = v.findViewById(R.id.tv_model_selected_tag)
        fun bind(model: String) {
            tvName.text = model
            tvTag.visibility = if (model == current) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onSelect(model) }
        }
    }
}

class SessionListAdapter(
    private val onClick: (ChatSessionRow) -> Unit,
    private val onRename: (ChatSessionRow, String) -> Unit,
    private val onDelete: (ChatSessionRow) -> Unit
) : RecyclerView.Adapter<SessionListAdapter.VH>() {
    private val list = mutableListOf<ChatSessionRow>()
    private var openedPosition: Int = RecyclerView.NO_POSITION
    private var editingPosition: Int = RecyclerView.NO_POSITION

    fun submit(data: List<ChatSessionRow>) {
        val openedKey = list.getOrNull(openedPosition)?.let { it.bookName to it.conversationId }
        val editingKey = list.getOrNull(editingPosition)?.let { it.bookName to it.conversationId }
        list.clear()
        list.addAll(data)
        openedPosition = list.indexOfFirst {
            (it.bookName to it.conversationId) == openedKey
        }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        editingPosition = list.indexOfFirst {
            (it.bookName to it.conversationId) == editingKey
        }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    fun closeSwipeActions() {
        if (openedPosition == RecyclerView.NO_POSITION) return
        val old = openedPosition
        openedPosition = RecyclerView.NO_POSITION
        notifyItemChanged(old)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_session_drawer, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = list.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(
            item = list[position],
            opened = position == openedPosition,
            editing = position == editingPosition
        )
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val foreground: View = v.findViewById(R.id.layout_session_foreground)
        private val tvTitle: TextView = v.findViewById(R.id.tv_session_title)
        private val etTitle: EditText = v.findViewById(R.id.et_session_title)
        private val tvPreview: TextView = v.findViewById(R.id.tv_session_preview)
        private val tvTime: TextView = v.findViewById(R.id.tv_session_time)
        private val btnRename: ImageView = v.findViewById(R.id.btn_session_rename)
        private val btnDelete: ImageView = v.findViewById(R.id.btn_session_delete)
        private val slop = ViewConfiguration.get(v.context).scaledTouchSlop
        private val actionsWidthPx = 120f * v.resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var dragging = false

        init {
            foreground.setOnTouchListener { _, ev -> onForegroundTouch(ev) }
            foreground.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (editingPosition == pos) return@setOnClickListener
                val item = list.getOrNull(pos) ?: return@setOnClickListener
                if (openedPosition == pos) {
                    closeSwipeActions()
                    return@setOnClickListener
                }
                closeSwipeActions()
                onClick(item)
            }
            btnRename.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                closeSwipeActions()
                startInlineEdit(pos)
            }
            btnDelete.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = list.getOrNull(pos) ?: return@setOnClickListener
                closeSwipeActions()
                onDelete(item)
            }
            etTitle.setOnEditorActionListener { _, actionId, event ->
                val imeDone = actionId == EditorInfo.IME_ACTION_DONE
                val keyDone = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
                if (imeDone || keyDone) {
                    commitInlineRename()
                    true
                } else {
                    false
                }
            }
            etTitle.setOnFocusChangeListener { _, hasFocus ->
                val pos = adapterPosition
                if (!hasFocus && pos != RecyclerView.NO_POSITION && editingPosition == pos) {
                    commitInlineRename()
                }
            }
        }

        private fun onForegroundTouch(ev: MotionEvent): Boolean {
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION) return false
            if (editingPosition == pos) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startTx = foreground.translationX
                    dragging = false
                    if (openedPosition != RecyclerView.NO_POSITION && openedPosition != pos) {
                        val old = openedPosition
                        openedPosition = RecyclerView.NO_POSITION
                        notifyItemChanged(old)
                    }
                    itemView.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging) {
                        when {
                            abs(dx) > slop && abs(dx) > abs(dy) -> dragging = true
                            abs(dy) > slop -> {
                                itemView.parent?.requestDisallowInterceptTouchEvent(false)
                                return false
                            }
                        }
                    }
                    if (dragging) {
                        val tx = (startTx + dx).coerceIn(-actionsWidthPx, 0f)
                        foreground.translationX = tx
                        return true
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    itemView.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragging) {
                        settleSwipe(pos)
                        dragging = false
                        return true
                    }
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        foreground.performClick()
                        return true
                    }
                }
            }
            return false
        }

        private fun settleSwipe(pos: Int) {
            val shouldOpen = foreground.translationX < -actionsWidthPx * 0.38f
            val target = if (shouldOpen) -actionsWidthPx else 0f
            if (shouldOpen) {
                val old = openedPosition
                openedPosition = pos
                if (old != RecyclerView.NO_POSITION && old != pos) notifyItemChanged(old)
            } else if (openedPosition == pos) {
                openedPosition = RecyclerView.NO_POSITION
            }
            foreground.animate().translationX(target).setDuration(UiMotion.FAST).setInterpolator(UiMotion.STANDARD_EASING).start()
        }

        private fun startInlineEdit(pos: Int) {
            val old = editingPosition
            editingPosition = pos
            if (old != RecyclerView.NO_POSITION && old != pos) notifyItemChanged(old)
            notifyItemChanged(pos)
        }

        private fun commitInlineRename() {
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val item = list.getOrNull(pos) ?: return
            val newTitle = etTitle.text?.toString().orEmpty().trim()
            editingPosition = RecyclerView.NO_POSITION
            hideKeyboard(etTitle)
            notifyItemChanged(pos)
            if (newTitle.isBlank() || newTitle == item.title) return
            onRename(item, newTitle)
        }

        private fun hideKeyboard(view: View) {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }

        fun bind(item: ChatSessionRow, opened: Boolean, editing: Boolean) {
            tvTitle.text = item.title
            tvPreview.text = item.preview
            tvTime.text = item.displayTime
            foreground.animate().cancel()
            foreground.translationX = if (opened) -actionsWidthPx else 0f
            val bgRes = when {
                item.isCurrent && opened -> R.drawable.bg_chat_session_item_selected_opened
                item.isCurrent -> R.drawable.bg_chat_session_item_selected
                opened -> R.drawable.bg_chat_session_item_opened
                else -> R.drawable.bg_chat_session_item
            }
            foreground.setBackgroundResource(bgRes)
            if (editing) {
                tvTitle.visibility = View.GONE
                etTitle.visibility = View.VISIBLE
                if (etTitle.text?.toString() != item.title) etTitle.setText(item.title)
                etTitle.post {
                    if (adapterPosition != RecyclerView.NO_POSITION && adapterPosition == editingPosition) {
                        etTitle.requestFocus()
                        etTitle.setSelection(etTitle.text?.length ?: 0)
                        val imm = etTitle.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(etTitle, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            } else {
                tvTitle.visibility = View.VISIBLE
                etTitle.visibility = View.GONE
            }
        }
    }
}

class DrawerSearchResultAdapter(
    private val onClick: (ChatMessage) -> Unit,
    private val aiNameProvider: () -> String,
    private val parseVoicePayload: (String) -> VoicePayload,
    private val parseBillsFromMessageContent: (String) -> List<Bill>
) : RecyclerView.Adapter<DrawerSearchResultAdapter.VH>() {
    private val list = mutableListOf<ChatMessage>()

    fun submit(data: List<ChatMessage>) {
        list.clear()
        list.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_search_result, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = list.size
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(list[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvSender: TextView = v.findViewById(R.id.tv_search_sender)
        private val tvTime: TextView = v.findViewById(R.id.tv_search_time)
        private val tvContent: TextView = v.findViewById(R.id.tv_search_content)

        fun bind(msg: ChatMessage) {
            val isUser = msg.msgType in listOf(ChatActivity.MSG_TYPE_USER_TEXT, ChatActivity.MSG_TYPE_USER_IMAGE, ChatActivity.MSG_TYPE_USER_VOICE)
            tvSender.text = if (isUser) "我" else aiNameProvider()
            tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
            tvContent.text = when (msg.msgType) {
                ChatActivity.MSG_TYPE_USER_IMAGE -> "[图片]"
                ChatActivity.MSG_TYPE_USER_VOICE -> {
                    val transcript = parseVoicePayload(msg.content).transcript
                    if (transcript.isNotBlank()) "语音：${transcript.replace(Regex("\\s+"), " ").trim().take(80)}" else "[语音]"
                }

                ChatActivity.MSG_TYPE_AI_BILL -> {
                    val bill = parseBillsFromMessageContent(msg.content).lastOrNull()
                    val remark = bill?.remark?.takeIf { it.isNotBlank() }
                        ?: bill?.categoryName
                        ?: "账单记录"
                    "账单：$remark"
                }

                else -> msg.content.trim().ifBlank { "(空内容)" }.take(100)
            }
            if (tvContent.text.contains("/") || tvContent.text.contains("\\") || tvContent.text.contains("base64", true)) {
                tvContent.text = "[内容已隐藏]"
            }
            itemView.setOnClickListener { onClick(msg) }
        }
    }
}
