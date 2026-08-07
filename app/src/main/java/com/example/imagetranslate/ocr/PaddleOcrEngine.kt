package com.example.imagetranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRBox
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.floor

internal class PaddleOcrEngine(context: Context) : OcrEngine {
    private val appContext = context.applicationContext
    private val engineMutex = Mutex()
    private var delegate: PaddleOCR? = null

    private suspend fun delegateLocked(): PaddleOCR = delegate ?: PaddleOCR.create(
        context = appContext,
        config = PaddleOCRConfig(
            detLimitSideLen = DETECTION_LONG_EDGE_PX,
            detLimitType = "max",
            detMaxSideLimit = DETECTION_LONG_EDGE_PX,
            recBatchSize = RECOGNITION_BATCH_SIZE
        ),
        engineConfig = EngineConfig(
            numThreads = inferenceThreads(),
            interOpNumThreads = 1,
            parallelExecution = false
        )
    ).also { delegate = it }

    override suspend fun recognize(
        bitmap: Bitmap,
        recognitionMode: OcrRecognitionMode
    ): List<RecognizedText> = recognizeInternal(bitmap, recognitionMode)

    override suspend fun recognizeFast(
        bitmap: Bitmap,
        recognitionMode: OcrRecognitionMode
    ): List<RecognizedText> = recognizeInternal(bitmap, recognitionMode)

    private suspend fun recognizeInternal(
        bitmap: Bitmap,
        recognitionMode: OcrRecognitionMode
    ): List<RecognizedText> = engineMutex.withLock {
        val paddle = delegateLocked()
        paddle.recognize(bitmap).results
            .asSequence()
            .filter { it.text.isNotBlank() }
            .map { result ->
                RecognizedText(
                    text = result.text.trim(),
                    bounds = result.box.toRect(bitmap.width, bitmap.height),
                    consensusScore = result.confidence.coerceIn(0f, 1f),
                    modelConfidence = result.confidence.coerceIn(0f, 1f),
                    recognizerScript = recognitionMode.recognizerScript
                )
            }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .toList()
    }

    override suspend fun modelStates(): Map<OcrModel, OcrModelState> =
        OcrModel.entries.associateWith { OcrModelState.READY }

    override suspend fun downloadModel(
        model: OcrModel,
        onState: (OcrModel, OcrModelState) -> Unit
    ) {
        onState(model, OcrModelState.READY)
    }

    override suspend fun ensureModels(
        models: Set<OcrModel>,
        onState: (OcrModel, OcrModelState) -> Unit
    ) {
        models.forEach { onState(it, OcrModelState.CHECKING) }
        engineMutex.withLock { delegateLocked() }
        models.forEach { onState(it, OcrModelState.READY) }
    }

    override fun close() {
        val paddle = delegate ?: return
        delegate = null
        runBlocking { paddle.release() }
    }

    private fun OCRBox.toRect(width: Int, height: Int): Rect {
        val left = floor(points.minOf { it.x }.toDouble()).toInt().coerceIn(0, width - 1)
        val top = floor(points.minOf { it.y }.toDouble()).toInt().coerceIn(0, height - 1)
        val right = ceil(points.maxOf { it.x }.toDouble()).toInt().coerceIn(left + 1, width)
        val bottom = ceil(points.maxOf { it.y }.toDouble()).toInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private companion object {
        const val DETECTION_LONG_EDGE_PX = 1_280
        const val RECOGNITION_BATCH_SIZE = 4
        const val MAX_INTRA_OP_THREADS = 6
    }

    private fun inferenceThreads(): Int = Runtime.getRuntime()
        .availableProcessors()
        .coerceIn(2, MAX_INTRA_OP_THREADS)
}
