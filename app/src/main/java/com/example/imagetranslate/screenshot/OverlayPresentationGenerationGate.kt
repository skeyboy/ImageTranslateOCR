package com.example.imagetranslate.screenshot

internal class OverlayPresentationGenerationGate {
    private var activeGeneration: Int? = null

    fun begin(generation: Int): Boolean {
        val active = activeGeneration
        if (active != null && generation < active) return false
        activeGeneration = generation
        return true
    }

    fun accepts(generation: Int): Boolean = activeGeneration == generation
}
