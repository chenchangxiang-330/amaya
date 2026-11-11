package com.amaya.intelligence.localization

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Fork-only UI checks. No live model or mock server is used. */
@RunWith(AndroidJUnit4::class)
class LocalizationSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val output = File(context.filesDir, "locale-validation").apply { mkdirs() }

    private fun launch() {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        device.waitForIdle()
    }

    private fun find(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), 10_000)
            ?: throw AssertionError("Missing $selector. See failure screenshot.")

    // Compose merges clickable row titles and supporting text into one accessibility node.
    private fun label(value: String) = By.text(Pattern.compile("^" + Pattern.quote(value) + "(?:\\n.*)?$", Pattern.DOTALL))
    private fun text(value: String) = find(label(value))
    private fun click(value: String) { text(value).click(); device.waitForIdle() }
    private fun desc(value: String) { find(By.desc(value)).click(); device.waitForIdle() }
    private fun back() { device.pressBack(); device.waitForIdle() }

    private fun capture(name: String) {
        // Allow Compose navigation/sheet transitions to finish before the visual record.
        SystemClock.sleep(750)
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
        device.dumpWindowHierarchy(File(output, "$name.xml"))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SCREENSHOT: $name\n") })
    }

    private fun scrollTo(value: String): UiObject2 {
        repeat(9) {
            device.findObject(label(value))?.let {
                SystemClock.sleep(500)
                return text(value)
            }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 30)
            SystemClock.sleep(400)
            device.waitForIdle()
        }
        return text(value)
    }

    private fun fields(vararg values: String) {
        val edits = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 10_000).orEmpty()
        assertTrue("Expected ${values.size} edit fields, found ${edits.size}", edits.size >= values.size)
        values.forEachIndexed { index, value -> edits[index].text = value }
        device.waitForIdle()
        // Hide the IME only when it is actually visible.
        if (device.findObject(By.pkg("com.google.android.inputmethod.latin")) != null ||
            device.findObject(By.pkg("com.android.inputmethod.latin")) != null) back()
    }

    private fun settings(chinese: Boolean) {
        desc(if (chinese) "菜单" else "Menu")
        desc(if (chinese) "设置" else "Settings")
    }

    private fun openLanguage(chinese: Boolean) {
        repeat(3) {
            val title = if (chinese) "语言" else "Language"
            val row = scrollTo(title)
            // Settings has a floating tab bar; move this row clear of it before tapping.
            if (row.visibleBounds.centerY() > device.displayHeight * 4 / 5) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 2, 30)
                SystemClock.sleep(500)
            }
            text(title).click()
            val marker = if (chinese) "English" else "简体中文"
            if (device.wait(Until.hasObject(label(marker)), 2_000)) return
        }
        throw AssertionError("Language dialog did not open")
    }

    private fun language(choice: String, chinese: Boolean) {
        openLanguage(chinese)
        capture("language-options-${if (chinese) "zh" else "en"}")
        click(choice)
        device.waitForIdle()
    }

    private fun workspace() {
        click("选择工作区")
        // The app's file browser is app-controlled; no system picker text is asserted here.
        click("Download")
        click("Amaya-l10n")
        capture("workspace-picker-zh")
        desc("选择")
    }

    @Test fun chineseCoreFlow() = recorded("core-flow") {
        launch()
        settings(false)
        capture("settings-en")
        language("简体中文", false)
        text("设置")
        capture("settings-zh")
        back()
        capture("home-zh")

        settings(true)
        click("管理模型")
        capture("models-empty-zh")
        click("添加模型服务商")
        for (attempt in 1..3) {
            scrollTo("OpenAI-compatible").click()
            if (device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 3_000)) break
        }
        fields("中文测试服务", "not-a-url", "")
        scrollTo("连接").click()
        text("无法完成操作")
        text("请输入包含 https:// 等协议前缀的有效网址")
        capture("provider-invalid-url-zh")
        fields("中文测试服务", "https://example.invalid/v1", "")
        scrollTo("跳过模型列表并保存").click()
        // This deliberately saves an offline configuration, without claiming a connection.
        text("中文测试服务")
        capture("provider-saved-offline-zh")
        desc("添加自定义模型")
        fields("localization-test-model", "测试模型")
        click("添加模型")
        capture("custom-model-zh")
        desc("配置 测试模型")
        text("配置模型")
        capture("model-config-zh")
        back()
        back()
        click("选择模型")
        click("测试模型")
        capture("active-model-zh")
        back()

        desc("项目")
        desc("添加项目")
        fields("中文项目", "这是中文项目指令。请结合项目资料回答问题，并保留用户输入的名称。")
        workspace()
        click("创建")
        click("中文项目")
        capture("project-details-zh")
        click("资料")
        capture("project-references-empty-zh")
        desc("添加笔记")
        fields("中文资料", "这是一份用于本地化验证的中文项目资料。\n第二行包含标点、数字 123 和 English，验证保存后内容保持原样。")
        capture("reference-editor-zh")
        click("保存")
        text("中文资料.txt")
        capture("project-references-saved-zh")
        back()
        scrollTo("删除项目").click()
        text("删除“中文项目”？")
        capture("delete-project-dialog-zh")
        click("取消")
        back()

        desc("智能体")
        desc("添加智能体群组")
        fields("中文协作组", "共同整理中文项目资料。")
        workspace()
        click("创建")
        click("中文协作组")
        capture("agent-group-zh")
        desc("添加智能体")
        fields("中文助手", "资料整理", "阅读项目资料后，用简洁自然的中文回答。")
        click("添加智能体")
        click("中文助手 · ID 1")
        capture("agent-config-zh")
        scrollTo("委派")
        capture("agent-tools-zh")
        scrollTo("删除智能体").click()
        capture("delete-agent-dialog-zh")
        click("取消")
        back()
        back()
        back()

        desc("菜单")
        click("中文项目")
        // First New Chat is the expanded project's child, preceding global chats.
        click("新建聊天")
        capture("project-chat-zh")
        fields("请概括中文项目资料")
        desc("发送")
        // No credential is configured. A real model response is explicitly out of scope.
        assertTrue("The offline endpoint must surface an application error",
            device.wait(Until.hasObject(label("无法完成操作")), 30_000))
        capture("chat-without-live-api-zh")
    }

    private fun seedPickerFile(name: String, mime: String, image: Boolean): android.net.Uri {
        val collection = if (image) android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, if (image) "Pictures/AmayaValidation" else "Download/AmayaValidation")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { output ->
            if (image) {
                val bitmap = android.graphics.Bitmap.createBitmap(2400, 1200, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLUE)
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
            } else output.write("中文文件内容，用于验证附件显示与重选。".toByteArray())
        }
        context.contentResolver.update(uri, android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        return uri
    }

    private fun pickFile(name: String) {
        desc("添加附件")
        click("添加文件")
        val recent = device.wait(Until.findObject(By.text(name)), 8_000)
        if (recent == null) {
            capture("system-file-picker")
            val roots = device.findObject(By.desc("Show roots")) ?: device.findObject(By.desc("显示根目录"))
            roots?.click()
            device.waitForIdle()
            val location = if (name.endsWith(".png")) "Images" else "Downloads"
            device.findObject(By.text(location))?.click()
            device.waitForIdle()
            device.findObject(By.text("AmayaValidation"))?.click()
        }
        find(By.text(name)).click()
        device.waitForIdle()
    }

    private fun openProjectConversation(title: String) {
        desc("菜单")
        click("中文项目")
        click(title)
    }

    private fun projectRecord(): Triple<Long, String, String> {
        val file = context.databaseList().map(context::getDatabasePath).first { it.name.endsWith(".db") || it.name.contains("amaya") }
        return android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, title, messages_json FROM conversations WHERE assistant_mode = 'PROJECT' ORDER BY updated_at DESC LIMIT 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
            }
        }
    }

    @Test fun pickedPhotosAndFilesAreVisible() = recorded("photo-and-file-attachments") {
        launch()
        openProjectConversation("请概括中文项目资料")
        seedPickerFile("中文照片.png", "image/png", true)
        seedPickerFile("中文附件.txt", "text/plain", false)
        pickFile("中文照片.png")
        text("中文照片.jpg")
        capture("photo-picked-through-add-file")
        desc("移除")
        assertFalse(device.hasObject(label("中文照片.jpg")))
        pickFile("中文附件.txt")
        text("中文附件.txt")
        capture("text-file-attachment-visible")
        desc("移除")
        pickFile("中文附件.txt")
        text("中文附件.txt")
        desc("移除")
        pickFile("中文照片.png")
        text("中文照片.jpg")
        fields("请查看这张测试图片")
        desc("发送")
        assertTrue(device.wait(Until.hasObject(label("无法完成操作")), 30_000))
        SystemClock.sleep(1500)
        val messages = org.json.JSONArray(projectRecord().third)
        val photo = (0 until messages.length()).map { messages.getJSONObject(it) }
            .first { it.optString("content") == "请查看这张测试图片" }
        val attachments = photo.getJSONArray("attachments")
        assertTrue("Photo is retained as an image attachment", attachments.length() > 0)
        assertTrue(attachments.toString().contains("image/jpeg"))
        assertFalse("Image bytes must not become text-file instructions", photo.getString("content").contains("Attached file content"))
        capture("photo-message-without-live-api")
    }

    @Test fun projectConversationCanBeRenamed() = recorded("project-conversation-rename") {
        launch()
        val before = projectRecord()
        desc("菜单")
        click("中文项目")
        text(before.second).longClick()
        click("重命名对话")
        fields("")
        assertFalse("Blank names cannot be saved", text("保存").isEnabled)
        fields("取消的名称")
        click("取消")
        assertEquals(before.second, projectRecord().second)
        text(before.second).longClick()
        click("重命名对话")
        fields("项目照片讨论（已重命名）")
        capture("rename-project-conversation-dialog")
        click("保存")
        text("项目照片讨论（已重命名）")
        val after = projectRecord()
        assertEquals(before.first, after.first)
        assertEquals(before.third, after.third)
        File(output, "renamed-conversation.json").writeText(org.json.JSONObject()
            .put("id", before.first).put("messages", before.third).toString())
        capture("project-conversation-renamed")
        text(after.second).longClick()
        click("删除")
        text("删除对话？")
        click("取消")
        assertEquals(after.second, projectRecord().second)
    }

    @Test fun renamedConversationSurvivesRestart() = recorded("renamed-conversation-restart") {
        launch()
        openProjectConversation("项目照片讨论（已重命名）")
        val before = org.json.JSONObject(File(output, "renamed-conversation.json").readText())
        val after = projectRecord()
        assertEquals(before.getLong("id"), after.first)
        assertEquals(before.getString("messages"), after.third)
        assertEquals("项目照片讨论（已重命名）", after.second)
        capture("renamed-project-conversation-after-restart")
    }

    @Test fun chinesePersistsThenEnglish() = recorded("zh-restart") {
        launch()
        desc("菜单")
        text("中文项目")
        text("中文协作组")
        capture("chinese-after-process-restart")
        val savedReference = File(context.filesDir, "references").walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith("_中文资料.txt") }
        assertNotNull("Chinese reference filename survives process restart", savedReference)
        assertTrue(savedReference!!.readText().contains("第二行包含标点、数字 123 和 English"))
        desc("设置")
        language("English", true)
        text("Settings")
        capture("settings-english-after-switch")
    }

    @Test fun englishPersistsThenSystem() = recorded("en-restart") {
        launch()
        desc("Menu")
        text("中文项目")
        text("中文协作组")
        capture("english-after-process-restart")
        desc("Settings")
        language("Follow system", false)
        text("Settings")
        capture("follow-system-english")
    }

    @Test fun followsChineseSystem() = recorded("system-zh") {
        launch()
        desc("菜单")
        text("中文项目")
        text("中文协作组")
        capture("follow-system-chinese")
        desc("设置")
        openLanguage(true)
        text("跟随系统")
        capture("follow-system-choice-zh")
        click("取消")
    }

    @Test fun unsupportedSystemFallsBackToEnglish() = recorded("fallback") {
        launch()
        settings(false)
        text("Settings")
        capture("unsupported-system-english-fallback")
    }

    @Test fun largeFontLanguageDialog() = recorded("large-font") {
        launch()
        settings(false)
        openLanguage(false)
        capture("language-dialog-large-font")
        click("简体中文")
        openLanguage(true)
        capture("language-dialog-chinese-large-font")
    }

    private fun recorded(name: String, body: () -> Unit) {
        try { body(); File(output, "$name-result.txt").writeText("PASS\nNo live API or mocked model response used.\n") }
        catch (t: Throwable) {
            capture("failure-$name")
            File(output, "$name-result.txt").writeText("FAIL: ${t.stackTraceToString()}")
            throw t
        }
    }
}
