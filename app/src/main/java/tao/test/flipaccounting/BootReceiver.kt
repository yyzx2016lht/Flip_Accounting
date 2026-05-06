package tao.test.flipaccounting

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

        val quickGestureEnabled = Prefs.isQuickGestureEnabled(context)
        val flipEnabled = Prefs.isFlipEnabled(context)
        val tapEnabled = Prefs.isDoubleTapEnabled(context)

        Log.d(TAG, "onReceive: action=${intent.action}, quickGesture=$quickGestureEnabled, flip=$flipEnabled, tap=$tapEnabled")

        if (!quickGestureEnabled || (!flipEnabled && !tapEnabled)) return

        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED == action ||
            Intent.ACTION_MY_PACKAGE_REPLACED == action ||
            "tao.test.flipaccounting.RESTART_SERVICE" == action) {

            // 发一个 Intent，让 OverlayService 在 onCreate 中自行从 Prefs 恢复检测
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
