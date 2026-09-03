package com.example.imagetranslate.screenshot

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

class ScreenTranslationAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        connectedService = WeakReference(this)
        notifyOverlayService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val sourcePackage = event?.packageName?.toString()
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            !ProjectionScrollGuardPolicy.shouldForwardScroll(sourcePackage, packageName) ||
            !OneShotScreenCaptureService.isRunning
        ) {
            return
        }
        ContextCompat.startForegroundService(
            this,
            android.content.Intent(this, OneShotScreenCaptureService::class.java).apply {
                action = OneShotScreenCaptureService.ACTION_ACCESSIBILITY_VIEW_SCROLLED
                putExtra(
                    OneShotScreenCaptureService.EXTRA_ACCESSIBILITY_SOURCE_PACKAGE,
                    sourcePackage
                )
                putExtra(
                    OneShotScreenCaptureService.EXTRA_ACCESSIBILITY_WINDOW_ID,
                    event.windowId
                )
            }
        )
    }

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

        fun visibleBrowserToolbarBounds(): List<Rect> {
            val service = connectedService?.get() ?: return emptyList()
            val roots = buildList {
                runCatching { service.windows }
                    .getOrDefault(emptyList())
                    .mapNotNullTo(this) { window -> window.root }
                runCatching { service.rootInActiveWindow }.getOrNull()?.let(::add)
            }.distinctBy { root -> root.windowId }
            return roots.flatMap { root ->
                val packageName = root.packageName?.toString().orEmpty()
                val toolbarIds = BROWSER_TOOLBAR_RESOURCE_IDS[packageName]
                    ?: return@flatMap emptyList()
                toolbarIds.mapNotNull { resourceId ->
                    root.findAccessibilityNodeInfosByViewId(resourceId)
                        .firstOrNull { it.isVisibleToUser }
                        ?.let { node ->
                            Rect().also(node::getBoundsInScreen).takeIf { bounds ->
                                bounds.right > bounds.left && bounds.bottom > bounds.top
                            }
                        }
                }
            }.distinctBy { bounds ->
                listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }

        private val BROWSER_TOOLBAR_RESOURCE_IDS = mapOf(
            "com.android.chrome" to listOf(
                "com.android.chrome:id/control_container",
                "com.android.chrome:id/bottom_container"
            ),
            "com.android.browser" to listOf(
                "com.android.browser:id/titleBar",
                "com.android.browser:id/bottom_bar"
            )
        )
    }
}
