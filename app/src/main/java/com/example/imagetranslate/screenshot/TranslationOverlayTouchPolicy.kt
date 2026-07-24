package com.example.imagetranslate.screenshot

internal object TranslationOverlayTouchPolicy {
    private const val PREFERRED_ALPHA = 0.78f
    private const val SAFETY_MARGIN = 0.02f

    fun windowAlpha(maximumObscuringAlpha: Float): Float = minOf(
        PREFERRED_ALPHA,
        (maximumObscuringAlpha - SAFETY_MARGIN).coerceAtLeast(0f)
    )
}
