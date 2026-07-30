package com.example.imagetranslate.translate

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.experimentaltranslation.ExperimentalModelRepository
import com.example.experimentaltranslation.ExperimentalModelState
import com.example.experimentaltranslation.ExperimentalTranslationEngine
import com.example.experimentaltranslation.ExperimentalTranslationLibrary
import com.example.experimentaltranslation.ExperimentalTranslationRequest
import com.example.experimentaltranslation.TranslationLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarianExperimentalTranslationSmokeTest {
    @Test
    fun translatesChineseAndEnglishWithInstalledInt8Model() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(
            ExperimentalModelState.READY,
            ExperimentalModelRepository(context)
                .status(ExperimentalTranslationEngine.MARIAN_INT8)
                .state
        )

        ExperimentalTranslationLibrary(context).use { library ->
            val chineseToEnglish = library.translate(
                ExperimentalTranslationEngine.MARIAN_INT8,
                ExperimentalTranslationRequest(
                    text = "你好，欢迎使用图片翻译。",
                    sourceLanguage = TranslationLanguage.CHINESE,
                    targetLanguage = TranslationLanguage.ENGLISH
                )
            )
            val englishToChinese = library.translate(
                ExperimentalTranslationEngine.MARIAN_INT8,
                ExperimentalTranslationRequest(
                    text = "Hello, welcome to image translation.",
                    sourceLanguage = TranslationLanguage.ENGLISH,
                    targetLanguage = TranslationLanguage.CHINESE
                )
            )

            Log.i(
                TAG,
                "zh-en='${chineseToEnglish.text}', inferenceMs=${chineseToEnglish.inferenceMs}"
            )
            Log.i(
                TAG,
                "en-zh='${englishToChinese.text}', inferenceMs=${englishToChinese.inferenceMs}"
            )
            val normalizedEnglish = chineseToEnglish.text.lowercase()
            assertTrue(normalizedEnglish.contains("hello"))
            assertTrue(normalizedEnglish.contains("welcome"))
            assertTrue(englishToChinese.text.contains("欢迎"))
            assertTrue(englishToChinese.text.contains("翻译"))
        }
    }

    private companion object {
        const val TAG = "MarianSmokeTest"
    }
}
