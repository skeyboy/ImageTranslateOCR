package com.example.imagetranslate.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRecognitionModeTest {
    @Test
    fun autoModeRequiresBothModelsAndUsesFusedRecognition() {
        assertEquals(OcrModel.entries.toSet(), OcrRecognitionMode.AUTO.requiredModels)
        assertEquals(setOf(OcrModel.ENGLISH), OcrRecognitionMode.AUTO.startupModels)
        assertEquals(RecognizerScript.FUSED, OcrRecognitionMode.AUTO.recognizerScript)
    }

    @Test
    fun fixedModesOnlyRequireTheirOwnModel() {
        assertEquals(setOf(OcrModel.CHINESE), OcrRecognitionMode.CHINESE.requiredModels)
        assertEquals(setOf(OcrModel.CHINESE), OcrRecognitionMode.CHINESE.startupModels)
        assertEquals(RecognizerScript.CHINESE, OcrRecognitionMode.CHINESE.recognizerScript)
        assertEquals(setOf(OcrModel.ENGLISH), OcrRecognitionMode.ENGLISH.requiredModels)
        assertEquals(setOf(OcrModel.ENGLISH), OcrRecognitionMode.ENGLISH.startupModels)
        assertEquals(RecognizerScript.LATIN, OcrRecognitionMode.ENGLISH.recognizerScript)
    }
}
