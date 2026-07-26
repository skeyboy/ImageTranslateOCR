package com.example.imagetranslate.ocr

import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes

internal enum class OcrRecognitionMode {
    AUTO,
    CHINESE,
    ENGLISH;

    val recognizerScript: RecognizerScript
        get() = when (this) {
            AUTO -> RecognizerScript.FUSED
            CHINESE -> RecognizerScript.CHINESE
            ENGLISH -> RecognizerScript.LATIN
        }

    val requiredModels: Set<OcrModel>
        get() = when (this) {
            AUTO -> OcrModel.entries.toSet()
            CHINESE -> setOf(OcrModel.CHINESE)
            ENGLISH -> setOf(OcrModel.ENGLISH)
        }
}

internal enum class OcrModel {
    CHINESE,
    ENGLISH
}

internal enum class OcrModelState {
    UNKNOWN,
    CHECKING,
    NOT_DOWNLOADED,
    DOWNLOADING,
    READY,
    FAILED
}

internal class OcrModelDownloadException(
    val statusCode: Int
) : IllegalStateException(
    "OCR model download failed: " +
        ModuleInstallStatusCodes.getStatusCodeString(statusCode) + " ($statusCode)"
)
