package com.example.experimentaltranslation

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

class ExperimentalModelRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, ROOT_DIRECTORY)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun status(engine: ExperimentalTranslationEngine): ExperimentalModelStatus {
        if (engine == ExperimentalTranslationEngine.DISABLED) {
            return ExperimentalModelStatus(engine, ExperimentalModelState.READY)
        }
        val definition = ExperimentalModelCatalog.definition(engine)
        val directory = modelDirectory(engine)
        if (!directory.isDirectory) {
            return ExperimentalModelStatus(engine, ExperimentalModelState.NOT_INSTALLED)
        }
        val missing = definition.requiredFiles.filterNot { File(directory, it).isFile }
        if (missing.isNotEmpty()) {
            return ExperimentalModelStatus(
                engine,
                ExperimentalModelState.INVALID,
                directorySize(directory),
                "Missing files: ${missing.joinToString()}"
            )
        }
        return ExperimentalModelStatus(
            engine,
            ExperimentalModelState.READY,
            directorySize(directory)
        )
    }

    fun modelDirectory(engine: ExperimentalTranslationEngine): File =
        File(root, engine.storageId)

    fun modelFile(engine: ExperimentalTranslationEngine, name: String): File =
        File(modelDirectory(engine), name)

    fun configuredDownloadUrl(engine: ExperimentalTranslationEngine): String? =
        preferences.getString("url_${engine.storageId}", null)?.trim()?.takeIf(String::isNotEmpty)
            ?: ExperimentalModelCatalog.definition(engine).defaultDownloadUrl

    fun setDownloadUrl(engine: ExperimentalTranslationEngine, url: String?) {
        require(engine != ExperimentalTranslationEngine.DISABLED)
        preferences.edit().apply {
            if (url.isNullOrBlank()) remove("url_${engine.storageId}")
            else putString("url_${engine.storageId}", url.trim())
        }.apply()
    }

    suspend fun importArchive(
        engine: ExperimentalTranslationEngine,
        uri: Uri
    ): ExperimentalModelStatus = withContext(Dispatchers.IO) {
        val input = requireNotNull(appContext.contentResolver.openInputStream(uri)) {
            "Unable to open selected model file"
        }
        input.use { installModelFile(engine, it) }
    }

    suspend fun importArchive(
        engine: ExperimentalTranslationEngine,
        archive: File
    ): ExperimentalModelStatus = withContext(Dispatchers.IO) {
        require(archive.isFile) { "Model file does not exist: ${archive.absolutePath}" }
        archive.inputStream().use { installModelFile(engine, it) }
    }

    suspend fun download(
        engine: ExperimentalTranslationEngine,
        progress: ModelDownloadProgressListener = ModelDownloadProgressListener { _, _ -> }
    ): ExperimentalModelStatus = withContext(Dispatchers.IO) {
        val configuredUrl = configuredDownloadUrl(engine)
            ?: throw ExperimentalModelUnavailableException(
                "No download URL configured for ${ExperimentalModelCatalog.definition(engine).displayName}"
            )
        val connection = (URL(configuredUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Model download failed with HTTP ${connection.responseCode}"
            }
            val total = connection.contentLengthLong.takeIf { it > 0 }
            val tempModel = File(workingRoot(), "${engine.storageId}.download")
            connection.inputStream.use { input ->
                FileOutputStream(tempModel).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        progress.onProgress(downloaded, total)
                    }
                    output.fd.sync()
                }
            }
            try {
                tempModel.inputStream().use { installModelFile(engine, it) }
            } finally {
                tempModel.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun remove(engine: ExperimentalTranslationEngine) = withContext(Dispatchers.IO) {
        require(engine != ExperimentalTranslationEngine.DISABLED)
        modelDirectory(engine).deleteRecursively()
    }

    private fun installZip(
        engine: ExperimentalTranslationEngine,
        input: InputStream
    ): ExperimentalModelStatus {
        require(engine != ExperimentalTranslationEngine.DISABLED)
        val staging = File(workingRoot(), "${engine.storageId}-${System.nanoTime()}")
        check(staging.mkdirs()) { "Unable to create model staging directory" }
        try {
            unzipSafely(input, staging)
            flattenSingleTopLevelDirectory(staging)
            val definition = ExperimentalModelCatalog.definition(engine)
            val missing = definition.requiredFiles.filterNot { File(staging, it).isFile }
            check(missing.isEmpty()) {
                "Invalid ${definition.displayName} archive; missing ${missing.joinToString()}"
            }
            return commitStaging(engine, staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun installModelFile(
        engine: ExperimentalTranslationEngine,
        input: InputStream
    ): ExperimentalModelStatus {
        val buffered = input.buffered()
        buffered.mark(FILE_SIGNATURE_BYTES)
        val signature = ByteArray(FILE_SIGNATURE_BYTES)
        val signatureLength = buffered.read(signature)
        buffered.reset()
        return if (signatureLength >= 2 && signature[0] == 'P'.code.toByte() &&
            signature[1] == 'K'.code.toByte()
        ) {
            installZip(engine, buffered)
        } else {
            installStandaloneModel(engine, buffered)
        }
    }

    private fun installStandaloneModel(
        engine: ExperimentalTranslationEngine,
        input: InputStream
    ): ExperimentalModelStatus {
        require(engine == ExperimentalTranslationEngine.TRANSLATEGEMMA_4B) {
            "${ExperimentalModelCatalog.definition(engine).displayName} must be imported as ZIP"
        }
        val staging = File(workingRoot(), "${engine.storageId}-${System.nanoTime()}")
        check(staging.mkdirs()) { "Unable to create model staging directory" }
        try {
            val modelFile = File(staging, ExperimentalModelCatalog.translateGemma.requiredFiles.single())
            BufferedOutputStream(FileOutputStream(modelFile), COPY_BUFFER_SIZE).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
                output.flush()
            }
            check(modelFile.length() > 0L) { "Selected TranslateGemma model is empty" }
            return commitStaging(engine, staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun commitStaging(
        engine: ExperimentalTranslationEngine,
        staging: File
    ): ExperimentalModelStatus {
        val destination = modelDirectory(engine)
        val backup = File(workingRoot(), "${engine.storageId}.backup")
        backup.deleteRecursively()
        if (destination.exists()) {
            check(destination.renameTo(backup)) { "Unable to replace existing model" }
        }
        try {
            moveDirectory(staging, destination)
            backup.deleteRecursively()
        } catch (error: Throwable) {
            destination.deleteRecursively()
            if (backup.exists()) backup.renameTo(destination)
            throw error
        }
        return status(engine)
    }

    private fun unzipSafely(input: InputStream, destination: File) {
        val canonicalRoot = destination.canonicalFile
        var extractedBytes = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(destination, entry.name).canonicalFile
                check(output.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Unsafe model archive path: ${entry.name}"
                }
                if (entry.isDirectory) {
                    check(output.mkdirs() || output.isDirectory)
                } else {
                    val parent = checkNotNull(output.parentFile)
                    check(parent.mkdirs() || parent.isDirectory) {
                        "Unable to create model directory: ${parent.name}"
                    }
                    FileOutputStream(output).use { fileOutput ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extractedBytes += count
                            check(extractedBytes <= MAXIMUM_EXTRACTED_BYTES) {
                                "Model archive exceeds maximum extracted size"
                            }
                            fileOutput.write(buffer, 0, count)
                        }
                        fileOutput.fd.sync()
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun flattenSingleTopLevelDirectory(staging: File) {
        val children = staging.listFiles().orEmpty()
        if (children.size != 1 || !children.single().isDirectory) return
        val nested = children.single()
        nested.listFiles().orEmpty().forEach { child ->
            check(child.renameTo(File(staging, child.name))) { "Unable to flatten model archive" }
        }
        nested.delete()
    }

    private fun moveDirectory(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (source.renameTo(destination)) return
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun workingRoot(): File = File(root, WORKING_DIRECTORY).also {
        check(it.mkdirs() || it.isDirectory) { "Unable to create model working directory" }
    }

    private fun directorySize(directory: File): Long =
        directory.walkTopDown().filter(File::isFile).sumOf(File::length)

    private companion object {
        const val ROOT_DIRECTORY = "experimental-translation-models"
        const val WORKING_DIRECTORY = ".working"
        const val PREFERENCES = "experimental_translation_models"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val COPY_BUFFER_SIZE = 1024 * 1024
        const val FILE_SIGNATURE_BYTES = 4
        const val MAXIMUM_EXTRACTED_BYTES = 12L * 1024 * 1024 * 1024
    }
}
