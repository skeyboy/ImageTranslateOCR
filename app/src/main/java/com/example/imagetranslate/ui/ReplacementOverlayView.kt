package com.example.imagetranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val target = imageView ?: return
        val radius = 10f * density
        val point = FloatArray(2)

        for (marker in markers) {
            point[0] = marker.bounds.left.toFloat()
            point[1] = marker.bounds.top.toFloat()
            target.imageMatrix.mapPoints(point)
            point[0] += target.paddingLeft + radius
            point[1] += target.paddingTop + radius

            circlePaint.color = if (marker.showingOriginal) 0xFF2E7D32.toInt() else 0xFFD32F2F.toInt()
            circlePaint.style = Paint.Style.FILL
            canvas.drawCircle(point[0], point[1], radius, circlePaint)
            circlePaint.color = Color.WHITE
            circlePaint.style = Paint.Style.STROKE
            circlePaint.strokeWidth = density
            canvas.drawCircle(point[0], point[1], radius, circlePaint)

            val baseline = point[1] - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(marker.number.toString(), point[0], baseline, textPaint)
        }
    }
}
