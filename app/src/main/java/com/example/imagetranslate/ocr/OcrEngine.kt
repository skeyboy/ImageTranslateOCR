package com.example.imagetranslate.ocr

import android.graphics.Bitmap

/** Stable OCR contract shared by application workflows and replaceable engine adapters. */
internal interface OcrEngine : AutoCloseable {
    suspend fun recognize(
        bitmap: Bitmap,
        recognitionMode: OcrRecognitionMode = OcrRecognitionMode.AUTO
    ): List<RecognizedText>

    suspend fun recognizeFast(
        bitmap: Bitmap,
        recognitionMode: OcrRecognitionMode
    ): List<RecognizedText>

    suspend fun modelStates(): Map<OcrModel, OcrModelState>

    suspend fun downloadModel(
        model: OcrModel,
        onState: (OcrModel, OcrModelState) -> Unit = { _, _ -> }
    )

    suspend fun ensureModels(
        models: Set<OcrModel>,
        onState: (OcrModel, OcrModelState) -> Unit = { _, _ -> }
    )
}
