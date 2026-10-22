package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.BrowserSettingsIntent
import com.niki914.zafiro.app.ui.model.BrowserSettingsViewModel

private const val BROWSER_OPEN_ROW_ID = "browser.open"
private const val BROWSER_MEDIA_GRANT_ROW_ID = "browser.media_grant"

/**
 * 浏览器设置（Settings 组的 Browser 组内容）：
 * - 打开内置浏览器（IabBrowserPage 的入口）；
 * - 媒体权限自动放行开关（IAB 全局，进程级）；
 * - 标签上限（只读，IAB 固定策略）。
 * 工具 browser 的启停不在这里——它随 builtin-tools 设置页的注册表自动出现。
 */
@Composable
fun BrowserSettingsContent(
    onOpenBrowser: () -> Unit,
    viewModel: BrowserSettingsViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.sendIntent(BrowserSettingsIntent.Load)
    }

    val spec = SettingsPageSpec(
        description = null,
        sections = listOf(
            SettingsSectionSpec(
                title = stringResource(R.string.ui_iab_browser_group),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = BROWSER_OPEN_ROW_ID,
                        title = stringResource(R.string.ui_iab_open_browser),
                        summary = stringResource(R.string.ui_iab_open_browser_summary),
                    ),
                    SettingsRowSpec.Toggle(
                        id = BROWSER_MEDIA_GRANT_ROW_ID,
                        title = stringResource(R.string.ui_iab_media_grant),
                        summary = stringResource(R.string.ui_iab_media_grant_summary),
                        checked = uiState.autoGrantMedia,
                    ),
                    SettingsRowSpec.Value(
                        title = stringResource(R.string.ui_iab_max_tabs),
                        summary = stringResource(R.string.ui_iab_max_tabs_summary),
                        currentState = uiState.maxTabs.toString(),
                    ),
                ),
            ),
        ),
    )

    SettingsSpecPageContent(
        spec = spec,
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> if (action.id == BROWSER_OPEN_ROW_ID) onOpenBrowser()
                is SettingsRowAction.ToggleChanged ->
                    if (action.id == BROWSER_MEDIA_GRANT_ROW_ID) {
                        viewModel.sendIntent(BrowserSettingsIntent.ToggleMediaGrant(action.checked))
                    }
                else -> Unit
            }
        },
    )
}
