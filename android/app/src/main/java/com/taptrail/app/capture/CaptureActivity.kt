package com.taptrail.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class CaptureActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingCallback
        pendingCallback = null
        cb?.invoke(result.resultCode, result.data)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        val cb = pendingCallback
        pendingCallback = null
        cb?.invoke(Activity.RESULT_CANCELED, null)
        super.onDestroy()
    }

    companion object {
        private var pendingCallback: ((resultCode: Int, data: Intent?) -> Unit)? = null

        fun requestCapturePermission(
            context: Context,
            onResult: (resultCode: Int, data: Intent?) -> Unit
        ) {
            pendingCallback = onResult
            val intent = Intent(context, CaptureActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            context.startActivity(intent)
        }
    }
}
