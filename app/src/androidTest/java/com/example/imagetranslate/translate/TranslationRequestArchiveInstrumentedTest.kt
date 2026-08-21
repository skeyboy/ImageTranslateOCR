package com.example.imagetranslate.translate

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

class TranslationRequestArchiveInstrumentedTest {
    @Test
    fun exportsImportableZipToPublicDocuments(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requestId = UUID.randomUUID().toString()
        TranslationBackendSettings.setRequestArchiveExportEnabled(context, true)
        try {
            val request = JSONObject()
                .put("schemaVersion", 4)
                .put("requestId", requestId)
                .put("sessionId", "archive-test-session")
                .put("generation", 1)
                .put("translationRevision", 1)
            TranslationRequestArchiveStore.recordExchange(
                context = context,
                requestJson = request.toString(),
                responseJson = JSONObject()
                    .put("schemaVersion", 4)
                    .put("requestId", requestId)
                    .toString(),
                provider = "openlux",
                model = "gemini-test",
                timings = JSONObject()
                    .put("schemaVersion", 2)
                    .put("providerTotalMs", 123)
            )
            val stage = File(context.cacheDir, "translation-request-archives/$requestId")
            val displayName = File(stage, ".archive-name").readText().trim()
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cursor = context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null
            ) ?: error("Unable to query exported archive")
            val uri = cursor.use {
                check(it.moveToFirst()) { "Exported archive was not found" }
                assertTrue(it.getString(1).startsWith("Documents/ImageTranslateOCR"))
                ContentUris.withAppendedId(collection, it.getLong(0))
            }
            val names = context.contentResolver.openInputStream(uri).use { input ->
                ZipInputStream(checkNotNull(input)).use { zip ->
                    buildSet {
                        while (true) add(zip.nextEntry?.name ?: break)
                    }
                }
            }
            assertTrue(
                names.containsAll(
                    setOf("manifest.json", "request.json", "response.json", "timings.json")
                )
            )
            context.contentResolver.delete(uri, null, null)
        } finally {
            TranslationBackendSettings.setRequestArchiveExportEnabled(context, false)
        }
    }
}
