package com.example.imagetranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import kotlin.math.hypot

class ReplacementOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Marker(
        val number: Int,
        val bounds: Rect,
        val showingOriginal: Boolean
    )

    private data class PositionedMarker(
        val index: Int,
        val centerX: Float,
        val centerY: Float
    )

    private var imageView: ImageView? = null
    private var markers = emptyList<Marker>()
    private var markerClickListener: ((index: Int, x: Float, y: Float) -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = 11f * density
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun attachTo(imageView: ImageView) {
        this.imageView = imageView
        invalidate()
    }

    fun setMarkers(markers: List<Marker>) {
        this.markers = markers
        invalidate()
    }

    fun setOnMarkerClickListener(listener: (index: Int, x: Float, y: Float) -> Unit) {
        markerClickListener = listener
    }

    fun performMarkerClick(x: Float, y: Float): Boolean {
        val markerIndex = findMarkerAt(x, y)
        if (markerIndex < 0) return false
        markerClickListener?.invoke(markerIndex, x, y)
        return true
    }

    private fun findMarkerAt(x: Float, y: Float): Int {
        val hitRadius = 18f * density
        val closest = positionedMarkers().minByOrNull { marker ->
            hypot(x - marker.centerX, y - marker.centerY)
        } ?: return -1
        return closest.index.takeIf {
            hypot(x - closest.centerX, y - closest.centerY) <= hitRadius
        } ?: -1
    }

    private fun positionedMarkers(): List<PositionedMarker> {
        val target = imageView ?: return emptyList()
        val radius = 8f * density
        val laneSpacing = radius * 2f + 3f * density
        val minimumVerticalDistance = radius * 2f + 2f * density
        val anchor = floatArrayOf(0f, 0f)
        target.imageMatrix.mapPoints(anchor)
        val baseX = anchor[0] + target.paddingLeft + radius + 3f * density
        val maximumX = (width - radius).coerceAtLeast(baseX)
        val laneLastY = mutableListOf<Float>()
        val positions = arrayOfNulls<PositionedMarker>(markers.size)

        markers.indices.map { index ->
            val point = floatArrayOf(0f, markers[index].bounds.centerY().toFloat())
            target.imageMatrix.mapPoints(point)
            index to (point[1] + target.paddingTop)
        }.sortedBy { it.second }.forEach { (index, centerY) ->
            var lane = laneLastY.indexOfFirst { lastY ->
                centerY - lastY >= minimumVerticalDistance
            }
            if (lane < 0) {
                lane = laneLastY.size
                laneLastY.add(centerY)
            } else {
                laneLastY[lane] = centerY
            }
            positions[index] = PositionedMarker(
                index = index,
                centerX = (baseX + lane * laneSpacing).coerceAtMost(maximumX),
                centerY = centerY
            )
        }
        return positions.filterNotNull()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageView == null) return
        val radius = 8f * density

        for (positionedMarker in positionedMarkers()) {
            val marker = markers[positionedMarker.index]
            val centerX = positionedMarker.centerX
            val centerY = positionedMarker.centerY

            circlePaint.color = if (marker.showingOriginal) 0xFF2E7D32.toInt() else 0xFFD32F2F.toInt()
            circlePaint.style = Paint.Style.FILL
            canvas.drawCircle(centerX, centerY, radius, circlePaint)
            circlePaint.color = Color.WHITE
            circlePaint.style = Paint.Style.STROKE
            circlePaint.strokeWidth = density
            canvas.drawCircle(centerX, centerY, radius, circlePaint)

            val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(marker.number.toString(), centerX, baseline, textPaint)
        }
    }
}
