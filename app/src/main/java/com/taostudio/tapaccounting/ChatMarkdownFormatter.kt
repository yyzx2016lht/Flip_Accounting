package com.taostudio.tapaccounting

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

object ChatMarkdownFormatter {
    fun render(raw: String): CharSequence {
        if (raw.isEmpty()) return raw
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
                        out.append(raw[i])
                        i++
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
                        out.append(raw[i])
                        i++
                    }
                }
                else -> {
                    out.append(raw[i])
                    i++
                }
            }
        }
        return out
    }
}
