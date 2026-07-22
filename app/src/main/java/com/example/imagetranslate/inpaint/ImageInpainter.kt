package com.example.imagetranslate.inpaint

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

data class InpaintResult(
    val bitmap: Bitmap,
    val erasedRegions: List<Rect>,
    val maskAnalyses: List<PreciseMaskAnalysis> = emptyList()
)

data class PreciseMaskAnalysis(
    val bounds: Rect,
    val darkRatio: Double,
    val lightRatio: Double,
    val accepted: Boolean
)

class ImageInpainter {

    fun eraseWithRectMask(bitmap: Bitmap, textRegions: List<Rect>): InpaintResult {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val rgb = Mat()
        Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)

        val mask = Mat.zeros(src.size(), CvType.CV_8UC1)
        val erasedRegions = mutableListOf<Rect>()
        for (region in textRegions) {
            val expanded = Rect(
                maxOf(0, region.left - 4),
                maxOf(0, region.top - 4),
                minOf(bitmap.width, region.right + 4),
                minOf(bitmap.height, region.bottom + 4)
            )
            if (expanded.isEmpty) continue
            erasedRegions.add(Rect(region))
            Imgproc.rectangle(
                mask,
                Point(expanded.left.toDouble(), expanded.top.toDouble()),
                Point(expanded.right.toDouble(), expanded.bottom.toDouble()),
                Scalar(255.0), -1
            )
        }

        val outBitmap = if (erasedRegions.isEmpty()) {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            inpaintOntoOriginal(src, rgb, mask, 4.0, bitmap.width, bitmap.height)
        }

        src.release()
        rgb.release()
        mask.release()

        return InpaintResult(outBitmap, erasedRegions)
    }

    fun eraseWithPreciseMask(bitmap: Bitmap, textRegions: List<Rect>): InpaintResult {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val rgb = Mat()
        Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        val mask = Mat.zeros(src.size(), CvType.CV_8UC1)
        val erasedRegions = mutableListOf<Rect>()
        val maskAnalyses = mutableListOf<PreciseMaskAnalysis>()
        for (region in textRegions) {
            val left = maxOf(0, region.left - 2)
            val top = maxOf(0, region.top - 2)
            val right = minOf(src.cols(), region.right + 2)
            val bottom = minOf(src.rows(), region.bottom + 2)
            val validRect = CvRect(left, top, right - left, bottom - top)
            if (validRect.width <= 0 || validRect.height <= 0) continue

            val roiGray = Mat(gray, validRect)
            val roiMask = Mat(mask, validRect)
            val darkTextMask = Mat()
            val lightTextMask = Mat()

            val smallestSide = minOf(validRect.width, validRect.height)
            var blockSize = minOf(15, smallestSide)
            if (blockSize % 2 == 0) blockSize--
            if (blockSize >= 3) {
                Imgproc.adaptiveThreshold(
                    roiGray, darkTextMask, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY_INV, blockSize, 4.0
                )
                Imgproc.adaptiveThreshold(
                    roiGray, lightTextMask, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY, blockSize, -4.0
                )
            } else {
                Imgproc.threshold(
                    roiGray, darkTextMask, 0.0, 255.0,
                    Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU
                )
                Core.bitwise_not(darkTextMask, lightTextMask)
            }
            val area = validRect.area()
            val darkRatio = Core.countNonZero(darkTextMask) / area
            val lightRatio = Core.countNonZero(lightTextMask) / area
            val lightScore = maskScore(lightRatio, validRect)
            val darkScore = maskScore(darkRatio, validRect)
            val selectedMask = if (lightScore < darkScore) {
                lightTextMask
            } else {
                darkTextMask
            }
            val accepted = minOf(lightScore, darkScore) != Double.MAX_VALUE
            maskAnalyses.add(
                PreciseMaskAnalysis(
                    bounds = Rect(region),
                    darkRatio = darkRatio,
                    lightRatio = lightRatio,
                    accepted = accepted
                )
            )
            if (accepted) {
                selectedMask.copyTo(roiMask)
                erasedRegions.add(Rect(region))
            }
            darkTextMask.release()
            lightTextMask.release()
            roiGray.release()
            roiMask.release()
        }

        if (erasedRegions.isNotEmpty()) {
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            Imgproc.dilate(mask, mask, kernel)
            kernel.release()
        }
        gray.release()

        val outBitmap = if (erasedRegions.isEmpty()) {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            inpaintOntoOriginal(src, rgb, mask, 2.0, bitmap.width, bitmap.height)
        }

        src.release()
        rgb.release()
        mask.release()

        return InpaintResult(outBitmap, erasedRegions, maskAnalyses)
    }

    private fun maskScore(foregroundRatio: Double, region: CvRect): Double {
        val aspectRatio = region.width.toDouble() / region.height.coerceAtLeast(1)
        val maximumRatio = if (aspectRatio >= BOLD_TEXT_MINIMUM_ASPECT_RATIO) {
            BOLD_TEXT_MAXIMUM_FOREGROUND_RATIO
        } else {
            DEFAULT_MAXIMUM_FOREGROUND_RATIO
        }
        if (foregroundRatio !in MINIMUM_FOREGROUND_RATIO..maximumRatio) {
            return Double.MAX_VALUE
        }
        return kotlin.math.abs(foregroundRatio - TARGET_FOREGROUND_RATIO)
    }

    private fun inpaintOntoOriginal(
        sourceRgba: Mat,
        sourceRgb: Mat,
        mask: Mat,
        radius: Double,
        width: Int,
        height: Int
    ): Bitmap {
        val repairedRgb = Mat()
        Photo.inpaint(sourceRgb, mask, repairedRgb, radius, Photo.INPAINT_TELEA)
        val repairedRgba = Mat()
        Imgproc.cvtColor(repairedRgb, repairedRgba, Imgproc.COLOR_RGB2RGBA)
        val composed = sourceRgba.clone()
        repairedRgba.copyTo(composed, mask)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(composed, output)

        repairedRgb.release()
        repairedRgba.release()
        composed.release()
        return output
    }

    private companion object {
        const val MINIMUM_FOREGROUND_RATIO = 0.015
        const val DEFAULT_MAXIMUM_FOREGROUND_RATIO = 0.42
        const val BOLD_TEXT_MAXIMUM_FOREGROUND_RATIO = 0.48
        const val BOLD_TEXT_MINIMUM_ASPECT_RATIO = 1.8
        const val TARGET_FOREGROUND_RATIO = 0.18
    }
}
