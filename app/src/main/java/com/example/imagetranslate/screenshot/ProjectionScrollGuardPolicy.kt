package com.example.imagetranslate.screenshot

internal object ProjectionScrollGuardPolicy {
    fun shouldForwardScroll(sourcePackage: String?, ownPackage: String): Boolean {
        val normalized = sourcePackage?.trim().orEmpty()
        return normalized.isNotEmpty() &&
            normalized != ownPackage &&
            normalized != SYSTEM_UI_PACKAGE
    }

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
}
