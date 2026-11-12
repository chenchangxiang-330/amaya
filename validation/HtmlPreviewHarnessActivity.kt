package com.amaya.intelligence.localization

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.ui.components.shared.MessageBubble
import com.amaya.intelligence.domain.models.UiMessage
import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.ui.theme.AmayaTheme
import java.io.File

/** Fork-only fixture host: the actual production chat tool card, with synthetic tool metadata. */
class HtmlPreviewHarnessActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(filesDir, "html-preview-test/中文网页.html")
        setContent {
            AmayaTheme {
                Column(Modifier.fillMaxSize().padding(top = 40.dp).verticalScroll(rememberScrollState())) {
                    Text("HTML preview UI fixture · no live model")
                    val tool = ToolExecution("html-fixture", "write_file", mapOf("path" to "中文网页.html"),
                        "Successfully written to: ${file.path} (123 chars)", ToolStatus.SUCCESS,
                        metadata = mapOf("source" to "local"))
                    MessageBubble(message = UiMessage(role = MessageRole.ASSISTANT, content = "网页已生成。",
                        toolExecutions = listOf(tool, tool.copy(toolCallId = "duplicate-history")),
                        metadata = mapOf("source" to "local")))
                }
            }
        }
    }
}
