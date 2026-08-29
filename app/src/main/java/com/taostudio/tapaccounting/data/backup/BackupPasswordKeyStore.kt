package com.taostudio.tapaccounting.data.backup

import android.content.Context
import android.util.Base64

class BackupPasswordKeyUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Remembers only the PIN-derived key in the app's private, non-backup preferences.
 *
 * The original PIN is never stored. This deliberately avoids Android Keystore so normal and
 * automatic backups remain available on devices with vendor-specific Keystore behaviour.
 */
object BackupPasswordKeyStore {
    private const val PREFS = "backup_password_key"
    private const val KEY_DERIVED = "derived_key_v2"
    private const val KEY_SALT = "kdf_salt_v2"
    private const val KEY_ITERATIONS = "kdf_iterations_v2"

    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_DERIVED)

    fun configure(context: Context, pin: String): BackupPasswordKeyMaterial {
        val material = BackupPasswordCrypto.create(pin)
        store(context, material)
        return material
    }

    fun load(context: Context): BackupPasswordKeyMaterial? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keyRaw = prefs.getString(KEY_DERIVED, null) ?: return null
        val saltRaw = prefs.getString(KEY_SALT, null)
            ?: throw BackupPasswordKeyUnavailableException("本机备份密钥缺少盐值，请重新设置备份密码")
        return try {
            BackupPasswordKeyMaterial(
                keyBytes = Base64.decode(keyRaw, Base64.NO_WRAP),
                parameters = BackupPasswordKdfParameters(
                    salt = Base64.decode(saltRaw, Base64.NO_WRAP),
                    iterations = prefs.getInt(KEY_ITERATIONS, 0)
                )
            ).also(BackupPasswordKeyMaterial::requireValid)
        } catch (error: Exception) {
            throw BackupPasswordKeyUnavailableException("无法读取本机备份密钥，请重新设置备份密码", error)
        }
    }

    /** Stores a derived key; callers must only call this after PIN authentication succeeded. */
    fun store(context: Context, material: BackupPasswordKeyMaterial) {
        material.requireValid()
        val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DERIVED, Base64.encodeToString(material.keyBytes, Base64.NO_WRAP))
            .putString(KEY_SALT, Base64.encodeToString(material.parameters.salt, Base64.NO_WRAP))
            .putInt(KEY_ITERATIONS, material.parameters.iterations)
            // Remove incomplete fields left by the abandoned Android Keystore implementation.
            .remove("wrapped_key_v1")
            .remove("nonce_v1")
            .remove("kdf_salt_v1")
            .remove("kdf_iterations_v1")
            .commit()
        if (!committed) throw BackupPasswordKeyUnavailableException("无法保存本机备份密钥")
    }
}
