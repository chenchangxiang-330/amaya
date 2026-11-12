package com.amaya.intelligence.ui.components.shared

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

internal enum class LocalFileKind { TEXT, HTML, IMAGE, EXTERNAL }

internal data class LocalFilePreview(
    val file: File,
    val kind: LocalFileKind,
    val text: String = "",
    val truncated: Boolean = false
)

internal const val TEXT_PREVIEW_LIMIT = 200_000

/** Bounded, read-only previews; references are text even when their extension is unknown. */
internal fun readLocalFilePreview(path: String, referenceText: Boolean = false): LocalFilePreview {
    val file = File(path).canonicalFile
    require(file.isFile && file.canRead())
    val extension = file.extension.lowercase()
    val kind = when {
        referenceText -> LocalFileKind.TEXT
        extension in setOf("html", "htm") -> LocalFileKind.HTML
        extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> LocalFileKind.IMAGE
        extension in setOf("txt", "md", "markdown", "json", "xml", "csv", "tsv", "log", "yaml", "yml",
            "kt", "java", "js", "mjs", "ts", "tsx", "jsx", "css", "py", "sh", "sql", "toml", "ini", "conf") -> LocalFileKind.TEXT
        else -> LocalFileKind.EXTERNAL
    }
    if (kind != LocalFileKind.TEXT) return LocalFilePreview(file, kind)
    val text = file.inputStream().buffered().use { input ->
        input.mark(3)
        val first = input.read()
        val second = input.read()
        val third = input.read()
        input.reset()
        val (charset, bomBytes) = when {
            first == 0xFF && second == 0xFE -> Charsets.UTF_16LE to 2
            first == 0xFE && second == 0xFF -> Charsets.UTF_16BE to 2
            first == 0xEF && second == 0xBB && third == 0xBF -> Charsets.UTF_8 to 3
            else -> Charsets.UTF_8 to 0
        }
        repeat(bomBytes) { input.read() }
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        InputStreamReader(input, decoder).use { reader ->
            val buffer = CharArray(4096)
            buildString {
                while (length <= TEXT_PREVIEW_LIMIT) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, TEXT_PREVIEW_LIMIT + 1 - length))
                    if (count < 0) break
                    append(buffer, 0, count)
                }
            }
        }
    }
    require('\u0000' !in text)
    var displayed = text.take(TEXT_PREVIEW_LIMIT)
    if (text.length > TEXT_PREVIEW_LIMIT && displayed.lastOrNull()?.isHighSurrogate() == true) {
        displayed = displayed.dropLast(1)
    }
    return LocalFilePreview(file, kind, displayed, text.length > TEXT_PREVIEW_LIMIT)
}
