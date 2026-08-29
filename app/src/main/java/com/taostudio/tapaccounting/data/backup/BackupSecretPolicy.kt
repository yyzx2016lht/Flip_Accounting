package com.taostudio.tapaccounting.data.backup

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Guards the user-readable ZIP format against accidentally acquiring credentials later. */
object BackupSecretPolicy {
    private val forbiddenModules = setOf(BackupModuleId.SHARED_SECRETS)
    private val forbiddenNormalizedKeys = setOf(
        "aiapikeyv1",
        "aiproviderkeysv1",
        "aiapikeyencv1",
        "aiproviderkeysencv1",
        "cloudwebdavpassv1",
        "webdavpassword",
        "password",
        "passwd",
        "passphrase",
        "apikey",
        "accesskey",
        "accesstoken",
        "refreshtoken",
        "authtoken",
        "bearertoken",
        "token",
        "authorization",
        "clientsecret",
        "privatekey",
        "secret",
        "credential",
        "credentials"
    )
    private val urlFieldsByModule = mapOf(
        "settings_general_cloud" to setOf("cloud_webdav_url_v1"),
        "settings_ai_core" to setOf("ai_api_url_v1"),
        BackupModuleId.SHARED_LEDGERS to setOf("webdavUrl")
    )

    fun sanitizePortableModule(moduleName: String, json: String): String {
        val urlFields = urlFieldsByModule[moduleName] ?: return json
        val root = parse(moduleName, json)
        sanitizeUrls(root, urlFields)
        return root.toString()
    }

    fun requireSecretFree(jsonModules: Map<String, String>) {
        val forbiddenModule = jsonModules.keys.firstOrNull(forbiddenModules::contains)
        require(forbiddenModule == null) { "秘密模块不得写入备份：$forbiddenModule" }

        jsonModules.forEach { (moduleName, json) ->
            val root = parse(moduleName, json)
            val forbiddenKey = findForbiddenKey(root)
            require(forbiddenKey == null) {
                "备份模块 $moduleName 包含禁止写入的秘密字段：$forbiddenKey"
            }
        }
    }

    private fun findForbiddenKey(element: JsonElement): String? = when {
        element.isJsonObject -> {
            element.asJsonObject.entrySet().firstNotNullOfOrNull { (key, value) ->
                key.takeIf { normalizeKey(it) in forbiddenNormalizedKeys } ?: findForbiddenKey(value)
            }
        }
        element.isJsonArray -> element.asJsonArray.firstNotNullOfOrNull(::findForbiddenKey)
        else -> null
    }

    private fun sanitizeUrls(element: JsonElement, urlFields: Set<String>) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                if (key in urlFields && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    element.asJsonObject.addProperty(key, stripUrlCredentials(value.asString))
                } else {
                    sanitizeUrls(value, urlFields)
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach { sanitizeUrls(it, urlFields) }
        }
    }

    private fun stripUrlCredentials(value: String): String {
        val trimmed = value.trim()
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            ?: return stripUnparsedUrlCredentials(trimmed)
        val scheme = uri.scheme ?: return value.substringBefore('#').substringBefore('?')
        val authority = uri.rawAuthority
            ?: return "$scheme:${uri.rawSchemeSpecificPart.substringBefore('#').substringBefore('?')}"
        val safeAuthority = authority.substringAfterLast('@')
        return buildString {
            append(scheme)
            append("://")
            append(safeAuthority)
            append(uri.rawPath.orEmpty())
        }
    }

    private fun stripUnparsedUrlCredentials(value: String): String {
        val withoutQuery = value.substringBefore('?').substringBefore('#')
        val schemeEnd = withoutQuery.indexOf("://")
        if (schemeEnd < 0) return withoutQuery
        val authorityStart = schemeEnd + 3
        val pathStart = withoutQuery.indexOf('/', authorityStart).let { index ->
            if (index < 0) withoutQuery.length else index
        }
        val authority = withoutQuery.substring(authorityStart, pathStart).substringAfterLast('@')
        return withoutQuery.substring(0, authorityStart) + authority + withoutQuery.substring(pathStart)
    }

    private fun normalizeKey(key: String): String =
        key.lowercase().filter(Char::isLetterOrDigit)

    private fun parse(moduleName: String, json: String): JsonElement = try {
        JsonParser.parseString(json)
    } catch (error: Exception) {
        throw BackupFormatException("备份模块 $moduleName 不是有效 JSON", error)
    }
}
