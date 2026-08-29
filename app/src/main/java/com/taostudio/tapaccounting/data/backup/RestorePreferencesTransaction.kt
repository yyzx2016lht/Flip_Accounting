package com.taostudio.tapaccounting.data.backup

import android.content.Context
import android.content.SharedPreferences
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * In-memory rollback boundary for every preference file that a restore may mutate.
 *
 * Construct this immediately before restore mutations begin. Call [commit] after the complete
 * restore succeeds, or [rollback] after any failure. A completed transaction is idempotent: later
 * calls to either method do nothing.
 */
class RestorePreferencesTransaction(context: Context) {

    enum class State {
        ACTIVE,
        COMMITTED,
        ROLLED_BACK
    }

    @Volatile
    var state: State = State.ACTIVE
        private set

    private var targets: List<PreferenceTarget> = captureTargets(
        context.applicationContext ?: context
    )

    /** Marks the restored preference values as final and releases the rollback snapshots. */
    @Synchronized
    fun commit() {
        if (state != State.ACTIVE) return
        targets = emptyList()
        state = State.COMMITTED
    }

    /**
     * Replaces each preference file with its constructor-time contents using synchronous writes.
     * Keys introduced during restore are removed by the preceding [SharedPreferences.Editor.clear].
     *
     * If any file cannot be committed, the remaining files are still attempted and this transaction
     * stays active so the caller can retry the rollback.
     */
    @Synchronized
    fun rollback() {
        if (state != State.ACTIVE) return

        val failures = mutableListOf<RollbackFailure>()
        targets.forEach { target ->
            try {
                if (!restore(target.preferences, target.snapshot)) {
                    failures += RollbackFailure(target.name)
                }
            } catch (error: Exception) {
                failures += RollbackFailure(target.name, error)
            }
        }

        if (failures.isNotEmpty()) {
            val exception = IllegalStateException(
                "无法回滚偏好文件：${failures.joinToString { it.name }}"
            )
            failures.mapNotNull(RollbackFailure::cause).forEach(exception::addSuppressed)
            throw exception
        }

        targets = emptyList()
        state = State.ROLLED_BACK
    }

    private fun restore(
        preferences: SharedPreferences,
        snapshot: PreferenceFileSnapshot
    ): Boolean {
        val editor = preferences.edit().clear()
        snapshot.materialize().forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }

                else -> error("Unsupported snapshotted preference value: ${value.javaClass.name}")
            }
        }
        return editor.commit()
    }

    private data class PreferenceTarget(
        val name: String,
        val preferences: SharedPreferences,
        val snapshot: PreferenceFileSnapshot
    )

    private data class RollbackFailure(
        val name: String,
        val cause: Throwable? = null
    )

    companion object {
        internal val preferenceFileNames: List<String> = listOf(
            "flip_prefs",
            "flip_currency_prefs",
            "tap_cloud_backup_prefs",
            "device_prefs",
            "investment_lot_drafts",
            "shared_ledger_credentials"
        )

        private fun captureTargets(context: Context): List<PreferenceTarget> =
            preferenceFileNames.map { name ->
                val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                PreferenceTarget(
                    name = name,
                    preferences = preferences,
                    snapshot = PreferenceFileSnapshot.capture(preferences.all)
                )
            }
    }
}

/** Pure, Android-independent representation used to deep-copy SharedPreferences values. */
internal class PreferenceFileSnapshot private constructor(
    private val entries: Map<String, StoredPreferenceValue>
) {
    fun materialize(): Map<String, Any> = entries.mapValuesTo(LinkedHashMap()) { (_, value) ->
        value.materialize()
    }

    companion object {
        fun capture(values: Map<String, *>): PreferenceFileSnapshot {
            val copied = values.mapValuesTo(LinkedHashMap()) { (key, value) ->
                StoredPreferenceValue.capture(key, value)
            }
            return PreferenceFileSnapshot(copied)
        }
    }
}

internal sealed interface StoredPreferenceValue {
    fun materialize(): Any

    data class BooleanValue(private val value: Boolean) : StoredPreferenceValue {
        override fun materialize(): Any = value
    }

    data class FloatValue(private val value: Float) : StoredPreferenceValue {
        override fun materialize(): Any = value
    }

    data class IntValue(private val value: Int) : StoredPreferenceValue {
        override fun materialize(): Any = value
    }

    data class LongValue(private val value: Long) : StoredPreferenceValue {
        override fun materialize(): Any = value
    }

    data class StringValue(private val value: String) : StoredPreferenceValue {
        override fun materialize(): Any = value
    }

    data class StringSetValue(private val values: List<String>) : StoredPreferenceValue {
        override fun materialize(): Any = LinkedHashSet(values)
    }

    companion object {
        fun capture(key: String, value: Any?): StoredPreferenceValue = when (value) {
            is Boolean -> BooleanValue(value)
            is Float -> FloatValue(value)
            is Int -> IntValue(value)
            is Long -> LongValue(value)
            is String -> StringValue(value)
            is Set<*> -> {
                require(value.all { it is String }) {
                    "Preference '$key' contains a non-string set value"
                }
                @Suppress("UNCHECKED_CAST")
                StringSetValue((value as Set<String>).toList())
            }

            null -> throw IllegalArgumentException("Preference '$key' has a null value")
            else -> throw IllegalArgumentException(
                "Preference '$key' has unsupported type ${value.javaClass.name}"
            )
        }
    }
}
