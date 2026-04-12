package tao.test.flipaccounting

import android.content.Intent
import android.service.quicksettings.TileService
import android.os.Build

class QuickStartTileService : TileService() {
    
    override fun onClick() {
        super.onClick()
        
        Logger.d(this, "QuickStartTile", "Tile clicked. Trying to start OverlayService...")

        // 直接调起悬浮窗 Service，不使用任何 Activity 中转
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Logger.d(this, "QuickStartTile", "Service start requested successfully.")
        } catch (e: Exception) {
            Logger.d(this, "QuickStartTile", "Error starting service: ${e.message}")
            e.printStackTrace()
            Utils.toast(this, "悬浮窗唤起失败: ${e.message}")
        }
        
        // Android 12 (API 31) 以上禁止在后台发送 ACTION_CLOSE_SYSTEM_DIALOGS，
        // 这里尝试使用兼容的无痛收起方式，如果失败也不应该崩溃。
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+：如果目标不是 Activity，可以用这种特殊的欺骗方式关闭：
                // 启动一个啥也不做的透明 PendingIntent 来触发 Collapse
                val blankIntent = Intent()
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this, 0, blankIntent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                // Android 12/13：这确实是个真空期，如果不弹 Activity，很难主动关闭状态栏。
                // 我们妥协：只能让用户自己划上去，但保证不会崩溃和不会弹出应用主界面。
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
