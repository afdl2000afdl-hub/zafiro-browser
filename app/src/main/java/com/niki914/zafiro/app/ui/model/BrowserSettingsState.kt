package com.niki914.zafiro.app.ui.model

import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.chat.agentic.iab.IabManager

/** 浏览器设置页 UI 状态（IAB 全局开关的可视化；工具本身的启停走 builtin-tools 设置）。 */
data class BrowserSettingsUiState(
    val autoGrantMedia: Boolean = IabManager.autoGrantMedia,
    val maxTabs: Int = IabManager.MAX_TABS,
)

sealed interface BrowserSettingsIntent {
    data object Load : BrowserSettingsIntent
    data class ToggleMediaGrant(val enabled: Boolean) : BrowserSettingsIntent
}

sealed interface BrowserSettingsEffect {
    data object None : BrowserSettingsEffect
}

/**
 * 浏览器设置 MVI ViewModel。
 * 注意：autoGrantMedia 目前是进程级开关（[IabManager] 的 var），不持久化——
 * 如实呈现：重启 App 后恢复默认。持久化版本需要走 XRepo 设置管线（后续扩展点）。
 */
class BrowserSettingsViewModel :
    ComposeMVIViewModel<BrowserSettingsIntent, BrowserSettingsUiState, BrowserSettingsEffect>() {

    override fun initUiState(): BrowserSettingsUiState = BrowserSettingsUiState()

    override suspend fun handleIntent(intent: BrowserSettingsIntent) {
        when (intent) {
            BrowserSettingsIntent.Load -> updateState { this }

            is BrowserSettingsIntent.ToggleMediaGrant -> {
                IabManager.autoGrantMedia = intent.enabled
                updateState { copy(autoGrantMedia = intent.enabled) }
            }
        }
    }
}
