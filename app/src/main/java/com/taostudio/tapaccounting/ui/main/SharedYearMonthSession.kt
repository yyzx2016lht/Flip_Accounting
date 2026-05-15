package com.taostudio.tapaccounting.ui.main

import android.content.Context
import com.taostudio.tapaccounting.TapApplication
import java.util.Calendar

object SharedYearMonthSession {
    private const val PREF_NAME = "flip_prefs"
    private const val KEY_YEAR = "shared_session_year"
    private const val KEY_MONTH = "shared_session_month"
    private val lock = Any()
    private var year: Int = loadStoredYearMonth().first
    private var month: Int = loadStoredYearMonth().second

    fun getYearMonth(): Pair<Int, Int> = synchronized(lock) { year to month }

    fun setYearMonth(year: Int, month: Int) {
        val safeYear = year.coerceIn(2000, 2100)
        val safeMonth = month.coerceIn(1, 12)
        synchronized(lock) {
            this.year = safeYear
            this.month = safeMonth
            prefs()
                .edit()
                .putInt(KEY_YEAR, safeYear)
                .putInt(KEY_MONTH, safeMonth)
                .apply()
        }
    }

    fun resetToCurrentMonth() {
        val now = Calendar.getInstance()
        setYearMonth(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
    }

    private fun loadStoredYearMonth(): Pair<Int, Int> {
        val now = Calendar.getInstance()
        return try {
            val prefs = prefs()
            val storedYear = prefs.getInt(KEY_YEAR, now.get(Calendar.YEAR)).coerceIn(2000, 2100)
            val storedMonth = prefs.getInt(KEY_MONTH, now.get(Calendar.MONTH) + 1).coerceIn(1, 12)
            storedYear to storedMonth
        } catch (_: Exception) {
            now.get(Calendar.YEAR) to (now.get(Calendar.MONTH) + 1)
        }
    }

    private fun prefs() =
        TapApplication.app().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}

