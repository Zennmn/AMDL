package com.kyant.amdl.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kyant.amdl.api.AmApi
import com.kyant.amdl.api.AmTokens
import com.kyant.amdl.api.AppleMusicHttpException
import com.kyant.amdl.auth.BrowserLogin
import com.kyant.amdl.downloader.DownloadManager
import com.kyant.amdl.downloader.UserConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File

class DesktopState(private val store: SettingsStore = SettingsStore()) : AutoCloseable {
    private val initial = store.load()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settingsWrites = Channel<Pair<UserConfig, AmTokens>>(Channel.UNLIMITED)
    private val settingsWriter: Job
    private var credentials = initial.tokens
    private var browserLogin: BrowserLogin? = null
    private var loginJob: Job? = null
    private var validationJob: Job? = null
    private var savedSessionRejected = false

    var config by mutableStateOf(initial.config)
        private set
    var isLoggedIn by mutableStateOf(false)
        private set
    var isValidatingSession by mutableStateOf(false)
        private set
    val canRetryConnection: Boolean
        get() = !savedSessionRejected && credentials.devToken.isNotBlank() && credentials.mediaUserToken.isNotBlank()
    var loginStatus by mutableStateOf("")
        private set
    var isLoggingIn by mutableStateOf(false)
        private set
    var message by mutableStateOf(initial.warning)
        private set
    var nativeError by mutableStateOf<String?>(null)
        private set
    var nativeReady by mutableStateOf(false)
        private set

    val downloadManager = DownloadManager(credentials, config.language)

    init {
        settingsWriter = scope.launch(Dispatchers.IO) {
            for ((savedConfig, savedTokens) in settingsWrites) {
                try {
                    store.save(savedConfig, savedTokens)
                } catch (_: Exception) {
                    scope.launch { message = "设置保存失败，请检查磁盘空间和目录权限。" }
                }
            }
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { NativeLibrary.load() }
            nativeReady = result.isSuccess
            nativeError = result.exceptionOrNull()?.let {
                "下载引擎暂不可用：${it.message ?: "请重新安装 AMDL"}"
            }
        }
        scope.launch {
            downloadManager.authenticationRequired.collect { required ->
                if (required) {
                    savedSessionRejected = true
                    isLoggedIn = false
                    message = "Apple Music 登录已失效，请重新登录（401）。"
                }
            }
        }
        if (canRetryConnection) validateSavedSession()
    }

    private fun validateSavedSession() {
        if (isValidatingSession) return
        isValidatingSession = true
        val tokens = credentials
        validationJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { AmApi(tokens, config.language).validateSession() }
                isLoggedIn = true
                savedSessionRejected = false
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                isLoggedIn = false
                if (failure is AppleMusicHttpException && failure.requiresLogin) {
                    savedSessionRejected = true
                    message = failure.message
                } else {
                    message = "暂时无法验证 Apple Music 连接，请检查网络后点击「重试连接」。"
                }
            } finally {
                isValidatingSession = false
            }
        }
    }

    fun login() {
        if (isLoggingIn || isValidatingSession) return
        if (canRetryConnection && !isLoggedIn) {
            validateSavedSession()
            return
        }
        isLoggingIn = true
        loginStatus = "正在打开 Apple Music 登录窗口…"
        loginJob = scope.launch {
            val session = BrowserLogin { status -> scope.launch { loginStatus = status } }
            browserLogin = session
            try {
                val tokens = withContext(Dispatchers.IO) { session.login() }
                require(tokens.devToken.isNotBlank() && tokens.mediaUserToken.isNotBlank()) {
                    "未获取到完整的登录信息，请重试。"
                }
                loginStatus = "正在验证 Apple Music 账号…"
                withContext(Dispatchers.IO) { AmApi(tokens, config.language).validateSession() }
                credentials = tokens
                downloadManager.updateTokens(tokens)
                savedSessionRejected = false
                isLoggedIn = true
                settingsWrites.trySend(config to tokens)
                message = "Apple Music 登录成功。"
            } catch (_: CancellationException) {
                loginStatus = ""
            } catch (exception: Exception) {
                message = exception.message ?: "登录失败，请重试。"
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { session.close() }
                browserLogin = null
                isLoggingIn = false
                loginStatus = ""
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        scope.launch(Dispatchers.IO) { browserLogin?.close() }
    }

    fun logout() {
        validationJob?.cancel()
        val empty = AmTokens("", "")
        credentials = empty
        downloadManager.updateTokens(empty)
        isLoggedIn = false
        savedSessionRejected = false
        settingsWrites.trySend(config to empty)
        message = "已退出 Apple Music。"
    }

    fun saveConfig(value: UserConfig) {
        config = value
        downloadManager.updateLanguage(value.language)
        settingsWrites.trySend(value to credentials)
    }

    fun clipboardText(): String = try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        (clipboard.getData(DataFlavor.stringFlavor) as? String).orEmpty()
    } catch (_: Exception) {
        message = "暂时无法读取剪贴板，请直接粘贴链接。"
        ""
    }

    fun download(url: String) {
        when {
            isValidatingSession -> message = "正在验证 Apple Music 连接，请稍候。"
            isLoggingIn -> message = "请先完成当前登录。"
            !isLoggedIn -> {
                message = if (canRetryConnection) "正在重新验证 Apple Music 连接…" else "请先登录 Apple Music。"
                login()
            }
            !nativeReady -> message = nativeError ?: "下载引擎正在准备，请稍后重试。"
            url.isBlank() -> message = "请输入歌曲或专辑链接。"
            else -> downloadManager.parseFromUrl(url.trim(), config)
        }
    }

    fun openDirectory(path: String = config.downloadPath) {
        scope.launch(Dispatchers.IO) {
            try {
                val directory = File(path)
                if (!directory.isDirectory) directory.mkdirs()
                Desktop.getDesktop().open(directory)
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { message = "无法打开目录，请在设置中检查路径。" }
            }
        }
    }

    fun dismissMessage(expected: String? = message) {
        if (message == expected) message = null
    }

    override fun close() {
        validationJob?.cancel()
        loginJob?.cancel()
        browserLogin?.close()
        downloadManager.close()
        settingsWrites.close()
        runBlocking { settingsWriter.join() }
        scope.cancel()
    }
}
