package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import com.example.imagetranslate.ocr.RecognizedText

internal data class LiveOcrContentViewport(
    val bounds: Rect,
    val obscuredBounds: List<Rect> = emptyList()
)

internal data class LiveOcrContentFilterResult(
    val retained: List<RecognizedText>,
    val edgeFilteredCount: Int,
    val continuationCount: Int
)

internal object LiveOcrContentPolicy {
    private const val MINIMUM_VISIBLE_AREA_RATIO = 0.25f
    private const val EDGE_PROXIMITY_HEIGHT_MULTIPLIER = 1.25f
    private const val MINIMUM_EDGE_PROXIMITY_PX = 24
    private const val EDGE_RECOVERY_BAND_RATIO = 0.18f
    private const val MINIMUM_EDGE_RECOVERY_BAND_PX = 240
    private const val MAXIMUM_EDGE_RECOVERY_BAND_PX = 640
    private const val MINIMUM_EDGE_COVERAGE_CHARACTERS = 4
    private val BROWSER_ADDRESS_REGEX = Regex("[a-z0-9-]+\\.(com|org|net|io|dev)(/|$)")

    fun filter(
        recognized: List<RecognizedText>,
        viewport: LiveOcrContentViewport
    ): LiveOcrContentFilterResult {
        val retained = recognized.mapNotNull { item ->
            val itemArea = area(item.bounds).coerceAtLeast(1L)
            val contentArea = intersectionArea(item.bounds, viewport.bounds)
            val obscuredArea = viewport.obscuredBounds.sumOf { obscured ->
                intersectionArea(item.bounds, obscured)
            }.coerceAtMost(contentArea)
            val visibleArea = (contentArea - obscuredArea).coerceAtLeast(0L)
            if (visibleArea.toFloat() / itemArea < MINIMUM_VISIBLE_AREA_RATIO) {
                return@mapNotNull null
            }

            val itemHeight = (item.bounds.bottom - item.bounds.top).coerceAtLeast(1)
            val proximity = maxOf(
                MINIMUM_EDGE_PROXIMITY_PX,
                (itemHeight * EDGE_PROXIMITY_HEIGHT_MULTIPLIER).toInt()
            )
            item.copy(
                continuationAtTop = item.continuationAtTop ||
                    item.bounds.top <= viewport.bounds.top + proximity,
                continuationAtBottom = item.continuationAtBottom ||
                    item.bounds.bottom >= viewport.bounds.bottom - proximity
            )
        }
        return LiveOcrContentFilterResult(
            retained = retained,
            edgeFilteredCount = recognized.size - retained.size,
            continuationCount = retained.count {
                it.continuationAtTop || it.continuationAtBottom
            }
        )
    }

    fun recoveryBands(
        recognized: List<RecognizedText>,
        viewport: LiveOcrContentViewport
    ): List<Pair<Rect, Boolean>> {
        val contentHeight = (viewport.bounds.bottom - viewport.bounds.top).coerceAtLeast(1)
        val bandHeight = (contentHeight * EDGE_RECOVERY_BAND_RATIO).toInt()
            .coerceIn(MINIMUM_EDGE_RECOVERY_BAND_PX, MAXIMUM_EDGE_RECOVERY_BAND_PX)
            .coerceAtMost(contentHeight)
        val topBand = rectOf(
            viewport.bounds.left,
            viewport.bounds.top,
            viewport.bounds.right,
            viewport.bounds.top + bandHeight
        )
        val bottomBand = rectOf(
            viewport.bounds.left,
            viewport.bounds.bottom - bandHeight,
            viewport.bounds.right,
            viewport.bounds.bottom
        )
        val probeDepth = maxOf(96, bandHeight / 3).coerceAtMost(bandHeight)
        val topProbe = rectOf(
            topBand.left,
            topBand.top,
            topBand.right,
            topBand.top + probeDepth
        )
        val bottomProbe = rectOf(
            bottomBand.left,
            bottomBand.bottom - probeDepth,
            bottomBand.right,
            bottomBand.bottom
        )
        return buildList {
            if (!hasVisibleText(recognized, topProbe, viewport)) add(topBand to true)
            if (!hasVisibleText(recognized, bottomProbe, viewport)) add(bottomBand to false)
        }.distinctBy { (bounds, _) ->
            listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
    }

    fun isDuplicate(candidate: RecognizedText, existing: RecognizedText): Boolean {
        val overlap = intersectionArea(candidate.bounds, existing.bounds)
        val minimumArea = minOf(area(candidate.bounds), area(existing.bounds)).coerceAtLeast(1L)
        val candidateText = normalize(candidate.text)
        val existingText = normalize(existing.text)
        val textMatches = candidateText == existingText ||
            (candidateText.length >= 8 && existingText.contains(candidateText)) ||
            (existingText.length >= 8 && candidateText.contains(existingText))
        return overlap.toFloat() / minimumArea >= 0.6f && textMatches
    }

    private fun hasVisibleText(
        recognized: List<RecognizedText>,
        band: Rect,
        viewport: LiveOcrContentViewport
    ): Boolean = recognized.any { item ->
        !isLikelyBrowserToolbarText(item.text) &&
            item.text.count(Char::isLetterOrDigit) >= MINIMUM_EDGE_COVERAGE_CHARACTERS &&
            intersectionArea(item.bounds, band) > 0L &&
            intersectionArea(item.bounds, viewport.bounds) > 0L
    }

    private fun isLikelyBrowserToolbarText(text: String): Boolean {
        val compact = text.trim().lowercase()
        return compact.startsWith("http://") || compact.startsWith("https://") ||
            compact.startsWith("www.") ||
            compact.contains(BROWSER_ADDRESS_REGEX)
    }

    private fun normalize(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)

    private fun area(rect: Rect): Long =
        (rect.right - rect.left).coerceAtLeast(0).toLong() *
            (rect.bottom - rect.top).coerceAtLeast(0).toLong()

    private fun intersectionArea(first: Rect, second: Rect): Long {
        val width = (minOf(first.right, second.right) - maxOf(first.left, second.left))
            .coerceAtLeast(0)
        val height = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top))
            .coerceAtLeast(0)
        return width.toLong() * height.toLong()
    }

    private fun rectOf(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}

internal object LiveOcrContentViewportResolver {
    fun resolve(
        context: Context,
        frameWidth: Int,
        frameHeight: Int,
        obscuredBounds: List<Rect> = emptyList()
    ): LiveOcrContentViewport {
        val insets = resolveSystemInsets(context)
        val displayBounds = resolveDisplayBounds(context)
        val scaleX = frameWidth.toFloat() / displayBounds.width.coerceAtLeast(1)
        val scaleY = frameHeight.toFloat() / displayBounds.height.coerceAtLeast(1)
        val left = (insets.left * scaleX).toInt().coerceIn(0, frameWidth)
        val top = (insets.top * scaleY).toInt().coerceIn(0, frameHeight)
        val right = (frameWidth - insets.right * scaleX).toInt().coerceIn(left, frameWidth)
        val bottom = (frameHeight - insets.bottom * scaleY).toInt().coerceIn(top, frameHeight)
        return LiveOcrContentViewport(
            bounds = Rect(left, top, right, bottom),
            obscuredBounds = obscuredBounds.map(::Rect)
        )
    }

    private fun resolveSystemInsets(context: Context): InsetsValues {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return InsetsValues(insets.left, insets.top, insets.right, insets.bottom)
        }
        return InsetsValues(
            left = 0,
            top = legacyDimension(context, "status_bar_height"),
            right = 0,
            bottom = legacyDimension(context, "navigation_bar_height")
        )
    }

    private fun resolveDisplayBounds(context: Context): DisplaySize {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            return DisplaySize(bounds.width(), bounds.height())
        }
        val resources = context.resources
        return DisplaySize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    @Suppress("DiscouragedApi")
    private fun legacyDimension(context: Context, name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id == 0) 0 else context.resources.getDimensionPixelSize(id)
    }

    private data class InsetsValues(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class DisplaySize(val width: Int, val height: Int)
}
