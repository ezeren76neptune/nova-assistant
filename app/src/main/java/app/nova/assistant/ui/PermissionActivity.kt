package app.nova.assistant.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.nova.assistant.core.AppLog

/** Invisible activity that pops the system permission dialog (usable from services and tools) and closes. */
class PermissionActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        AppLog.d("perm", "result: " + res.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = intent.getStringArrayExtra(EXTRA_PERMS)
            ?.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            .orEmpty()
        if (perms.isEmpty()) {
            finish(); return
        }
        if (savedInstanceState == null) launcher.launch(perms.toTypedArray())
    }

    companion object {
        const val EXTRA_PERMS = "perms"

        fun request(ctx: Context, perms: List<String>) {
            if (perms.isEmpty()) return
            try {
                ctx.startActivity(
                    Intent(ctx, PermissionActivity::class.java)
                        .putExtra(EXTRA_PERMS, perms.toTypedArray())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            } catch (t: Throwable) {
                AppLog.d("perm", "can't open permission prompt", t)
            }
        }
    }
}
