package com.example.imagetranslate.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private companion object {
        const val MIN_ZOOM = 1f
        const val DOUBLE_TAP_ZOOM = 2.5f
        const val MAX_ZOOM = 5f
    }

    private val baseMatrix = Matrix()
    private val zoomMatrix = Matrix()
    private val drawableBounds = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var currentZoom = MIN_ZOOM
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragStarted = false
    private var matrixChangedListener: (() -> Unit)? = null
    private var singleTapListener: ((x: Float, y: Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return drawable != null
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val targetZoom = (currentZoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                val appliedFactor = targetZoom / currentZoom
                if (abs(appliedFactor - 1f) < 0.001f) return false
                zoomMatrix.postScale(
                    appliedFactor,
                    appliedFactor,
                    detector.focusX - paddingLeft,
                    detector.focusY - paddingTop
                )
                currentZoom = targetZoom
                constrainTranslation()
                applyZoomMatrix()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (currentZoom <= MIN_ZOOM + 0.01f) resetZoom()
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                performClick()
                singleTapListener?.invoke(event.x, event.y)
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (currentZoom > MIN_ZOOM + 0.01f) {
                    resetZoom()
                } else {
                    zoomTo(DOUBLE_TAP_ZOOM, event.x, event.y)
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    fun setOnMatrixChangedListener(listener: () -> Unit) {
        matrixChangedListener = listener
    }

    fun setOnSingleTapConfirmedListener(listener: (x: Float, y: Float) -> Unit) {
        singleTapListener = listener
    }

    fun resetZoom() {
        val image = drawable ?: return
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        if (contentWidth <= 0 || contentHeight <= 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) {
            post { resetZoom() }
            return
        }

        val scale = minOf(
            contentWidth.toFloat() / image.intrinsicWidth,
            contentHeight.toFloat() / image.intrinsicHeight
        )
        val offsetX = (contentWidth - image.intrinsicWidth * scale) / 2f
        val offsetY = (contentHeight - image.intrinsicHeight * scale) / 2f
        baseMatrix.reset()
        baseMatrix.setScale(scale, scale)
        baseMatrix.postTranslate(offsetX, offsetY)
        zoomMatrix.set(baseMatrix)
        currentZoom = MIN_ZOOM
        applyZoomMatrix()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) post { resetZoom() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                dragStarted = false
                parent?.requestDisallowInterceptTouchEvent(currentZoom > MIN_ZOOM)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remainingPointerIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingPointerIndex < event.pointerCount) {
                    lastTouchX = event.getX(remainingPointerIndex)
                    lastTouchY = event.getY(remainingPointerIndex)
                }
                dragStarted = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && currentZoom > MIN_ZOOM) {
                    val deltaX = event.x - lastTouchX
                    val deltaY = event.y - lastTouchY
                    if (dragStarted || abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) {
                        dragStarted = true
                        zoomMatrix.postTranslate(deltaX, deltaY)
                        constrainTranslation()
                        applyZoomMatrix()
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (currentZoom <= MIN_ZOOM) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragStarted = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun zoomTo(targetZoom: Float, focusX: Float, focusY: Float) {
        val boundedZoom = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = boundedZoom / currentZoom
        zoomMatrix.postScale(factor, factor, focusX - paddingLeft, focusY - paddingTop)
        currentZoom = boundedZoom
        constrainTranslation()
        applyZoomMatrix()
    }

    private fun constrainTranslation() {
        val image = drawable ?: return
        drawableBounds.set(0f, 0f, image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat())
        zoomMatrix.mapRect(drawableBounds)

        val contentLeft = 0f
        val contentTop = 0f
        val contentRight = (width - paddingLeft - paddingRight).toFloat()
        val contentBottom = (height - paddingTop - paddingBottom).toFloat()
        val contentWidth = contentRight - contentLeft
        val contentHeight = contentBottom - contentTop

        val correctionX = when {
            drawableBounds.width() <= contentWidth ->
                contentLeft + (contentWidth - drawableBounds.width()) / 2f - drawableBounds.left
            drawableBounds.left > contentLeft -> contentLeft - drawableBounds.left
            drawableBounds.right < contentRight -> contentRight - drawableBounds.right
            else -> 0f
        }
        val correctionY = when {
            drawableBounds.height() <= contentHeight ->
                contentTop + (contentHeight - drawableBounds.height()) / 2f - drawableBounds.top
            drawableBounds.top > contentTop -> contentTop - drawableBounds.top
            drawableBounds.bottom < contentBottom -> contentBottom - drawableBounds.bottom
            else -> 0f
        }
        zoomMatrix.postTranslate(correctionX, correctionY)
    }

    private fun applyZoomMatrix() {
        imageMatrix = zoomMatrix
        matrixChangedListener?.invoke()
    }
}
