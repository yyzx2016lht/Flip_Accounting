package tao.test.flipaccounting

import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CloudBackupActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "flip_cloud_backup_prefs"
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
            Utils.toast(this, "云端设置已保存")
        }

        findViewById<MaterialButton>(R.id.btn_test_cloud_connection).setOnClickListener {
            saveSettings()
            Utils.toast(this, "连接测试功能将在 WebDAV 接入后启用")
        }

        findViewById<MaterialButton>(R.id.btn_manual_upload).setOnClickListener {
            saveSettings()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "backup_${etDevice.text.toString().trim().ifBlank { "device" }}_lite_$ts.bak"
            Utils.toast(this, "手动上传已触发（预留）：$fileName")
        }

        findViewById<MaterialButton>(R.id.btn_manual_download).setOnClickListener {
            saveSettings()
            Utils.toast(this, "手动下载已触发（预留）")
        }

        findViewById<MaterialButton>(R.id.btn_show_cleanup_policy).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("云端保留策略")
                .setMessage(
                    "每台设备保留最近 10 份轻量备份 + 3 份完整备份。\n" +
                        "超出数量自动删除最老文件。\n\n" +
                        "当前为手动同步模式，不会后台自动上传。"
                )
                .setPositiveButton("我知道了", null)
                .show()
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
        etDir.setText(sp.getString(KEY_WEBDAV_DIR, "FlipAccounting") ?: "FlipAccounting")
        etDevice.setText(sp.getString(KEY_DEVICE_NAME, android.os.Build.MODEL ?: "android") ?: "android")
    }
}

