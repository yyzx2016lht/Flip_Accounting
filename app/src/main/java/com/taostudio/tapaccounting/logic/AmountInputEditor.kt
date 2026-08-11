package com.taostudio.tapaccounting.logic

data class AmountInputEdit(
    val text: String,
    val cursor: Int
)

object AmountInputEditor {
    private val operators = charArrayOf('+', '-', '×', '÷')

    fun insert(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        token: String
    ): AmountInputEdit {
        val selection = selection(text, selectionStart, selectionEnd)
        val base = text.removeRange(selection.first, selection.last)
        val cursor = selection.first

        if (token == ".") {
            val segmentStart = base.substring(0, cursor).indexOfLast { it in operators } + 1
            val nextOperator = base.indexOfAny(operators, startIndex = cursor)
            val segmentEnd = nextOperator.takeIf { it >= 0 } ?: base.length
            if (base.substring(segmentStart, segmentEnd).contains('.')) {
                return AmountInputEdit(text, selection.first)
            }
            val inserted = if (cursor == segmentStart) "0." else "."
            return insertAt(base, cursor, inserted)
        }

        val segmentStart = base.substring(0, cursor).indexOfLast { it in operators } + 1
        val nextOperator = base.indexOfAny(operators, startIndex = cursor)
        val segmentEnd = nextOperator.takeIf { it >= 0 } ?: base.length
        if (cursor == segmentEnd && base.substring(segmentStart, segmentEnd) == "0") {
            val next = base.replaceRange(segmentStart, segmentEnd, token)
            return AmountInputEdit(next, segmentStart + token.length)
        }

        return insertAt(base, cursor, token)
    }

    fun insertOperator(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        operator: String
    ): AmountInputEdit {
        val selection = selection(text, selectionStart, selectionEnd)
        val base = text.removeRange(selection.first, selection.last)
        val cursor = selection.first
        if (operator.length != 1 || operator[0] !in operators || base.isBlank() || cursor == 0) {
            return AmountInputEdit(text, selection.first)
        }

        if (base[cursor - 1] in operators) {
            return AmountInputEdit(base.replaceRange(cursor - 1, cursor, operator), cursor)
        }
        if (cursor < base.length && base[cursor] in operators) {
            return AmountInputEdit(base.replaceRange(cursor, cursor + 1, operator), cursor + 1)
        }
        return insertAt(base, cursor, operator)
    }

    fun delete(
        text: String,
        selectionStart: Int,
        selectionEnd: Int
    ): AmountInputEdit {
        val selection = selection(text, selectionStart, selectionEnd)
        if (selection.first != selection.last) {
            return AmountInputEdit(text.removeRange(selection.first, selection.last), selection.first)
        }
        if (selection.first == 0) return AmountInputEdit(text, 0)

        val cursor = selection.first
        return AmountInputEdit(text.removeRange(cursor - 1, cursor), cursor - 1)
    }

    private fun insertAt(text: String, cursor: Int, token: String): AmountInputEdit {
        return AmountInputEdit(text.replaceRange(cursor, cursor, token), cursor + token.length)
    }

    private fun selection(text: String, start: Int, end: Int): IntRange {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(0, text.length)
        return minOf(safeStart, safeEnd)..maxOf(safeStart, safeEnd)
    }
}
