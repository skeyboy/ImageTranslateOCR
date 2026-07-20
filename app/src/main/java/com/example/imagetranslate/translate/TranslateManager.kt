package com.example.imagetranslate.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslateManager {
    private val uiTranslations = mapOf(
        "Android系统" to "Android system",
        "已连接到USB调试·现在" to "Connected to USB debugging · now",
        "点按即可关闭USB调试" to "Tap to turn off USB debugging",
        "颜色" to "Color",
        "自然色" to "Natural colors",
        "颜色对比度" to "Color contrast",
        "默认" to "Default",
        "其他显示控件" to "Other display controls",
        "旋转设置" to "Rotation settings",
        "启用自动旋转" to "Auto-rotate",
        "分享" to "Share",
        "编辑" to "Edit",
        "删除" to "Delete"
    )

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    suspend fun downloadModelIfNeeded(): Boolean = suspendCancellableCoroutine { cont ->
        val conditions = DownloadConditions.Builder().requireWifi().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

    suspend fun translate(text: String): String {
        uiTranslations[text.replace(Regex("\\s+"), "")]?.let { return it }
        return translateWithModel(text)
    }

    private suspend fun translateWithModel(text: String): String = suspendCancellableCoroutine { cont ->
        translator.translate(text)
            .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }

    fun close() {
        translator.close()
    }
}
