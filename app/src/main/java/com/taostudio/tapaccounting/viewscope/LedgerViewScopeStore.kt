package com.taostudio.tapaccounting.viewscope

import android.content.Context
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONArray

object LedgerViewScopeStore {
    private const val PREF_NAME = "ledger_view_scope"
    private const val KEY_BOOK_MODE = "book_mode"
    private const val KEY_BOOK_IDS = "book_ids"
    private const val KEY_MEMBER_SCOPE = "member_scope"
    private const val KEY_REVISION = "revision"
    private const val MODE_ALL = "all"
    private const val MODE_SELECTED = "selected"

    fun revision(context: Context): Long = prefs(context).getLong(KEY_REVISION, 0L)

    fun save(context: Context, scope: LedgerViewScope) {
        val preferences = prefs(context)
        val edit = preferences.edit()
            .putString(KEY_MEMBER_SCOPE, scope.members.name)
            .putLong(KEY_REVISION, preferences.getLong(KEY_REVISION, 0L) + 1L)
        when (val books = scope.books) {
            LedgerBookSelection.All -> edit
                .putString(KEY_BOOK_MODE, MODE_ALL)
                .remove(KEY_BOOK_IDS)
            is LedgerBookSelection.Selected -> {
                val ids = JSONArray()
                books.bookIds.sorted().forEach(ids::put)
                edit.putString(KEY_BOOK_MODE, MODE_SELECTED)
                    .putString(KEY_BOOK_IDS, ids.toString())
            }
        }
        edit.apply()
    }

    suspend fun saveLegacySelection(
        context: Context,
        db: AppDatabase,
        bookName: String
    ): ResolvedLedgerViewScope {
        val normalized = BookAccountManager.normalizeBookName(bookName)
        val scope = if (normalized == BookAccountManager.ALL_BOOK) {
            LedgerViewScope(LedgerBookSelection.All, LedgerMemberScope.EVERYONE)
        } else {
            val id = db.bookDao().resolveOrCreateId(normalized)
            LedgerViewScope(LedgerBookSelection.Selected(setOf(id)), LedgerMemberScope.EVERYONE)
        }
        save(context, scope)
        return resolve(context, db, normalized)
    }

    suspend fun resolve(
        context: Context,
        db: AppDatabase,
        legacyBookName: String? = null
    ): ResolvedLedgerViewScope {
        val databaseBooks = db.billDao().getAllBookNames()
        val visibleNames = BookAccountManager.getBookAccounts(context, databaseBooks)
            .map(BookAccountManager::normalizeBookName)
            .filter { it.isNotBlank() && it != BookAccountManager.ALL_BOOK && it != BookAccountManager.COLLAPSED_BOOK_GROUP }
            .distinct()

        val ledgers = db.sharedLedgerDao().getAll()
        val ledgerByBookId = ledgers.associateBy { it.bookId }
        val options = visibleNames.map { name ->
            val id = db.bookDao().resolveOrCreateId(name)
            ViewBookOption(id = id, name = name, isShared = ledgerByBookId.containsKey(id))
        }
        val availableIds = options.map { it.id }.toSet()
        val requested = read(context) ?: legacyScope(context, db, legacyBookName)
        val normalizedScope = when (val selection = requested.books) {
            LedgerBookSelection.All -> requested
            is LedgerBookSelection.Selected -> {
                val retained = selection.bookIds intersect availableIds
                if (retained.isNotEmpty()) {
                    requested.copy(books = LedgerBookSelection.Selected(retained))
                } else {
                    val fallbackName = BookAccountManager.normalizeBookName(
                        legacyBookName ?: BookAccountManager.getDefaultBook(context, visibleNames)
                    )
                    val fallbackId = options.firstOrNull { it.name == fallbackName }?.id
                        ?: options.firstOrNull()?.id
                    if (fallbackId == null) {
                        LedgerViewScope(LedgerBookSelection.All, requested.members)
                    } else {
                        requested.copy(books = LedgerBookSelection.Selected(setOf(fallbackId)))
                    }
                }
            }
        }

        val memberContexts = buildMap {
            ledgers.forEach { ledger ->
                val bookName = options.firstOrNull { it.id == ledger.bookId }?.name ?: return@forEach
                val names = db.sharedMemberDao().getByLedgerId(ledger.id)
                    .associate { it.memberId to it.resolvedName() }
                put(bookName, SharedBookMemberContext(ledger.localMemberId, names))
            }
        }
        return ResolvedLedgerViewScope(normalizedScope, options, memberContexts)
    }

    private suspend fun legacyScope(
        context: Context,
        db: AppDatabase,
        explicitBookName: String?
    ): LedgerViewScope {
        val selected = BookAccountManager.normalizeBookName(
            explicitBookName ?: BookAccountManager.getSelectedBook(context)
        )
        return if (selected == BookAccountManager.ALL_BOOK) {
            LedgerViewScope(LedgerBookSelection.All, LedgerMemberScope.EVERYONE)
        } else {
            val id = db.bookDao().resolveOrCreateId(selected)
            LedgerViewScope(LedgerBookSelection.Selected(setOf(id)), LedgerMemberScope.EVERYONE)
        }
    }

    private fun read(context: Context): LedgerViewScope? {
        val preferences = prefs(context)
        val memberScope = runCatching {
            LedgerMemberScope.valueOf(
                preferences.getString(KEY_MEMBER_SCOPE, LedgerMemberScope.EVERYONE.name)
                    ?: LedgerMemberScope.EVERYONE.name
            )
        }.getOrDefault(LedgerMemberScope.EVERYONE)
        return when (preferences.getString(KEY_BOOK_MODE, null)) {
            MODE_ALL -> LedgerViewScope(LedgerBookSelection.All, memberScope)
            MODE_SELECTED -> {
                val raw = preferences.getString(KEY_BOOK_IDS, null) ?: return null
                val ids = runCatching {
                    val array = JSONArray(raw)
                    buildSet {
                        for (index in 0 until array.length()) {
                            array.optLong(index).takeIf { it > 0L }?.let(::add)
                        }
                    }
                }.getOrDefault(emptySet())
                ids.takeIf { it.isNotEmpty() }
                    ?.let { LedgerViewScope(LedgerBookSelection.Selected(it), memberScope) }
            }
            else -> null
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
