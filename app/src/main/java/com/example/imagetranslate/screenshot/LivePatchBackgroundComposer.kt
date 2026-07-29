package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import android.graphics.Color
import com.example.imagetranslate.App
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

internal data class LivePatchBackground(
    val bitmap: Bitmap,
    val detailRetentionRatio: Float
)

internal object LivePatchBackgroundComposer {
    fun createBlurTintTarget(
        source: Bitmap,
        themeSurface: Int,
        overlayAlpha: Float
    ): LivePatchBackground = createBlurTintTarget(
        source = source,
        themeSurface = themeSurface,
        overlayAlpha = overlayAlpha,
        profile = BlurProfile.PATCH
    )

    fun createFullPageBlurTintTarget(
        source: Bitmap,
        themeSurface: Int,
        overlayAlpha: Float
    ): LivePatchBackground = createBlurTintTarget(
        source = source,
        themeSurface = themeSurface,
        overlayAlpha = overlayAlpha,
        profile = BlurProfile.FULL_PAGE
    )

    private fun createBlurTintTarget(
        source: Bitmap,
        themeSurface: Int,
        overlayAlpha: Float,
        profile: BlurProfile
    ): LivePatchBackground {
        check(App.isOpenCVReady) { "OpenCV is not ready for live patch blur" }
        val sourceMat = Mat()
        val reduced = Mat()
        val blurredReduced = Mat()
        val target = Mat()
        return try {
            Utils.bitmapToMat(source, sourceMat)
            val reducedWidth = (source.width / DOWNSAMPLE_FACTOR).coerceAtLeast(1)
            val reducedHeight = (source.height / DOWNSAMPLE_FACTOR).coerceAtLeast(1)
            Imgproc.resize(
                sourceMat,
                reduced,
                Size(reducedWidth.toDouble(), reducedHeight.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA
            )
            val fullResolutionRadius = (source.height * profile.radiusHeightRatio)
                .roundToInt()
                .coerceIn(profile.minimumRadiusPx, profile.maximumRadiusPx)
            val reducedRadius = (fullResolutionRadius.toFloat() / DOWNSAMPLE_FACTOR)
                .roundToInt()
                .coerceAtLeast(1)
            val kernelSize = reducedRadius * 2 + 1
            Imgproc.GaussianBlur(
                reduced,
                blurredReduced,
                Size(kernelSize.toDouble(), kernelSize.toDouble()),
                0.0
            )
            Imgproc.resize(
                blurredReduced,
                target,
                sourceMat.size(),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR
            )

            val alpha = overlayAlpha.coerceIn(0.01f, 1f)
            val minimumComponent = kotlin.math.ceil((1f - alpha) * 255f).toInt()
            val maximumComponent = kotlin.math.floor(alpha * 255f).toInt()
                .coerceAtLeast(minimumComponent)
            val safeScale = (maximumComponent - minimumComponent) / 255.0
            target.convertTo(target, -1, safeScale, minimumComponent.toDouble())
            Core.multiply(
                target,
                Scalar(
                    profile.detailWeight.toDouble(),
                    profile.detailWeight.toDouble(),
                    profile.detailWeight.toDouble(),
                    0.0
                ),
                target
            )
            val themeWeight = 1.0 - profile.detailWeight
            Core.add(
                target,
                Scalar(
                    Color.red(themeSurface) * themeWeight,
                    Color.green(themeSurface) * themeWeight,
                    Color.blue(themeSurface) * themeWeight,
                    255.0
                ),
                target
            )

            val sourceDetail = detailEnergy(sourceMat)
            val targetDetail = detailEnergy(target)
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(target, output)
            LivePatchBackground(
                bitmap = output,
                detailRetentionRatio = if (sourceDetail <= 0.0) {
                    0f
                } else {
                    (targetDetail / sourceDetail).toFloat().coerceAtMost(1f)
                }
            )
        } finally {
            sourceMat.release()
            reduced.release()
            blurredReduced.release()
            target.release()
        }
    }

    fun drawCompensatedTarget(
        output: Bitmap,
        target: Bitmap,
        source: Bitmap,
        overlayAlpha: Float
    ) {
        require(output.width == target.width && output.height == target.height)
        require(source.width == target.width && source.height == target.height)
        val targetMat = Mat()
        val sourceMat = Mat()
        val compensated = Mat()
        try {
            Utils.bitmapToMat(target, targetMat)
            Utils.bitmapToMat(source, sourceMat)
            val alpha = overlayAlpha.coerceIn(0.01f, 1f).toDouble()
            Core.addWeighted(
                targetMat,
                1.0 / alpha,
                sourceMat,
                -(1.0 - alpha) / alpha,
                0.0,
                compensated
            )
            Utils.matToBitmap(compensated, output)
        } finally {
            targetMat.release()
            sourceMat.release()
            compensated.release()
        }
    }

    private fun detailEnergy(source: Mat): Double {
        val gray = Mat()
        val laplacian = Mat()
        val magnitude = Mat()
        return try {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_16S, 3)
            Core.convertScaleAbs(laplacian, magnitude)
            Core.mean(magnitude).`val`[0]
        } finally {
            gray.release()
            laplacian.release()
            magnitude.release()
        }
    }

    private data class BlurProfile(
        val radiusHeightRatio: Float,
        val minimumRadiusPx: Int,
        val maximumRadiusPx: Int,
        val detailWeight: Float
    ) {
        companion object {
            val PATCH = BlurProfile(
                radiusHeightRatio = 0.15f,
                minimumRadiusPx = 4,
                maximumRadiusPx = 12,
                detailWeight = 0.18f
            )
            val FULL_PAGE = BlurProfile(
                radiusHeightRatio = 0.015f,
                minimumRadiusPx = 24,
                maximumRadiusPx = 48,
                detailWeight = 0.28f
            )
        }
    }

    private const val DOWNSAMPLE_FACTOR = 4
}
