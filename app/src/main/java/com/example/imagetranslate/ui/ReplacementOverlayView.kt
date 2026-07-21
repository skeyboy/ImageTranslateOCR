package com.example.imagetranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
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

    private var imageView: ImageView? = null
    private var markers = emptyList<Marker>()
    private var markerClickListener: ((index: Int, x: Float, y: Float) -> Unit)? = null
    private var pressedMarkerIndex = -1
    private val density = resources.displayMetrics.density
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = 11f * density
    }

    init {
        isClickable = true
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedMarkerIndex = findMarkerAt(event.x, event.y)
                return pressedMarkerIndex >= 0
            }
            MotionEvent.ACTION_UP -> {
                val releasedMarkerIndex = findMarkerAt(event.x, event.y)
                if (pressedMarkerIndex >= 0 && releasedMarkerIndex == pressedMarkerIndex) {
                    performClick()
                    markerClickListener?.invoke(releasedMarkerIndex, event.x, event.y)
                }
                pressedMarkerIndex = -1
                return releasedMarkerIndex >= 0
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedMarkerIndex = -1
                return false
            }
        }
        return pressedMarkerIndex >= 0
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun findMarkerAt(x: Float, y: Float): Int {
        val hitRadius = 18f * density
        return markers.indices.firstOrNull { index ->
            val center = markerCenter(markers[index]) ?: return@firstOrNull false
            hypot(x - center.first, y - center.second) <= hitRadius
        } ?: -1
    }

    private fun markerCenter(marker: Marker): Pair<Float, Float>? {
        val target = imageView ?: return null
        val radius = 8f * density
        val point = floatArrayOf(0f, marker.bounds.centerY().toFloat())
        target.imageMatrix.mapPoints(point)
        return (point[0] + target.paddingLeft + radius + 3f * density) to
            (point[1] + target.paddingTop)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageView == null) return
        val radius = 8f * density

        for (marker in markers) {
            val center = markerCenter(marker) ?: continue
            val centerX = center.first
            val centerY = center.second

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
