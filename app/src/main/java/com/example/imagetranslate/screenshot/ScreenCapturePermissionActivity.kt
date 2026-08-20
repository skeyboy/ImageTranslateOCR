package com.example.imagetranslate.screenshot

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.imagetranslate.R

class ScreenCapturePermissionActivity : ComponentActivity() {
    private val requestScreenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultData = result.data
        if (result.resultCode == RESULT_OK && resultData != null) {
            finishAndRemoveTask()
            OneShotScreenCaptureService.start(
                context = this,
                resultCode = result.resultCode,
                resultData = resultData,
                startImmediately = true
            )
        } else {
            finishAndRemoveTask()
            OneShotScreenCaptureService.showOverlay(this)
            Toast.makeText(
                this,
                R.string.active_screenshot_cancelled,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                manager.createScreenCaptureIntent()
            }
            requestScreenCapture.launch(captureIntent)
        }
    }

    companion object {
        fun request(context: Context) {
            context.startActivity(
                Intent(context, ScreenCapturePermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
            )
        }
    }
}
