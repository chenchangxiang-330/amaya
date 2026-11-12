package com.amaya.intelligence.ui.activities.shared

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.amaya.intelligence.R
import com.amaya.intelligence.ui.components.shared.*
import com.amaya.intelligence.ui.theme.AmayaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Read-only viewer shared by workspace files and imported references. */
class LocalFileViewerActivity : AppCompatActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: File(path).name
        val referenceText = intent.getBooleanExtra(EXTRA_REFERENCE_TEXT, false)
        setContent {
            AmayaTheme {
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val preview by produceState<Result<LocalFilePreview>?>(null, path) {
                    value = withContext(Dispatchers.IO) { runCatching { readLocalFilePreview(path, referenceText) } }
                }
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = { SettingsBackButton(onClick = { finish() }) })
                    },
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val result = preview
                        val item = result?.getOrNull()
                        when {
                            result == null -> CircularProgressIndicator()
                            item == null -> Text(stringResource(R.string.file_preview_unavailable))
                            item.kind == LocalFileKind.HTML -> HtmlPreviewCard(item.file.path)
                            item.kind == LocalFileKind.IMAGE -> {
                                var imageFailed by remember { mutableStateOf(false) }
                                if (imageFailed) Text(stringResource(R.string.file_preview_unavailable))
                                else AsyncImage(
                                    model = item.file,
                                    contentDescription = stringResource(R.string.file_image_preview, title),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 640.dp),
                                    contentScale = ContentScale.Fit,
                                    onError = { imageFailed = true }
                                )
                            }
                            item.kind == LocalFileKind.TEXT -> {
                                if (item.truncated) Text(stringResource(R.string.text_preview_truncated, TEXT_PREVIEW_LIMIT),
                                    style = MaterialTheme.typography.bodySmall)
                                SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyLarge) }
                            }
                            else -> Text(stringResource(R.string.file_preview_external))
                        }
                        if (result != null) OutlinedButton(onClick = {
                            runCatching { openExternally(path) }.onFailure {
                                scope.launch { snackbar.showSnackbar(getString(R.string.no_app_to_open_file)) }
                            }
                        }) { Text(stringResource(R.string.open_with_app)) }
                    }
                }
            }
        }
    }

    private fun openExternally(path: String) {
        val file = File(path).canonicalFile
        require(file.isFile && file.canRead())
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(file.name, uri) }
        startActivity(Intent.createChooser(view, getString(R.string.open_with_app)))
    }

    companion object {
        private const val EXTRA_PATH = "file_path"
        private const val EXTRA_TITLE = "file_title"
        private const val EXTRA_REFERENCE_TEXT = "reference_text"
        fun start(context: Context, path: String, referenceText: Boolean = false, title: String = File(path).name) {
            context.startActivity(Intent(context, LocalFileViewerActivity::class.java)
                .putExtra(EXTRA_PATH, path).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_REFERENCE_TEXT, referenceText))
        }
    }
}
