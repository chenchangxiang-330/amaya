package com.amaya.intelligence.ui.components.shared

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LocalFilePreviewTest {
    @Test fun preservesChineseAndUtf16ReferencesWithoutChangingFiles() {
        val directory = Files.createTempDirectory("file-preview").toFile()
        try {
            val file = File(directory, "中文资料.txt")
            val content = "中文内容\nEnglish 123，标点与换行。"
            file.writeText(content)
            assertEquals(content, readLocalFilePreview(file.path).text)
            val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + content.toByteArray(Charsets.UTF_16LE)
            file.writeBytes(bytes)
            assertEquals(content, readLocalFilePreview(file.path).text)
            assertContentEquals(bytes, file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun boundsLongTextAndKeepsUnsupportedFilesOutOfTextRendering() {
        val directory = Files.createTempDirectory("file-preview").toFile()
        try {
            val file = File(directory, "long.txt").apply { writeText("文".repeat(TEXT_PREVIEW_LIMIT + 10)) }
            val before = file.length()
            val preview = readLocalFilePreview(file.path)
            assertTrue(preview.truncated)
            assertEquals(TEXT_PREVIEW_LIMIT, preview.text.length)
            assertEquals(before, file.length())
            val binary = File(directory, "data.pdf").apply { writeBytes(byteArrayOf(0, 1, 2)) }
            assertEquals(LocalFileKind.EXTERNAL, readLocalFilePreview(binary.path).kind)
            assertFails { readLocalFilePreview(binary.path, referenceText = true) }
            assertFails { readLocalFilePreview(File(directory, "missing.txt").path) }
            assertFails { readLocalFilePreview(directory.path) }
            val html = File(directory, "网页.html").apply { writeText("<h1>中文</h1>") }
            assertEquals(LocalFileKind.HTML, readLocalFilePreview(html.path).kind)
            assertEquals(LocalFileKind.TEXT, readLocalFilePreview(html.path, referenceText = true).kind)
        } finally { directory.deleteRecursively() }
    }
}
