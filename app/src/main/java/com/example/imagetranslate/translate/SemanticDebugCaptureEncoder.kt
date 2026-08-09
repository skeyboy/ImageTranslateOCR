package com.example.imagetranslate.translate

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal object SemanticDebugCaptureEncoder {
    fun encode(bitmap: Bitmap): SemanticDebugCapture {
        val scale = (MAXIMUM_EDGE_PX / maxOf(bitmap.width, bitmap.height).toFloat())
            .coerceAtMost(1f)
        val encodedBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                check(encodedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "Unable to encode debug OCR capture"
                }
                output.toByteArray()
            }
            SemanticDebugCapture(
                mimeType = "image/jpeg",
                dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                pixelWidth = encodedBitmap.width,
                pixelHeight = encodedBitmap.height
            )
        } finally {
            if (encodedBitmap !== bitmap && !encodedBitmap.isRecycled) encodedBitmap.recycle()
        }
    }

    private const val MAXIMUM_EDGE_PX = 1080f
    private const val JPEG_QUALITY = 72
}

internal object SemanticDebugCaptureUploadPolicy {
    fun shouldUpload(
        isDebugBuild: Boolean,
        scene: String,
        backend: TranslationBackend,
        uploadEnabled: Boolean,
        missingGroupCount: Int
    ): Boolean = isDebugBuild &&
        scene == "LIVE_SCREEN" &&
        backend.isSelfHosted &&
        uploadEnabled &&
        missingGroupCount > 0
}
