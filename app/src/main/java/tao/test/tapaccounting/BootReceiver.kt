package tao.test.tapaccounting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val tapEnabled = Prefs.isDoubleTapEnabled(context)
        if (!tapEnabled) return

        Log.d(TAG, "onReceive: action=${intent.action}, tap=$tapEnabled")

        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED == action ||
            Intent.ACTION_MY_PACKAGE_REPLACED == action ||
            "tao.test.tapaccounting.RESTART_SERVICE" == action) {

            // 交给 OverlayService 从 Prefs 恢复 tap 状态
            val serviceIntent = Intent(context, OverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.d(TAG, "start OverlayService failed: ${e.message}")
            }
        }
    }
}
