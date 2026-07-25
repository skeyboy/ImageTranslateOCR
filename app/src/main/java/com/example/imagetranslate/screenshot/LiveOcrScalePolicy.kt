package com.example.imagetranslate.screenshot

import kotlin.math.roundToInt

internal data class LiveOcrInputSize(val width: Int, val height: Int)

internal object LiveOcrScalePolicy {
    fun inputSize(width: Int, height: Int): LiveOcrInputSize {
        if (width <= 0 || height <= 0) return LiveOcrInputSize(width, height)
        val longEdge = maxOf(width, height)
        if (longEdge <= MAXIMUM_LONG_EDGE_PX) return LiveOcrInputSize(width, height)
        val scale = MAXIMUM_LONG_EDGE_PX.toFloat() / longEdge
        return LiveOcrInputSize(
            width = (width * scale).roundToInt().coerceAtLeast(1),
            height = (height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    fun mapX(value: Int, inputSize: LiveOcrInputSize, sourceSize: LiveOcrInputSize): Int =
        mapCoordinate(value, inputSize.width, sourceSize.width)

    fun mapY(value: Int, inputSize: LiveOcrInputSize, sourceSize: LiveOcrInputSize): Int =
        mapCoordinate(value, inputSize.height, sourceSize.height)

    private fun mapCoordinate(value: Int, input: Int, source: Int): Int =
        if (input <= 0) value else (value * source.toFloat() / input).roundToInt()

    private const val MAXIMUM_LONG_EDGE_PX = 2_880
}
