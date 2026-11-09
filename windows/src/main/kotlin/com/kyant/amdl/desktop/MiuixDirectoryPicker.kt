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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.Locale

@OptIn(ExperimentalScrollBarApi::class)
@Composable
internal fun MiuixDirectoryPicker(
    currentPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val initialPath = remember(currentPath) { normalizeFolderPath(currentPath) }
    var pathInput by remember(currentPath) { mutableStateOf(initialPath) }
    var browsingPath by remember(currentPath) { mutableStateOf(initialPath) }
    var children by remember(currentPath) { mutableStateOf<List<File>>(emptyList()) }
    var currentDirectory by remember(currentPath) { mutableStateOf<File?>(null) }
    var roots by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var loadRequestId by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(browsingPath) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(Unit) {
        roots = withContext(Dispatchers.IO) {
            runCatching {
                File.listRoots()
                    .toList()
                    .sortedBy { it.path.lowercase(Locale.ROOT) }
            }.getOrDefault(emptyList<File>())
        }
    }

    LaunchedEffect(browsingPath, reloadKey) {
        val target = browsingPath
        val requestId = loadRequestId + 1
        loadRequestId = requestId
        loading = true
        error = null
        children = emptyList()
        currentDirectory = null

        val snapshot = withContext(Dispatchers.IO) { readDirectory(target) }

        if (requestId != loadRequestId || browsingPath != target) return@LaunchedEffect

        loading = false
        error = snapshot.error
        children = snapshot.children
        currentDirectory = snapshot.directory
        val directory = snapshot.directory
        if (directory != null && pathInput.trim() == target) {
            pathInput = directory.path
        }
    }

    val parent = currentDirectory?.parentFile ?: File(browsingPath).parentFile
    val pathEdited = pathInput.trim() != (currentDirectory?.path ?: browsingPath)

    fun browse(rawPath: String) {
        val normalized = normalizeFolderPath(rawPath)
        val problem = localPathProblem(normalized)
        if (problem != null) {
            loadRequestId++
            error = problem
            loading = false
            children = emptyList()
            currentDirectory = null
            return
        }

        pathInput = normalized
        if (normalized == browsingPath) {
            reloadKey++
        } else {
            browsingPath = normalized
        }
    }

    OverlayDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = "选择下载目录",
        largeScreen = true,
        maxWidth = 640.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val contentHeight = maxHeight.coerceAtMost(520.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeight),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        modifier = Modifier.weight(1f),
                        label = "文件夹路径",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = { browse(pathInput) },
                        ),
                    )
                    Button(onClick = { browse(pathInput) }) {
                        Text("转到")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = "上一级",
                        onClick = { parent?.let { browse(it.path) } },
                        enabled = parent != null,
                    )
                    TextButton(
                        text = "刷新",
                        onClick = { reloadKey++ },
                    )
                    TextButton(
                        text = "根目录",
                        onClick = {
                            val root = roots.firstOrNull {
                                browsingPath.startsWith(it.path, ignoreCase = true)
                            } ?: roots.firstOrNull()
                            root?.let { browse(it.path) }
                        },
                        enabled = roots.isNotEmpty(),
                    )
                    if (roots.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(roots, key = { it.absolutePath }) { root ->
                                TextButton(
                                    text = root.path,
                                    onClick = { browse(root.path) },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(top = 12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 4.dp),
                ) {
                    when {
                        loading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }

                        error != null -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = error.orEmpty(),
                                color = MiuixTheme.colorScheme.error,
                            )
                        }

                        children.isEmpty() -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "此目录没有子文件夹",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }

                        else -> Row(Modifier.fillMaxSize()) {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                state = listState,
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                items(children, key = { it.absolutePath }) { child ->
                                    ArrowPreference(
                                        title = child.name.ifBlank { child.path },
                                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                        onClick = { browse(child.path) },
                                        endActions = {
                                            TextButton(
                                                text = "选择",
                                                onClick = { onSelect(child.absolutePath) },
                                            )
                                        },
                                    )
                                }
                            }
                            VerticalScrollBar(
                                adapter = rememberScrollBarAdapter(listState),
                                modifier = Modifier.fillMaxHeight(),
                                trackPadding = PaddingValues(vertical = 4.dp),
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "选择此文件夹",
                        onClick = { currentDirectory?.let { onSelect(it.path) } },
                        modifier = Modifier.weight(1f),
                        enabled = !loading && error == null && currentDirectory != null && !pathEdited,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

private data class DirectorySnapshot(
    val directory: File?,
    val children: List<File>,
    val error: String?,
)

private fun normalizeFolderPath(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.length == 2 && trimmed[0].isLetter() && trimmed[1] == ':') {
        trimmed + File.separator
    } else {
        trimmed
    }
}

private fun localPathProblem(path: String): String? {
    val separator = File.separatorChar
    val networkPath = path.length >= 2 && path[0] == separator && path[1] == separator
    return when {
        path.isBlank() -> "请输入文件夹路径。"
        networkPath || path.startsWith("//") -> "不支持网络路径。"
        path.contains("://") -> "请选择本地文件夹。"
        else -> null
    }
}

private fun readDirectory(rawPath: String): DirectorySnapshot {
    val path = normalizeFolderPath(rawPath)
    localPathProblem(path)?.let { problem ->
        return DirectorySnapshot(null, emptyList(), problem)
    }

    val directory = runCatching { File(path).canonicalFile }
        .getOrElse { File(path).absoluteFile }

    if (!directory.exists()) {
        return DirectorySnapshot(null, emptyList(), "找不到目录：" + directory.path)
    }
    if (!directory.isDirectory) {
        return DirectorySnapshot(null, emptyList(), "这不是文件夹：" + directory.path)
    }

    val children = try {
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?.toList()
    } catch (_: SecurityException) {
        null
    }

    if (children == null) {
        return DirectorySnapshot(null, emptyList(), "无法读取此目录（权限不足或设备不可用）。")
    }
    return DirectorySnapshot(directory, children, null)
}
