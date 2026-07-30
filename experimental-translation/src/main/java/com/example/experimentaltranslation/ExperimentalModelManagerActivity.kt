package com.example.experimentaltranslation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExperimentalModelManagerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: ExperimentalModelRepository
    private lateinit var statusView: TextView
    private lateinit var urlInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var contractView: TextView
    private lateinit var actionButtons: List<Button>
    private var activeJob: Job? = null
    private var selectedEngine = ExperimentalTranslationEngine.MARIAN_INT8

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ExperimentalModelRepository(this)
        selectedEngine = intent.getStringExtra(EXTRA_ENGINE)
            ?.let { runCatching { ExperimentalTranslationEngine.valueOf(it) }.getOrNull() }
            ?.takeIf { it != ExperimentalTranslationEngine.DISABLED }
            ?: ExperimentalTranslationEngine.MARIAN_INT8
        setContentView(buildContent())
        refresh()
    }

    private fun buildContent(): View = ScrollView(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))

            addView(TextView(context).apply {
                text = "实验翻译模型"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "模型保存在应用私有目录，不会打入 APK。只有在悬浮窗中人工选择后才参与翻译。"
                textSize = 14f
                setPadding(0, dp(8), 0, dp(16))
            })

            val choices = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
            val marian = RadioButton(context).apply {
                id = View.generateViewId()
                text = "Marian INT8"
                isChecked = selectedEngine == ExperimentalTranslationEngine.MARIAN_INT8
            }
            val gemma = RadioButton(context).apply {
                id = View.generateViewId()
                text = "TranslateGemma 4B"
                isChecked = selectedEngine == ExperimentalTranslationEngine.TRANSLATEGEMMA_4B
            }
            choices.addView(marian)
            choices.addView(gemma)
            choices.setOnCheckedChangeListener { _, id ->
                selectedEngine = if (id == gemma.id) {
                    ExperimentalTranslationEngine.TRANSLATEGEMMA_4B
                } else {
                    ExperimentalTranslationEngine.MARIAN_INT8
                }
                refresh()
            }
            addView(choices)

            statusView = TextView(context).apply {
                textSize = 14f
                setPadding(0, dp(16), 0, dp(12))
            }
            addView(statusView)

            urlInput = EditText(context).apply {
                hint = "模型下载地址（ZIP 或 .task）"
                isSingleLine = true
            }
            addView(urlInput, matchWrap())

            progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                visibility = View.GONE
                isIndeterminate = true
            }
            addView(progress, matchWrap())

            val download = Button(context).apply {
                text = "下载并安装"
                setOnClickListener { downloadSelected() }
            }
            val import = Button(context).apply {
                text = "从电脑或文件导入"
                setOnClickListener { openArchive() }
            }
            val remove = Button(context).apply {
                text = "删除本地模型"
                setOnClickListener { removeSelected() }
            }
            actionButtons = listOf(download, import, remove)
            addView(download, matchWrap())
            addView(import, matchWrap())
            addView(remove, matchWrap())

            contractView = TextView(context).apply {
                text = archiveContractText()
                textSize = 12f
                setPadding(0, dp(20), 0, 0)
                typeface = Typeface.MONOSPACE
            }
            addView(contractView)
        })
    }

    private fun refresh() {
        val definition = ExperimentalModelCatalog.definition(selectedEngine)
        val status = repository.status(selectedEngine)
        statusView.text = buildString {
            append(definition.displayName).append(": ")
            append(
                when (status.state) {
                    ExperimentalModelState.READY -> "已就绪"
                    ExperimentalModelState.NOT_INSTALLED -> "未安装"
                    ExperimentalModelState.INSTALLING -> "正在安装"
                    ExperimentalModelState.INVALID -> "模型不完整"
                }
            )
            if (status.installedBytes > 0) append(" · ").append(formatBytes(status.installedBytes))
            status.message?.let { append("\n").append(it) }
        }
        urlInput.setText(repository.configuredDownloadUrl(selectedEngine).orEmpty())
        contractView.text = archiveContractText()
    }

    private fun downloadSelected() {
        repository.setDownloadUrl(selectedEngine, urlInput.text?.toString())
        runModelOperation {
            repository.download(selectedEngine) { downloaded, total ->
                runOnUiThread {
                    progress.isIndeterminate = total == null
                    if (total != null) {
                        progress.max = 1000
                        progress.progress = ((downloaded * 1000L) / total).toInt()
                    }
                }
            }
        }
    }

    private fun openArchive() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            REQUEST_IMPORT_ARCHIVE
        )
    }

    @Deprecated("Uses the Activity file picker for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_ARCHIVE || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        runModelOperation { repository.importArchive(selectedEngine, uri) }
    }

    private fun removeSelected() = runModelOperation {
        repository.remove(selectedEngine)
        repository.status(selectedEngine)
    }

    private fun runModelOperation(
        operation: suspend () -> ExperimentalModelStatus
    ) {
        if (activeJob?.isActive == true) return
        setBusy(true)
        activeJob = scope.launch {
            runCatching { withContext(Dispatchers.IO) { operation() } }
                .onSuccess { Toast.makeText(this@ExperimentalModelManagerActivity, "模型操作完成", Toast.LENGTH_SHORT).show() }
                .onFailure {
                    Log.e(TAG, "Experimental model operation failed", it)
                    Toast.makeText(
                        this@ExperimentalModelManagerActivity,
                        it.message ?: "模型操作失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            setBusy(false)
            refresh()
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        actionButtons.forEach { it.isEnabled = !busy }
        urlInput.isEnabled = !busy
    }

    private fun archiveContractText(): String {
        val definition = ExperimentalModelCatalog.definition(selectedEngine)
        return "导入文件 (${definition.suggestedArchiveName}):\n" +
            definition.requiredFiles.sorted().joinToString("\n") { "  $it" }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} KB"
    }

    companion object {
        private const val TAG = "ExperimentalModels"
        private const val EXTRA_ENGINE = "experimental_engine"
        private const val REQUEST_IMPORT_ARCHIVE = 7101

        fun intent(context: Context, engine: ExperimentalTranslationEngine): Intent =
            Intent(context, ExperimentalModelManagerActivity::class.java)
                .putExtra(EXTRA_ENGINE, engine.name)
    }
}
