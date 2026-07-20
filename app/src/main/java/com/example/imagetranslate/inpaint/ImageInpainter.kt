package com.example.imagetranslate.inpaint

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

class ImageInpainter {

    fun eraseWithRectMask(bitmap: Bitmap, textRegions: List<Rect>): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val rgb = Mat()
        Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)

        val mask = Mat.zeros(src.size(), CvType.CV_8UC1)
        for (region in textRegions) {
            val expanded = Rect(
                maxOf(0, region.left - 4),
                maxOf(0, region.top - 4),
                minOf(bitmap.width, region.right + 4),
                minOf(bitmap.height, region.bottom + 4)
            )
            if (expanded.isEmpty) continue
            Imgproc.rectangle(
                mask,
                Point(expanded.left.toDouble(), expanded.top.toDouble()),
                Point(expanded.right.toDouble(), expanded.bottom.toDouble()),
                Scalar(255.0), -1
            )
        }

        val result = Mat()
        Photo.inpaint(rgb, mask, result, 5.0, Photo.INPAINT_TELEA)

        val outBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, outBitmap)

        src.release()
        rgb.release()
        mask.release()
        result.release()

        return outBitmap
    }

    fun eraseWithPreciseMask(bitmap: Bitmap, textRegions: List<Rect>): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val rgb = Mat()
        Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        val mask = Mat.zeros(src.size(), CvType.CV_8UC1)
        for (region in textRegions) {
            val left = maxOf(0, region.left - 3)
            val top = maxOf(0, region.top - 3)
            val right = minOf(src.cols(), region.right + 3)
            val bottom = minOf(src.rows(), region.bottom + 3)
            val validRect = CvRect(left, top, right - left, bottom - top)
            if (validRect.width <= 0 || validRect.height <= 0) continue

            val roiGray = Mat(gray, validRect)
            val roiMask = Mat(mask, validRect)
            val binary = Mat()

            val smallestSide = minOf(validRect.width, validRect.height)
            var blockSize = minOf(15, smallestSide)
            if (blockSize % 2 == 0) blockSize--
            if (blockSize >= 3) {
                Imgproc.adaptiveThreshold(
                    roiGray, binary, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY_INV, blockSize, 4.0
                )
            } else {
                Imgproc.threshold(roiGray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            }
            binary.copyTo(roiMask)
            binary.release()
            roiGray.release()
            roiMask.release()
        }

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.dilate(mask, mask, kernel)
        kernel.release()
        gray.release()

        val result = Mat()
        Photo.inpaint(rgb, mask, result, 3.0, Photo.INPAINT_TELEA)

        val outBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, outBitmap)

        src.release()
        rgb.release()
        mask.release()
        result.release()

        return outBitmap
    }
}
