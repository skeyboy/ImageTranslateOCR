package com.example.imagetranslate.screenshot

import android.content.Context
import com.example.imagetranslate.ocr.PaddleNetworkSettings

internal data class LiveOcrTranslationEngineDescriptor(
    val cacheKey: Any,
    val create: () -> LiveOcrTranslationEngine
)

internal interface LiveOcrTranslationEngineFactory {
    val type: LiveOcrTranslationEngineType

    fun resolve(context: Context): LiveOcrTranslationEngineDescriptor
}

internal object PaddleNetworkOcrTranslationEngineFactory : LiveOcrTranslationEngineFactory {
    override val type = LiveOcrTranslationEngineType.PADDLE_NETWORK

    override fun resolve(context: Context): LiveOcrTranslationEngineDescriptor {
        val configuration = PaddleNetworkSettings.get(context)
        check(configuration.isConfigured) { "PaddleOCR network service is not configured" }
        return LiveOcrTranslationEngineDescriptor(
            cacheKey = configuration,
            create = { PaddleNetworkOcrTranslationEngine(configuration) }
        )
    }
}

internal class LiveOcrTranslationEngineRegistry(
    context: Context,
    factories: List<LiveOcrTranslationEngineFactory> = listOf(
        PaddleNetworkOcrTranslationEngineFactory
    )
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val factoriesByType = factories.associateBy(LiveOcrTranslationEngineFactory::type)
    private val lock = Any()
    private var activeType: LiveOcrTranslationEngineType? = null
    private var activeCacheKey: Any? = null
    private var activeEngine: LiveOcrTranslationEngine? = null

    init {
        require(factoriesByType.size == factories.size) {
            "Only one live OCR translation engine factory may be registered per type"
        }
    }

    fun get(type: LiveOcrTranslationEngineType): LiveOcrTranslationEngine {
        require(type != LiveOcrTranslationEngineType.LOCAL_PIPELINE) {
            "The local pipeline is managed directly by the image processor"
        }
        val descriptor = checkNotNull(factoriesByType[type]) {
            "No integrated OCR translation engine is registered for ${type.name}"
        }.resolve(appContext)
        return synchronized(lock) {
            if (activeEngine == null || activeType != type || activeCacheKey != descriptor.cacheKey) {
                activeEngine?.close()
                activeEngine = descriptor.create()
                activeType = type
                activeCacheKey = descriptor.cacheKey
            }
            checkNotNull(activeEngine)
        }
    }

    override fun close() = synchronized(lock) {
        activeEngine?.close()
        activeEngine = null
        activeType = null
        activeCacheKey = null
    }
}
