package com.niki914.zafiro.chat.agentic.iab

import android.webkit.WebView

/**
 * IAB 标签页记录 + 环形日志缓冲。
 *
 * 一个 [IabTab] 独占一个系统 WebView（Chromium 内核）；标签上限见
 * [IabManager.MAX_TABS]，超限时按 LRU 驱逐非激活标签（WebView destroy，cookie 仍留在
 * WebView 数据目录中，重新 navigate 即恢复会话态）。
 */
class IabTab(
    val id: String,
    val webView: WebView,
    val createdAtMs: Long,
) {
    var url: String = ""
    var title: String = ""
    var progress: Int = 0
    var active: Boolean = false
    var lastActiveMs: Long = createdAtMs
    /** 文件上传等待槽：JS click input[type=file] 后 onShowFileChooser 回填的 Uri。 */
    var pendingUpload: Array<android.net.Uri>? = null

    val consoleLog = IabRingBuffer<String>(500)
    val networkLog = IabRingBuffer<IabNetworkEntry>(200)

    /** UI 侧（AndroidView）是否持有该 WebView 的父容器。 */
    var uiAttached: Boolean = false

    fun touch() {
        lastActiveMs = System.currentTimeMillis()
    }

    /** 纯数据快照（供工具结果 / 设置页），不含 WebView 引用。 */
    fun snapshot(): IabTabInfo = IabTabInfo(
        id = id,
        url = url,
        title = title,
        progress = progress,
        active = active,
        createdAtMs = createdAtMs,
        lastActiveMs = lastActiveMs,
    )
}

data class IabTabInfo(
    val id: String,
    val url: String,
    val title: String,
    val progress: Int,
    active: Boolean,
    val createdAtMs: Long,
    val lastActiveMs: Long,
)

/** 网络请求级日志（请求发生时记录；页面级错误折进 [note]）。 */
data class IabNetworkEntry(
    val atMs: Long,
    val method: String,
    val url: String,
    val mime: String?,
    val note: String?,
)

/** 下载捕获记录（onDownloadStart → 后台落盘到 filesDir/downloads/）。 */
data class IabDownload(
    val id: String,
    val url: String,
    val path: String,
    var sizeBytes: Long,
    var status: String, // SAVING / DONE / FAILED（落盘流转中更新）
    val startedAtMs: Long,
    var error: String? = null,
)

/**
 * 线程安全环形缓冲（容量固定，满则丢最旧）。
 * 纯 JVM 类型——日志/网络/下载三个 ring 都是它，便于单测。
 */
class IabRingBuffer<T>(val capacity: Int) {
    require(capacity > 0) { "capacity must be > 0" }

    private val items = ArrayDeque<T>()
    private val lock = Any()

    fun add(item: T) {
        synchronized(lock) {
            while (items.size >= capacity) items.removeFirst()
            items.addLast(item)
        }
    }

    /** 最近 [limit] 条快照（limit 为 null 取满容量）。 */
    fun snapshot(limit: Int? = null): List<T> = synchronized(lock) {
        val take = limit?.coerceIn(0, capacity) ?: capacity
        items.takeLast(take).toList()
    }

    val size: Int
        get() = synchronized(lock) { items.size }
}
