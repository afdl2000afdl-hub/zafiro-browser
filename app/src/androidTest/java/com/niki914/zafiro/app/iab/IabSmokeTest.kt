package com.niki914.zafiro.app.iab

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.iab.IabJs
import com.niki914.zafiro.chat.agentic.iab.IabManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IAB 运行时冒烟测试（instrumented，真实 WebView 内核）。
 *
 * 与 JVM 单测（G1）不同，这里验证的是 [IabManager] 在「真实 Chromium 内核」上的行为：
 * 建标签 → 加载 data: 页（不依赖外网，CI 上确定）→ 轮询 progress=100 → evaluate 读回
 * document.title / 正文。任何一环失败都会让用例变红（不是永远绿）。
 *
 * 上下文：instrumented 测试跑在 App 主进程，[ContextProvider] 由 App.onCreate 提供；
 * 这里再 provide 一次是幂等的（XProvider.complete 对已完成的 deferred 返回 false，不抛）。
 */
@RunWith(AndroidJUnit4::class)
class IabSmokeTest {

    @Before
    fun provideContext() {
        ContextProvider.provide(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
    }

    private fun dataHtml(title: String, body: String): String {
        val html = "<html><head><title>$title</title></head><body>$body</body></html>"
        return "data:text/html;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }

    @Test
    fun navigateAndReadBackTitle() = runBlocking {
        val info = IabManager.newTab(dataHtml("ZAFIRO_IAB_SMOKE", "<h1>ok</h1>"))
        val loaded = waitForLoad(info.id)
        assertNotNull("tab not found", loaded)
        assertEquals(100, loaded!!.progress)

        val raw = IabManager.evaluate(info.id, IabJs.evaluateEnvelope("document.title", awaitPromise = false))
        assertNotNull("evaluate returned null", raw)
        assertTrue("title not read back: $raw", raw!!.contains("ZAFIRO_IAB_SMOKE"))
    }

    @Test
    fun pageTextExtraction() = runBlocking {
        val info = IabManager.newTab(dataHtml("T", "<p>Hello IAB</p>"))
        waitForLoad(info.id)
        val raw = IabManager.evaluate(info.id, IabJs.pageText(1000))
        assertNotNull(raw)
        assertTrue("page text missing: $raw", raw!!.contains("Hello IAB"))
    }

    @Test
    fun clickAndFillInteract() = runBlocking {
        val info = IabManager.newTab(
            dataHtml("T", "<input id='x' value='0'/><button id='b' onclick=\"document.title='clicked'\">go</button>"),
        )
        waitForLoad(info.id)
        val filled = IabManager.evaluate(info.id, IabJs.fill("#x", "42"))
        assertTrue("fill failed: $filled", filled?.contains("\"ok\":true") == true)
        val clicked = IabManager.evaluate(info.id, IabJs.click("#b"))
        assertTrue("click failed: $clicked", clicked?.contains("\"ok\":true") == true)
        val title = IabManager.evaluate(info.id, IabJs.evaluateEnvelope("document.title", false))
        assertTrue("title should be 'clicked' after button: $title", title?.contains("clicked") == true)
    }

    @Test
    fun cookiesRoundTrip() = runBlocking {
        val url = "https://example.com"
        IabManager.cookiesSet(url, "zafiro_iab_smoke=1")
        val got = IabManager.cookiesGet(url)
        assertTrue("cookie not set: $got", got.contains("zafiro_iab_smoke=1"))
        IabManager.cookiesClear(url)
    }

    /** 轮询标签直到 progress=100（最多 ~30s）；返回最终快照。 */
    private suspend fun waitForLoad(tabId: String): com.niki914.zafiro.chat.agentic.iab.IabTabInfo? {
        var last: com.niki914.zafiro.chat.agentic.iab.IabTabInfo? = null
        repeat(150) {
            last = IabManager.tabs().firstOrNull { it.id == tabId }
            if (last?.progress == 100) return last
            delay(200)
        }
        return last
    }
}
