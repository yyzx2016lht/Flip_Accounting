package tao.test.flipaccounting

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Transparent bridge Activity for runtime permissions requested from service-owned UI.
 */
class PermissionRequestActivity : Activity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001

        var onPermissionResult: ((Boolean) -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            onPermissionResult?.invoke(granted)
        }
        finish()
    }
}
