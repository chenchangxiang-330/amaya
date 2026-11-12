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
import com.amaya.intelligence.data.remote.api.ActiveModelSelection
import com.amaya.intelligence.data.remote.api.AiSettingsManager
import com.amaya.intelligence.data.remote.api.ConfiguredModel
import com.amaya.intelligence.data.remote.api.ProviderConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Fork-only UI checks. No live model response is used. The capability regression uses
 * a loopback catalog fixture and records image requests, returning an intentional error. */
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
        dismissLauncherAnr()
    }

    private fun dismissLauncherAnr() {
        // A cold cloud emulator can ANR its launcher. Record it and close only that
        // system app; an Amaya crash/ANR must remain visible and fail the check.
        if (device.hasObject(By.pkg("android").text("Pixel Launcher isn't responding"))) {
            capture("emulator-pixel-launcher-anr")
            device.findObject(By.pkg("android").text("Close app"))!!.click()
            device.waitForIdle()
        }
    }

    private fun find(selector: BySelector): UiObject2 {
        repeat(5) {
            dismissLauncherAnr()
            device.wait(Until.findObject(selector), 2_000)?.let { return it }
        }
        throw AssertionError("Missing $selector. See failure screenshot.")
    }

    // Compose merges clickable row titles and supporting text into one accessibility node.
    private fun label(value: String) = By.text(Pattern.compile("^" + Pattern.quote(value) + "(?:\\n.*)?$", Pattern.DOTALL))
    private fun text(value: String) = find(label(value))
    private fun click(value: String) { text(value).click(); device.waitForIdle() }
    private fun desc(value: String) { find(By.desc(value)).click(); device.waitForIdle() }
    private fun back() { device.pressBack(); device.waitForIdle() }

    private fun tapPicker(selector: BySelector) {
        repeat(3) {
            try { find(selector).click(); device.waitForIdle(); return }
            catch (_: androidx.test.uiautomator.StaleObjectException) { device.waitForIdle() }
        }
        throw AssertionError("Picker kept refreshing $selector")
    }

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
        val deadline = SystemClock.uptimeMillis() + 10_000
        var edits = device.findObjects(By.clazz("android.widget.EditText"))
        while (edits.size < values.size && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            edits = device.findObjects(By.clazz("android.widget.EditText"))
        }
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
                bitmap.compress(if (mime == "image/jpeg") android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG, 100, output)
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
            tapPicker(By.desc(Pattern.compile("Show roots|显示根目录")))
            val location = if (name.endsWith(".png") || name.endsWith(".jpg")) "Images" else "Downloads"
            // Android 13 also has an Images chip behind the open drawer.
            // Select the actual root title, then wait for its folder listing.
            tapPicker(By.res("android:id/title").text(location))
            if (device.wait(Until.hasObject(By.res("android:id/title").text("AmayaValidation")), 5_000)) {
                tapPicker(By.res("android:id/title").text("AmayaValidation"))
            }
        }
        tapPicker(By.res("android:id/title").text(name))
        assertTrue("Picker must return to the chat", device.wait(Until.hasObject(By.desc("添加附件")), 10_000))
        device.waitForIdle()
    }

    private fun conversationRow(title: String): UiObject2 {
        // The title is a TextView child; long-click semantics belong to its row.
        var row = text(title)
        while (!row.isLongClickable) {
            row = row.parent ?: throw AssertionError("No long-click action on conversation $title")
        }
        return row
    }

    private fun openProjectConversation(title: String) {
        desc("菜单")
        click("中文项目")
        conversationRow(title).click()
        device.waitForIdle()
    }

    private fun projectRecord(): Triple<Long, String, String> {
        val file = context.getDatabasePath("Amaya_db")
        return android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, title, messages_json FROM conversations WHERE assistant_mode = 'PROJECT' ORDER BY updated_at DESC LIMIT 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
            }
        }
    }

    private fun projectFilesRecord(): JSONObject {
        return android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("Amaya_db").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, name, root_path, reference_paths_json FROM projects WHERE name = ?", arrayOf("中文项目")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                JSONObject().put("id",cursor.getLong(0)).put("name",cursor.getString(1))
                    .put("root",cursor.getString(2)).put("references",cursor.getString(3))
            }
        }
    }

    private fun openProjectConfiguration() {
        context.startActivity(Intent().setClassName(context.packageName,
            "com.amaya.intelligence.ui.activities.project.local.LocalProjectDetailActivity")
            .putExtra("project_id", projectFilesRecord().getLong("id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        text("中文项目")
    }

    @Test fun projectWorkspaceAndReferencesCanBeViewed() = recorded("project-file-viewing") {
        val before = projectFilesRecord()
        val root = File(before.getString("root"))
        val explanation = File(root,"工作区说明.txt").apply { writeText("工作区文件内容：中文与 English。\n第二行保持不变。") }
        File(root,"子目录").mkdirs()
        File(root,"子目录/子文件.txt").writeText("这是子目录内的文件。")
        File(root,"工作区网页.html").writeText("""<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><h2>工作区交互网页</h2><button onclick="this.textContent='交互成功'">改变内容</button></html>""")
        File(root,"未知格式.bin").writeBytes(byteArrayOf(0,1,2,3))
        val picture = android.graphics.Bitmap.createBitmap(48,48,android.graphics.Bitmap.Config.ARGB_8888)
        picture.eraseColor(android.graphics.Color.BLUE)
        File(root,"工作区图片.png").outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        picture.recycle()
        openProjectConfiguration()
        click("工作区")
        text("工作区说明.txt")
        assertFalse("Browsing must not reassign the project workspace",device.hasObject(By.desc("选择")))
        capture("existing-project-workspace-browse")
        click("工作区说明.txt")
        find(By.textContains("工作区文件内容：中文与 English。"))
        capture("workspace-text-viewer")
        back()
        click("子目录")
        click("子文件.txt")
        text("这是子目录内的文件。")
        back()
        back() // Browsing history returns to the original workspace, not the project screen.
        click("工作区网页.html")
        text("工作区交互网页")
        click("改变内容")
        text("交互成功")
        capture("workspace-interactive-html")
        back()
        click("工作区图片.png")
        find(By.desc("图片：工作区图片.png"))
        capture("workspace-image-viewer")
        back()
        click("未知格式.bin")
        text("暂不支持预览此类型的文件，可用其他应用打开。")
        text("用其他应用打开")
        capture("workspace-unsupported-file")
        back()
        back()
        assertEquals(before.getString("root"), projectFilesRecord().getString("root"))
        click("资料")
        click("中文资料.txt")
        find(By.textContains("这是一份用于本地化验证的中文项目资料。"))
        assertFalse("Viewing a reference must not open removal confirmation",device.hasObject(label("移除资料？")))
        capture("project-reference-read-only-viewer")
        back()
        desc("移除 中文资料.txt")
        capture("reference-separate-remove-confirmation")
        click("取消")
        assertEquals(before.getString("references"),projectFilesRecord().getString("references"))
        desc("添加笔记")
        fields("待移除资料", "这份资料仅用于移除验证。")
        click("保存")
        text("待移除资料.txt")
        back()
        click("项目名称")
        fields("中文项目")
        click("保存名称")
        assertEquals("Saving project identity must keep newly added references",2,
            org.json.JSONArray(projectFilesRecord().getString("references")).length())
        click("资料")
        click("待移除资料.txt")
        text("这份资料仅用于移除验证。")
        back()
        desc("移除 待移除资料.txt")
        click("移除")
        assertTrue(device.wait(Until.gone(label("待移除资料.txt")),10_000))
        text("中文资料.txt")
        assertEquals(before.getString("references"),projectFilesRecord().getString("references"))
        val reference = File(org.json.JSONArray(before.getString("references")).getString(0))
        val content = reference.readBytes()
        assertTrue(reference.delete()) // Simulate a missing imported file in the isolated emulator only.
        try {
            click("中文资料.txt")
            text("文件不存在或无法读取。")
            capture("reference-missing-file-error")
            back()
        } finally { reference.writeBytes(content) }
        back()
        val temporarilyMoved = File(root.parentFile, "Amaya-l10n-temporarily-moved")
        assertTrue(root.renameTo(temporarilyMoved))
        try {
            click("工作区")
            text("文件夹不存在或无法读取，请检查文件访问权限。")
            capture("workspace-missing-folder-error")
        } finally { assertTrue(temporarilyMoved.renameTo(root)) }
        click("重试")
        text("工作区说明.txt")
        capture("workspace-retry-after-folder-restored")
        assertEquals("工作区文件内容：中文与 English。\n第二行保持不变。",explanation.readText())
        run {
            assertTrue(content.contentEquals(reference.readBytes()))
            File(output,"project-files-before-restart.json").writeText(projectFilesRecord().toString())
        }
    }

    @Test fun projectFileViewsSurviveRestartAndEnglish() = recorded("project-file-viewing-restart") {
        val before = JSONObject(File(output,"project-files-before-restart.json").readText())
        val after = projectFilesRecord()
        assertEquals(before.toString(),after.toString())
        openProjectConfiguration()
        click("工作区")
        click("工作区说明.txt")
        find(By.textContains("工作区文件内容：中文与 English。"))
        capture("workspace-view-after-restart")
        back()
        back()
        click("资料")
        click("中文资料.txt")
        find(By.textContains("第二行包含标点、数字 123 和 English"))
        capture("reference-view-after-restart")
        launch()
        settings(true)
        device.findObject(label("全局"))?.click()
        language("English",true)
        text("Settings")
        openProjectConfiguration()
        click("Workspace")
        click("未知格式.bin")
        text("Preview is unavailable for this file type. Open it with another app.")
        text("Open with another app")
        capture("workspace-viewer-english")
        back()
        back()
        click("References")
        click("中文资料.txt")
        find(By.textContains("这是一份用于本地化验证的中文项目资料。"))
        text("Open with another app")
        capture("reference-viewer-english-chinese-content")
        assertEquals(before.toString(),projectFilesRecord().toString())
    }

    @Test fun pickedPhotosAndFilesAreVisible() = recorded("photo-and-file-attachments") {
        launch()
        openProjectConversation("请概括中文项目资料")
        seedPickerFile("中文照片.png", "image/png", true)
        seedPickerFile("相册照片.jpg", "image/jpeg", true)
        seedPickerFile("中文附件.txt", "text/plain", false)
        pickFile("中文照片.png")
        text("中文照片.jpg")
        capture("photo-picked-through-add-file")
        desc("移除")
        assertFalse(device.hasObject(label("中文照片.jpg")))
        pickFile("相册照片.jpg")
        text("相册照片.jpg")
        capture("jpeg-photo-attachment-visible")
        desc("移除")
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
        val savedImage = attachments.getJSONObject(0)
        assertEquals("image/jpeg", savedImage.getString("mimeType"))
        val bytes = android.util.Base64.decode(savedImage.getString("dataBase64"), android.util.Base64.DEFAULT)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Stored attachment decodes as an image", bitmap)
        assertTrue("Large photo is sampled", maxOf(bitmap!!.width, bitmap.height) <= 2048)
        bitmap.recycle()
        assertFalse("Image bytes must not become text-file instructions", photo.getString("content").contains("Attached file content"))
        capture("photo-message-without-live-api")
    }

    @Test fun projectConversationCanBeRenamed() = recorded("project-conversation-rename") {
        launch()
        val before = projectRecord()
        desc("菜单")
        click("中文项目")
        conversationRow(before.second).longClick()
        click("重命名对话")
        fields("")
        assertTrue("Blank names cannot be saved",
            generateSequence(text("保存")) { it.parent }.any { !it.isEnabled })
        fields("取消的名称")
        click("取消")
        assertEquals(before.second, projectRecord().second)
        conversationRow(before.second).longClick()
        click("重命名对话")
        fields("项目照片讨论（已重命名）")
        capture("rename-project-conversation-dialog")
        click("保存")
        assertTrue(device.wait(Until.gone(By.text("重命名对话")), 10_000))
        text("项目照片讨论（已重命名）")
        val after = projectRecord()
        assertEquals(before.first, after.first)
        assertEquals(before.third, after.third)
        File(output, "renamed-conversation.json").writeText(org.json.JSONObject()
            .put("id", before.first).put("messages", before.third).toString())
        capture("project-conversation-renamed")
        conversationRow(after.second).longClick()
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

    @Test fun refreshedImageCapabilityReachesProvider() = recorded("image-capability-refresh") {
        CatalogFixture().use { fixture ->
            val manager = AiSettingsManager(context.applicationContext)
            val savedModel = ConfiguredModel(
                id = "deepseek-flash", displayName = "图片能力回归模型",
                contextWindowTokens = 32_768, maxOutputTokens = 1_024,
                supportsTools = false, supportsImages = false
            )
            val connection = ProviderConnection(
                id = "image-capability-regression", name = "图片能力回归服务",
                providerId = "custom_openai_compatible", baseUrl = fixture.baseUrl,
                visibleModels = listOf(savedModel)
            )
            runBlocking {
                manager.saveConnection(connection)
                manager.setActiveModel(ActiveModelSelection(connection.id, savedModel.id))
            }
            launch()
            openProjectConversation("项目照片讨论（已重命名）")
            seedPickerFile("能力验证照片.png", "image/png", true)
            pickFile("能力验证照片.png")
            val rejectedDraft = "图片被拦截时保留这段中文草稿"
            fields(rejectedDraft)
            desc("发送")
            text("当前模型不支持图片，请选择支持图片的模型。")
            assertEquals(rejectedDraft, find(By.clazz("android.widget.EditText")).text)
            text("能力验证照片.jpg")
            assertTrue("Local rejection must not send a request", fixture.chatRequests.isEmpty())
            capture("image-rejection-retains-draft-and-attachment")

            settings(true)
            // A project chat opens Settings on the Project tab.
            desc("全局")
            click("管理模型")
            // The active-model subtitle also names this provider. Open its inventory row.
            val providerRows = device.wait(Until.findObjects(label("图片能力回归服务")), 10_000)
            assertFalse("Provider inventory row must be visible", providerRows.isNullOrEmpty())
            providerRows!!.last().click()
            find(By.desc("刷新模型"))
            val refreshed = runBlocking {
                withTimeout(15_000) {
                    manager.settingsFlow.first { settings ->
                        settings.connections.firstOrNull { it.id == connection.id }
                            ?.visibleModels?.firstOrNull { it.id == savedModel.id }?.supportsImages == true
                    }.connections.first { it.id == connection.id }.visibleModels.single()
                }
            }
            assertEquals(savedModel.copy(supportsImages = true), refreshed)
            assertTrue("Refresh must read the fixture's catalog", fixture.catalogRequests > 0)
            capture("image-capability-refreshed-provider")

            back() // Provider details -> inventory.
            back() // Manage Models -> Settings.
            back() // Settings -> the same project chat and its retained draft.
            text("能力验证照片.jpg")
            assertEquals(rejectedDraft, find(By.clazz("android.widget.EditText")).text)
            fields("请查看本地接口验证图片")
            desc("发送")
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (fixture.chatRequests.isEmpty() && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
            }
            assertTrue("Image request must reach the local HTTP fixture", fixture.chatRequests.isNotEmpty())
            val request = JSONObject(fixture.chatRequests.first())
            assertEquals("deepseek-flash", request.getString("model"))
            val messages = request.getJSONArray("messages")
            val message = (0 until messages.length()).map { messages.getJSONObject(it) }
                .last { it.optString("role") == "user" }
            val parts = message.getJSONArray("content")
            val image = (0 until parts.length()).map { parts.getJSONObject(it) }
                .first { it.optString("type") == "image_url" }
            val dataUrl = image.getJSONObject("image_url").getString("url")
            assertTrue(dataUrl.startsWith("data:image/jpeg;base64,"))
            val bytes = android.util.Base64.decode(dataUrl.substringAfter(','), android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("The actual request contains a decodable image", bitmap)
            bitmap!!.recycle()
            assertTrue(device.wait(Until.hasObject(label("无法完成操作")), 30_000))
            assertFalse(device.hasObject(label("当前模型不支持图片，请选择支持图片的模型。")))
            capture("image-request-reaches-local-fixture-error")
            File(output, "image-request-fixture-result.txt").writeText(
                "PASS\nLoopback HTTP fixture only; no real provider or successful model response.\n" +
                "Legacy cached false repaired by provider-settings refresh; user configuration retained.\n" +
                "model=${request.getString("model")} image_mime=image/jpeg image_bytes=${bytes.size}\n" +
                "Chat endpoint intentionally returns HTTP 400.\n"
            )
        }
    }

    /** Catalog metadata and transport recorder only. Never generates a successful AI answer. */
    private class CatalogFixture : java.io.Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
        val chatRequests = CopyOnWriteArrayList<String>()
        @Volatile var catalogRequests = 0
            private set
        private val thread = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                socket.use {
                    it.soTimeout = 10_000
                    val input = it.getInputStream().buffered()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) {
                            val value = input.read()
                            if (value < 0 || value == 10) break
                            if (value != 13) bytes.write(value)
                        }
                        return bytes.toString("UTF-8")
                    }
                    val requestLine = line()
                    var contentLength = 0
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val bytes = ByteArray(contentLength)
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = input.read(bytes, offset, bytes.size - offset)
                        check(count > 0)
                        offset += count
                    }
                    val catalog = requestLine.startsWith("GET /v1/models ")
                    val body = if (catalog) {
                        catalogRequests++
                        """{"data":[{"id":"deepseek-flash","name":"DeepSeek-V4.1-Flash","input_modalities":["text","image"]}]}"""
                    } else {
                        if (requestLine.startsWith("POST /v1/chat/completions ")) {
                            chatRequests.add(bytes.toString(Charsets.UTF_8))
                        }
                        """{"error":{"message":"Local validation fixture: no model response generated."}}"""
                    }.toByteArray()
                    val status = if (catalog) "200 OK" else "400 Bad Request"
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            server.close()
            thread.join(1_000)
        }
    }

    private fun recorded(name: String, body: () -> Unit) {
        try { body(); File(output, "$name-result.txt").writeText("PASS\nNo live API or successful simulated model response used.\n") }
        catch (t: Throwable) {
            capture("failure-$name")
            File(output, "$name-result.txt").writeText("FAIL: ${t.stackTraceToString()}")
            throw t
        }
    }
}
