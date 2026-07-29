package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class DeterministicOverlayRenderingMatrixTest {
    @Test
    fun rendersFiftyDeterministicViewportsWithCalibratedInputProfiles() = runBlocking {
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
                    val seed = zeroBasedSeed + 1
                    val scene = DeterministicSceneFactory.create(category, seed)
                    val ideal = renderProfile(
                        processor = processor,
                        scene = scene,
                        inputRegions = scene.regions,
                        expectedRegions = scene.regions,
                        droppedRegionCount = 0,
                        retainedSourceRegionCount = 0
                    )
                    val calibratedInput = CurrentCapabilityCalibration.apply(
                        scene.regions,
                        caseNumber
                    )
                    val calibrated = renderProfile(
                        processor = processor,
                        scene = scene,
                        inputRegions = calibratedInput.regions,
                        expectedRegions = scene.regions,
                        droppedRegionCount = calibratedInput.droppedRegionCount,
                        retainedSourceRegionCount = calibratedInput.retainedSourceRegionCount
                    )

                    val prefix = "%03d-%s".format(caseNumber, category.id)
                    savePng(File(outputDirectory, "$prefix-source.png"), scene.bitmap)
                    savePng(File(outputDirectory, "$prefix-ideal.png"), ideal.preview)
                    savePng(File(outputDirectory, "$prefix-current.png"), calibrated.preview)

                    val report = JSONObject()
                        .put("case", caseNumber)
                        .put("category", category.id)
                        .put("seed", seed)
                        .put("width", scene.bitmap.width)
                        .put("height", scene.bitmap.height)
                        .put("expected_regions", scene.regions.size)
                        .put("protected_regions", scene.protectedRegions.size)
                        .put("ideal", ideal.json)
                        .put("current_calibrated", calibrated.json)
                        .put(
                            "calibration",
                            JSONObject()
                                .put("source", "2026-07-29 live scroll telemetry")
                                .put("observed_regions", OBSERVED_REGION_COUNT)
                                .put("observed_failed_regions", OBSERVED_FAILED_REGION_COUNT)
                                .put("observed_failure_ratio", OBSERVED_FAILURE_RATIO)
                                .put("observed_retained_latin_p90", OBSERVED_RETAINED_LATIN_P90)
                        )
                    reports += report.toString()

                    ideal.preview.recycle()
                    calibrated.preview.recycle()
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

    private suspend fun renderProfile(
        processor: BackgroundTranslatedImageProcessor,
        scene: DeterministicScene,
        inputRegions: List<LiveDeterministicTranslationRegion>,
        expectedRegions: List<LiveDeterministicTranslationRegion>,
        droppedRegionCount: Int,
        retainedSourceRegionCount: Int
    ): RenderProfileEvidence {
        val result = processor.renderDeterministicOverlay(
            bitmap = scene.bitmap,
            regions = inputRegions,
            overlayAlpha = OVERLAY_ALPHA,
            executionProfile = LiveRecognitionExecutionProfile(
                contextProfile = LiveDifferentialContextProfile.ACCURACY,
                renderingMode = LivePatchRenderingMode.PARALLEL,
                backgroundMode = LivePatchBackgroundMode.STANDARD
            )
        )
        val patchBounds = result.patches.map { Rect(it.bounds) }
        val visual = LiveRecognitionVisualArtifactRenderer.render(
            source = scene.bitmap,
            patches = result.patches,
            compositeAlpha = OVERLAY_ALPHA
        )
        val coveredRegions = expectedRegions.count { region ->
            patchBounds.any { patch -> patch.contains(region.bounds) }
        }
        val protectedUnchangedRatio = unchangedRatio(
            scene.bitmap,
            visual.bitmap,
            scene.protectedRegions
        )
        val chromeUnchangedRatio = unchangedRatio(
            scene.bitmap,
            visual.bitmap,
            scene.chromeRegions
        )
        val clippedCount = result.renderedText.count(LiveRenderedTextEvidence::clipped)
        val minimumTextScale = result.renderedText.minOfOrNull { it.textScale } ?: 0f
        val minimumContrast = result.renderedText.minOfOrNull { it.contrastRatio } ?: 0f
        val protectedPatchIntersections = patchBounds.count { patch ->
            scene.protectedRegions.any { protected -> Rect.intersects(patch, protected) }
        }
        val coverageRatio = coveredRegions.toFloat() / expectedRegions.size.coerceAtLeast(1)
        val pass = result.failedRegionCount == 0 &&
            coverageRatio == 1f &&
            clippedCount == 0 &&
            minimumTextScale >= MINIMUM_TEXT_SCALE &&
            minimumContrast >= MINIMUM_CONTRAST_RATIO &&
            protectedUnchangedRatio >= MINIMUM_PROTECTED_UNCHANGED_RATIO &&
            chromeUnchangedRatio >= MINIMUM_CHROME_UNCHANGED_RATIO &&
            protectedPatchIntersections == 0 &&
            visual.changedOutsidePatchCount == 0

        val json = JSONObject()
            .put("input_regions", inputRegions.size)
            .put("rendered_regions", result.renderedRegionCount)
            .put("failed_regions", result.failedRegionCount)
            .put("dropped_input_regions", droppedRegionCount)
            .put("retained_source_regions", retainedSourceRegionCount)
            .put("coverage_ratio", coverageRatio)
            .put("clipped_regions", clippedCount)
            .put("minimum_text_scale", minimumTextScale)
            .put("minimum_contrast_ratio", minimumContrast)
            .put("protected_unchanged_ratio", protectedUnchangedRatio)
            .put("chrome_unchanged_ratio", chromeUnchangedRatio)
            .put("protected_patch_intersections", protectedPatchIntersections)
            .put("changed_outside_patch_samples", visual.changedOutsidePatchCount)
            .put("rendering_ms", result.renderingMs)
            .put("visual_artifact_pass", visual.passesVisualGate)
            .put("pass", pass)

        result.patches.recyclePatchBitmaps()
        return RenderProfileEvidence(visual.bitmap, json)
    }

    private fun unchangedRatio(source: Bitmap, output: Bitmap, regions: List<Rect>): Float {
        var unchanged = 0L
        var sampled = 0L
        regions.forEach { region ->
            val left = region.left.coerceIn(0, source.width)
            val top = region.top.coerceIn(0, source.height)
            val right = region.right.coerceIn(left, source.width)
            val bottom = region.bottom.coerceIn(top, source.height)
            for (y in top until bottom step PROTECTED_SAMPLE_STEP_PX) {
                for (x in left until right step PROTECTED_SAMPLE_STEP_PX) {
                    sampled++
                    if (source.getPixel(x, y) == output.getPixel(x, y)) unchanged++
                }
            }
        }
        return if (sampled == 0L) 1f else unchanged.toFloat() / sampled
    }

    private fun savePng(file: File, bitmap: Bitmap) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private data class RenderProfileEvidence(
        val preview: Bitmap,
        val json: JSONObject
    )

    private companion object {
        const val OUTPUT_DIRECTORY_NAME = "deterministic-render"
        const val RESULTS_FILE_NAME = "results.jsonl"
        const val CASES_PER_CATEGORY = 10
        const val EXPECTED_CASE_COUNT = 50
        const val OVERLAY_ALPHA = 0.72f
        const val MINIMUM_TEXT_SCALE = 0.5f
        const val MINIMUM_CONTRAST_RATIO = 4.5f
        const val MINIMUM_PROTECTED_UNCHANGED_RATIO = 0.99f
        const val MINIMUM_CHROME_UNCHANGED_RATIO = 0.999f
        const val PROTECTED_SAMPLE_STEP_PX = 6
        const val OBSERVED_REGION_COUNT = 466
        const val OBSERVED_FAILED_REGION_COUNT = 17
        const val OBSERVED_FAILURE_RATIO = 0.036480687
        const val OBSERVED_RETAINED_LATIN_P90 = 0.057142857
    }
}

internal enum class SceneCategory(val id: String) {
    LIGHT_BODY("light-body"),
    DARK_BODY("dark-body"),
    NON_TEXT_MIXED("non-text-mixed"),
    LANDSCAPE_EDGE("landscape-edge"),
    LENGTH_STRESS("length-stress")
}

internal data class DeterministicScene(
    val bitmap: Bitmap,
    val regions: List<LiveDeterministicTranslationRegion>,
    val protectedRegions: List<Rect>,
    val chromeRegions: List<Rect>
)

private data class CalibratedInput(
    val regions: List<LiveDeterministicTranslationRegion>,
    val droppedRegionCount: Int,
    val retainedSourceRegionCount: Int
)

private object CurrentCapabilityCalibration {
    fun apply(
        regions: List<LiveDeterministicTranslationRegion>,
        caseNumber: Int
    ): CalibratedInput {
        var dropped = 0
        var retained = 0
        val calibrated = regions.mapIndexedNotNull { index, region ->
            val globalRegion = (caseNumber - 1) * MAXIMUM_REGIONS_PER_CASE + index + 1
            if (globalRegion % FAILURE_INTERVAL == 0) {
                dropped++
                return@mapIndexedNotNull null
            }
            if (globalRegion % RETAINED_SOURCE_INTERVAL == 0) {
                retained++
                val retainedToken = region.sourceText
                    .split(Regex("\\s+"))
                    .firstOrNull { token -> token.any(Char::isLetter) }
                    .orEmpty()
                region.copy(
                    translation = listOf(region.translation, retainedToken)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                )
            } else {
                region
            }
        }
        return CalibratedInput(calibrated, dropped, retained)
    }

    private const val MAXIMUM_REGIONS_PER_CASE = 8
    private const val FAILURE_INTERVAL = 27
    private const val RETAINED_SOURCE_INTERVAL = 18
}

internal object DeterministicSceneFactory {
    fun create(category: SceneCategory, seed: Int): DeterministicScene {
        val landscape = category == SceneCategory.LANDSCAPE_EDGE
        val width = if (landscape) 1920 else 1080
        val height = if (landscape) 1080 else 1920
        val dark = category == SceneCategory.DARK_BODY
        val pageColor = if (dark) Color.rgb(18, 21, 26) else {
            val tint = seed % 4
            Color.rgb(246 - tint, 248 - tint, 250 - tint)
        }
        val surfaceColor = if (dark) Color.rgb(31, 35, 42) else Color.WHITE
        val textColor = if (dark) Color.rgb(238, 241, 245) else Color.rgb(26, 29, 34)
        val mutedColor = if (dark) Color.rgb(177, 185, 196) else Color.rgb(82, 91, 104)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(pageColor)
        val chrome = drawChrome(canvas, width, height, dark, seed)
        val builder = SceneBuilder(canvas, textColor, mutedColor, surfaceColor)

        when (category) {
            SceneCategory.LIGHT_BODY,
            SceneCategory.DARK_BODY -> builder.bodyArticle(width, height, seed)
            SceneCategory.NON_TEXT_MIXED -> builder.nonTextMixed(width, height, seed)
            SceneCategory.LANDSCAPE_EDGE -> builder.landscapeEdge(width, height, seed)
            SceneCategory.LENGTH_STRESS -> builder.lengthStress(width, height, seed)
        }
        val protected = chrome + builder.protectedRegions
        return DeterministicScene(bitmap, builder.regions, protected, chrome)
    }

    private fun drawChrome(
        canvas: Canvas,
        width: Int,
        height: Int,
        dark: Boolean,
        seed: Int
    ): List<Rect> {
        val topHeight = if (width > height) 150 else 210
        val bottomHeight = if (width > height) 76 else 96
        val chromeColor = if (dark) Color.rgb(10, 12, 15) else Color.rgb(252, 252, 253)
        val barColor = if (dark) Color.rgb(40, 44, 51) else Color.rgb(235, 238, 242)
        val iconColor = if (dark) Color.rgb(225, 229, 235) else Color.rgb(48, 53, 61)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = chromeColor
        canvas.drawRect(0f, 0f, width.toFloat(), topHeight.toFloat(), paint)
        canvas.drawRect(0f, (height - bottomHeight).toFloat(), width.toFloat(), height.toFloat(), paint)
        paint.color = barColor
        canvas.drawRoundRect(
            width * 0.12f,
            if (width > height) 42f else 86f,
            width * 0.88f,
            if (width > height) 116f else 168f,
            24f,
            24f,
            paint
        )
        paint.color = iconColor
        paint.strokeWidth = 8f
        canvas.drawLine(44f, 44f, 88f, 44f, paint)
        canvas.drawCircle(width - 64f, 44f, 14f + seed % 3, paint)
        val bottomCenter = height - bottomHeight / 2f
        canvas.drawCircle(width * 0.2f, bottomCenter, 13f, paint)
        canvas.drawCircle(width * 0.5f, bottomCenter, 13f, paint)
        canvas.drawCircle(width * 0.8f, bottomCenter, 13f, paint)
        return listOf(Rect(0, 0, width, topHeight), Rect(0, height - bottomHeight, width, height))
    }
}

private class SceneBuilder(
    private val canvas: Canvas,
    private val textColor: Int,
    private val mutedColor: Int,
    private val surfaceColor: Int
) {
    val regions = mutableListOf<LiveDeterministicTranslationRegion>()
    val protectedRegions = mutableListOf<Rect>()

    fun bodyArticle(width: Int, height: Int, seed: Int) {
        text(
            Rect(64, 264, width - 64, 370),
            "Reliable systems begin with explicit state",
            "可靠的系统始于明确状态",
            52f,
            bold = true
        )
        text(
            Rect(64, 400, width - 64, 512),
            "Every transition has one owner and one visible outcome.",
            "每次状态转换都应有唯一负责人和清晰可见的结果。",
            34f,
            color = mutedColor
        )
        divider(width, 552)
        text(
            Rect(64, 610, width - 64, 790),
            "Retries carry context forward instead of repeating isolated work.",
            "重试应继承上下文，而不是重复执行孤立任务。",
            37f
        )
        text(
            Rect(64, 840, width - 64, 1045),
            "A stale result must be discarded even when its computation completed successfully.",
            "即使计算成功完成，过期结果仍必须被丢弃。",
            37f
        )
        note(Rect(64, 1110, width - 64, 1360), seed)
        text(
            Rect(88, 1164, width - 88, 1308),
            "Stable interfaces respond immediately and present each update as one group.",
            "稳定界面应立即响应，并将每次更新作为一个整体呈现。",
            34f
        )
        text(
            Rect(64, 1430, width - 64, min(height - 140, 1680)),
            "Images, charts, controls, and navigation remain part of the meaning.",
            "图片、图表、控件和导航始终是页面语义的一部分。",
            36f
        )
    }

    fun nonTextMixed(width: Int, height: Int, seed: Int) {
        text(
            Rect(64, 260, width - 64, 360),
            "Operational evidence",
            "运行证据",
            54f,
            bold = true
        )
        text(
            Rect(64, 392, width - 64, 500),
            "The chart remains visible while surrounding labels are translated.",
            "翻译周围标签时，图表本身必须保持清晰可见。",
            34f,
            color = mutedColor
        )
        val chart = Rect(70, 560, width - 70, 1050)
        chart(chart, seed)
        protectedRegions += chart
        text(
            Rect(70, 1110, width - 70, 1240),
            "Median presentation latency",
            "呈现延迟中位数",
            38f,
            bold = true
        )
        text(
            Rect(70, 1270, width - 70, 1410),
            "Non-text pixels are protected from translation materials.",
            "非文字像素不应被翻译材料覆盖。",
            35f
        )
        val icon = Rect(width - 220, 1460, width - 80, 1600)
        icon(icon)
        protectedRegions += icon
        text(
            Rect(70, 1480, width - 260, min(height - 130, 1650)),
            "Protected visual marker",
            "受保护的视觉标记",
            32f
        )
    }

    fun landscapeEdge(width: Int, height: Int, seed: Int) {
        text(
            Rect(54, 195, 1110, 290),
            "Translation at wide and narrow boundaries",
            "宽窄边界下的翻译排版",
            48f,
            bold = true
        )
        text(
            Rect(54, 330, 900, 475),
            "The primary column keeps a calm reading rhythm after rotation.",
            "旋转屏幕后，主内容列仍应保持稳定的阅读节奏。",
            34f
        )
        text(
            Rect(54, 520, 1040, 685),
            "Text near the viewport edge must remain inside the visible material.",
            "靠近视口边缘的文字必须完整保留在可见材料内。",
            34f
        )
        val chart = Rect(1220, 230, width - 54, 720)
        chart(chart, seed)
        protectedRegions += chart
        text(
            Rect(1220, 760, width - 26, 850),
            "Right edge status",
            "右侧边缘状态",
            31f,
            bold = true
        )
        text(
            Rect(54, 745, 1040, height - 98),
            "Source and translated views share the same spatial anchor.",
            "原文与译文视图应共享同一个空间锚点。",
            33f
        )
    }

    fun lengthStress(width: Int, height: Int, seed: Int) {
        text(
            Rect(64, 260, width - 64, 360),
            "Translation length stress",
            "译文长度压力验证",
            52f,
            bold = true
        )
        val boxes = listOf(
            Triple("Continue", "继续", 0.5f),
            Triple("Save changes", "保存所有更改", 1f),
            Triple("Review current state", "查看并确认当前运行状态", 1.5f),
            Triple(
                "Retry interrupted operation",
                "重新执行刚才被中断但仍保留完整上下文的操作",
                2f
            ),
            Triple(
                "Restore workspace",
                "恢复工作区、原有滚动位置以及全部尚未提交的上下文信息",
                2.5f
            )
        )
        boxes.forEachIndexed { index, (source, translation, ratio) ->
            val top = 430 + index * 250
            surface(Rect(58, top - 24, width - 58, top + 170), seed + index)
            text(
                Rect(86, top, width - 86, top + 126),
                source,
                translation,
                if (ratio <= 1f) 42f else 35f,
                bold = ratio <= 1f
            )
        }
    }

    private fun note(bounds: Rect, seed: Int) {
        surface(bounds, seed)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 137, 122)
        }
        canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.left + 10f, bounds.bottom.toFloat(), paint)
    }

    private fun surface(bounds: Rect, seed: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (seed % 2 == 0) surfaceColor else blend(surfaceColor, Color.rgb(120, 132, 148), 0.06f)
        }
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            12f,
            12f,
            paint
        )
    }

    private fun divider(width: Int, y: Int) {
        val paint = Paint().apply { color = blend(surfaceColor, mutedColor, 0.22f) }
        canvas.drawRect(64f, y.toFloat(), width - 64f, y + 3f, paint)
        protectedRegions += Rect(64, y, width - 64, y + 3)
    }

    private fun chart(bounds: Rect, seed: Int) {
        surface(bounds, seed)
        val colors = intArrayOf(
            Color.rgb(21, 135, 122),
            Color.rgb(31, 111, 214),
            Color.rgb(205, 59, 48),
            Color.rgb(48, 135, 55)
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val gap = bounds.width() / 16
        val barWidth = (bounds.width() - gap * 5) / 4
        colors.forEachIndexed { index, color ->
            val left = bounds.left + gap + index * (barWidth + gap)
            val barHeight = bounds.height() * (0.38f + ((index + seed) % 4) * 0.12f)
            paint.color = color
            canvas.drawRect(
                left.toFloat(),
                bounds.bottom - 42f - barHeight,
                (left + barWidth).toFloat(),
                bounds.bottom - 42f,
                paint
            )
        }
        paint.color = mutedColor
        canvas.drawRect(
            bounds.left + 24f,
            bounds.bottom - 44f,
            bounds.right - 24f,
            bounds.bottom - 40f,
            paint
        )
    }

    private fun icon(bounds: Rect) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 135, 122) }
        canvas.drawRoundRect(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            20f, 20f, paint
        )
        paint.color = Color.WHITE
        paint.strokeWidth = 10f
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(bounds.centerX().toFloat(), bounds.centerY().toFloat(), 38f, paint)
    }

    private fun text(
        bounds: Rect,
        source: String,
        translation: String,
        textSize: Float,
        bold: Boolean = false,
        color: Int = textColor
    ) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        var layout = sourceLayout(source, paint, bounds.width())
        while (layout.height > bounds.height() && paint.textSize > MINIMUM_SOURCE_TEXT_SIZE_PX) {
            paint.textSize = (paint.textSize * 0.9f).coerceAtLeast(MINIMUM_SOURCE_TEXT_SIZE_PX)
            layout = sourceLayout(source, paint, bounds.width())
        }
        val sourceWithLineBreaks = (0 until layout.lineCount).joinToString("\n") { line ->
            source.substring(layout.getLineStart(line), layout.getLineEnd(line)).trim()
        }
        layout = sourceLayout(sourceWithLineBreaks, paint, bounds.width())
        val actualBounds = Rect(
            bounds.left,
            bounds.top,
            bounds.right,
            min(bounds.bottom, bounds.top + layout.height + SOURCE_BOUNDS_BOTTOM_PADDING_PX)
        )
        canvas.save()
        canvas.clipRect(actualBounds)
        canvas.translate(actualBounds.left.toFloat(), actualBounds.top.toFloat())
        layout.draw(canvas)
        canvas.restore()
        regions += LiveDeterministicTranslationRegion(
            sourceWithLineBreaks,
            translation,
            actualBounds
        )
    }

    private fun sourceLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.12f)
            .build()

    private fun blend(first: Int, second: Int, amount: Float): Int {
        val inverse = 1f - amount
        return Color.rgb(
            (Color.red(first) * inverse + Color.red(second) * amount).toInt(),
            (Color.green(first) * inverse + Color.green(second) * amount).toInt(),
            (Color.blue(first) * inverse + Color.blue(second) * amount).toInt()
        )
    }

    private companion object {
        const val MINIMUM_SOURCE_TEXT_SIZE_PX = 24f
        const val SOURCE_BOUNDS_BOTTOM_PADDING_PX = 4
    }
}
