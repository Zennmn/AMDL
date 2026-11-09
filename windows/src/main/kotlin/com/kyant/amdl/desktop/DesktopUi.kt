package com.kyant.amdl.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.amdl.downloader.Task
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.DividerDefaults
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private enum class Destination(val title: String, val icon: ImageVector) {
    NewDownload("新建下载", MiuixIcons.Link),
    Downloads("下载任务", MiuixIcons.Download),
    History("下载记录", DesktopIcons.HistoryOutline),
    Settings("设置", MiuixIcons.Settings),
}

@Composable
fun AmdlDesktop(state: DesktopState, initialPage: Int = 0) {
    val tasks by state.downloadManager.tasks.collectAsState()
    val failed by state.downloadManager.failedTasks.collectAsState()
    val completed by state.downloadManager.completedTasks.collectAsState()
    val parsing by state.downloadManager.isParsing.collectAsState()
    val downloadError by state.downloadManager.error.collectAsState()
    var selectedPage by rememberSaveable { mutableIntStateOf(initialPage.coerceIn(Destination.entries.indices)) }
    var confirmLogout by remember { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    val destination = Destination.entries[selectedPage]
    val snackbars = remember { SnackbarHostState() }
    val dividerPosition = LocalSidebarDividerPosition.current
    val appMessage = state.message
    LaunchedEffect(appMessage) {
        if (appMessage != null) {
            snackbars.showSnackbar(appMessage, actionLabel = "知道了", duration = SnackbarDuration.Short)
            state.dismissMessage(appMessage)
        }
    }
    LaunchedEffect(downloadError) {
        downloadError?.let { error ->
            snackbars.showSnackbar(error, actionLabel = "知道了", duration = SnackbarDuration.Indefinite)
            state.downloadManager.clearError(error)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(state = snackbars) }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val expandRail = maxWidth >= 1060.dp
                val railState = rememberNavigationRailState(
                    if (expandRail) NavigationRailValue.Expanded else NavigationRailValue.Collapsed,
                )
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        state = railState,
                        showDivider = false,
                        defaultWindowInsetsPadding = false,
                        expandContentDescription = "展开侧栏",
                        collapseContentDescription = "收起侧栏",
                    ) {
                        Destination.entries.forEachIndexed { index, item ->
                            NavigationRailItem(
                                selected = selectedPage == index,
                                onClick = { selectedPage = index },
                                icon = item.icon,
                                label = item.title,
                            )
                        }
                    }
                    if (dividerPosition != null) {
                        Spacer(
                            Modifier.width(DividerDefaults.Thickness).fillMaxHeight()
                                .testTag("sidebar-divider-anchor")
                                .onPlaced { dividerPosition.intValue = it.positionInRoot().x.roundToInt() },
                        )
                    } else {
                        VerticalDivider()
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            DesktopPageHeader(
                                title = destination.title,
                                subtitle = when (destination) {
                                    Destination.NewDownload -> "下载 Apple Music 歌曲与专辑"
                                    Destination.Downloads -> "查看下载进度与失败的任务"
                                    Destination.History -> "本次运行已完成 ${completed.size} 首"
                                    Destination.Settings -> "下载偏好、存储与 Apple Music 账号"
                                },
                            ) {
                                when (destination) {
                                    Destination.NewDownload -> IconButton(onClick = { state.openDirectory() }) {
                                        Icon(MiuixIcons.Folder, contentDescription = "打开下载目录")
                                    }
                                    Destination.Downloads -> TextButton(
                                        "新建下载",
                                        { selectedPage = Destination.NewDownload.ordinal },
                                        colors = ButtonDefaults.textButtonColorsPrimary(),
                                    )
                                    Destination.History -> TextButton(
                                        "清空记录", state.downloadManager::clearCompletedTasks,
                                        enabled = completed.isNotEmpty(),
                                    )
                                    Destination.Settings -> Unit
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when (destination) {
                                    Destination.NewDownload -> NewDownloadPage(
                                        state = state,
                                        link = link,
                                        onLinkChange = { link = it },
                                        parsing = parsing,
                                        onSettings = { selectedPage = Destination.Settings.ordinal },
                                        onSubmit = {
                                            state.download(link)
                                            selectedPage = Destination.Downloads.ordinal
                                        },
                                    )
                                    Destination.Downloads -> DownloadQueue(
                                        state, tasks, failed, parsing,
                                        onNewDownload = { selectedPage = Destination.NewDownload.ordinal },
                                    )
                                    Destination.History -> DownloadHistory(state, completed)
                                    Destination.Settings -> MiuixSettings(state, onLogout = { confirmLogout = true })
                                }
                            }
                        }
                    }
                }
                if (confirmLogout) {
                    LogoutConfirmation(
                        onDismiss = { confirmLogout = false },
                        onConfirm = { state.logout(); confirmLogout = false },
                    )
                }
            }
    }
}

@Composable
internal fun LogoutConfirmation(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    OverlayDialog(
        show = true,
        title = "退出 Apple Music？",
        summary = "移除本机保存的登录信息。再次下载时需要重新登录。",
        onDismissRequest = onDismiss,
        largeScreen = true,
        maxWidth = 480.dp,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", onDismiss, Modifier.weight(1f))
            TextButton(
                "退出登录", onConfirm, Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// Miuix's small app bar centers titles. Desktop pages share a leading title aligned
// with their cards while retaining Miuix typography, colors and action components.
@Composable
private fun DesktopPageHeader(
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3)
            Text(subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        actions()
    }
}

@Composable
private fun DownloadQueue(
    state: DesktopState,
    tasks: List<Task>,
    failed: List<Task>,
    parsing: Boolean,
    onNewDownload: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(
            tabs = listOf("进行中 (${tasks.size})", "失败 (${failed.size})"),
            selectedTabIndex = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
        )
        val shownTasks = if (selectedTab == 0) tasks else failed
        MiuixTaskList(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            topPadding = 0.dp,
        ) {
            if (!state.isLoggedIn || state.isLoggingIn || state.isValidatingSession) {
                item("account") {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) { AccountConnection(state) }
                }
            }
            state.nativeError?.let { error ->
                item("engine") {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                        BasicComponent(
                            title = "下载引擎不可用", summary = error,
                            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                        )
                    }
                }
            }
            if (parsing) {
                item("parsing") {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                        BasicComponent(
                            title = "正在解析链接",
                            summary = "读取歌曲与专辑信息，任务会自动加入队列。",
                            startAction = { CircularProgressIndicator(Modifier.size(24.dp)) },
                        )
                    }
                }
            }
            if (selectedTab == 1 && failed.isNotEmpty()) {
                item("retry-actions") {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton("清除失败", state.downloadManager::clearAllFailedTasks)
                        TextButton(
                            "重试全部", state.downloadManager::retryAllFailedTasks,
                            enabled = state.isLoggedIn && state.nativeReady,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
            if (shownTasks.isEmpty() && !parsing) {
                item("empty") {
                    QueueEmptyState(
                        icon = if (selectedTab == 0) MiuixIcons.Download else MiuixIcons.Recent,
                        title = if (selectedTab == 0) "还没有下载任务" else "没有失败的下载",
                        summary = if (selectedTab == 0) "添加 Apple Music 歌曲或专辑链接即可开始。" else "需要重试的歌曲会显示在这里。",
                        modifier = Modifier.heightIn(min = 280.dp),
                        onNewDownload = if (selectedTab == 0) onNewDownload else null,
                    )
                }
            }
            items(shownTasks, key = { it.id }) { task ->
                DownloadTaskItem(
                    task,
                    onRetry = if (selectedTab == 1 && state.isLoggedIn && state.nativeReady) {
                        { state.downloadManager.retryTask(task) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun DownloadHistory(state: DesktopState, completed: List<Task>) {
    if (completed.isEmpty()) {
        QueueEmptyState(
            icon = DesktopIcons.HistoryOutline,
            title = "还没有下载记录",
            summary = "本次运行中完成的歌曲会显示在这里。",
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        MiuixTaskList(
            modifier = Modifier.fillMaxSize(),
        ) {
            items(completed, key = { it.id }) { task ->
                DownloadTaskItem(task, onOpen = { state.openDirectory(task.config.downloadPath) })
            }
        }
    }
}

@Composable
private fun DownloadTaskItem(task: Task, onRetry: (() -> Unit)? = null, onOpen: (() -> Unit)? = null) {
    val status = task.status
    val running = status == Task.Status.Preparing || status == Task.Status.Downloading || status == Task.Status.Processing
    val statusText = when (status) {
        Task.Status.Pending -> "等待中"
        Task.Status.Preparing -> "准备中"
        Task.Status.Downloading -> "下载中"
        Task.Status.Processing -> "写入信息"
        Task.Status.Completed -> "已完成"
        is Task.Status.Failed -> "下载失败"
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent(
            startAction = {
                Icon(
                    MiuixIcons.Music,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            endActions = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (running) CircularProgressIndicator(Modifier.size(20.dp))
                    Text(
                        statusText,
                        color = if (status is Task.Status.Failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                    if (onRetry != null) TextButton("重试", onRetry)
                    if (onOpen != null) {
                        IconButton(onClick = onOpen) {
                            Icon(MiuixIcons.Folder, contentDescription = "打开歌曲目录")
                        }
                    }
                }
            },
            bottomAction = if (status is Task.Status.Failed) {
                {
                    Text(
                        text = status.error ?: "下载失败，请重试。",
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else null,
        ) {
            Text(task.track.name, style = MiuixTheme.textStyles.headline1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                task.track.artistName,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun AccountConnection(state: DesktopState) {
    val busy = state.isLoggingIn || state.isValidatingSession
    BasicComponent(
        title = when {
            state.isValidatingSession -> "正在验证 Apple Music"
            state.isLoggingIn -> "在浏览器中完成登录"
            state.isLoggedIn -> "Apple Music 已连接"
            else -> "连接 Apple Music"
        },
        summary = when {
            state.isValidatingSession -> "正在验证本机保存的登录信息…"
            state.isLoggingIn -> state.loginStatus
            state.isLoggedIn -> "登录信息已加密保存在本机。"
            state.canRetryConnection -> "暂时无法连接，请检查网络后重试。"
            else -> "下载前请使用有效的订阅账号登录。"
        },
        endActions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    if (state.isLoggingIn) TextButton("取消", state::cancelLogin)
                } else if (!state.isLoggedIn) {
                    TextButton(
                        text = if (state.canRetryConnection) "重试连接" else "登录",
                        onClick = state::login,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

@Composable
private fun QueueEmptyState(
    icon: ImageVector,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    onNewDownload: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
            Text(title, style = MiuixTheme.textStyles.title4)
            Text(summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
            if (onNewDownload != null) {
                TextButton("新建下载", onNewDownload, colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

@Composable
private fun NewDownloadPage(
    state: DesktopState,
    link: String,
    onLinkChange: (String) -> Unit,
    parsing: Boolean,
    onSettings: () -> Unit,
    onSubmit: () -> Unit,
) {
    val canSubmit = link.isNotBlank() && state.isLoggedIn && state.nativeReady &&
        !parsing && !state.isLoggingIn && !state.isValidatingSession
    MiuixTaskList(
        modifier = Modifier.fillMaxSize(),
        itemSpacing = 24.dp,
    ) {
        item("link") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("添加音乐", style = MiuixTheme.textStyles.title4)
                        Text(
                            "粘贴 Apple Music 歌曲或专辑链接，开始下载。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    TextField(
                        value = link,
                        onValueChange = onLinkChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = "歌曲或专辑链接",
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                MiuixIcons.Link,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 16.dp, end = 8.dp).size(24.dp),
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (canSubmit) onSubmit() }),
                    )
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            text = "粘贴链接",
                            onClick = { state.clipboardText().takeIf { it.isNotBlank() }?.let(onLinkChange) },
                        )
                        TextButton(
                            text = if (parsing) "正在解析…" else "开始下载",
                            onClick = onSubmit,
                            modifier = Modifier.widthIn(min = 144.dp),
                            enabled = canSubmit,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
        if (!state.isLoggedIn || state.isLoggingIn || state.isValidatingSession) {
            item("account") {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) { AccountConnection(state) }
            }
        }
        item("preferences") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(
                    title = "下载音质",
                    summary = if (state.config.preferAtmos) "优先杜比全景声 · 不可用时尝试 AAC" else "AAC",
                    onClick = onSettings,
                    onClickLabel = "修改下载音质",
                    endActions = { TextButton("修改", onSettings) },
                )
                BasicComponent(
                    title = "保存位置",
                    summary = state.config.downloadPath,
                    onClick = onSettings,
                    onClickLabel = "修改保存位置",
                    endActions = {
                        IconButton(onClick = { state.openDirectory() }) {
                            Icon(MiuixIcons.Folder, contentDescription = "打开下载目录")
                        }
                    },
                )
            }
        }
        if (!state.nativeReady) {
            item("engine") {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    BasicComponent(
                        title = if (state.nativeError == null) "正在准备下载引擎" else "下载引擎不可用",
                        summary = state.nativeError ?: "准备完成后即可开始下载。",
                        titleColor = BasicComponentDefaults.titleColor(
                            color = if (state.nativeError == null) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.error,
                        ),
                        endActions = if (state.nativeError == null) {
                            { CircularProgressIndicator(Modifier.size(24.dp)) }
                        } else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalScrollBarApi::class)
@Composable
private fun MiuixTaskList(
    modifier: Modifier = Modifier,
    topPadding: Dp = 16.dp,
    itemSpacing: Dp = 16.dp,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    Box(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = topPadding, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            content = content,
        )
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp),
            trackPadding = PaddingValues(top = topPadding, bottom = 24.dp),
        )
    }
}
