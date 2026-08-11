package com.taostudio.tapaccounting

import android.content.Context
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.io.Reader

object JsonUtils {
    fun getBuiltInCategories(context: Context): List<BuiltInCategory> {
        return try {
            context.resources.openRawResource(R.raw.category).use { inputStream ->
                InputStreamReader(inputStream).use(::parseBuiltInCategories)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    internal fun parseBuiltInCategories(reader: Reader): List<BuiltInCategory> {
        val root = JsonParser.parseReader(reader)
        if (!root.isJsonArray) return emptyList()

        return root.asJsonArray.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val item = element.asJsonObject
            val name = item.stringValue("name").trim()
            val icon = item.stringValue("icon").trim()
            if (name.isEmpty() || icon.isEmpty()) {
                null
            } else {
                BuiltInCategory(
                    name = name,
                    icon = icon,
                    type = item.stringValue("type").trim()
                )
            }
        }
    }

    private fun com.google.gson.JsonObject.stringValue(key: String): String {
        val value = get(key) ?: return ""
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else ""
    }
}
