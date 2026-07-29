package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imagetranslate.debug.LiveRecognitionVisualArtifactRenderer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class FeatheredMaterialRenderingMatrixTest {
    @Test
    fun comparesStandardThemeAndGaussianMaterialsAcrossFiftyViewports() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDirectory = requireNotNull(
            context.getExternalFilesDir(OUTPUT_DIRECTORY_NAME)
        )
        outputDirectory.deleteRecursively()
        assertTrue(outputDirectory.mkdirs())

        val processor = BackgroundTranslatedImageProcessor(context, reuseResources = true)
        val reports = mutableListOf<String>()
        try {
            var caseNumber = 0
            SceneCategory.entries.forEach { category ->
                repeat(CASES_PER_CATEGORY) { zeroBasedSeed ->
                    caseNumber++
                    val scene = DeterministicSceneFactory.create(category, zeroBasedSeed + 1)
                    val evidence = MaterialVariant.entries.associateWith { variant ->
                        renderVariant(processor, scene, variant)
                    }
                    val prefix = "%03d-%s".format(caseNumber, category.id)
                    savePng(File(outputDirectory, "$prefix-source.png"), scene.bitmap)
                    evidence.forEach { (variant, result) ->
                        savePng(
                            File(outputDirectory, "$prefix-${variant.id}.png"),
                            result.preview
                        )
                    }
                    reports += JSONObject()
                        .put("case", caseNumber)
                        .put("category", category.id)
                        .put("seed", zeroBasedSeed + 1)
                        .put("width", scene.bitmap.width)
                        .put("height", scene.bitmap.height)
                        .put("expected_regions", scene.regions.size)
                        .put("protected_regions", scene.protectedRegions.size)
                        .apply {
                            evidence.forEach { (variant, result) -> put(variant.id, result.json) }
                        }
                        .toString()

                    evidence.values.forEach { result -> result.preview.recycle() }
                    scene.bitmap.recycle()
                }
            }
        } finally {
            processor.close()
        }

        File(outputDirectory, RESULTS_FILE_NAME).writeText(
            reports.joinToString(separator = "\n", postfix = "\n")
        )
        assertEquals(EXPECTED_CASE_COUNT, reports.size)
        assertTrue(File(outputDirectory, RESULTS_FILE_NAME).length() > 0L)
    }

    private suspend fun renderVariant(
        processor: BackgroundTranslatedImageProcessor,
        scene: DeterministicScene,
        variant: MaterialVariant
    ): MaterialVariantEvidence {
        val profile = LiveRecognitionExecutionProfile(
            contextProfile = LiveDifferentialContextProfile.ACCURACY,
            renderingMode = LivePatchRenderingMode.PARALLEL,
            backgroundMode = variant.mode
        )
        val result = processor.renderDeterministicOverlay(
            bitmap = scene.bitmap,
            regions = scene.regions,
            overlayAlpha = variant.overlayAlpha,
            executionProfile = profile
        )
        val materialOnlyResult = processor.renderDeterministicOverlay(
            bitmap = scene.bitmap,
            regions = scene.regions.map { region -> region.copy(translation = " ") },
            overlayAlpha = variant.overlayAlpha,
            executionProfile = profile
        )
        val preview = LiveRecognitionVisualArtifactRenderer.render(
            source = scene.bitmap,
            patches = result.patches,
            compositeAlpha = variant.overlayAlpha
        )
        val materialPreview = LiveRecognitionVisualArtifactRenderer.render(
            source = scene.bitmap,
            patches = materialOnlyResult.patches,
            compositeAlpha = variant.overlayAlpha
        )
        val patchBounds = result.patches.map { Rect(it.bounds) }
        val coveredRegions = scene.regions.count { region ->
            patchBounds.any { patch -> patch.contains(region.bounds) }
        }
        val coverageRatio = coveredRegions.toFloat() / scene.regions.size.coerceAtLeast(1)
        val clippedRegions = result.renderedText.count(LiveRenderedTextEvidence::clipped)
        val minimumTextScale = result.renderedText.minOfOrNull { it.textScale } ?: 0f
        val minimumContrast = result.renderedText.minOfOrNull { it.contrastRatio } ?: 0f
        val protectedUnchanged = unchangedRatio(
            scene.bitmap,
            preview.bitmap,
            scene.protectedRegions
        )
        val chromeUnchanged = unchangedRatio(
            scene.bitmap,
            preview.bitmap,
            scene.chromeRegions
        )
        val protectedIntersections = patchBounds.count { patch ->
            scene.protectedRegions.any { protected -> Rect.intersects(patch, protected) }
        }
        val sourceDetail = detailEnergy(scene.bitmap, scene.regions.map { it.bounds })
        val materialDetail = detailEnergy(
            materialPreview.bitmap,
            scene.regions.map { it.bounds }
        )
        val detailRetention = if (sourceDetail <= 0f) {
            0f
        } else {
            (materialDetail / sourceDetail).coerceAtMost(1f)
        }
        val boundaryChangeRatio = boundaryChangeRatio(
            source = scene.bitmap,
            output = materialPreview.bitmap,
            patchBounds = materialOnlyResult.patches.map { it.bounds }
        )
        val changedAreaRatio = changedAreaRatio(scene.bitmap, materialPreview.bitmap)
        val structuralPass = result.failedRegionCount == 0 &&
            coverageRatio == 1f &&
            clippedRegions == 0 &&
            minimumTextScale >= MINIMUM_TEXT_SCALE &&
            minimumContrast >= MINIMUM_CONTRAST_RATIO &&
            protectedUnchanged >= MINIMUM_PROTECTED_UNCHANGED_RATIO &&
            chromeUnchanged >= MINIMUM_CHROME_UNCHANGED_RATIO &&
            protectedIntersections == 0 &&
            preview.changedOutsidePatchCount == 0
        val materialPass = detailRetention <= variant.maximumDetailRetention &&
            boundaryChangeRatio <= variant.maximumBoundaryChangeRatio
        val json = JSONObject()
            .put("background_mode", variant.mode.name)
            .put("overlay_alpha", variant.overlayAlpha)
            .put("rendered_regions", result.renderedRegionCount)
            .put("failed_regions", result.failedRegionCount)
            .put("coverage_ratio", coverageRatio)
            .put("clipped_regions", clippedRegions)
            .put("minimum_text_scale", minimumTextScale)
            .put("minimum_contrast_ratio", minimumContrast)
            .put("protected_unchanged_ratio", protectedUnchanged)
            .put("chrome_unchanged_ratio", chromeUnchanged)
            .put("protected_patch_intersections", protectedIntersections)
            .put("changed_outside_patch_samples", preview.changedOutsidePatchCount)
            .put("source_detail_energy", sourceDetail)
            .put("material_detail_energy", materialDetail)
            .put("source_detail_retention_ratio", detailRetention)
            .put("material_boundary_change_ratio", boundaryChangeRatio)
            .put("material_changed_area_ratio", changedAreaRatio)
            .put("rendering_ms", result.renderingMs)
            .put("material_only_rendering_ms", materialOnlyResult.renderingMs)
            .put("structural_pass", structuralPass)
            .put("material_pass", materialPass)
            .put("pass", structuralPass && materialPass)

        result.patches.recyclePatchBitmaps()
        materialOnlyResult.patches.recyclePatchBitmaps()
        materialPreview.bitmap.recycle()
        return MaterialVariantEvidence(preview.bitmap, json)
    }

    private fun unchangedRatio(source: Bitmap, output: Bitmap, regions: List<Rect>): Float {
        var unchanged = 0L
        var sampled = 0L
        regions.forEach { region ->
            for (y in region.top.coerceAtLeast(0) until region.bottom.coerceAtMost(source.height)
                step PROTECTED_SAMPLE_STEP_PX
            ) {
                for (x in region.left.coerceAtLeast(0) until region.right.coerceAtMost(source.width)
                    step PROTECTED_SAMPLE_STEP_PX
                ) {
                    sampled++
                    if (source.getPixel(x, y) == output.getPixel(x, y)) unchanged++
                }
            }
        }
        return if (sampled == 0L) 1f else unchanged.toFloat() / sampled
    }

    private fun detailEnergy(bitmap: Bitmap, bounds: List<Rect>): Float {
        var total = 0L
        var sampled = 0L
        bounds.forEach { region ->
            val right = region.right.coerceAtMost(bitmap.width - 1)
            val bottom = region.bottom.coerceAtMost(bitmap.height - 1)
            for (y in region.top.coerceAtLeast(0) until bottom step DETAIL_SAMPLE_STEP_PX) {
                for (x in region.left.coerceAtLeast(0) until right step DETAIL_SAMPLE_STEP_PX) {
                    val center = luminance(bitmap.getPixel(x, y))
                    total += abs(center - luminance(bitmap.getPixel(x + 1, y)))
                    total += abs(center - luminance(bitmap.getPixel(x, y + 1)))
                    sampled += 2
                }
            }
        }
        return total.toFloat() / sampled.coerceAtLeast(1)
    }

    private fun boundaryChangeRatio(
        source: Bitmap,
        output: Bitmap,
        patchBounds: List<Rect>
    ): Float {
        var changed = 0
        var sampled = 0
        patchBounds.forEach { bounds ->
            fun sample(x: Int, y: Int) {
                if (x <= 0 || y <= 0 || x >= source.width - 1 || y >= source.height - 1) return
                sampled++
                if (colorDistance(source.getPixel(x, y), output.getPixel(x, y)) >
                    BOUNDARY_COLOR_DISTANCE
                ) {
                    changed++
                }
            }
            for (x in bounds.left until bounds.right step BOUNDARY_SAMPLE_STEP_PX) {
                sample(x, bounds.top)
                sample(x, bounds.bottom - 1)
            }
            for (y in bounds.top until bounds.bottom step BOUNDARY_SAMPLE_STEP_PX) {
                sample(bounds.left, y)
                sample(bounds.right - 1, y)
            }
        }
        return if (sampled == 0) 0f else changed.toFloat() / sampled
    }

    private fun changedAreaRatio(source: Bitmap, output: Bitmap): Float {
        var changed = 0
        var sampled = 0
        for (y in 0 until source.height step AREA_SAMPLE_STEP_PX) {
            for (x in 0 until source.width step AREA_SAMPLE_STEP_PX) {
                sampled++
                if (colorDistance(source.getPixel(x, y), output.getPixel(x, y)) >
                    AREA_COLOR_DISTANCE
                ) {
                    changed++
                }
            }
        }
        return changed.toFloat() / sampled.coerceAtLeast(1)
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 54 + Color.green(color) * 183 + Color.blue(color) * 19) / 256

    private fun colorDistance(first: Int, second: Int): Int = maxOf(
        abs(Color.red(first) - Color.red(second)),
        abs(Color.green(first) - Color.green(second)),
        abs(Color.blue(first) - Color.blue(second))
    )

    private fun savePng(file: File, bitmap: Bitmap) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private data class MaterialVariantEvidence(
        val preview: Bitmap,
        val json: JSONObject
    )

    private enum class MaterialVariant(
        val id: String,
        val mode: LivePatchBackgroundMode,
        val overlayAlpha: Float,
        val maximumDetailRetention: Float,
        val maximumBoundaryChangeRatio: Float
    ) {
        STANDARD(
            id = "standard",
            mode = LivePatchBackgroundMode.STANDARD,
            overlayAlpha = STANDARD_OVERLAY_ALPHA,
            maximumDetailRetention = 1f,
            maximumBoundaryChangeRatio = 1f
        ),
        FEATHERED_THEME(
            id = "feathered_theme",
            mode = LivePatchBackgroundMode.FEATHERED_THEME_SURFACE,
            overlayAlpha = STANDARD_OVERLAY_ALPHA,
            maximumDetailRetention = 0.2f,
            maximumBoundaryChangeRatio = 0.15f
        ),
        FEATHERED_GAUSSIAN(
            id = "feathered_gaussian",
            mode = LivePatchBackgroundMode.FEATHERED_BLUR_TINT,
            overlayAlpha = STANDARD_OVERLAY_ALPHA,
            maximumDetailRetention = 0.35f,
            maximumBoundaryChangeRatio = 0.15f
        ),
        ENHANCED_THEME(
            id = "enhanced_theme",
            mode = LivePatchBackgroundMode.FEATHERED_THEME_SURFACE,
            overlayAlpha = ENHANCED_OVERLAY_ALPHA,
            maximumDetailRetention = 0.2f,
            maximumBoundaryChangeRatio = 0.15f
        ),
        ENHANCED_GAUSSIAN(
            id = "enhanced_gaussian",
            mode = LivePatchBackgroundMode.FEATHERED_BLUR_TINT,
            overlayAlpha = ENHANCED_OVERLAY_ALPHA,
            maximumDetailRetention = 0.35f,
            maximumBoundaryChangeRatio = 0.15f
        )
    }

    private companion object {
        const val OUTPUT_DIRECTORY_NAME = "feathered-material-render"
        const val RESULTS_FILE_NAME = "results.jsonl"
        const val CASES_PER_CATEGORY = 10
        const val EXPECTED_CASE_COUNT = 50
        const val STANDARD_OVERLAY_ALPHA = 0.72f
        const val ENHANCED_OVERLAY_ALPHA = 1f
        const val MINIMUM_TEXT_SCALE = 0.5f
        const val MINIMUM_CONTRAST_RATIO = 4.5f
        const val MINIMUM_PROTECTED_UNCHANGED_RATIO = 0.99f
        const val MINIMUM_CHROME_UNCHANGED_RATIO = 0.999f
        const val PROTECTED_SAMPLE_STEP_PX = 6
        const val DETAIL_SAMPLE_STEP_PX = 2
        const val BOUNDARY_SAMPLE_STEP_PX = 4
        const val BOUNDARY_COLOR_DISTANCE = 8
        const val AREA_SAMPLE_STEP_PX = 6
        const val AREA_COLOR_DISTANCE = 8
    }
}
