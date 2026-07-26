package com.example.imagetranslate.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRecognitionModeTest {
    @Test
    fun autoModeRequiresBothModelsAndUsesFusedRecognition() {
        assertEquals(OcrModel.entries.toSet(), OcrRecognitionMode.AUTO.requiredModels)
        assertEquals(RecognizerScript.FUSED, OcrRecognitionMode.AUTO.recognizerScript)
    }

    @Test
    fun fixedModesOnlyRequireTheirOwnModel() {
        assertEquals(setOf(OcrModel.CHINESE), OcrRecognitionMode.CHINESE.requiredModels)
        assertEquals(RecognizerScript.CHINESE, OcrRecognitionMode.CHINESE.recognizerScript)
        assertEquals(setOf(OcrModel.ENGLISH), OcrRecognitionMode.ENGLISH.requiredModels)
        assertEquals(RecognizerScript.LATIN, OcrRecognitionMode.ENGLISH.recognizerScript)
    }
}
