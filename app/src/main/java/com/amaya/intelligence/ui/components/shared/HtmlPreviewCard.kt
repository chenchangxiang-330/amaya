package com.amaya.intelligence.ui.components.shared

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.amaya.intelligence.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/** Inline preview is offline: scripts may interact with the page, not the host application. */
@Composable
internal fun HtmlPreviewCard(path: String) {
    var reload by remember(path) { mutableIntStateOf(0) }
    var expanded by remember(path) { mutableStateOf(false) }
    val result by produceState<Pair<Boolean, HtmlPreviewDocument?>>(false to null, path, reload) {
        value = false to null
        value = true to withContext(Dispatchers.IO) { HtmlPreviewDocument.open(path) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.html_preview_title, File(path).name),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.html_preview_collapse else R.string.html_preview_expand))
            }
            IconButton(onClick = { reload++ }) {
                Icon(Icons.Default.Refresh, stringResource(R.string.html_preview_reload))
            }
        }
        val document = result.second
        when {
            !result.first -> LinearProgressIndicator(Modifier.fillMaxWidth())
            document == null -> Text(stringResource(R.string.html_preview_unavailable),
                style = MaterialTheme.typography.bodySmall)
            else -> key(document) {
                HtmlPreviewWebView(document, Modifier.fillMaxWidth().height(
                    if (expanded) (LocalConfiguration.current.screenHeightDp * 0.8f).dp else 320.dp))
            }
        }
        Text(stringResource(R.string.html_preview_local_only), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@SuppressLint("SetJavaScriptEnabled") // Offline origin, no file/content access, native bridge.
@Composable
private fun HtmlPreviewWebView(document: HtmlPreviewDocument, modifier: Modifier) {
    val context = LocalContext.current
    var failed by remember(document) { mutableStateOf(false) }
    val destroyed = remember(document) { booleanArrayOf(false) }
    val view = remember(document) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            isVerticalScrollBarEnabled = true
            contentDescription = context.getString(R.string.html_preview_accessibility)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !document.accepts(request.url.toString())

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val asset = if (request.method == "GET") document.asset(request.url.toString()) else null
                    val stream = asset?.let { runCatching { it.first.inputStream() }.getOrNull() }
                    return if (asset == null || stream == null) {
                        WebResourceResponse("text/plain", "UTF-8", 404, "Unavailable", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    } else {
                        WebResourceResponse(asset.second, "UTF-8", 200, "OK", mapOf(
                            "Content-Security-Policy" to "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'",
                            "X-Content-Type-Options" to "nosniff"
                        ), stream)
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    failed = true
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view.destroy()
                    destroyed[0] = true
                    return true
                }
            }
            loadUrl(document.url)
        }
    }
    DisposableEffect(view) {
        onDispose { if (!destroyed[0]) { view.stopLoading(); view.destroy() } }
    }
    if (failed) Text(stringResource(R.string.html_preview_unavailable))
    else AndroidView(factory = { view }, modifier = modifier)
}
