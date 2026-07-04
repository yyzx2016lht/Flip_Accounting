package com.taostudio.tapaccounting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.markwon.utils.LeadingMarginUtils
import io.noties.prism4j.Prism4j
import org.commonmark.node.FencedCodeBlock

/**
 * Full markdown renderer for chat bubbles: headings, lists, quotes, tables, links,
 * images, inline/block HTML, task lists, strikethrough, syntax-highlighted code blocks.
 */
object ChatMarkdownFormatter {

    @Volatile
    private var markwon: Markwon? = null

    fun init(ctx: Context) {
        if (markwon != null) return
        synchronized(this) {
            if (markwon != null) return
            val appCtx = ctx.applicationContext
            val prism4j = Prism4j(ChatPrismGrammarLocator())
            markwon = Markwon.builder(appCtx)
                .usePlugin(CorePlugin.create())
                .usePlugin(HtmlPlugin.create())
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(appCtx))
                .usePlugin(TaskListPlugin.create(appCtx))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(GlideImagesPlugin.create(appCtx))
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, Prism4jThemeDefault.create()))
                .usePlugin(ChatBubblePlugin(appCtx))
                .usePlugin(CodeBlockCopyPlugin(appCtx))
                .build()
        }
    }

    fun applyTo(textView: TextView, raw: String) {
        if (raw.isEmpty()) {
            textView.text = raw
            return
        }
        val m = markwon
        if (m == null) {
            textView.text = renderFallback(raw)
            return
        }
        m.setMarkdown(textView, raw)
        textView.linksClickable = true
        textView.movementMethod = LinkMovementMethod.getInstance()
    }

    fun render(raw: String): CharSequence {
        if (raw.isEmpty()) return raw
        val m = markwon ?: return renderFallback(raw)
        return m.toMarkdown(raw)
    }

    private fun renderFallback(raw: String): CharSequence {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < raw.length) {
            when {
                raw.startsWith("**", i) -> {
                    val end = raw.indexOf("**", startIndex = i + 2)
                    if (end > i + 2) {
                        val start = out.length
                        out.append(raw.substring(i + 2, end))
                        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else {
                        out.append(raw[i]); i++
                    }
                }
                raw[i] == '`' -> {
                    val end = raw.indexOf('`', startIndex = i + 1)
                    if (end > i + 1) {
                        val start = out.length
                        out.append(raw.substring(i + 1, end))
                        out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        out.append(raw[i]); i++
                    }
                }
                else -> {
                    out.append(raw[i]); i++
                }
            }
        }
        return out
    }

    private class ChatBubblePlugin(private val ctx: Context) : AbstractMarkwonPlugin() {
        private val linkColor = ContextCompat.getColor(ctx, R.color.chat_markdown_link)

        private fun sp(value: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, ctx.resources.displayMetrics).toInt()

        private fun dp(value: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics).toInt()

        override fun configureTheme(builder: MarkwonTheme.Builder) {
            builder
                .linkColor(linkColor)
                .bulletWidth(dp(4f))
                .blockMargin(dp(8f))
                .listItemColor(ContextCompat.getColor(ctx, R.color.chat_ai_bubble_text))
                .codeBlockTypeface(Typeface.MONOSPACE)
                .codeBlockTextSize(sp(13f))
                .codeBlockBackgroundColor(ContextCompat.getColor(ctx, R.color.chat_code_block_bg))
                .codeBlockMargin(dp(10f))
                .codeTypeface(Typeface.MONOSPACE)
                .codeTextSize(sp(13f))
                .codeBackgroundColor(ContextCompat.getColor(ctx, R.color.chat_code_block_bg))
                .blockQuoteColor(0xFF_94_A3_B8.toInt())
                .blockQuoteWidth(dp(3f))
                .headingBreakHeight(dp(8f))
                .headingTextSizeMultipliers(
                    floatArrayOf(1.35f, 1.2f, 1.1f, 1.0f, 0.95f, 0.9f)
                )
        }
    }

    private class CodeBlockCopyPlugin(private val ctx: Context) : AbstractMarkwonPlugin() {
        private val copyIcon: Drawable by lazy {
            AppCompatResources.getDrawable(ctx, R.drawable.ic_copy_code)!!.apply {
                setBounds(0, 0, dp(16f), dp(16f))
            }
        }
        private val copyLabel: String by lazy { ctx.getString(R.string.copy) }

        override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
            builder.appendFactory(FencedCodeBlock::class.java) { _, _ ->
                CodeBlockCopySpan(ctx)
            }
            builder.appendFactory(FencedCodeBlock::class.java) { _, _ ->
                CodeBlockCopyActionSpan(copyIcon, copyLabel, dp(16f))
            }
        }

        private fun dp(value: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics).toInt()
    }

    private class CodeBlockCopySpan(private val ctx: Context) : ClickableSpan() {
        override fun onClick(widget: View) {
            val spanned = (widget as? TextView)?.text as? Spanned ?: return
            val start = spanned.getSpanStart(this)
            val end = spanned.getSpanEnd(this)
            if (start < 0 || end <= start) return
            val contents = spanned.subSequence(start, end).toString().trim()
            if (contents.isEmpty()) return
            val clipboard = ctx.getSystemService(ClipboardManager::class.java) ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("code", contents))
            Utils.toast(ctx, ctx.getString(R.string.code_copied))
        }

        override fun updateDrawState(ds: TextPaint) = Unit
    }

    private class CodeBlockCopyActionSpan(
        private val icon: Drawable,
        private val label: String,
        private val iconSize: Int
    ) : LeadingMarginSpan {
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF_64748_B.toInt()
            textSize = iconSize * 0.75f
            typeface = Typeface.DEFAULT
        }

        override fun getLeadingMargin(first: Boolean): Int = 0

        override fun drawLeadingMargin(
            c: Canvas,
            p: Paint,
            x: Int,
            dir: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            first: Boolean,
            layout: Layout
        ) {
            if (!LeadingMarginUtils.selfStart(start, text, this)) return
            val labelWidth = labelPaint.measureText(label)
            val gap = iconSize * 0.35f
            val totalWidth = labelWidth + gap + iconSize
            val left = layout.width - totalWidth - iconSize * 0.5f
            val textY = top + iconSize * 0.85f
            c.drawText(label, left, textY, labelPaint)
            val save = c.save()
            try {
                c.translate(left + labelWidth + gap, top + (iconSize * 0.1f))
                icon.draw(c)
            } finally {
                c.restoreToCount(save)
            }
        }
    }
}
