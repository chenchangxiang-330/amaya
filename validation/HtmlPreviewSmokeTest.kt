package com.amaya.intelligence.localization

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HtmlPreviewSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val output = File(context.filesDir, "locale-validation").apply { mkdirs() }
    private fun launch(tags: String) {
        context.startActivity(Intent().setClassName(context.packageName,
            "com.amaya.intelligence.localization.HtmlPreviewHarnessActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        text("网页已生成。")
        // Use the same call as Settings, after the Activity has an active AppCompat delegate.
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AppCompatActivity>().single()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "LOCALE: package=${activity.packageName}; requested=$tags; selected=${AppCompatDelegate.getApplicationLocales().toLanguageTags()}\n") })
        }
        text(if (tags == "zh-CN") "展开" else "Expand")
    }
    private fun text(value: String): UiObject2 = device.wait(Until.findObject(By.text(value)), 15_000)
        ?: throw AssertionError("Missing text: $value")
    private fun capture(name: String) {
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
        device.dumpWindowHierarchy(File(output, "$name.xml"))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SCREENSHOT: $name\n") })
    }
    private fun recorded(name: String, body: () -> Unit) {
        try { body(); File(output,"$name-result.txt").writeText("PASS\nSynthetic generated file and tool metadata; real production assistant-bubble/WebView UI. No live model.\n") }
        catch (t: Throwable) { capture("failure-$name"); File(output,"$name-result.txt").writeText("FAIL: $t\n${t.stackTraceToString()}"); throw t }
    }
    @Test fun htmlWorksInsideChatCard() = recorded("inline-html") {
        val root = File(context.filesDir, "html-preview-test").apply { mkdirs() }
        File(root,"styles.css").writeText("body{font:16px sans-serif;padding:8px;background:#e8f5e9}h2,p{margin:4px 0}button{padding:8px}")
        File(root,"data.json").writeText("{\"text\":\"同目录数据已读取\"}")
        File(context.filesDir,"outside.json").writeText("{\"secret\":\"must not be served\"}")
        File(root,"script.js").writeText("""
            document.getElementById('script').textContent='本地脚本已运行';
            let count=0;
            document.getElementById('add').onclick=()=>document.getElementById('count').textContent='计数：'+(++count);
            document.getElementById('dialog').onclick=()=>{alert('网页提示正常');document.getElementById('dialog-state').textContent='提示已关闭';};
            fetch('data.json').then(r=>r.json()).then(d=>document.getElementById('data').textContent=d.text);
            fetch('https://example.invalid/private').then(()=>document.getElementById('network').textContent='意外联网')
              .catch(()=>document.getElementById('network').textContent='外部连接已阻止');
            fetch('../outside.json').then(r=>document.getElementById('boundary').textContent=r.ok?'意外越界':'目录越界已阻止')
              .catch(()=>document.getElementById('boundary').textContent='目录越界已阻止');
        """.trimIndent())
        File(root,"中文网页.html").writeText("""
            <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="stylesheet" href="styles.css"><h2>中文交互网页</h2><p id="script"></p>
            <button id="add">点击加一</button><p id="count">计数：0</p><p id="data"></p>
            <p id="network"></p><p id="boundary"></p><button id="dialog">显示网页提示</button><p id="dialog-state"></p><script src="script.js"></script></html>
        """.trimIndent())
        launch("zh-CN")
        text("本地脚本已运行")
        text("同目录数据已读取")
        text("点击加一").click()
        text("计数：1")
        capture("inline-html-interactive-zh")
        text("展开").click()
        text("计数：1") // Resizing must not recreate the page or lose form state.
        text("外部连接已阻止")
        text("目录越界已阻止")
        text("显示网页提示").click()
        text("网页提示正常")
        capture("inline-html-js-dialog")
        device.findObject(By.res("android", "button1"))!!.click()
        text("提示已关闭")
        text("计数：1")
        capture("inline-html-expanded-and-isolated")
        text("收起").click()
        device.findObject(By.desc("刷新网页"))!!.click()
        text("计数：0")
        capture("inline-html-refreshed")
    }
    @Test fun htmlReopensAfterRestartAndUsesEnglishLabels() = recorded("inline-html-restart") {
        launch("en")
        text("Expand")
        text("中文交互网页")
        text("点击加一").click()
        text("计数：1")
        capture("inline-html-after-restart-en")
    }
}
