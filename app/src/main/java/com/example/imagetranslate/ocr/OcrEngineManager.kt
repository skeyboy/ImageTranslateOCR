package com.example.imagetranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Compatibility facade that routes existing OCR calls to the selected engine. */
internal class OCRManager(context: Context) : OcrEngine {
    private val appContext = context.applicationContext
    private val engineMutex = Mutex()
    private var activeType: OcrEngineType? = null
    private var activeEngine: OcrEngine? = null

    private fun engineLocked(): OcrEngine {
        val selected = OcrEngineSettings.get(appContext)
        if (activeType == selected) return requireNotNull(activeEngine)
        activeEngine?.close()
        return when (selected) {
            OcrEngineType.ML_KIT -> MlKitOcrEngine(appContext)
            OcrEngineType.PADDLE -> PaddleOcrEngine(appContext)
        }.also {
            activeType = selected
            activeEngine = it
        }
    }

    override suspend fun recognize(
        bitmap: Bitmap,
        recognitionMode: OcrRecognitionMode
    ): List<RecognizedText> = engineMutex.withLock {
        engineLocked().recognize(bitmap, recognitionMode)
    }

    override suspend fun recognizeFast(
        bitmap: Bitmap,
        recognitionMode: OcrRecognitionMode
    ): List<RecognizedText> = engineMutex.withLock {
        engineLocked().recognizeFast(bitmap, recognitionMode)
    }

    override suspend fun modelStates(): Map<OcrModel, OcrModelState> = engineMutex.withLock {
        engineLocked().modelStates()
    }

    override suspend fun downloadModel(
        model: OcrModel,
        onState: (OcrModel, OcrModelState) -> Unit
    ) = engineMutex.withLock { engineLocked().downloadModel(model, onState) }

    override suspend fun ensureModels(
        models: Set<OcrModel>,
        onState: (OcrModel, OcrModelState) -> Unit
    ) = engineMutex.withLock { engineLocked().ensureModels(models, onState) }

    override fun close() {
        runBlocking {
            engineMutex.withLock {
                activeEngine?.close()
                activeEngine = null
                activeType = null
            }
        }
    }
}
