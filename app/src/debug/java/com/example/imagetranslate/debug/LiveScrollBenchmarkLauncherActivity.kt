package com.example.imagetranslate.debug

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.example.imagetranslate.screenshot.LiveCaptureScenePreset
import com.example.imagetranslate.screenshot.LiveCaptureSettingsPolicy
import com.example.imagetranslate.screenshot.LiveCaptureSettingsPreferences
import com.example.imagetranslate.screenshot.LiveOcrRecognitionPreferences
import com.example.imagetranslate.screenshot.LiveOverlayExperienceMode
import com.example.imagetranslate.screenshot.LiveOverlayExperiencePreferences
import com.example.imagetranslate.screenshot.LivePatchBackgroundExperienceMode
import com.example.imagetranslate.screenshot.LivePatchBackgroundExperiencePreferences
import com.example.imagetranslate.screenshot.LiveSmartAssistPreferences
import com.example.imagetranslate.screenshot.OneShotScreenCaptureService

class LiveScrollBenchmarkLauncherActivity : ComponentActivity() {
    private val requestScreenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultData = result.data
        if (result.resultCode == Activity.RESULT_OK && resultData != null) {
            OneShotScreenCaptureService.start(
                context = this,
                resultCode = result.resultCode,
                resultData = resultData,
                startImmediately = true
            )
        }
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureDeterministicSession()
        if (savedInstanceState == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            requestScreenCapture.launch(manager.createScreenCaptureIntent())
        }
    }

    private fun configureDeterministicSession() {
        val backgroundMode = runCatching {
            LivePatchBackgroundExperienceMode.valueOf(
                intent.getStringExtra(EXTRA_BACKGROUND_MODE).orEmpty()
            )
        }.getOrDefault(LivePatchBackgroundExperienceMode.OFF)
        val experienceMode = runCatching {
            LiveOverlayExperienceMode.valueOf(
                intent.getStringExtra(EXTRA_EXPERIENCE_MODE).orEmpty()
            )
        }.getOrDefault(LiveOverlayExperienceMode.DEFAULT)
        LivePatchBackgroundExperiencePreferences.set(this, backgroundMode)
        LiveOverlayExperiencePreferences.setRequestedMode(
            this,
            experienceMode
        )
        LiveOcrRecognitionPreferences.set(this, OcrRecognitionMode.ENGLISH)
        LiveCaptureSettingsPreferences.set(
            this,
            LiveCaptureSettingsPolicy.forPreset(LiveCaptureScenePreset.READING)
        )
        LiveSmartAssistPreferences.setEnabled(this, false)
    }

    companion object {
        const val EXTRA_BACKGROUND_MODE = "background_mode"
        const val EXTRA_EXPERIENCE_MODE = "experience_mode"
    }
}
