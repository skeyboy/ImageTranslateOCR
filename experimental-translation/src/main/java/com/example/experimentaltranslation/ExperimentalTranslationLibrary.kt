package com.example.experimentaltranslation

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

class ExperimentalTranslationLibrary(context: Context) : Closeable {
    private val appContext = context.applicationContext
    val models = ExperimentalModelRepository(appContext)
    private val providerMutex = Mutex()
    private var provider: ExperimentalTranslationProvider? = null

    fun status(engine: ExperimentalTranslationEngine): ExperimentalModelStatus =
        models.status(engine)

    suspend fun translate(
        engine: ExperimentalTranslationEngine,
        request: ExperimentalTranslationRequest
    ): ExperimentalTranslationResult {
        require(engine != ExperimentalTranslationEngine.DISABLED)
        val status = models.status(engine)
        if (status.state != ExperimentalModelState.READY) {
            throw ExperimentalModelUnavailableException(
                status.message ?: "${engine.name} model is not installed"
            )
        }
        return providerFor(engine).translate(request)
    }

    suspend fun unload() = providerMutex.withLock {
        provider?.close()
        provider = null
    }

    private suspend fun providerFor(
        engine: ExperimentalTranslationEngine
    ): ExperimentalTranslationProvider = providerMutex.withLock {
        provider?.takeIf { it.engine == engine } ?: run {
            provider?.close()
            when (engine) {
                ExperimentalTranslationEngine.MARIAN_INT8 -> MarianOnnxTranslationProvider(
                    models.modelDirectory(engine)
                )
                ExperimentalTranslationEngine.TRANSLATEGEMMA_4B -> TranslateGemmaProvider(
                    appContext,
                    models.modelDirectory(engine)
                )
                ExperimentalTranslationEngine.DISABLED -> error("Disabled engine cannot translate")
            }.also { provider = it }
        }
    }

    override fun close() {
        provider?.close()
        provider = null
    }
}
