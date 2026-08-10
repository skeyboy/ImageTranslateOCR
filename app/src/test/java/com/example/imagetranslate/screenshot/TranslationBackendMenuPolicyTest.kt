package com.example.imagetranslate.screenshot

import com.example.imagetranslate.translate.TranslationBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBackendMenuPolicyTest {
    @Test
    fun defaultsToLocalAndDisablesUnconfiguredNetwork() {
        val options = translationBackendMenuOptions(
            selectedBackend = TranslationBackend.LOCAL,
            networkConfigured = false
        )

        assertEquals(
            listOf(
                TranslationBackend.LOCAL,
                TranslationBackend.NETWORK,
                TranslationBackend.SELF_HOSTED,
                TranslationBackend.SELF_HOSTED_V4
            ),
            options.map { it.backend }
        )
        assertTrue(options.single { it.backend == TranslationBackend.LOCAL }.selected)
        assertTrue(options.single { it.backend == TranslationBackend.LOCAL }.enabled)
        assertFalse(options.single { it.backend == TranslationBackend.NETWORK }.selected)
        assertFalse(options.single { it.backend == TranslationBackend.NETWORK }.enabled)
        assertFalse(options.single { it.backend == TranslationBackend.SELF_HOSTED }.enabled)
        assertTrue(options.single { it.backend == TranslationBackend.SELF_HOSTED_V4 }.enabled)
    }

    @Test
    fun enablesAndSelectsConfiguredNetwork() {
        val options = translationBackendMenuOptions(
            selectedBackend = TranslationBackend.NETWORK,
            networkConfigured = true
        )

        assertFalse(options.single { it.backend == TranslationBackend.LOCAL }.selected)
        assertTrue(options.single { it.backend == TranslationBackend.NETWORK }.selected)
        assertTrue(options.single { it.backend == TranslationBackend.NETWORK }.enabled)
    }

    @Test
    fun enablesSelfHostedOnlyWhenItsOwnEndpointIsConfigured() {
        val options = translationBackendMenuOptions(
            selectedBackend = TranslationBackend.SELF_HOSTED,
            networkConfigured = false,
            selfHostedConfigured = true
        )

        assertTrue(options.single { it.backend == TranslationBackend.SELF_HOSTED }.selected)
        assertTrue(options.single { it.backend == TranslationBackend.SELF_HOSTED }.enabled)
        assertTrue(options.single { it.backend == TranslationBackend.SELF_HOSTED_V4 }.enabled)
        assertFalse(options.single { it.backend == TranslationBackend.NETWORK }.enabled)
    }

    @Test
    fun embeddedV4IsAvailableWithoutAnyNetworkConfiguration() {
        val options = translationBackendMenuOptions(
            selectedBackend = TranslationBackend.SELF_HOSTED_V4,
            networkConfigured = false,
            selfHostedConfigured = false
        )

        assertTrue(options.single { it.backend == TranslationBackend.SELF_HOSTED_V4 }.selected)
        assertTrue(options.single { it.backend == TranslationBackend.SELF_HOSTED_V4 }.enabled)
        assertFalse(options.single { it.backend == TranslationBackend.SELF_HOSTED }.enabled)
    }

    @Test
    fun integratedEngineFallsBackAndDisablesUnconfiguredPaddleNetwork() {
        assertEquals(
            LiveOcrTranslationEngineType.LOCAL_PIPELINE,
            resolveLiveOcrTranslationEngine(
                storedValue = LiveOcrTranslationEngineType.PADDLE_NETWORK.name,
                networkConfigured = false
            )
        )
        val options = liveOcrTranslationEngineMenuOptions(
            selectedEngine = LiveOcrTranslationEngineType.LOCAL_PIPELINE,
            paddleNetworkConfigured = false
        )
        assertTrue(
            options.single { it.engine == LiveOcrTranslationEngineType.LOCAL_PIPELINE }.enabled
        )
        assertFalse(
            options.single { it.engine == LiveOcrTranslationEngineType.PADDLE_NETWORK }.enabled
        )
    }

    @Test
    fun integratedEngineSelectsConfiguredPaddleNetwork() {
        assertEquals(
            LiveOcrTranslationEngineType.PADDLE_NETWORK,
            resolveLiveOcrTranslationEngine(
                storedValue = LiveOcrTranslationEngineType.PADDLE_NETWORK.name,
                networkConfigured = true
            )
        )
        val options = liveOcrTranslationEngineMenuOptions(
            selectedEngine = LiveOcrTranslationEngineType.PADDLE_NETWORK,
            paddleNetworkConfigured = true
        )
        assertTrue(
            options.single { it.engine == LiveOcrTranslationEngineType.PADDLE_NETWORK }.selected
        )
        assertTrue(
            options.single { it.engine == LiveOcrTranslationEngineType.PADDLE_NETWORK }.enabled
        )
    }

    @Test
    fun paddleNetworkUploadPreservesAspectRatioAndAvoidsUpscaling() {
        assertEquals(LiveOcrInputSize(320, 180), paddleNetworkUploadSize(320, 180))
        assertEquals(LiveOcrInputSize(720, 1600), paddleNetworkUploadSize(1440, 3200))
        assertEquals(LiveOcrInputSize(1600, 750), paddleNetworkUploadSize(3200, 1500))
        assertEquals(
            LiveOcrInputSize(576, 1280),
            paddleNetworkUploadSize(1440, 3200, maximumLongEdge = 1280)
        )
    }
}
