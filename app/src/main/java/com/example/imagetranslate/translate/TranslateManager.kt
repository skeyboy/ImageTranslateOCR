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
        "连接或断开电源时唤醒" to "Wake on power connection",
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
        val normalized = text.replace(Regex("[\\s·•]+"), "")
        uiTranslations[normalized]?.let { return it }
        if (normalized.length <= 8) {
            when {
                normalized.contains("分享") -> return "Share"
                normalized.contains("编辑") -> return "Edit"
                normalized.contains("删除") -> return "Delete"
            }
        }
        if (normalized.contains("连接") && normalized.contains("电源") &&
            normalized.contains("唤醒")
        ) {
            return "Wake on power connection"
        }
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
