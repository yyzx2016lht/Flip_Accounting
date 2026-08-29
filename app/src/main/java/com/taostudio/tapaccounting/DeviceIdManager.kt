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

    /**
     * Assigns a fresh identity before reconnecting a restored shared-ledger outbox. Old device IDs
     * are runtime state and must never be cloned from a backup.
     */
    fun rotateDeviceId(context: Context): String {
        val deviceId = UUID.randomUUID().toString()
        replaceDeviceId(context, deviceId)
        return deviceId
    }

    fun replaceDeviceId(context: Context, deviceId: String) {
        require(runCatching { UUID.fromString(deviceId).toString() == deviceId }.getOrDefault(false)) {
            "设备身份格式无效"
        }
        check(
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .commit()
        ) { "无法保存新的设备身份" }
    }
}
