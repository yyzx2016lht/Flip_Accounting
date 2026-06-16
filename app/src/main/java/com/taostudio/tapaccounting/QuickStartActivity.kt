package com.taostudio.tapaccounting

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 快捷启动 Activity
 * 作为一个透明的 Activity 暴露给外部应用，用于调起悬浮窗。
 */
class QuickStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Logger.d(this, "QuickStartActivity", "Activity created. Trying to start OverlayService...")
        
        // 调起 OverlayService 显示悬浮窗
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
        }
        
        try {
            OverlayService.startCompat(this, intent)
            Logger.d(this, "QuickStartActivity", "Service start requested successfully.")
        } catch (e: Exception) {
            Logger.d(this, "QuickStartActivity", "Error starting service: ${e.message}")
            e.printStackTrace()
            Utils.toast(this, getString(R.string.overlay_permission))
        }
        
        // 瞬间完成并退出，不留下界面
        finish()
    }
}

