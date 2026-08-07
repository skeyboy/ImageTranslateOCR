package com.example.imagetranslate.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.ocr.OcrEngineSettings
import com.example.imagetranslate.ocr.OcrEngineType
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.semantic.SemanticTextGrouper
import com.example.imagetranslate.semantic.StaticImageTextFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SelfHostedActualPageTranslationInstrumentedTest {
    @Test
    fun recordsActualPageOcrGroupsTranslationsAndLayoutHints() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val imagePath = arguments.getString("validationImagePath")?.trim().orEmpty()
        val baseUrl = arguments.getString("selfHostedBaseUrl")?.trim().orEmpty()
        val expectSelfHostedResult = arguments
            .getString("expectSelfHostedResult")
            ?.toBooleanStrictOrNull()
            ?: true
        val uploadDebugCapture = arguments
            .getString("uploadDebugCapture")
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("validationImagePath instrumentation argument is required", imagePath.isNotEmpty())
        assumeTrue("selfHostedBaseUrl instrumentation argument is required", baseUrl.isNotEmpty())

        val context = instrumentation.targetContext
        OcrEngineSettings.set(context, OcrEngineType.ML_KIT)
        TranslationBackendSettings.setSelfHosted(context, baseUrl, "")
        TranslationBackendSettings.set(context, TranslationBackend.SELF_HOSTED)
        TranslationBackendSettings.setDebugCaptureUploadEnabled(context, uploadDebugCapture)

        val sourceBitmap = checkNotNull(BitmapFactory.decodeFile(imagePath)) {
            "Unable to decode validation image: $imagePath"
        }
        val ocrBitmap = createOcrBitmap(sourceBitmap)
        val ocrManager = OCRManager(context)
        val translateManager = TranslateManager(context)
        try {
            val ocrStartedAt = SystemClock.elapsedRealtime()
            val recognizedAtOcrScale = ocrManager.recognize(
                ocrBitmap,
                OcrRecognitionMode.AUTO
            )
            val recognized = mapRecognizedBounds(
                recognizedAtOcrScale,
                ocrBitmap,
                sourceBitmap
            )
            val ocrMs = SystemClock.elapsedRealtime() - ocrStartedAt
            val filtered = StaticImageTextFilter.filter(
                recognized,
                sourceBitmap.width,
                sourceBitmap.height
            )
            val groups = SemanticTextGrouper.group(
                filtered,
                sourceBitmap.width,
                sourceBitmap.height
            )
            val sources = groups.map { it.toSemanticTranslationSource() }

            val translationStartedAt = SystemClock.elapsedRealtime()
            val translations = translateManager.translateSemanticGroups(
                sources = sources,
                viewportWidth = sourceBitmap.width,
                viewportHeight = sourceBitmap.height,
                mode = TranslationMode.ENGLISH_TO_CHINESE,
                scene = if (uploadDebugCapture) "LIVE_SCREEN" else "STATIC_IMAGE_VALIDATION",
                debugCapture = if (uploadDebugCapture) {
                    SemanticDebugCaptureEncoder.encode(sourceBitmap)
                } else {
                    null
                }
            )
            val translationMs = SystemClock.elapsedRealtime() - translationStartedAt
            val translationsById = translations.associateBy(TranslationExecutionResult::regionId)
            val longBodies = sources.filter { source ->
                source.role == "BODY" && (
                    source.regions.size >= 4 ||
                        source.sourceText.length >= 60 ||
                        (source.bounds.right - source.bounds.left).toLong() *
                        (source.bounds.bottom - source.bounds.top) >=
                        sourceBitmap.width.toLong() * sourceBitmap.height * 3 / 100
                    )
            }
            val longBodyResults = longBodies.mapNotNull { source ->
                translationsById[source.groupId]?.let { result -> source to result }
            }
            val succeededLongBodies = longBodyResults.count { (_, result) -> result.succeeded }
            val safelyPreservedLongBodies = longBodyResults.count { (source, result) ->
                !result.succeeded && result.translatedText == source.sourceText &&
                    result.provider == "none"
            }
            val failedShortItems = translations.count { result ->
                !result.succeeded && longBodies.none { source -> source.groupId == result.regionId }
            }

            val report = JSONObject()
                .put("schemaVersion", 1)
                .put("event", "self_hosted_actual_page_translation")
                .put("source", JSONObject()
                    .put("path", imagePath)
                    .put("width", sourceBitmap.width)
                    .put("height", sourceBitmap.height))
                .put("service", JSONObject()
                    .put("baseUrl", baseUrl)
                    .put("expectedReachable", expectSelfHostedResult)
                    .put("debugCaptureUploaded", uploadDebugCapture)
                    .put("backend", TranslationBackendSettings.get(context).name))
                .put("ocr", JSONObject()
                    .put("engine", OcrEngineSettings.get(context).name)
                    .put("mode", OcrRecognitionMode.AUTO.name)
                    .put("elapsedMs", ocrMs)
                    .put("regionCount", recognized.size)
                    .put("regions", JSONArray(recognized.mapIndexed { index, region ->
                        regionJson(index, region)
                    })))
                .put("contentFilter", JSONObject()
                    .put("inputCount", recognized.size)
                    .put("includedCount", filtered.size)
                    .put("excludedCount", recognized.size - filtered.size)
                    .put("excludedRegions", JSONArray(
                        recognized.filterNot(filtered::contains).mapIndexed { index, region ->
                            regionJson(index, region)
                        }
                    )))
                .put("semanticGrouping", JSONObject()
                    .put("groupCount", groups.size)
                    .put("documentContext", sources.joinToString("\n") { source ->
                        "[${source.role}] ${source.sourceText}"
                    })
                    .put("groups", JSONArray(sources.map { source ->
                        val translation = translationsById[source.groupId]
                        JSONObject()
                            .put("groupId", source.groupId)
                            .put("role", source.role)
                            .put("translationUnit", source.translationUnit)
                            .put("sourceText", source.sourceText)
                            .put("memberRegionIds", JSONArray(source.memberRegionIds))
                            .put("readingOrder", source.readingOrder)
                            .put("groupingConfidence", source.groupingConfidence.toDouble())
                            .put("groupingEvidence", JSONArray(source.groupingEvidence))
                            .put("bounds", boundsJson(source.bounds.left, source.bounds.top,
                                source.bounds.right, source.bounds.bottom))
                            .put("layoutShape", source.layoutShape)
                            .put("renderSlots", JSONArray(source.renderSlots.map { slot ->
                                boundsJson(slot.left, slot.top, slot.right, slot.bottom)
                            }))
                            .put("regions", JSONArray(source.regions.map { region ->
                                JSONObject()
                                    .put("regionId", region.regionId)
                                    .put("text", region.text)
                                    .put("rawText", region.rawText)
                                    .put("corrections", JSONArray(region.corrections.map { correction ->
                                        JSONObject()
                                            .put("code", correction.code)
                                            .put("original", correction.original)
                                            .put("replacement", correction.replacement)
                                    }))
                                    .put("confidence", region.confidence.toDouble())
                                    .put("blockId", region.blockId ?: JSONObject.NULL)
                                    .put("lineIndex", region.lineIndex ?: JSONObject.NULL)
                                    .put("bounds", boundsJson(region.bounds.left, region.bounds.top,
                                        region.bounds.right, region.bounds.bottom))
                            }))
                            .put("translation", translation?.let(::translationJson) ?: JSONObject.NULL)
                    })))
                .put("translation", JSONObject()
                    .put("elapsedMs", translationMs)
                    .put("resultCount", translations.size)
                    .put("succeededCount", translations.count { it.succeeded })
                    .put("failedCount", translations.count { !it.succeeded })
                    .put("longBodyCount", longBodies.size)
                    .put("succeededLongBodyCount", succeededLongBodies)
                    .put("safelyPreservedLongBodyCount", safelyPreservedLongBodies)
                    .put("failedShortItemCount", failedShortItems))

            val output = File(
                context.filesDir,
                "validation/self-hosted-actual-page.json"
            )
            output.parentFile?.mkdirs()
            output.writeText(report.toString(2))

            assertTrue("No OCR regions recognized", recognized.isNotEmpty())
            assertTrue("No semantic groups created", groups.isNotEmpty())
            assertEquals(groups.size, translations.size)
            assertTrue("No long body was recognized", longBodies.isNotEmpty())
            assertTrue(
                "A failed long body was fragmented or locally translated instead of being preserved",
                longBodyResults.all { (source, result) ->
                    result.succeeded || (
                        result.translatedText == source.sourceText && result.provider == "none"
                        )
                }
            )
            if (expectSelfHostedResult) {
                assertTrue(
                    "Self-hosted service did not provide any group result",
                    translations.any { it.provider.startsWith("self-hosted-qwen") }
                )
            }
        } finally {
            translateManager.close()
            ocrManager.close()
            if (ocrBitmap !== sourceBitmap) ocrBitmap.recycle()
            sourceBitmap.recycle()
        }
    }

    private fun regionJson(index: Int, region: RecognizedText) = JSONObject()
        .put("regionId", "ocr-region-$index")
        .put("text", region.text)
        .put("bounds", boundsJson(region.bounds.left, region.bounds.top,
            region.bounds.right, region.bounds.bottom))
        .put("modelConfidence", region.modelConfidence.toDouble())
        .put("consensusScore", region.consensusScore.toDouble())
        .put("passCount", region.passCount)
        .put("recognizerScript", region.recognizerScript.name)
        .put("sourceBlockId", region.sourceBlockId ?: JSONObject.NULL)
        .put("sourceLineIndex", region.sourceLineIndex ?: JSONObject.NULL)

    private fun translationJson(result: TranslationExecutionResult) = JSONObject()
        .put("succeeded", result.succeeded)
        .put("translatedText", result.translatedText)
        .put("provider", result.provider)
        .put("failureCode", result.failure?.code ?: JSONObject.NULL)
        .put("layoutHint", result.layoutHint?.let { hint ->
            JSONObject()
                .put("preferredMaxLines", hint.preferredMaxLines)
                .put("minimumTextScale", hint.minimumTextScale.toDouble())
                .put("alignment", hint.alignment)
                .put("overflowStrategy", hint.overflowStrategy)
                .put("sourceLineCount", hint.sourceLineCount)
                .put("layoutShape", hint.layoutShape)
                .put("renderSlots", JSONArray(hint.renderSlots.map { slot ->
                    boundsJson(slot.left, slot.top, slot.right, slot.bottom)
                }))
        } ?: JSONObject.NULL)

    private fun createOcrBitmap(bitmap: Bitmap): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide >= 1_600) return bitmap
        val scale = minOf(3f, 3_072f / longestSide)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    private fun mapRecognizedBounds(
        regions: List<RecognizedText>,
        ocrBitmap: Bitmap,
        sourceBitmap: Bitmap
    ): List<RecognizedText> {
        if (ocrBitmap === sourceBitmap) return regions
        val scaleX = sourceBitmap.width.toFloat() / ocrBitmap.width
        val scaleY = sourceBitmap.height.toFloat() / ocrBitmap.height
        fun map(bounds: Rect) = Rect(
            (bounds.left * scaleX).toInt().coerceIn(0, sourceBitmap.width),
            (bounds.top * scaleY).toInt().coerceIn(0, sourceBitmap.height),
            (bounds.right * scaleX).toInt().coerceIn(0, sourceBitmap.width),
            (bounds.bottom * scaleY).toInt().coerceIn(0, sourceBitmap.height)
        )
        return regions.map { region ->
            region.copy(
                bounds = map(region.bounds),
                componentBounds = region.componentBounds.map(::map)
            )
        }
    }

    private fun boundsJson(left: Int, top: Int, right: Int, bottom: Int) = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)
}
