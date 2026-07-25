package com.example.imagetranslate.screenshot

import android.accessibilityservice.AccessibilityService
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

class ScreenTranslationAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        connectedService = WeakReference(this)
        notifyOverlayService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        clearConnectedService()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearConnectedService()
        super.onDestroy()
    }

    private fun clearConnectedService() {
        if (connectedService?.get() === this) connectedService = null
        notifyOverlayService()
    }

    private fun notifyOverlayService() {
        if (!OneShotScreenCaptureService.isRunning) return
        ContextCompat.startForegroundService(
            this,
            android.content.Intent(this, OneShotScreenCaptureService::class.java).apply {
                action = OneShotScreenCaptureService.ACTION_REFRESH_OVERLAY_MODE
            }
        )
    }

    companion object {
        @Volatile
        private var connectedService: WeakReference<ScreenTranslationAccessibilityService>? = null

        val isConnected: Boolean
            get() = connectedService?.get() != null

        fun windowManagerOrNull(): WindowManager? =
            connectedService?.get()?.getSystemService(WindowManager::class.java)
    }
}
