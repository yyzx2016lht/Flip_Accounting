package tao.test.tapaccounting.tap

import android.content.Context
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

enum class TapModel(
    val path: String,
    val screenInches: Double,
    val displayName: String
) {
    REDFIN("columbus/12/tap7cls_redfin.tflite", 6.0, "Pixel 5 (6.0寸)"),
    FLAME("columbus/12/tap7cls_flame.tflite", 5.7, "Pixel 4 (5.7寸)"),
    BRAMBLE("columbus/12/tap7cls_bramble.tflite", 6.2, "Pixel 4a 5G (6.2寸)"),
    CORAL("columbus/12/tap7cls_coral.tflite", 6.3, "Pixel 4 XL (6.3寸)");

    companion object {
        fun fromPath(path: String): TapModel? = values().find { it.path == path }

        fun recommend(context: Context): TapModel {
            val dm = context.resources.displayMetrics
            val wInches = dm.widthPixels / dm.xdpi
            val hInches = dm.heightPixels / dm.ydpi
            val diagonal = sqrt(wInches.toDouble().pow(2) + hInches.toDouble().pow(2))
            return values().minByOrNull { abs(it.screenInches - diagonal) } ?: CORAL
        }

        fun resolve(context: Context): TapModel {
            val saved = tao.test.tapaccounting.Prefs.getTapModel(context)
            return if (saved.isNotEmpty()) fromPath(saved) ?: recommend(context)
            else recommend(context)
        }
    }
}
