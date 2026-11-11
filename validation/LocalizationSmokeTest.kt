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
            device.findObject(label(value))?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 30)
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

    private fun language(label: String, chinese: Boolean) {
        scrollTo(if (chinese) "语言" else "Language").click()
        capture("language-options-${if (chinese) "zh" else "en"}")
        click(label)
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
        scrollTo("OpenAI-compatible").click()
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
        device.wait(Until.hasObject(By.text("无法完成操作")), 15_000)
        capture("chat-without-live-api-zh")
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
        scrollTo("语言").click()
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
        scrollTo("Language").click()
        capture("language-dialog-large-font")
        click("简体中文")
        scrollTo("语言").click()
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
