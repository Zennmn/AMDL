package com.kyant.amdl.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalScrollBarApi::class)
@Composable
internal fun MiuixSettings(
    state: DesktopState,
    onLogout: () -> Unit,
) {
    var showDirectoryPicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(scrollState)
                .padding(top = 16.dp, bottom = 24.dp),
        ) {
            SettingsGroup(title = "存储位置") {
                ArrowPreference(
                    title = "下载目录",
                    summary = state.config.downloadPath,
                    onClick = { showDirectoryPicker = true },
                )
                ArrowPreference(
                    title = "打开下载目录",
                    summary = "在文件资源管理器中打开当前下载目录",
                    onClick = { state.openDirectory() },
                )
            }

            Spacer(Modifier.height(24.dp))
            SettingsGroup(title = "下载与整理") {
                SwitchPreference(
                    title = "优先杜比全景声",
                    summary = "优先下载 E-AC-3 JOC；不可用时尝试 AAC。",
                    checked = state.config.preferAtmos,
                    onCheckedChange = {
                        state.saveConfig(state.config.copy(preferAtmos = it))
                    },
                )
                SwitchPreference(
                    title = "按专辑整理",
                    summary = "使用歌手、专辑和曲目序号创建目录。",
                    checked = state.config.saveByAlbum,
                    onCheckedChange = {
                        state.saveConfig(state.config.copy(saveByAlbum = it))
                    },
                )
                SwitchPreference(
                    title = "合并单曲目录",
                    summary = "将同一歌手的单曲放入 Singles 目录。",
                    checked = state.config.mergeSingles,
                    enabled = state.config.saveByAlbum,
                    onCheckedChange = {
                        state.saveConfig(state.config.copy(mergeSingles = it))
                    },
                )
                SwitchPreference(
                    title = "保存逐字歌词",
                    summary = "有逐字歌词时，额外保存 TTML 文件。",
                    checked = state.config.saveTtml,
                    onCheckedChange = {
                        state.saveConfig(state.config.copy(saveTtml = it))
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            SettingsGroup(title = "语言") {
                OverlayDropdownPreference(
                    title = "歌曲信息语言",
                    summary = "影响 Apple Music 返回的歌曲与专辑信息。",
                    items = listOf("简体中文", "English"),
                    selectedIndex = if (state.config.language.startsWith("zh")) 0 else 1,
                    onSelectedIndexChange = { index ->
                        state.saveConfig(
                            state.config.copy(
                                language = if (index == 0) "zh-Hans-CN" else "en-US",
                            ),
                        )
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            SettingsGroup(title = "账号") {
                val accountSummary = when {
                    state.isLoggingIn -> state.loginStatus.ifBlank { "正在登录 Apple Music…" }
                    state.isValidatingSession -> "正在验证 Apple Music 连接…"
                    state.isLoggedIn -> "已连接 · 登录信息已加密保存在本机"
                    state.canRetryConnection -> "已保存登录信息，可重试连接"
                    else -> "需要有效的 Apple Music 订阅"
                }

                BasicComponent(
                    title = "Apple Music",
                    summary = accountSummary,
                    endActions = {
                        when {
                            state.isLoggingIn -> TextButton(
                                text = "取消",
                                onClick = state::cancelLogin,
                            )

                            state.isValidatingSession -> TextButton(
                                text = "验证中",
                                onClick = {},
                                enabled = false,
                            )

                            state.isLoggedIn -> TextButton(
                                text = "退出",
                                onClick = onLogout,
                            )

                            state.canRetryConnection -> TextButton(
                                text = "连接",
                                onClick = state::login,
                            )

                            else -> TextButton(
                                text = "登录",
                                onClick = state::login,
                            )
                        }
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            SettingsGroup(title = "下载引擎") {
                BasicComponent(
                    title = "下载引擎",
                    summary = state.nativeError ?: if (state.nativeReady) {
                        "已就绪"
                    } else {
                        "正在初始化…"
                    },
                    summaryColor = BasicComponentDefaults.summaryColor(
                        color = if (state.nativeError != null) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    ),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "AMDL for Windows 1.1.6",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        )
    }

    if (showDirectoryPicker) {
        MiuixDirectoryPicker(
            currentPath = state.config.downloadPath,
            onDismiss = { showDirectoryPicker = false },
            onSelect = { path ->
                state.saveConfig(state.config.copy(downloadPath = path))
                showDirectoryPicker = false
            },
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(
            text = title,
            insideMargin = PaddingValues(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
            content = content,
        )
    }
}
