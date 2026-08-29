package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RestorePreferencesTransactionTest {

    @Test
    fun `snapshot round trips every SharedPreferences value type`() {
        val source = linkedMapOf<String, Any>(
            "boolean" to true,
            "float" to 1.25f,
            "int" to 7,
            "long" to 8L,
            "string" to "value",
            "stringSet" to linkedSetOf("first", "second")
        )

        val restored = PreferenceFileSnapshot.capture(source).materialize()

        assertEquals(source, restored)
    }

    @Test
    fun `snapshot deeply copies string sets in both directions`() {
        val sourceSet = linkedSetOf("kept")
        val snapshot = PreferenceFileSnapshot.capture(mapOf("set" to sourceSet))

        sourceSet += "added after capture"
        val firstMaterialization = snapshot.materialize().getValue("set") as Set<*>
        assertEquals(setOf("kept"), firstMaterialization)
        assertNotSame(sourceSet, firstMaterialization)

        @Suppress("UNCHECKED_CAST")
        (firstMaterialization as MutableSet<String>) += "mutated materialization"
        val secondMaterialization = snapshot.materialize().getValue("set") as Set<*>
        assertEquals(setOf("kept"), secondMaterialization)
        assertNotSame(firstMaterialization, secondMaterialization)
    }

    @Test
    fun `snapshot rejects values SharedPreferences cannot store`() {
        assertThrows(IllegalArgumentException::class.java) {
            PreferenceFileSnapshot.capture(mapOf("double" to 1.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreferenceFileSnapshot.capture(mapOf("mixedSet" to setOf("valid", 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreferenceFileSnapshot.capture(mapOf("null" to null))
        }
    }

    @Test
    fun `transaction covers every preference file mutated during restore`() {
        assertEquals(
            listOf(
                "flip_prefs",
                "flip_currency_prefs",
                "tap_cloud_backup_prefs",
                "device_prefs",
                "investment_lot_drafts",
                "shared_ledger_credentials"
            ),
            RestorePreferencesTransaction.preferenceFileNames
        )
    }
}
