package tao.test.flipaccounting.ui.common

import android.graphics.Color
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

object StatusBarStyle {

    fun applyByColor(
        window: Window,
        statusBarColor: Int,
        decorFitsSystemWindows: Boolean? = null,
    ) {
        val isLightBackground = isLightColor(statusBarColor)
        apply(window, statusBarColor, isLightBackground, decorFitsSystemWindows)
    }

    fun apply(
        window: Window,
        statusBarColor: Int,
        isLightBackground: Boolean,
        decorFitsSystemWindows: Boolean? = null,
    ) {
        if (decorFitsSystemWindows != null) {
            WindowCompat.setDecorFitsSystemWindows(window, decorFitsSystemWindows)
        }
        window.statusBarColor = statusBarColor
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = isLightBackground
    }

    private fun isLightColor(color: Int): Boolean {
        // Blend translucent colors onto white so alpha colors still produce a stable decision.
        val alpha = Color.alpha(color) / 255f
        val r = (Color.red(color) * alpha + 255f * (1f - alpha)).toInt()
        val g = (Color.green(color) * alpha + 255f * (1f - alpha)).toInt()
        val b = (Color.blue(color) * alpha + 255f * (1f - alpha)).toInt()
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return luminance >= 160
    }
}
