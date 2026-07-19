package com.example.imagetranslate.inpaint

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

class ImageInpainter {

    fun eraseWithRectMask(bitmap: Bitmap, textRegions: List<Rect>): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val mask = Mat.zeros(src.size(), CvType.CV_8UC1)
        for (region in textRegions) {
            val expanded = Rect(
                maxOf(0, region.left - 4),
                maxOf(0, region.top - 4),
                region.width() + 8,
                region.height() + 8
            )
            Imgproc.rectangle(
                mask,
                Point(expanded.left.toDouble(), expanded.top.toDouble()),
                Point(expanded.right.toDouble(), expanded.bottom.toDouble()),
                Scalar(255.0), -1
            )
        }

        val result = Mat()
        Photo.inpaint(src, mask, result, 5.0, Photo.INPAINT_TELEA)

        val outBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, outBitmap)

        src.release()
        mask.release()
        result.release()

        return outBitmap
    }

    fun eraseWithPreciseMask(bitmap: Bitmap, textRegions: List<Rect>): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)

        val mask = Mat.zeros(src.size(), CvType.CV_8UC1)
        for (region in textRegions) {
            val validRect = Rect(
                maxOf(0, region.left),
                maxOf(0, region.top),
                minOf(src.cols() - region.left, region.width()),
                minOf(src.rows() - region.top, region.height())
            )
            if (validRect.width <= 0 || validRect.height <= 0) continue

            val roiGray = Mat(gray, validRect)
            val roiMask = Mat(mask, validRect)
            val binary = Mat()

            Imgproc.adaptiveThreshold(
                roiGray, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 15, 4.0
            )
            binary.copyTo(roiMask)
            binary.release()
        }

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.dilate(mask, mask, kernel)
        kernel.release()
        gray.release()

        val result = Mat()
        Photo.inpaint(src, mask, result, 3.0, Photo.INPAINT_TELEA)

        val outBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, outBitmap)

        src.release()
        mask.release()
        result.release()

        return outBitmap
    }
}

