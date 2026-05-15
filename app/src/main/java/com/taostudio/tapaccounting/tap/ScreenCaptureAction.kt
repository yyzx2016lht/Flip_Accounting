package com.taostudio.tapaccounting.tap

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.OverlayService

class ScreenCaptureAction : TapAction {
    override val id = "screen_capture"
    override val displayName = "截屏记账"
    override val description = "截取屏幕并识别账单"

    override fun execute(context: Context) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SCREEN_CAPTURE
        }
        OverlayService.startCompat(context, intent)
    }
}

