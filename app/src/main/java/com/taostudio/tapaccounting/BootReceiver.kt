package com.taostudio.tapaccounting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taostudio.tapaccounting.logic.InvestmentInterestWorker

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        if (Intent.ACTION_MY_PACKAGE_REPLACED == intent.action) {
            InvestmentInterestWorker.schedule(context.applicationContext)
        }

        val flipEnabled = Prefs.isFlipEnabled(context)
        val tapEnabled = Prefs.isDoubleTapEnabled(context)
        if (!flipEnabled && !tapEnabled) return

        Log.d(TAG, "onReceive: action=${intent.action}, flip=$flipEnabled, tap=$tapEnabled")

        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED == action ||
            Intent.ACTION_MY_PACKAGE_REPLACED == action ||
            "com.taostudio.tapaccounting.RESTART_SERVICE" == action) {

            // 交给 OverlayService 从 Prefs 恢复 tap 状态
            val serviceIntent = Intent(context, OverlayService::class.java)
            try {
                OverlayService.startCompat(context, serviceIntent)
            } catch (e: Exception) {
                Log.d(TAG, "start OverlayService failed: ${e.message}")
            }
        }
    }
}

