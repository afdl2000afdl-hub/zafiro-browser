package com.niki914.zafiro.app.ui.content

import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.IabBrowserEffect
import com.niki914.zafiro.app.ui.model.IabBrowserIntent
import com.niki914.zafiro.app.ui.model.IabBrowserViewModel
import com.niki914.zafiro.chat.agentic.iab.IabManager

/**
 * IAB 浏览器屏幕（对应 ZCode IAB 的可视化面）：
 * 地址栏 + 进度 + WebView（AndroidView，跨标签复用同一组 WebView 实例）+ 标签条 +
 * 工具行（截图/控制台/下载/历史）+ 控制台与下载面板。
 * 所有状态来自 [IabBrowserViewModel]（MVI）；WebView 实例归属 agent-runtime 的
 * [IabManager]（与 agent 的 browser 工具共享同一组标签）。
 */
@Composable
fun IabBrowserContent(
    modifier: Modifier = Modifier,
    viewModel: IabBrowserViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()
    val context = LocalContext.current
    var urlDraft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.sendIntent(IabBrowserIntent.Load)
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                IabBrowserEffect.None -> Unit
                is IabBrowserEffect.Toast -> Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // 地址栏草稿与导航结果同步（加载完成后回填最终 URL）
    LaunchedEffect(uiState.activeTabId, uiState.progress) {
        if (uiState.progress == 100) {
            val active = uiState.tabs.firstOrNull { it.active }
            if (active != null && active.url.isNotBlank() && urlDraft != active.url) {
                urlDraft = active.url
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // ---- 地址栏 + 新建标签
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.ui_iab_address_hint)) },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            FilledIconButton(
                onClick = {
                    val target = urlDraft.trim()
                    if (target.isNotEmpty()) viewModel.sendIntent(IabBrowserIntent.Navigate(target))
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ui_iab_new_tab))
            }
        }

        // ---- 进度
        if (uiState.busy || uiState.progress < 100) {
            LinearProgressIndicator(
                progress = { uiState.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            )
        }

        // ---- WebView 宿主（标签切换 = 换 WebView 实例，实例由 IabManager 持有）
        val holder = remember { FrameLayout(context) }
        DisposableEffect(uiState.activeTabId) {
            val tabId = uiState.activeTabId
            runCatching { IabManager.attachUi(holder, tabId) }
            onDispose {
                runCatching { IabManager.detachUi() }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { holder },
                modifier = Modifier.fillMaxSize(),
            )
            if (uiState.tabs.isEmpty()) {
                Text(
                    text = stringResource(R.string.ui_iab_no_tab),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // ---- 标签条（水平滚动；激活标签高亮，可关闭）
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(uiState.tabs, key = { it.id }) { tabUi ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (tabUi.active) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable {
                        viewModel.sendIntent(IabBrowserIntent.SwitchTab(tabUi.id))
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.Center,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        Text(
                            text = tabUi.title.ifBlank { tabUi.url.ifBlank { tabUi.id } },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        IconButton(
                            onClick = { viewModel.sendIntent(IabBrowserIntent.CloseTab(tabUi.id)) },
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.ui_iab_close_tab),
                            )
                        }
                    }
                }
            }
        }

        // ---- 工具行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Center,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.Back) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.ui_iab_back))
            }
            IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.Forward) }) {
                Icon(Icons.Default.ArrowForward, contentDescription = stringResource(R.string.ui_iab_forward))
            }
            IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.Reload) }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ui_iab_reload))
            }
            IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.Stop) }) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.ui_iab_stop))
            }
            IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.Screenshot) }) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = stringResource(R.string.ui_iab_screenshot))
            }
            TextButton(onClick = { viewModel.sendIntent(IabBrowserIntent.RefreshConsole) }) {
                Text(stringResource(R.string.ui_iab_console))
            }
            TextButton(onClick = { viewModel.sendIntent(IabBrowserIntent.RefreshDownloads) }) {
                Text(stringResource(R.string.ui_iab_downloads))
            }
        }

        uiState.lastError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        uiState.screenshotPath?.let {
            Text(
                text = stringResource(R.string.ui_iab_screenshot_saved, it),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // ---- 控制台 / 下载面板
        if (uiState.console.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    text = uiState.console,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .padding(8.dp)
                        .height(160.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
        if (uiState.downloads.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                uiState.downloads.takeLast(5).forEach { dl ->
                    Text(
                        text = "${dl.status} ${dl.path} (${dl.sizeBytes} B)",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
