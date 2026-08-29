package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupSecretPolicyTest {
    @Test
    fun `accepts portable connection metadata without credentials`() {
        BackupSecretPolicy.requireSecretFree(
            mapOf(
                "settings_general_cloud" to
                    """{"cloud_webdav_url_v1":"https://dav.example.test/","cloud_webdav_user_v1":"user"}""",
                "settings_ai_core" to
                    """{"ai_api_url_v1":"https://api.example.test/","ai_text_model_v1":"model"}"""
            )
        )
    }

    @Test
    fun `rejects shared ledger password module`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupSecretPolicy.requireSecretFree(
                mapOf(BackupModuleId.SHARED_SECRETS to "[]")
            )
        }
    }

    @Test
    fun `rejects known secret fields at any nesting level`() {
        listOf(
            "ai_api_key_v1",
            "ai_provider_keys_v1",
            "ai_api_key_enc_v1",
            "ai_provider_keys_enc_v1",
            "cloud_webdav_pass_v1",
            "webDavPassword"
        ).forEach { key ->
            assertThrows("should reject $key", IllegalArgumentException::class.java) {
                BackupSecretPolicy.requireSecretFree(
                    mapOf("fixture" to """{"nested":{"$key":"secret"}}""")
                )
            }
        }
    }

    @Test
    fun `rejects common credential key variants`() {
        listOf(
            "Password",
            "apiKey",
            "access_token",
            "client-secret",
            "private_key",
            "Authorization",
            "credentials"
        ).forEach { key ->
            assertThrows("should reject $key", IllegalArgumentException::class.java) {
                BackupSecretPolicy.requireSecretFree(
                    mapOf("fixture" to """{"$key":"secret"}""")
                )
            }
        }
    }

    @Test
    fun `removes URL user info query and fragment from portable modules`() {
        val fixtures = mapOf(
            "settings_ai_core" to
                ("ai_api_url_v1" to "https://user:pass@example.test/v1?api_key=secret#fragment"),
            "settings_general_cloud" to
                ("cloud_webdav_url_v1" to "https://user:pass@dav.example.test/dav/?token=secret"),
            BackupModuleId.SHARED_LEDGERS to
                ("webdavUrl" to "https://user:pass@dav.example.test/shared/#secret")
        )

        fixtures.forEach { (module, fixture) ->
            val (field, url) = fixture
            val source = if (module == BackupModuleId.SHARED_LEDGERS) {
                """[{"name":"keep","$field":"$url"}]"""
            } else {
                """{"name":"keep","$field":"$url"}"""
            }
            val sanitized = BackupSecretPolicy.sanitizePortableModule(module, source)
            org.junit.Assert.assertFalse(sanitized.contains("user:pass"))
            org.junit.Assert.assertFalse(sanitized.contains("secret"))
            org.junit.Assert.assertTrue(sanitized.contains("\"name\":\"keep\""))
        }
    }
}
