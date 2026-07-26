package com.example.imagetranslate.debug

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveRecognitionBenchmarkManifestTest {
    @Test
    fun benchmarkCannotRemainAsAnInteractiveAppTask() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, LiveRecognitionBenchmarkActivity::class.java),
            PackageManager.GET_META_DATA
        )

        assertTrue(activityInfo.exported)
        assertTrue(activityInfo.taskAffinity.isNullOrEmpty())
        assertTrue(activityInfo.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        assertTrue(activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }
}
