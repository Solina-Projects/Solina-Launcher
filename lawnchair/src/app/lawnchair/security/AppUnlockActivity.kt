package app.lawnchair.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.android.launcher3.R

/** Uses Android's device-credential UI; Solina never stores a PIN or password. */
class AppUnlockActivity : ComponentActivity() {
    private var targetIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetIntent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_TARGET_INTENT)
        }
        if (targetIntent == null) {
            finish()
            return
        }

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val confirmation = keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.app_lock_unlock_title),
            getString(R.string.app_lock_unlock_subtitle),
        )
        if (confirmation == null) {
            Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(confirmation, REQUEST_CONFIRM_DEVICE)
    }

    @Deprecated("Uses device credential confirmation for API 26+ compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIRM_DEVICE && resultCode == Activity.RESULT_OK) {
            targetIntent?.let { startActivity(it) }
        }
        finish()
    }

    companion object {
        const val EXTRA_TARGET_INTENT = "app.lawnchair.security.EXTRA_TARGET_INTENT"
        private const val REQUEST_CONFIRM_DEVICE = 7142
    }
}
