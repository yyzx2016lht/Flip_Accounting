package com.taostudio.tapaccounting

import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CloudBackupActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "tap_cloud_backup_prefs"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USER = "webdav_user"
        private const val KEY_WEBDAV_PASS = "webdav_pass"
        private const val KEY_WEBDAV_DIR = "webdav_dir"
        private const val KEY_DEVICE_NAME = "webdav_device_name"
    }

    private lateinit var etUrl: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etDir: EditText
    private lateinit var etDevice: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_backup)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        etUrl = findViewById(R.id.et_webdav_url)
        etUser = findViewById(R.id.et_webdav_user)
        etPass = findViewById(R.id.et_webdav_pass)
        etDir = findViewById(R.id.et_webdav_dir)
        etDevice = findViewById(R.id.et_device_name)

        loadSettings()

        findViewById<MaterialButton>(R.id.btn_save_cloud_settings).setOnClickListener {
            saveSettings()
            Utils.toast(this, getString(R.string.cloud_settings_saved))
        }

        findViewById<MaterialButton>(R.id.btn_test_cloud_connection).setOnClickListener {
            saveSettings()
            Utils.toast(this, getString(R.string.connection_test_pending))
        }

        findViewById<MaterialButton>(R.id.btn_manual_upload).setOnClickListener {
            saveSettings()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "backup_${etDevice.text.toString().trim().ifBlank { "device" }}_lite_$ts.bak"
            Utils.toast(this, getString(R.string.upload_triggered_fmt, fileName))
        }

        findViewById<MaterialButton>(R.id.btn_manual_download).setOnClickListener {
            saveSettings()
            Utils.toast(this, getString(R.string.download_triggered))
        }

        findViewById<MaterialButton>(R.id.btn_show_cleanup_policy).setOnClickListener {
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.cloud_retention_title))
                .setMessage(getString(R.string.backup_retain_policy_desc))
                .setPositiveButton(getString(R.string.got_it), null)
                .create()
            OverlayDialogs.showPageCenterDialog(
                dialog = dialog,
                ctx = this,
                cancelOnTouchOutside = true,
                useSolidPanelBackground = true
            )
        }
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_WEBDAV_URL, etUrl.text?.toString().orEmpty().trim())
            .putString(KEY_WEBDAV_USER, etUser.text?.toString().orEmpty().trim())
            .putString(KEY_WEBDAV_PASS, etPass.text?.toString().orEmpty())
            .putString(KEY_WEBDAV_DIR, etDir.text?.toString().orEmpty().trim())
            .putString(KEY_DEVICE_NAME, etDevice.text?.toString().orEmpty().trim())
            .apply()
    }

    private fun loadSettings() {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        etUrl.setText(sp.getString(KEY_WEBDAV_URL, "https://dav.jianguoyun.com/dav/") ?: "")
        etUser.setText(sp.getString(KEY_WEBDAV_USER, "") ?: "")
        etPass.setText(sp.getString(KEY_WEBDAV_PASS, "") ?: "")
        etDir.setText(sp.getString(KEY_WEBDAV_DIR, "TapAccount") ?: "TapAccount")
        etDevice.setText(sp.getString(KEY_DEVICE_NAME, android.os.Build.MODEL ?: "android") ?: "android")
    }
}

