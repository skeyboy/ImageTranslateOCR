package com.example.imagetranslate.screenshot

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imagetranslate.R
import com.example.imagetranslate.ui.ImageTranslateActivity
import org.hamcrest.Matchers.allOf
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenCaptureEntryTest {
    @Test
    fun dailyScreenshotControlsAreVisibleAndEnabled() {
        launchActivity().use {
            onView(withId(R.id.btnCaptureScreenshot)).check(
                matches(
                    allOf(
                        isDisplayed(),
                        isEnabled(),
                        isClickable(),
                        withText(R.string.action_capture_screenshot)
                    )
                )
            )
            onView(withId(R.id.switchScreenshotMonitor)).check(
                matches(
                    allOf(
                        isDisplayed(),
                        isEnabled(),
                        isClickable(),
                        withText(R.string.setting_screenshot_monitor)
                    )
                )
            )
            onView(withId(R.id.switchScreenshotAutoStart)).check(
                matches(
                    allOf(
                        isDisplayed(),
                        isNotEnabled(),
                        withText(R.string.setting_screenshot_auto_start)
                    )
                )
            )
            onView(withId(R.id.switchScreenshotOverlay)).check(
                matches(
                    allOf(
                        isDisplayed(),
                        isEnabled(),
                        isClickable(),
                        isNotChecked(),
                        withText(R.string.setting_screenshot_overlay)
                    )
                )
            )
            onView(withId(R.id.btnTranslate)).check(
                matches(withText(R.string.action_recognize_text))
            )
        }
    }

    @Test
    fun advancedSettingsExpandAndCollapseInPlace() {
        launchActivity().use {
            onView(withId(R.id.advancedSettingsPanel)).check(
                matches(withEffectiveVisibility(Visibility.GONE))
            )

            onView(withId(R.id.btnAdvancedSettings)).perform(click())
            onView(withId(R.id.advancedSettingsPanel)).check(matches(isDisplayed()))
            onView(withId(R.id.switchReviewBeforeTranslation)).check(
                matches(allOf(isDisplayed(), isNotChecked()))
            )

            onView(withId(R.id.btnAdvancedSettings)).perform(click())
            onView(withId(R.id.advancedSettingsPanel)).check(
                matches(withEffectiveVisibility(Visibility.GONE))
            )
        }
    }

    private fun launchActivity(): ActivityScenario<ImageTranslateActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("image_translate_ui", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("screenshot_monitor", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        return ActivityScenario.launch(ImageTranslateActivity::class.java)
    }
}
