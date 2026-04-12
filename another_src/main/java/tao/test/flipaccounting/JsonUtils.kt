package tao.test.flipaccounting

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

object JsonUtils {
    fun getBuiltInCategories(context: Context): List<BuiltInCategory> {
        return try {
            // 假设你的 JSON 文件名是 categories.json
            val inputStream = context.resources.openRawResource(R.raw.category)
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<List<BuiltInCategory>>() {}.type
            Gson().fromJson(reader, type)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}