package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.iab.IabJs
import com.niki914.zafiro.chat.agentic.iab.IabManager
import com.niki914.zafiro.chat.agentic.iab.IabManager.IabError
import com.niki914.zafiro.chat.agentic.image.IngestResult
import com.niki914.zafiro.chat.agentic.toImageToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * browser 内置工具：进程内 In-App Browser（系统 WebView / Chromium 内核）的统一操作面。
 *
 * 一个工具 + action 判别（对齐 ZCode IAB 的能力面）：
 * 导航（navigate/back/forward/reload/stop/new_tab/close_tab/switch_tab/tabs）、
 * 读取（evaluate/page_text/page_html/query_selector/state/console/network）、
 * 交互（click/click_text/click_at/fill/set_checked/select_option/keypress/scroll/submit_form）、
 * 文件（file_upload/downloads/open_local/print_pdf）、
 * 环境（screenshot/cookies_*/clipboard_*/set_ua/set_zoom/emulate_desktop/set_media_grant）。
 *
 * 依赖装配：[IabManager] 为进程单例（上下文来自 App 启动的 ContextProvider），本工具零参构造，
 * 与 ScreenshotBuiltin/ViewImageBuiltin 同一模式。冷启动未就绪时返回 IAB_UNAVAILABLE（不挂起、不抛）。
 */
class BrowserBuiltin : BuiltinTool() {
    override val name: String = "browser"
    override val description: String = """
In-app browser (IAB): browse, read, interact with and operate web pages inside Zafiro without leaving the app.
Actions:
- navigate {url, tab?}, new_tab {url?}, close_tab {tab}, switch_tab {tab}, tabs, back/forward/reload/stop {tab?}
- evaluate {expression, tab?, await_promise?}  (arbitrary JS; result is JSON-normalized; DOM nodes become descriptors)
- page_text {tab?, limit?}, page_html {tab?, limit?}, query_selector {selector, limit?, tab?}
- click {selector, tab?}, click_text {text, tab?}, click_at {x, y, tab?}
- fill {selector, value, tab?}, set_checked {selector, checked, tab?}, select_option {selector, value, tab?}
- keypress {keys, tab?}  ("a", "Enter", "Tab"; comma-separated for sequences)
- scroll {x, y, tab?}, submit_form {selector, tab?}
- file_upload {selector, file, tab?}  (file must be inside app storage; e.g. a path from py_download_file)
- downloads {limit?}  (files captured from in-page download links; paths are readable by view_image / execute_python)
- open_local {file?, html?, tab?}  (preview a local file or raw HTML in the browser)
- screenshot {tab?}  (returns an image reference the model can see)
- state {tab?}, console {limit?, tab?}, network {limit?, tab?}
- cookies_get {url}, cookies_set {url, cookies}, cookies_clear {url}
- clipboard_read, clipboard_write {text}
- set_ua {user_agent?}, set_zoom {zoom}, emulate_desktop {enabled}, set_media_grant {enabled}
Tips: call tabs/navigate first, then poll state until progress=100 before reading content;
when a page looks like a mobile layout, emulate_desktop {enabled: true} then reload.
    """.trimIndent()
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = try {
            Json.parseToJsonElement(request.argumentsJson).jsonObject
        } catch (e: Exception) {
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = "Failed to parse arguments JSON: ${e.message}",
            )
        }
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (action.isEmpty()) {
            return BuiltinToolResult.failure(
                code = "MISSING_ACTION",
                message = "Field 'action' is required.",
                fieldErrors = mapOf("action" to "required, one of: ${ACTIONS.joinToString(",")},
            )
        }
        if (action !in ACTIONS) {
            return BuiltinToolResult.failure(
                code = "UNSUPPORTED_ACTION",
                message = "Unknown action '$action'.",
                fieldErrors = mapOf("action" to "must be one of: ${ACTIONS.joinToString(",")},
            )
        }
        // 冷启动未就绪时，IabManager 的各入口经 contextOrUnavailable 抛 IabError(IAB_UNAVAILABLE)，
        // 在这里统一映射为工具失败结果（带 hint），模型可安全重试。
        try {
            return dispatch(action, args)
        } catch (e: IabError) {
            return BuiltinToolResult.failure(code = e.code, message = e.message, hint = HINTS[e.code] ?: "")
        }
    }

    private suspend fun dispatch(action: String, a: JsonObject): BuiltinToolResult {
        fun s(key: String): String? = (a[key] as? JsonPrimitive)?.contentOrNull

        val tab = s("tab")
        val url = s("url")
        val expression = s("expression")
        val selector = s("selector")
        val text = s("text")
        val value = s("value")
        val file = s("file")
        val keys = s("keys")
        val html = s("html")
        val cookies = s("cookies")
        val userAgent = s("user_agent")
        when (action) {
            "navigate" -> {
                val u = url ?: throw IabError("MISSING_FIELD", "Field 'url' is required for navigate.")
                val info = IabManager.navigate(tab, u)
                return ok("Navigated to ${info.url}", tabData(info))
            }

            "new_tab" -> {
                val info = IabManager.newTab(url)
                return ok("Opened tab ${info.id}", tabData(info))
            }

            "close_tab" -> {
                val t = tab ?: throw IabError("MISSING_FIELD", "Field 'tab' is required for close_tab.")
                IabManager.closeTab(t)
                return ok("Closed tab $t", JsonObject(emptyMap()))
            }

            "switch_tab" -> {
                val t = tab ?: throw IabError("MISSING_FIELD", "Field 'tab' is required for switch_tab.")
                return ok("Switched to tab $t", tabData(IabManager.activateTab(t)))
            }

            "tabs" -> {
                val list = IabManager.tabs()
                val arr = buildJsonArray { list.forEach { addElement(obj(it)) } }
                return ok("Tabs: ${list.map { it.id }}", JsonObject(mapOf("tabs" to arr)))
            }

            "back" -> return ok("Went back", tabData(IabManager.back(tab)))
            "forward" -> return ok("Went forward", tabData(IabManager.forward(tab)))
            "reload" -> return ok("Reloaded", tabData(IabManager.reload(tab)))
            "stop" -> return ok("Stopped loading", tabData(IabManager.stop(tab)))

            "evaluate" -> {
                val expr = expression ?: throw IabError("MISSING_FIELD", "Field 'expression' is required for evaluate.")
                val raw = IabManager.evaluate(tab, IabJs.evaluateEnvelope(expr, awaitPromiseOf(a))) ?: run {
                    throw IabError(IabManager.IAB_JS_ENGINE_ERROR, "JS evaluation returned no value (engine error or tab closed).")
                }
                return ok("Evaluated.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "page_text" -> {
                val limit = a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 20_000
                val raw = IabManager.evaluate(tab, IabJs.pageText(limit)) ?: "null"
                return ok("Read page text.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "page_html" -> {
                val limit = a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 50_000
                val raw = IabManager.evaluate(tab, IabJs.pageHtml(limit)) ?: "null"
                return ok("Read page HTML.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "query_selector" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for query_selector.")
                val limit = a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 20
                val raw = IabManager.evaluate(tab, IabJs.querySelector(sel, limit)) ?: "null"
                return ok("Queried selector.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "click" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for click.")
                return ok("Clicked.", jsonOf(IabManager.evaluate(tab, IabJs.click(sel)) ?: "null", "text"))
            }

            "click_text" -> {
                val t = text ?: throw IabError("MISSING_FIELD", "Field 'text' is required for click_text.")
                return ok("Clicked by text.", jsonOf(IabManager.evaluate(tab, IabJs.clickText(t)) ?: "null", "text"))
            }

            "click_at" -> {
                val x = a["x"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                val y = a["y"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                return ok("Clicked at ($x,$y).", jsonOf(IabManager.evaluate(tab, IabJs.clickAt(x, y)) ?: "null", "text"))
            }

            "fill" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for fill.")
                val v = value ?: throw IabError("MISSING_FIELD", "Field 'value' is required for fill.")
                return ok("Filled.", jsonOf(IabManager.evaluate(tab, IabJs.fill(sel, v)) ?: "null", "text"))
            }

            "set_checked" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for set_checked.")
                val checked = a["checked"]?.jsonPrimitiveOrNull()?.content?.toBooleanStrict() ?: false
                return ok("Set checked.", jsonOf(IabManager.evaluate(tab, IabJs.setChecked(sel, checked)) ?: "null", "text"))
            }

            "select_option" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for select_option.")
                val v = value ?: throw IabError("MISSING_FIELD", "Field 'value' is required for select_option.")
                return ok("Selected.", jsonOf(IabManager.evaluate(tab, IabJs.selectOption(sel, v)) ?: "null", "text"))
            }

            "keypress" -> {
                val k = keys ?: throw IabError("MISSING_FIELD", "Field 'keys' is required for keypress.")
                // 序列按键逐个派发（每次 action 一个键）；逗号分隔取第一个
                val first = k.split(",").first().trim()
                if (first.isEmpty()) throw IabError("MISSING_FIELD", "Field 'keys' is empty.")
                return ok("Pressed key.", jsonOf(IabManager.evaluate(tab, IabJs.keypress(first)) ?: "null", "text"))
            }

            "scroll" -> {
                val x = a["x"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                val y = a["y"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                return ok("Scrolled.", jsonOf(IabManager.evaluate(tab, IabJs.scroll(x, y)) ?: "null", "text"))
            }

            "submit_form" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for submit_form.")
                return ok("Submitted form.", jsonOf(IabManager.evaluate(tab, IabJs.submitForm(sel)) ?: "null", "text"))
            }

            "file_upload" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for file_upload.")
                val f = file ?: throw IabError("MISSING_FIELD", "Field 'file' is required for file_upload.")
                val r = IabManager.fileUpload(tab, sel, f)
                return ok("File upload: ${r.status}", JsonObject(mapOf("text" to JsonPrimitive(r.detail))))
            }

            "open_local" -> {
                val info = IabManager.openLocal(tab, file, html)
                return ok("Opened local content.", tabData(info))
            }

            "screenshot" -> {
                val result = IabManager.screenshot(tab)
                return when (result) {
                    is IngestResult.Ok -> result.toImageToolResult(message = "Browser screenshot captured: ${result.image.path}")
                    is IngestResult.Err -> BuiltinToolResult.failure(
                        code = IabManager.IAB_SCREENSHOT_FAILED,
                        message = "Screenshot failed: ${result.error}",
                        hint = "If IAB_VIEW_DETACHED: open the in-app browser screen first (Settings > Browser > Open browser), then retry.",
                    )
                }
            }

            "downloads" -> {
                val list = IabManager.downloads(a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 20)
                val arr = buildJsonArray { list.forEach { addElement(objDownload(it)) } }
                return ok("Downloads: ${list.size}", JsonObject(mapOf("downloads" to arr)))
            }

            "print_pdf" -> {
                val path = IabManager.printPdf(tab)
                return ok("Printed PDF: $path", JsonObject(mapOf("path" to JsonPrimitive(path))))
            }

            "state" -> {
                val ready = IabManager.evaluate(tab, "JSON.stringify({readyState: document.readyState, title: document.title, url: location.href})") ?: "{}"
                val all = IabManager.tabs()
                val current = all.firstOrNull { it.id == tab } ?: all.firstOrNull { it.active }
                val body = buildJsonObject {
                    put("state", JsonPrimitive(ready))
                    put("tab", current?.let { obj(it) } ?: JsonNull)
                }
                return ok("Page state.", body)
            }

            "console" -> {
                val limit = a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 50
                val raw = IabManager.evaluate(tab, IabJs.consoleRead(limit)) ?: "null"
                return ok("Console log.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "network" -> {
                val limit = a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 50
                val raw = IabManager.networkOf(tab, limit)
                return ok("Network log.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "cookies_get" -> {
                val u = url ?: throw IabError("MISSING_FIELD", "Field 'url' is required for cookies_get.")
                val c = IabManager.cookiesGet(u)
                return ok("Cookies for $u", JsonObject(mapOf("text" to JsonPrimitive(c))))
            }

            "cookies_set" -> {
                val u = url ?: throw IabError("MISSING_FIELD", "Field 'url' is required for cookies_set.")
                val c = cookies ?: throw IabError("MISSING_FIELD", "Field 'cookies' is required for cookies_set.")
                val r = IabManager.cookiesSet(u, c)
                return ok(r, JsonObject(emptyMap()))
            }

            "cookies_clear" -> {
                val u = url ?: throw IabError("MISSING_FIELD", "Field 'url' is required for cookies_clear.")
                val r = IabManager.cookiesClear(u)
                return ok(r, JsonObject(emptyMap()))
            }

            "clipboard_read" -> {
                val c = IabManager.clipboardRead()
                return ok(if (c.isEmpty()) "Clipboard is empty." else "Clipboard read.", JsonObject(mapOf("text" to JsonPrimitive(c))))
            }

            "clipboard_write" -> {
                val c = text ?: throw IabError("MISSING_FIELD", "Field 'text' is required for clipboard_write.")
                IabManager.clipboardWrite(c)
                return ok("Clipboard written (${c.length} chars).", JsonObject(emptyMap()))
            }

            "set_ua" -> {
                val r = IabManager.setUserAgent(userAgent)
                return ok(r, JsonObject(emptyMap()))
            }

            "set_zoom" -> {
                val z = a["zoom"]?.jsonPrimitiveOrNull()?.content?.toFloatOrNull() ?: 1.0f
                val r = IabManager.setZoom(z)
                return ok(r, JsonObject(emptyMap()))
            }

            "emulate_desktop" -> {
                val enabled = a["enabled"]?.jsonPrimitiveOrNull()?.content?.toBooleanStrict() ?: true
                val r = IabManager.setDesktopMode(enabled)
                return ok(r, JsonObject(emptyMap()))
            }

            "set_media_grant" -> {
                val enabled = a["enabled"]?.jsonPrimitiveOrNull()?.content?.toBooleanStrict() ?: true
                IabManager.autoGrantMedia = enabled
                return ok(if (enabled) "Media permission requests will be auto-granted." else "Media permission requests will be denied.", JsonObject(emptyMap()))
            }

            else -> {
                throw IabError("UNSUPPORTED_ACTION", "Unhandled action: $action")
            }
        }
    }

    private fun ok(message: String, data: JsonObject): BuiltinToolResult =
        BuiltinToolResult.success(message = message, data = data)

    private fun tabData(info: com.niki914.zafiro.chat.agentic.iab.IabTabInfo): JsonObject =
        buildJsonObject {
            put("tab", JsonPrimitive(info.id))
            put("url", JsonPrimitive(info.url))
            put("title", JsonPrimitive(info.title))
            put("progress", JsonPrimitive(info.progress))
        }

    private fun obj(tab: com.niki914.zafiro.chat.agentic.iab.IabTabInfo): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(tab.id))
        put("url", JsonPrimitive(tab.url))
        put("title", JsonPrimitive(tab.title))
        put("active", JsonPrimitive(tab.active))
    }

    private fun objDownload(dl: com.niki914.zafiro.chat.agentic.iab.IabDownload): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(dl.id))
        put("url", JsonPrimitive(dl.url))
        put("path", JsonPrimitive(dl.path))
        put("size", JsonPrimitive(dl.sizeBytes))
        put("status", JsonPrimitive(dl.status))
        dl.error?.let { put("error", JsonPrimitive(it)) }
    }

    private fun jsonOf(rawJson: String, key: String): JsonObject = JsonObject(mapOf(key to JsonPrimitive(rawJson)))

    private fun JsonElement?.jsonPrimitiveOrNull(): JsonPrimitive? = (this as? JsonPrimitive)
    private fun String.toBooleanStrict(): Boolean? = when (lowercase()) { "true" -> true; "false" -> false; else -> null }
    private fun awaitPromiseOf(a: JsonObject): Boolean = (a["await_promise"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrict() ?: false

    companion object {
        private val ACTIONS = setOf(
            "navigate", "new_tab", "close_tab", "switch_tab", "tabs",
            "back", "forward", "reload", "stop",
            "evaluate", "page_text", "page_html", "query_selector",
            "click", "click_text", "click_at", "fill", "set_checked", "select_option", "keypress", "scroll", "submit_form",
            "file_upload", "downloads", "open_local", "print_pdf", "screenshot", "state", "console", "network",
            "cookies_get", "cookies_set", "cookies_clear",
            "clipboard_read", "clipboard_write",
            "set_ua", "set_zoom", "emulate_desktop", "set_media_grant",
        )

        private val HINTS = mapOf(
            IabManager.IAB_UNAVAILABLE to "App still initializing. Retry once.",
            IabManager.IAB_NO_TAB to "Call new_tab (or navigate) to open the first tab.",
            IabManager.IAB_BAD_TAB to "List tabs with action=tabs and use a valid id.",
            IabManager.IAB_VIEW_DETACHED to "Open the in-app browser screen first (Settings > Browser > Open browser), then retry.",
            IabManager.IAB_FILE_FORBIDDEN to "Copy the file into app private storage (e.g. via execute_python) and use that path.",
            IabManager.IAB_FILE_MISSING to "Verify the path exists (downloads action lists captured files).",
            IabManager.IAB_BAD_URL to "Check the URL (no script pseudo-URLs; data: capped at 400k chars).",
            IabManager.IAB_SCREENSHOT_FAILED to "Ensure the browser screen is open; retry after navigation settles.",
            IabManager.IAB_PDF_FAILED to "Retry after the page has fully loaded.",
            IabManager.IAB_JS_ENGINE_ERROR to "The page may have navigated away or crashed; reload and retry.",
        )

        private val SCHEMA = """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ${ACTIONS.map { "\"$it\"" }.joinToString(", ", prefix = "[", postfix = "]")},
      "description": "The IAB operation to perform."
    },
    "url": { "type": "string", "description": "Target URL (navigate / cookies_*)." },
    "tab": { "type": "string", "description": "Tab id (from tabs/new_tab). Omit = active tab." },
    "expression": { "type": "string", "description": "JS expression for evaluate (may use await when await_promise=true)." },
    "await_promise": { "type": "boolean", "description": "evaluate: wrap the expression in a promise-await." },
    "selector": { "type": "string", "description": "CSS selector (click/fill/query_selector/...)." },
    "text": { "type": "string", "description": "Visible text (click_text) or payload (clipboard_write)." },
    "value": { "type": "string", "description": "Input value (fill/select_option)." },
    "checked": { "type": "boolean", "description": "set_checked target state." },
    "keys": { "type": "string", "description": "Key name(s) for keypress (first key of a comma list is used)." },
    "x": { "type": "integer", "description": "X coordinate (click_at/scroll)." },
    "y": { "type": "integer", "description": "Y coordinate (click_at/scroll)." },
    "limit": { "type": "integer", "description": "Truncation cap (page_text/page_html/query_selector/console/network/downloads)." },
    "file": { "type": "string", "description": "File path for file_upload / open_local (app storage only)." },
    "html": { "type": "string", "description": "Raw HTML to render via open_local." },
    "cookies": { "type": "string", "description": "Raw cookie string for cookies_set." },
    "user_agent": { "type": "string", "description": "Custom UA for set_ua (null/omit = default)." },
    "zoom": { "type": "number", "description": "Zoom factor 0.25-3 for set_zoom." },
    "enabled": { "type": "boolean", "description": "Toggle for emulate_desktop / set_media_grant." }
  },
  "required": ["action"]
}
        """.trimIndent()
    }
}
