package com.taostudio.tapaccounting

import android.content.Context
import java.util.UUID

/**
 * 设备ID管理器
 * 用于生成和存储设备唯一标识
 */
object DeviceIdManager {
    private const val PREF_NAME = "device_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    /**
     * 获取设备ID
     * 首次调用时会生成一个新的UUID并存储
     */
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)

        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }

        return deviceId
    }
}
