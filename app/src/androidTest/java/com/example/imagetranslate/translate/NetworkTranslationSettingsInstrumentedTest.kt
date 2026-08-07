package com.example.imagetranslate.translate

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imagetranslate.BuildConfig
import com.example.imagetranslate.R
import com.example.imagetranslate.ui.ImageTranslateActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkTranslationSettingsInstrumentedTest {
    @Test
    fun endpointCanBeEditedAndIsNormalizedForAllProviders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalBaseUrl = TranslationBackendSettings.networkBaseUrl(context)
        val uiPreferences = context.getSharedPreferences(UI_PREFERENCES, Context.MODE_PRIVATE)
        val advancedSettingsExpanded = uiPreferences.getBoolean(
            ADVANCED_SETTINGS_EXPANDED,
            false
        )
        uiPreferences.edit().putBoolean(ADVANCED_SETTINGS_EXPANDED, true).commit()
        val expectedBaseUrl = BuildConfig.REMOTE_TRANSLATION_BASE_URL.trimEnd('/')
        val fullEndpoint = "$expectedBaseUrl/api/v1/translate/regions"

        try {
            ActivityScenario.launch(ImageTranslateActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val endpointInput = activity.findViewById<EditText>(
                        R.id.editNetworkTranslationBaseUrl
                    )
                    endpointInput.setText(fullEndpoint)
                    endpointInput.onEditorAction(EditorInfo.IME_ACTION_DONE)
                    assertEquals(expectedBaseUrl, endpointInput.text.toString())
                }
            }

            assertEquals(expectedBaseUrl, TranslationBackendSettings.networkBaseUrl(context))
            assertEquals(
                fullEndpoint,
                TranslationBackendSettings.networkBatchEndpoint(context)
            )
        } finally {
            TranslationBackendSettings.setNetworkBaseUrl(context, originalBaseUrl)
            uiPreferences.edit()
                .putBoolean(ADVANCED_SETTINGS_EXPANDED, advancedSettingsExpanded)
                .commit()
        }
    }

    private companion object {
        const val UI_PREFERENCES = "image_translate_ui"
        const val ADVANCED_SETTINGS_EXPANDED = "advanced_settings_expanded"
    }
}
