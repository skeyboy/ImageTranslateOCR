package com.example.imagetranslate.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPresentationResultTest {
    @Test
    fun detachedTranslationLayerCannotBeReportedAsPresented() {
        assertFalse(
            presentation(
                translationLayerAttached = false,
                acceptedPatchCount = 30,
                visiblePatchCount = 30
            ).presented
        )
    }

    @Test
    fun visiblePatchCountMustMatchAcceptedPatchCount() {
        assertFalse(
            presentation(
                acceptedPatchCount = 30,
                visiblePatchCount = 0
            ).presented
        )
    }

    @Test
    fun userHiddenTranslationIsAcceptedWithoutVisiblePatches() {
        assertTrue(
            presentation(
                translationVisible = false,
                acceptedPatchCount = 30,
                visiblePatchCount = 0
            ).presented
        )
    }

    @Test
    fun attachedEmptyResultCanCompleteNormally() {
        assertTrue(presentation().presented)
    }

    private fun presentation(
        translationLayerAttached: Boolean = true,
        translationVisible: Boolean = true,
        acceptedPatchCount: Int = 0,
        visiblePatchCount: Int = 0
    ) = OverlayPresentationResult(
        attemptCount = 1,
        controlAttached = true,
        translationLayerAttached = translationLayerAttached,
        translationVisible = translationVisible,
        acceptedPatchCount = acceptedPatchCount,
        visiblePatchCount = visiblePatchCount
    )
}
