package com.taostudio.tapaccounting

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build

object RecentTasksHelper {
    fun applyHideRecentsPreference(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val shouldHide = Prefs.isHideRecents(activity)
        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return
        try {
            activityManager.appTasks.forEach { task ->
                try {
                    task.setExcludeFromRecents(shouldHide)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}
