package com.example.imagetranslate.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

class WebValidationActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            contentDescription = "Web validation page"
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.loadWithOverviewMode = false
            settings.useWideViewPort = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.i(TAG, "page_finished url=$url")
                }
            }
        }
        setContentView(webView)
        if (savedInstanceState == null) {
            val url = intent.getStringExtra(EXTRA_URL).orEmpty()
            require(url.startsWith("https://") || url.startsWith("http://127.0.0.1")) {
                "Web validation URL must use HTTPS or local HTTP"
            }
            webView.loadUrl(url)
        }
        webView.post { executeCommand() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        webView.post { executeCommand() }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun executeCommand() {
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_SCROLL_BY -> {
                val distance = intent.getIntExtra(EXTRA_SCROLL_DISTANCE, DEFAULT_SCROLL_DISTANCE)
                webView.evaluateJavascript("window.scrollBy(0, $distance); void(0)", null)
                Log.i(TAG, "scroll_by distance=$distance")
            }
            COMMAND_CLICK_MORE -> webView.evaluateJavascript(
                """
                (() => {
                  const link = [...document.querySelectorAll('a')]
                    .find(item => item.textContent.trim().toLowerCase() === 'more');
                  if (!link) return 'not_found';
                  link.click();
                  return 'clicked';
                })()
                """.trimIndent()
            ) { result -> Log.i(TAG, "click_more result=$result") }
            COMMAND_SCROLL_TOP -> {
                webView.evaluateJavascript("window.scrollTo(0, 0); void(0)", null)
                Log.i(TAG, "scroll_top")
            }
        }
        intent.removeExtra(EXTRA_COMMAND)
    }

    companion object {
        private const val TAG = "WebValidation"
        const val EXTRA_URL = "url"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_SCROLL_DISTANCE = "scroll_distance"
        const val COMMAND_SCROLL_BY = "scroll_by"
        const val COMMAND_CLICK_MORE = "click_more"
        const val COMMAND_SCROLL_TOP = "scroll_top"
        private const val DEFAULT_SCROLL_DISTANCE = 1_600
    }
}
