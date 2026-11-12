package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class HtmlPreviewDocumentTest {
    @Test fun resolvesUnicodeAssetsButDeniesTraversalOtherOriginsAndSymlinks() {
        val directory = Files.createTempDirectory("html-preview").toFile()
        try {
            val root = File(directory, "site").apply { mkdir() }
            val page = File(root, "中文 + 网页.html").apply { writeText("<h1>你好</h1>") }
            val css = File(root, "样式.css").apply { writeText("h1{color:red}") }
            val outside = File(directory, "private.json").apply { writeText("private") }
            val document = HtmlPreviewDocument.open(page.path)!!
            assertEquals(page.canonicalFile, document.asset(document.url)?.first)
            assertEquals(css.canonicalFile, document.asset("https://${document.host}/%E6%A0%B7%E5%BC%8F.css")?.first)
            assertNull(document.asset("https://${document.host}/%2e%2e/private.json"))
            assertNull(document.asset("https://example.com/${page.name}"))
            assertNull(document.asset("file://${outside.path}"))
            assertNull(document.asset("content://files/private.json"))
            Files.createSymbolicLink(File(root, "escape.json").toPath(), outside.toPath())
            assertNull(document.asset("https://${document.host}/escape.json"))
            File(root, ".secrets.json").writeText("private")
            assertNull(document.asset("https://${document.host}/.secrets.json"))
            assertNull(document.asset("https://${document.host}/missing.js"))
            assertNotEquals(document.host, HtmlPreviewDocument.open(page.path)!!.host)
        } finally { directory.deleteRecursively() }
    }

    @Test fun onlySuccessfulLocalHtmlWritesBecomePreviews() {
        val tool = ToolExecution("write", "write_file", mapOf("path" to "页面.html"),
            result = "Successfully written to: /storage/project/页面.html (123 chars)",
            status = ToolStatus.SUCCESS, metadata = mapOf("source" to "local"))
        assertEquals("/storage/project/页面.html", htmlPreviewPath(tool))
        assertNull(htmlPreviewPath(tool.copy(status = ToolStatus.ERROR)))
        assertNull(htmlPreviewPath(tool.copy(status = ToolStatus.RUNNING)))
        assertNull(htmlPreviewPath(tool.copy(metadata = emptyMap())))
        assertNull(htmlPreviewPath(tool.copy(name = "delete_file")))
        assertNull(htmlPreviewPath(tool.copy(result = null)))
        assertNull(htmlPreviewPath(tool.copy(result = "Successfully written to: /tmp/code.kt (123 chars)")))
    }
}
