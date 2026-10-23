package com.niki914.zafiro.app.ui.model

import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.chat.agentic.iab.IabManager
import com.niki914.zafiro.chat.agentic.iab.IabManager.IabError
import kotlinx.coroutines.delay

/** 标签页 UI 快照（来自 IabManager 的 IabTabInfo，去掉进程内引用）。 */
data class IabTabUi(
    val id: String,
    val url: String,
    val title: String,
    val active: Boolean,
    val progress: Int,
)

/** 下载记录 UI 快照。 */
data class IabDownloadUi(
    val id: String,
    val url: String,
    val path: String,
    val sizeBytes: Long,
    val status: String,
    val error: String?,
)

data class IabBrowserUiState(
    val tabs: List<IabTabUi> = emptyList(),
    val activeTabId: String? = null,
    val progress: Int = 0,
    val busy: Boolean = false,
    val console: String = "",
    val downloads: List<IabDownloadUi> = emptyList(),
    val screenshotPath: String? = null,
    val autoGrantMedia: Boolean = IabManager.autoGrantMedia,
    val lastError: String? = null,
)

sealed interface IabBrowserIntent {
    /** 进入页面：确保至少一个标签 + 刷新全部快照。 */
    data object Load

    /** 地址栏提交（有标签 → 导航；无标签 → 新开）。 */
    data class Navigate(val url: String) : IabBrowserIntent

    data object NewTab : IabBrowserIntent
    data object Reload : IabBrowserIntent
    data object Stop : IabBrowserIntent
    data object Back : IabBrowserIntent
    data object Forward : IabBrowserIntent

    data class CloseTab(val id: String) : IabBrowserIntent
    data class SwitchTab(val id: String) : IabBrowserIntent

    data object Screenshot : IabBrowserIntent
    data object RefreshConsole : IabBrowserIntent
    data object RefreshDownloads : IabBrowserIntent
    data class SetAutoGrantMedia(val enabled: Boolean) : IabBrowserIntent
}

sealed interface IabBrowserEffect {
    data object None : IabBrowserEffect
    data class Toast(val message: String) : IabBrowserEffect
}

/**
 * IAB 页面 MVI ViewModel。
 *
 * 状态与一次性效果的分界（项目 MVI 约定）：
 * - tabs/progress/console/downloads/screenshotPath = State（重组后要还能读到同一个答案）；
 * - 导航成功后的 Toast、错误提示 = Effect（错过就过期）。
 */
class IabBrowserViewModel :
    ComposeMVIViewModel<IabBrowserIntent, IabBrowserUiState, IabBrowserEffect>() {

    override fun initUiState(): IabBrowserUiState = IabBrowserUiState()

    override suspend fun handleIntent(intent: IabBrowserIntent) {
        when (intent) {
            IabBrowserIntent.Load -> {
                ensureTab()
                refreshTabs()
                refreshDownloads()
            }

            is IabBrowserIntent.Navigate -> {
                val url = intent.url.trim()
                if (url.isEmpty()) return
                updateState { copy(busy = true, lastError = null) }
                try {
                    if (IabManager.tabs().isEmpty()) {
                        IabManager.newTab(url)
                    } else {
                        IabManager.navigate(null, url)
                    }
                    pollLoadProgress()
                } catch (e: IabError) {
                    updateState { copy(lastError = "${e.code}: ${e.message}") }
                } finally {
                    refreshTabs()
                    updateState { copy(busy = false) }
                }
            }

            IabBrowserIntent.NewTab -> {
                try {
                    IabManager.newTab(null)
                    refreshTabs()
                } catch (e: IabError) {
                    updateState { copy(lastError = e.message) }
                }
            }

            IabBrowserIntent.Reload -> runTabAction { IabManager.reload(null) }
            IabBrowserIntent.Stop -> runTabAction { IabManager.stop(null) }
            IabBrowserIntent.Back -> runTabAction { IabManager.back(null) }
            IabBrowserIntent.Forward -> runTabAction { IabManager.forward(null) }

            is IabBrowserIntent.CloseTab -> {
                try {
                    IabManager.closeTab(intent.id)
                    refreshTabs()
                } catch (e: IabError) {
                    updateState { copy(lastError = e.message) }
                }
            }

            is IabBrowserIntent.SwitchTab -> {
                try {
                    IabManager.activateTab(intent.id)
                    refreshTabs()
                } catch (e: IabError) {
                    updateState { copy(lastError = e.message) }
                }
            }

            IabBrowserIntent.Screenshot -> {
                try {
                    val path = IabManager.screenshotPath(null)
                    updateState { copy(screenshotPath = path) }
                    sendEffect(IabBrowserEffect.Toast("Screenshot saved: $path"))
                } catch (e: IabError) {
                    updateState { copy(lastError = "${e.code}: ${e.message}") }
                }
            }

            IabBrowserIntent.RefreshConsole -> {
                val raw = runCatching { IabManager.evaluate(null, CONSOLE_JS) }.getOrNull()
                updateState { copy(console = raw ?: "(no console capture yet)") }
            }

            IabBrowserIntent.RefreshDownloads -> refreshDownloads()

            is IabBrowserIntent.SetAutoGrantMedia -> {
                IabManager.autoGrantMedia = intent.enabled
                updateState { copy(autoGrantMedia = intent.enabled) }
            }
        }
    }

    /** 无标签时先开一个空白页（UI 与 agent 共享同一组标签，不各自为政）。 */
    private suspend fun ensureTab() {
        if (IabManager.tabs().isEmpty()) {
            runCatching { IabManager.newTab(null) }
        }
    }

    private suspend fun refreshTabs() {
        val tabs = runCatching { IabManager.tabs() }.getOrDefault(emptyList())
        val active = tabs.firstOrNull { it.active }
        updateState {
            copy(
                tabs = tabs.map { IabTabUi(it.id, it.url, it.title, it.active, it.progress) },
                activeTabId = active?.id,
                progress = active?.progress ?: 0,
            )
        }
    }

    private suspend fun refreshDownloads() {
        val list = runCatching { IabManager.downloads(20) }.getOrDefault(emptyList())
        updateState {
            copy(
                downloads = list.map {
                    IabDownloadUi(it.id, it.url, it.path, it.sizeBytes, it.status, it.error)
                },
            )
        }
    }

    /** 轮询加载进度（每 500ms 一次，最多 15s），结束前刷新一次标签快照。 */
    private suspend fun pollLoadProgress() {
        repeat(30) {
            val active = runCatching { IabManager.tabs() }.getOrDefault(emptyList()).firstOrNull { it.active }
            if (active?.progress == 100) return
            delay(500)
        }
    }

    private suspend fun runTabAction(action: suspend () -> Unit) {
        try {
            action()
            refreshTabs()
        } catch (e: IabError) {
            updateState { copy(lastError = e.message) }
        }
    }

    private companion object {
        /** 控制台读取 JS（与 browser 工具 console action 同源：IabJs.consoleRead）。 */
        val CONSOLE_JS = com.niki914.zafiro.chat.agentic.iab.IabJs.consoleRead(50)
    }
}
