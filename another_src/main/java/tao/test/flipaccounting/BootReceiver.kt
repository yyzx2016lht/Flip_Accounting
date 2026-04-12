package tao.test.flipaccounting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        // 只有当用户开启了“翻转触发”开关时，才进行复活
        if (!Prefs.isFlipEnabled(context)) return

        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED == action || 
            Intent.ACTION_MY_PACKAGE_REPLACED == action ||
            "tao.test.flipaccounting.RESTART_SERVICE" == action) {

            // 启动前台服务
            val serviceIntent = Intent(context, OverlayService::class.java).apply {
                this.action = OverlayService.ACTION_START_FLIP
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}