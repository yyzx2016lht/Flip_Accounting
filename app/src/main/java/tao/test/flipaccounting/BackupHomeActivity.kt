package tao.test.flipaccounting

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class BackupHomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_home)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btn_open_local_backup).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_open_cloud_backup).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_quick_local).setOnClickListener {
            startActivity(
                Intent(this, BackupActivity::class.java)
                    .putExtra(BackupActivity.EXTRA_OPEN_SECTION, BackupActivity.SECTION_DO_BACKUP)
                    .putExtra(BackupActivity.EXTRA_QUICK_ONESHOT, true)
            )
        }
        findViewById<MaterialButton>(R.id.btn_quick_restore).setOnClickListener {
            startActivity(
                Intent(this, BackupActivity::class.java)
                    .putExtra(BackupActivity.EXTRA_OPEN_SECTION, BackupActivity.SECTION_RESTORE)
                    .putExtra(BackupActivity.EXTRA_QUICK_ONESHOT, true)
            )
        }
        findViewById<MaterialButton>(R.id.btn_quick_save_as).setOnClickListener {
            startActivity(
                Intent(this, BackupActivity::class.java)
                    .putExtra(BackupActivity.EXTRA_OPEN_SECTION, BackupActivity.SECTION_SAVE_AS)
                    .putExtra(BackupActivity.EXTRA_QUICK_ONESHOT, true)
            )
        }
        findViewById<MaterialButton>(R.id.btn_quick_csv).setOnClickListener {
            startActivity(
                Intent(this, BackupActivity::class.java)
                    .putExtra(BackupActivity.EXTRA_OPEN_SECTION, BackupActivity.SECTION_CSV)
                    .putExtra(BackupActivity.EXTRA_QUICK_ONESHOT, true)
            )
        }
    }
}
