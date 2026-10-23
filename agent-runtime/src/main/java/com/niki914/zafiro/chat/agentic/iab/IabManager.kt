package com.niki914.zafiro.chat.agentic.iab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CompletableFuture
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.ContextMenu
import android.view.PixelCopy
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.core.content.FileProvider
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.SharedImageCodec
import com.niki914.zafiro.chat.agentic.image.IngestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * IAB（In-App Browser）核心管理器：进程级单例（与 [SharedImageCodec] 同一装配模式——
 * 懒创建，上下文来自 App 启动时 ContextProvider.provide(applicationContext)，工具层零参构造即可调用）。
 *
 * 能力面（对齐 ZCode 的 IAB）：
 * - 多标签：每标签独占一个系统 WebView（Chromium 内核），[MAX_TABS] 上限，LRU 驱逐；
 *   window.open / target=_blank 自动开新标签（WebViewClient.onCreatedWindow）。
 * - 导航：navigate / back / forward / reload / stop；SSL 错误不静默放行（handler.cancel）。
 * - JS：evaluate（任意表达式 + await 语义，结果 JSON 归一）/ querySelector / page_text / page_html。
 * - 交互：fill（React 兼容 value setter）/ click / click_text / click_at / set_checked /
 *   select_option / keypress / scroll / submit_form。
 * - 文件上传：JS 点击 input[type=file] 触发原生选择器 → onShowFileChooser 回填 FileProvider Uri，
 *   只接受 App 私有目录内的文件（filesDir / cacheDir）。
 * - 下载捕获：onDownloadStart → 后台落盘 filesDir/downloads/，环形记录（status/size 可查）。
 * - 截图：PixelCopy(WebView) → PNG → SharedImageCodec ingest（与 screenshot/view_image 同契约）。
 * - 日志：控制台钩子（console.* → 500 条环形缓冲）+ 网络请求级日志（200 条环形缓冲）。
 * - Cookie：android.webkit CookieManager（WebView 数据目录内跨重启持久）。
 * - 其余：剪贴板读写、UA 覆盖、桌面模式、缩放、媒体/地理权限请求（可开关自动放行）、
 *   打印 PDF、本地文件 / HTML 字符串预览（data: URI）。
 *
 * 线程模型：全部 WebView 访问走主线程（withContext(Dispatchers.Main)）；下载在 IO 线程；
 * 标签表与环形缓冲加锁，主线程回调与挂起 API 共用。
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
object IabManager {

    private const val TAG = "niki914_zafiro_IabManager"

    /** 标签上限（低内存设备约束；驱逐 LRU 非激活标签，cookie 仍在 WebView 数据目录，重新导航可恢复会话）。 */
    const val MAX_TABS = 4

    /** 错误码（BrowserBuiltin 映射为工具结果 code）。 */
    const val IAB_UNAVAILABLE = "IAB_UNAVAILABLE"
    const val IAB_NO_TAB = "IAB_NO_TAB"
    const val IAB_BAD_TAB = "IAB_BAD_TAB"
    const val IAB_VIEW_DETACHED = "IAB_VIEW_DETACHED"
    const val IAB_FILE_FORBIDDEN = "IAB_FILE_FORBIDDEN"
    const val IAB_FILE_MISSING = "IAB_FILE_MISSING"
    const val IAB_BAD_URL = "IAB_BAD_URL"
    const val IAB_SCREENSHOT_FAILED = "IAB_SCREENSHOT_FAILED"
    const val IAB_PDF_FAILED = "IAB_PDF_FAILED"
    const val IAB_JS_ENGINE_ERROR = "IAB_JS_ENGINE_ERROR"
    const val IAB_DOWNLOAD_FAILED = "IAB_DOWNLOAD_FAILED"

    /** evaluate 引擎回调超时（防标签销毁 / 页面崩溃导致永久挂起）。 */
    private const val EVALUATE_TIMEOUT_MS = 60_000L

    class IabError(val code: String, message: String) : Exception(message)

    /** 文件上传结果：ACCEPTED = 内核已接受；CHOOSER_SKIPPED = 选择器未弹出或选择被取消。 */
    data class IabUploadResult(val status: String, val detail: String)

    private val lock = Any()
    private val tabs = mutableListOf<IabTab>()
    private var seq = 0

    @Volatile
    var autoGrantMedia: Boolean = true

    @Volatile
    var customUserAgent: String? = null

    @Volatile
    var desktopMode: Boolean = false

    @Volatile
    private var context: Context? = null

    private val downloads = IabRingBuffer<IabDownload>(50)
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val defaultUserAgent: String?
        get() = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()

    /** 非挂起可用性检查（JVM 单测 / 冷启动未就绪时使用）。 */
    fun isReady(): Boolean = context != null

    /**
     * 取应用上下文；未就绪（App 冷启动早期 / JVM 单测环境）时立即抛 [IAB_UNAVAILABLE]，
     * 绝不挂起——这保证 browser 工具在任何环境都不会卡死，而是把「稍后重试」交给模型。
     */
    private fun contextOrUnavailable(): Context {
        context?.let { return it }
        val ctx = ContextProvider.awaitIfAvailable()?.applicationContext
            ?: throw IabError(IAB_UNAVAILABLE, "IAB context is not ready yet; retry shortly after app start.")
        synchronized(this) {
            if (context == null) context = ctx
        }
        return ctx
    }

    // ------------------------------------------------------------- tabs

    private fun createTabBlocking(): IabTab {
        val ctx = context ?: throw IabError(IAB_UNAVAILABLE, "IAB not initialized yet; retry after app start.")
        val tab = synchronized(lock) {
            if (tabs.size >= MAX_TABS) {
                val lru = tabs.filter { !it.active }.minByOrNull { it.lastActiveMs }
                if (lru != null) {
                    detachFromParent(lru.webView)
                    lru.webView.destroy()
                    tabs.remove(lru)
                    Logger.d(TAG, "IAB LRU-evicted tab ${lru.id} (${lru.url})")
                }
            }
            seq += 1
            val t = IabTab(id = "t$seq", webView = buildWebView(ctx), createdAtMs = System.currentTimeMillis())
            tabs.forEach { it.active = false }
            t.active = true
            t.touch()
            tabs.add(t)
            t
        }
        tab.configureClients()
        injectConsoleHook(tab)
        return tab
    }

    private fun buildWebView(ctx: Context): WebView {
        val webView = WebView(ctx)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.builtInZoomControls = false
        s.supportZoom = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setJavaScriptCanOpenWindowsAutomatically(true)
        s.javaScriptCanOpenWindows = true
        s.userAgentString = customUserAgent ?: s.userAgentString
        return webView
    }

    private fun IabTab.configureClients() {
        webView.webViewClient = IabWebViewClient(IabManager)
        webView.webChromeClient = IabWebChromeClient(IabManager)
    }

    /** 新建标签（url 为 null = 空白页）。 */
    suspend fun newTab(url: String? = null): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = createTabBlocking()
            if (url != null && url.isNotEmpty()) loadUrlBlocking(tab, url)
            tab.snapshot()
        }
    }

    suspend fun tabs(): List<IabTabInfo> {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            synchronized(lock) { tabs.map { it.snapshot() } }
        }
    }

    suspend fun closeTab(tabId: String): Boolean {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val removed = synchronized(lock) {
                val idx = tabs.indexOfFirst { it.id == tabId }
                if (idx < 0) null else tabs.removeAt(idx)
            }
            if (removed == null) throw IabError(IAB_BAD_TAB, "No tab with id '$tabId'. Current: ${synchronized(lock) { tabs.map { it.id } }}")
            detachFromParent(removed.webView)
            removed.webView.destroy()
            synchronized(lock) {
                if (removed.active) tabs.firstOrNull()?.active = tabs.isNotEmpty()
            }
            true
        }
    }

    suspend fun activateTab(tabId: String): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = synchronized(lock) { tabs.firstOrNull { it.id == tabId } }
                ?: throw IabError(IAB_BAD_TAB, "No tab with id '$tabId'.")
            synchronized(lock) {
                tabs.forEach { it.active = false }
                tab.active = true
            }
            tab.touch()
            tab.snapshot()
        }
    }

    // ------------------------------------------------------------- navigation

    private suspend fun tabForSuspend(tabId: String?): IabTab = withContext(Dispatchers.Main) {
        tabForBlocking(tabId)
    }

    private fun tabForBlocking(tabId: String?): IabTab {
        synchronized(lock) {
            if (tabId == null) {
                val t = tabs.firstOrNull { it.active } ?: tabs.firstOrNull()
                    ?: throw IabError(IAB_NO_TAB, "No open tab. Call 'new_tab' (or 'navigate') first.")
                t.active = true
                t.touch()
                return t
            }
            val t = tabs.firstOrNull { it.id == tabId }
                ?: throw IabError(IAB_BAD_TAB, "No tab with id '$tabId'. Current: ${tabs.map { it.id }}")
            tabs.forEach { it.active = false }
            t.active = true
            t.touch()
            return t
        }
    }

    /** 拒绝 script 伪 URL；超大 data: 载荷拒绝（防内存炸弹）；其余放行。 */
    private fun sanitizeUrl(url: String) {
        val u = url.trim()
        if (u.isEmpty()) throw IabError(IAB_BAD_URL, "url is empty.")
        val low = u.lowercase(Locale.ROOT)
        if (low.startsWith("javascript:") || low.startsWith("vbscript:")) {
            throw IabError(IAB_BAD_URL, "Refusing to load '$u' (script pseudo-URLs are not allowed).")
        }
        if (low.startsWith("data:") && u.length > 400_000) {
            throw IabError(IAB_BAD_URL, "data: payload too large (${u.length} chars; cap 400000).")
        }
    }

    suspend fun navigate(tabId: String?, url: String): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            sanitizeUrl(url)
            val tab = tabForBlocking(tabId)
            loadUrlBlocking(tab, url)
            tab.snapshot()
        }
    }

    private fun loadUrlBlocking(tab: IabTab, url: String) {
        tab.url = url
        tab.progress = 0
        runCatching { tab.webView.loadUrl(url) }
        injectConsoleHook(tab)
    }

    suspend fun back(tabId: String?): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            if (tab.webView.canGoBack()) tab.webView.goBack()
            tab.snapshot()
        }
    }

    suspend fun forward(tabId: String?): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            if (tab.webView.canGoForward()) tab.webView.goForward()
            tab.snapshot()
        }
    }

    suspend fun reload(tabId: String?): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            tab.webView.reload()
            tab.snapshot()
        }
    }

    suspend fun stop(tabId: String?): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            tab.webView.stopLoading()
            tab.snapshot()
        }
    }

    // ------------------------------------------------------------- JS / DOM

    /** 在标签内执行 JS（主线程派发，回调桥接为挂起；超时 [EVALUATE_TIMEOUT_MS] 返回 null）。 */
    suspend fun evaluate(tabId: String?, js: String): String? {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            val deferred = CompletableDeferred<String?>()
            runCatching {
                tab.webView.evaluateJavascript(js) { value ->
                    if (!deferred.isCompleted) deferred.complete(value)
                }
            }.onFailure { e -> throw IabError(IAB_JS_ENGINE_ERROR, "evaluate failed to dispatch: ${e.message}") }
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(EVALUATE_TIMEOUT_MS) { deferred.await() }
            }
        }
    }

    private fun injectConsoleHook(tab: IabTab) {
        runCatching { tab.webView.evaluateJavascript(IabJs.consoleHook()) { } }
    }

    // ------------------------------------------------------------- screenshot

    /**
     * 截图（公共 UI 侧入口）：成功返回落盘路径（String，跨模块可见）；
     * 失败抛 [IabError]（code = IAB_SCREENSHOT_FAILED / IAB_VIEW_DETACHED / IAB_UNAVAILABLE）。
     * 工具侧请直接用 [screenshot]（内部 IngestResult 契约，与 screenshot/view_image 一致）。
     */
    suspend fun screenshotPath(tabId: String?): String {
        return when (val r = screenshot(tabId)) {
            is IngestResult.Ok -> r.image.path
            is IngestResult.Err -> throw IabError(IAB_SCREENSHOT_FAILED, "screenshot failed: ${r.error}")
        }
    }

    /** PixelCopy 当前标签 WebView → PNG → 共享 ingest 管线（与 screenshot / view_image 同契约）。 */
    suspend fun screenshot(tabId: String?): IngestResult {
        val ctx = contextOrUnavailable()
        val codec = SharedImageCodec.get()
            ?: throw IabError(IAB_UNAVAILABLE, "Image codec is not available (context not ready).")
        val tab = tabForSuspend(tabId)
        val (w, h) = withContext(Dispatchers.Main) {
            val width = tab.webView.width
            val height = tab.webView.height
            if (width == 0 || height == 0) {
                throw IabError(
                    IAB_VIEW_DETACHED,
                    "The browser UI is not currently displayed; PixelCopy requires an attached, laid-out WebView. " +
                            "Open the in-app browser screen first, then retry.",
                )
            }
            width to height
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val future = CompletableFuture<Int>()
        withContext(Dispatchers.Main) {
            PixelCopy.copy(tab.webView, bitmap, future, Handler(Looper.getMainLooper()))
        }
        val result = withContext(Dispatchers.IO) {
            try {
                future.get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                bitmap.recycle()
                throw IabError(IAB_SCREENSHOT_FAILED, "PixelCopy failed: ${e.message}")
            }
        }
        if (result != PixelCopy.Result.SUCCESS) {
            bitmap.recycle()
            throw IabError(IAB_SCREENSHOT_FAILED, "PixelCopy returned code $result (secure content or invalid view).")
        }
        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "iab_shots").apply { mkdirs() }
            val out = File(dir, "iab_${System.currentTimeMillis()}.png")
            out.outputStream().use { os ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                    throw IabError(IAB_SCREENSHOT_FAILED, "PNG encode failed.")
                }
            }
            out
        }
        bitmap.recycle()
        return codec.ingestFile(file.absolutePath)
    }

    // ------------------------------------------------------------- file upload

    /**
     * 文件上传（ZCode IAB 的 file-upload 通道）：
     * 1. 校验 [filePath] 在 App 私有目录（filesDir / cacheDir）内，越界 → [IAB_FILE_FORBIDDEN]；
     * 2. 复制进 cacheDir/iab_upload/（与 [androidx.core.content.FileProvider] 的 iab_upload cache-path 对应）；
     * 3. JS 点击 input[type=file] → onShowFileChooser 回填 Uri → 内核接收后 input.files.length ≥ 1。
     */
    suspend fun fileUpload(tabId: String?, inputSelector: String, filePath: String): IabUploadResult {
        val ctx = contextOrUnavailable()
        val tab = tabForSuspend(tabId)
        val uri = withContext(Dispatchers.IO) {
            val src = File(filePath)
            if (!src.exists() || !src.isFile) throw IabError(IAB_FILE_MISSING, "File not found or not a regular file: $filePath")
            val appDirs = listOf(ctx.filesDir.absolutePath, ctx.cacheDir.absolutePath)
            if (!IabDownloads.isWithinAppDirs(src.absolutePath, appDirs)) {
                throw IabError(IAB_FILE_FORBIDDEN, "IAB file upload only accepts files inside app private storage: ${src.absolutePath}")
            }
            val destDir = File(ctx.cacheDir, "iab_upload").apply { mkdirs() }
            val dest = File(destDir, "up_${System.currentTimeMillis()}_${IabDownloads.sanitizeName(src.name)}")
            src.copyTo(dest, overwrite = true)
            runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dest) }
                .getOrElse { Uri.fromFile(dest) }
        }
        withContext(Dispatchers.Main) { tab.pendingUpload = arrayOf(uri) }
        val clicked = evaluate(tab.id, IabJs.uploadClick(inputSelector))
        when {
            clicked == null -> return IabUploadResult("CHOOSER_SKIPPED", "JS engine error when clicking the file input; check the selector.")
            clicked.contains("\"ok\":false") -> return IabUploadResult("CHOOSER_SKIPPED", clicked)
        }
        withContext(Dispatchers.Default) { delay(2500) }
        val countJson = evaluate(tab.id, IabJs.uploadFileCount(inputSelector)) ?: "{}"
        val files = runCatching {
            Json.parseToJsonElement(countJson).jsonObject["files"]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrDefault(-1)
        return if (files >= 1) {
            IabUploadResult("ACCEPTED", "WebView accepted the file (input.files.length=$files).")
        } else {
            withContext(Dispatchers.Main) { tab.pendingUpload = null }
            IabUploadResult("CHOOSER_SKIPPED", "The native file chooser did not accept a file (selector may not be a visible input[type=file]). Raw: $countJson")
        }
    }

    // ------------------------------------------------------------- downloads

    /** 主线程回调（由 [IabWebChromeClient.onDownloadStart] 触发）：登记 + 后台落盘。 */
    fun handleDownloadStart(tab: IabTab, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (context == null) return
        val dir = File(context!!.filesDir, "downloads")
        val name = "${System.currentTimeMillis()}_${IabDownloads.suggestedName(url, contentDisposition, mimeType)}"
        val target = File(dir, name)
        val entry = IabDownload(
            id = "dl${System.currentTimeMillis() % 100000}_${seq}",
            url = url,
            path = target.absolutePath,
            sizeBytes = 0,
            status = "SAVING",
            startedAtMs = System.currentTimeMillis(),
        )
        downloads.add(entry)
        downloadScope.launch {
            try {
                target.parentFile?.mkdirs()
                val conn = URL(url).openConnection() as? HttpURLConnection
                    ?: throw IabError(IAB_DOWNLOAD_FAILED, "Unsupported protocol for: $url")
                conn.requestProperty("User-Agent", userAgent ?: defaultUserAgent ?: "Mozilla/5.0")
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { input ->
                    target.outputStream().use { out -> input.copyTo(out, chunkSize = 128 * 1024) }
                }
                entry.sizeBytes = target.length()
                entry.status = "DONE"
            } catch (e: Exception) {
                entry.status = "FAILED"
                entry.error = e.message ?: e::class.simpleName
                runCatching { target.delete() }
                Logger.w(TAG, "IAB download failed: $url (${e.message})")
            }
        }
    }

    suspend fun downloads(limit: Int = 20): List<IabDownload> = downloads.snapshot(limit)

    /**
     * 取指定标签（null = 激活标签）的网络请求环形日志，序列化为 JSON 数组字符串。
     * 供 browser 工具的 network action 直接返回（不经 JS，ring 在 Kotlin 侧）。
     */
    suspend fun networkOf(tabId: String?, limit: Int = 50): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            val list = tab.networkLog.snapshot(limit)
            StringBuilder("[")
                .append(
                    list.joinToString(",") { e ->
                        buildString {
                            append("{\"at\":").append(e.atMs)
                            append(",\"method\":").append(jsonStr(e.method))
                            append(",\"url\":").append(jsonStr(e.url))
                            e.mime?.let { append(",\"mime\":").append(jsonStr(it)) }
                            e.note?.let { append(",\"note\":").append(jsonStr(it)) }
                            append("}")
                        }
                    }
                )
                .append("]")
                .toString()
        }
    }

    /** 最小 JSON 字符串转义（ring 条目均为内部可控字段，转义引号/反斜杠即可）。 */
    private fun jsonStr(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c.code < 0x20) b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
        }
        return b.append('"').toString()
    }

    // ------------------------------------------------------------- print / cookies / clipboard

    /** 打印当前标签为 PDF（落盘 filesDir/downloads/）。 */
    suspend fun printPdf(tabId: String?): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val ctx = contextOrUnavailable()
        val tab = tabForBlocking(tabId)
        val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
        val file = File(dir, "iab_pdf_${System.currentTimeMillis()}.pdf")
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
                .use { pfd -> tab.webView.printToPdf(pfd, 0, 0, tab.webView.width.coerceAtLeast(800), tab.webView.height.coerceAtLeast(1000)) }
        }.onFailure { throw IabError(IAB_PDF_FAILED, "printToPdf failed: ${it.message}") }
        file.absolutePath
        }
    }

    suspend fun cookiesGet(url: String): String = withContext(Dispatchers.IO) {
        contextOrUnavailable()
        CookieManager.getInstance().getCookie(url) ?: ""
    }

    suspend fun cookiesSet(url: String, cookies: String): String = withContext(Dispatchers.IO) {
        contextOrUnavailable()
        CookieManager.getInstance().apply {
            setCookie(url, cookies)
            flush()
        }
        "cookie set for $url"
    }

    suspend fun cookiesClear(url: String): String = withContext(Dispatchers.IO) {
        contextOrUnavailable()
        CookieManager.getInstance().apply {
            setCookie(url, "zafiro_clear=1; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
            flush()
        }
        "cookies cleared for $url"
    }

    suspend fun clipboardRead(): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val ctx = contextOrUnavailable()
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
        }
    }

    suspend fun clipboardWrite(text: String) {
        contextOrUnavailable()
        withContext(Dispatchers.Main) {
            val ctx = contextOrUnavailable()
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("zafiro", text))
        }
    }

    // ------------------------------------------------------------- settings

    suspend fun setUserAgent(userAgent: String?): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val target = userAgent?.takeIf { it.isNotBlank() } ?: defaultUserAgent
            customUserAgent = target
            synchronized(lock) {
                tabs.forEach { runCatching { it.webView.settings.userAgentString = target } }
            }
            "user agent set"
        }
    }

    suspend fun setZoom(scale: Float): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val z = scale.coerceIn(0.25f, 3f)
            synchronized(lock) {
                tabs.forEach { runCatching { it.webView.settings.zoomScale = z } }
            }
            "zoom set to $z"
        }
    }

    suspend fun setDesktopMode(enabled: Boolean): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            desktopMode = enabled
            val ua = if (enabled) DESKTOP_UA else defaultUserAgent
            customUserAgent = ua
            synchronized(lock) {
                tabs.forEach { t ->
                    runCatching { t.webView.settings.userAgentString = ua }
                    runCatching { t.webView.evaluateJavascript(IabJs.desktopViewport(enabled)) { } }
                }
            }
            "desktop mode ${if (enabled) "on" else "off"}"
        }
    }

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    // ------------------------------------------------------------- local preview

    /** 打开本地文件（App 私有目录内）/ URL / HTML 字符串（data:text/html;base64）。 */
    suspend fun openLocal(tabId: String?, file: String?, html: String?): IabTabInfo {
        val ctx = contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val target: String = when {
                html != null && html.isNotBlank() ->
                    "data:text/html;base64," + android.util.Base64.encodeToString(html.toByteArray(), android.util.Base64.NO_WRAP)

                file != null && file.isNotBlank() -> when {
                    file.startsWith("http://") || file.startsWith("https://") ||
                        file.startsWith("file://") || file.startsWith("data:") -> file

                    else -> {
                        val f = File(file).let { if (it.isAbsolute) it else File(ctx.filesDir, file) }
                        if (!f.exists()) throw IabError(IAB_FILE_MISSING, "Local file not found: ${f.absolutePath}")
                        val appDirs = listOf(ctx.filesDir.absolutePath, ctx.cacheDir.absolutePath)
                        if (!IabDownloads.isWithinAppDirs(f.absolutePath, appDirs)) {
                            throw IabError(IAB_FILE_FORBIDDEN, "IAB local preview only opens files inside app storage: ${f.absolutePath}")
                        }
                        "file://${f.absolutePath}"
                    }
                }

                else -> throw IabError(IAB_BAD_URL, "Provide 'file' or 'html'.")
            }
            val tab = tabForBlocking(tabId)
            loadUrlBlocking(tab, target)
            tab.snapshot()
        }
    }

    // ------------------------------------------------------------- UI 附着（AndroidView 宿主）

    /** 主线程：把指定（或激活）标签的 WebView 挂进宿主容器（标签状态保留，仅换父容器）。 */
    @MainThread
    fun attachUi(holder: ViewGroup, tabId: String?) {
        val tab = tabForBlocking(tabId)
        attachUiInternal(holder, tab)
    }

    /** 主线程：宿主容器销毁时解绑（WebView 本体与标签状态保留）。 */
    @MainThread
    fun detachUi() {
        synchronized(lock) {
            tabs.forEach { tab ->
                detachFromParent(tab.webView)
                tab.uiAttached = false
            }
        }
    }

    private fun attachUiInternal(holder: ViewGroup, tab: IabTab) {
        tab.uiAttached = true
        val webView = tab.webView
        (webView.parent as? ViewGroup)?.removeView(webView)
        holder.addView(webView, ViewGroup.LayoutParams(-1, -1))
    }

    private fun detachFromParent(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    // ------------------------------------------------------------- clients

    private class IabWebViewClient(private val manager: IabManager) : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            val tab = manager.tabForWebView(view) ?: return
            tab.url = url.orEmpty()
            tab.title = ""
            tab.progress = 0
            tab.networkLog.add(IabNetworkEntry(System.currentTimeMillis(), "PAGE", url.orEmpty(), null, "started"))
            manager.injectConsoleHook(tab)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            val tab = manager.tabForWebView(view) ?: return
            tab.url = view.url.orEmpty()
            tab.title = view.title.orEmpty()
            tab.progress = 100
            tab.networkLog.add(IabNetworkEntry(System.currentTimeMillis(), "PAGE", url.orEmpty(), null, "finished"))
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            super.onReceivedTitle(view, title)
            manager.tabForWebView(view)?.title = title.orEmpty()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // 只记录、不拦截（返回 null = 正常加载）
            manager.tabForWebView(view)?.networkLog?.add(
                IabNetworkEntry(
                    atMs = System.currentTimeMillis(),
                    method = request.method,
                    url = request.url.toString(),
                    mime = request.requestHeaders?.get("Content-Type"),
                    note = null,
                )
            )
            return null
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: android.webkit.SslErrorHandler,
            error: android.net.http.SslError,
        ) {
            manager.tabForWebView(view)?.networkLog?.add(
                IabNetworkEntry(System.currentTimeMillis(), "SSL", error.url.orEmpty(), null, "ssl error: ${error.primaryError}")
            )
            handler.cancel() // 安全默认：证书错误不放行
        }

        /**
         * window.open / target=_blank：开新标签（[createTabBlocking] 内部含 LRU 驱逐）。
         * 框架提供的 newWebView 未被使用 → 主动 destroy 防泄漏。
         */
        override fun onCreatedWindow(view: WebView, newWebView: WebView?): Boolean {
            val url = newWebView?.url
            newWebView?.destroy()
            if (url != null) {
                runCatching {
                    val tab = manager.createTabBlocking()
                    manager.loadUrlBlocking(tab, url)
                }.onFailure { Logger.w(TAG, "IAB new-window tab failed: ${it.message}") }
            }
            return true
        }

        override fun onShowContextMenu(view: WebView, contextMenu: ContextMenu?, hitTestResult: android.webkit.WebView.HitTestResult?) {
            // IAB 里吞掉长按菜单（与 agent 自动化不冲突）
            contextMenu?.clearMenu()
        }
    }

    private class IabWebChromeClient(private val manager: IabManager) : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            manager.tabForWebView(view)?.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            manager.tabForWebView(view)?.title = title.orEmpty()
        }

        override fun onDownloadStart(
            view: WebView?,
            url: String?,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?,
            contentLength: Long,
        ) {
            val u = url?.takeIf { it.isNotBlank() } ?: return
            val tab = view?.let { manager.tabForWebView(it) } ?: manager.activeTabForWebView()
            if (tab != null) manager.handleDownloadStart(tab, u, userAgent, contentDisposition, mimeType)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            val tab = manager.tabForWebView(webView) ?: return false
            val armed = tab.pendingUpload ?: return false
            tab.pendingUpload = null
            runCatching { filePathCallback.onReceiveValue(armed) }
            tab.networkLog.add(IabNetworkEntry(System.currentTimeMillis(), "UPLOAD", armed.joinToString(",") { it.toString() }, null, "file upload"))
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (manager.autoGrantMedia) {
                runCatching { request.grant(request.resources) }
                manager.activeTabForWebView()?.networkLog?.add(
                    IabNetworkEntry(System.currentTimeMillis(), "PERM", request.resources.joinToString(","), null, "granted")
                )
            } else {
                request.deny()
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: android.webkit.GeolocationPermissions.Callback?,
        ) {
            runCatching {
                callback?.onGeolocationPermissionsShowPrompt(origin) { invoke ->
                    if (manager.autoGrantMedia) invoke.onReceiveValue(true) else invoke.onReceiveValue(false)
                    manager.activeTabForWebView()?.networkLog?.add(
                        IabNetworkEntry(System.currentTimeMillis(), "GEO", origin.orEmpty(), null, if (manager.autoGrantMedia) "granted" else "denied")
                    )
                    false // 结果已由 onReceiveValue 给出
                }
            }
        }
    }

    // ------------------------------------------------------------- 给 client 用的辅助（主线程）

    /** 按 WebView 实例反查标签（主线程调用）。 */
    internal fun tabForWebView(view: WebView): IabTab? = synchronized(lock) {
        tabs.firstOrNull { it.webView === view }
    }

    internal fun activeTabForWebView(): IabTab? = synchronized(lock) {
        tabs.firstOrNull { it.active }
    }

    /** 全量销毁（进程退出 / 测试重置用；一般不需要调用）。 */
    @MainThread
    fun destroyAll() {
        synchronized(lock) {
            tabs.forEach { detachFromParent(it.webView); it.webView.destroy() }
            tabs.clear()
        }
        seq = 0
    }
}
