package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import java.io.File
import java.net.URI
import java.util.UUID

/** Local web assets only; never expose the application's files through a JS bridge. */
internal class HtmlPreviewDocument private constructor(val file: File) {
    private val root = file.parentFile!!.canonicalFile
    val host = "${UUID.randomUUID()}.preview.invalid"
    val url: String = URI("https", host, "/${file.name}", null).toASCIIString()

    fun accepts(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawAuthority == host
    }.getOrDefault(false)

    fun asset(url: String): Pair<File, String>? = runCatching {
        if (!accepts(url)) return null
        val path = URI(url).path.removePrefix("/")
        if (path.split('/').any { it.startsWith('.') || it.contains('\\') }) return null
        val candidate = File(root, path).canonicalFile
        if (!candidate.toPath().startsWith(root.toPath()) || !candidate.isFile) return null
        val mime = MIME_TYPES[candidate.extension.lowercase()] ?: return null
        if (candidate.length() > MAX_ASSET_BYTES) return null
        candidate to mime
    }.getOrNull()

    companion object {
        const val MAX_ASSET_BYTES = 8L * 1024 * 1024
        private val MIME_TYPES = mapOf(
            "html" to "text/html", "htm" to "text/html", "css" to "text/css",
            "js" to "application/javascript", "mjs" to "application/javascript",
            "json" to "application/json", "svg" to "image/svg+xml",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "webp" to "image/webp", "gif" to "image/gif", "ico" to "image/x-icon",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf"
        )
        fun open(path: String): HtmlPreviewDocument? = runCatching {
            val file = File(path)
            if (!file.isAbsolute || file.extension.lowercase() !in setOf("html", "htm")) return null
            val canonical = file.canonicalFile
            if (canonical.name.startsWith(".") || !canonical.isFile || !canonical.canRead() || canonical.length() > MAX_ASSET_BYTES) return null
            HtmlPreviewDocument(canonical)
        }.getOrNull()
    }
}

internal fun htmlPreviewPath(execution: ToolExecution): String? {
    if (execution.status != ToolStatus.SUCCESS || execution.metadata["source"] != "local" ||
        execution.name !in setOf("write_file", "edit_file")) return null
    // write_file returns the host-resolved path even when the model used a relative path.
    val written = Regex("^Successfully (?:written to|appended to): (.+) \\(\\d+ chars\\)$")
        .matchEntire(execution.result.orEmpty().trim())?.groupValues?.get(1)
    val path = written ?: listOf("path", "TargetFile", "AbsolutePath", "file", "filePath")
        .firstNotNullOfOrNull { execution.arguments[it]?.toString()?.takeIf(String::isNotBlank) }
        ?: return null
    return path.takeIf { File(it).isAbsolute && File(it).extension.lowercase() in setOf("html", "htm") }
}
