package com.kyant.amdl.auth

import com.kyant.amdl.api.AmTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens an isolated Edge/Chrome app window and waits for the signed-in Apple Music web
 * session. Tokens stay in memory and are never written to logs or sent anywhere by this class.
 */
class BrowserLogin(private val onStatus: (String) -> Unit = {}) : AutoCloseable {
    private val resourceLock = Any()
    private val statusLock = Any()
    private var closed = false
    private var browserProcess: Process? = null
    private var profileDirectory: Path? = null
    private var connection: CdpConnection? = null

    suspend fun login(): AmTokens = withContext(Dispatchers.IO) {
        ensureOpen()
        try {
            val launched = launchBrowser()
            adoptBrowser(launched.process, launched.profileDirectory)

            status("正在连接浏览器本地调试通道…")
            val tokenState = TokenState()
            val cdp = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                CdpConnection.open(launched.endpoint.uri) { message ->
                    observeNetworkEvent(message, tokenState)
                }
            } ?: throw BrowserLoginException(
                "无法连接浏览器的本地调试通道，请检查安全策略后重试。"
            )
            adoptConnection(cdp)

            val sessionId = attachToLoginPage(cdp)
            val tokens = withTimeoutOrNull(LOGIN_TIMEOUT_MILLIS) {
                waitForTokens(cdp, sessionId, tokenState)
            }
            if (tokens == null) {
                throw BrowserLoginException(
                    "等待 Apple Music 登录超时（10 分钟），请重新发起登录。"
                )
            }
            status("登录信息已获取，正在关闭登录窗口…")
            tokens
        } finally {
            closeInternal()
        }
    }

    override fun close() {
        closeInternal()
    }

    private suspend fun waitForTokens(
        cdp: CdpConnection,
        sessionId: String,
        state: TokenState
    ): AmTokens {
        checkOwnedBrowserAlive(cdp)
        cdp.command("Page.enable", JSONObject(), sessionId)
        cdp.command("Network.enable", JSONObject(), sessionId)
        status("登录窗口已打开，请在 Apple Music 页面完成登录。")

        // Reload after Network.enable so startup requests are observed even though the app
        // window itself is opened with --app=https://music.apple.com.
        runCatching {
            cdp.command(
                "Page.reload",
                JSONObject().put("ignoreCache", false),
                sessionId
            )
        }

        val startedAt = System.nanoTime()
        var waitReminderSent = false
        while (currentCoroutineContext().isActive) {
            checkOwnedBrowserAlive(cdp)
            readCapturedTokens(state)?.let {
                status("已确认 Apple Music 登录成功。")
                return it
            }

            val cookieResult: JSONObject? = try {
                cdp.command("Network.getCookies", cookieUrls(), sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!cdp.isOpen) {
                    throw BrowserLoginException("浏览器登录连接已断开，请重新登录。")
                }
                null
            }
            if (cookieResult != null) {
                val cookieToken = AuthTokenExtractor.mediaUserTokenFromCookieJson(
                    cookieResult.optJSONArray("cookies")
                )
                if (cookieToken != null) updateMediaToken(state, cookieToken)
            }

            readCapturedTokens(state)?.let {
                status("已确认 Apple Music 登录成功。")
                return it
            }

            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            if (!waitReminderSent && elapsedMillis >= 30_000) {
                waitReminderSent = true
                status("仍在等待 Apple Music 登录，请在新窗口中完成登录。")
            }
            delay(COOKIE_POLL_INTERVAL_MILLIS)
        }
        throw CancellationException("Apple Music login was cancelled")
    }

    private fun observeNetworkEvent(value: String, state: TokenState) {
        val message = runCatching { JSONObject(value) }.getOrNull() ?: return
        val params = message.optJSONObject("params") ?: return
        val requestId = params.optString("requestId")
        when (message.optString("method")) {
            "Network.requestWillBeSent" -> {
                val request = params.optJSONObject("request") ?: return
                val url = request.optString("url")
                val headers = AuthTokenExtractor.headersFromJson(request.optJSONObject("headers"))
                if (AuthTokenExtractor.isAppleMusicUrl(url)) {
                    if (requestId.isNotBlank()) state.appleMusicRequestIds += requestId
                    observeRequestData(url, headers, state)
                    if (requestId.isNotBlank()) {
                        state.pendingRelevantHeaders.remove(requestId)?.let { extraHeaders ->
                            observeRequestData(url, extraHeaders, state)
                        }
                    }
                } else if (requestId.isNotBlank()) {
                    state.appleMusicRequestIds.remove(requestId)
                    state.pendingRelevantHeaders.remove(requestId)
                }
            }

            "Network.requestWillBeSentExtraInfo" -> {
                if (requestId.isBlank()) return
                val headers = AuthTokenExtractor.headersFromJson(params.optJSONObject("headers"))
                if (requestId in state.appleMusicRequestIds) {
                    observeRequestData("https://music.apple.com/", headers, state)
                } else {
                    val relevant = headers.filterKeys { name ->
                        name.equals("Authorization", ignoreCase = true) ||
                            name.equals("Cookie", ignoreCase = true)
                    }
                    if (relevant.isNotEmpty()) {
                        if (state.pendingRelevantHeaders.size > MAX_PENDING_REQUESTS) {
                            state.pendingRelevantHeaders.clear()
                        }
                        state.pendingRelevantHeaders[requestId] = relevant
                    }
                }
            }

            "Network.loadingFinished", "Network.loadingFailed" -> {
                if (requestId.isNotBlank()) {
                    state.appleMusicRequestIds.remove(requestId)
                    state.pendingRelevantHeaders.remove(requestId)
                }
            }
        }
    }

    private fun observeRequestData(url: String, headers: Map<String, String>, state: TokenState) {
        AuthTokenExtractor.developerTokenFromRequest(url, headers)?.let { token ->
            if (state.developerToken.compareAndSet(null, token) &&
                state.mediaUserToken.get() == null
            ) {
                status("已识别开发者令牌，正在等待登录 Cookie…")
            }
        }

        val cookieHeaders = headers.entries
            .filter { (name, _) -> name.equals("Cookie", ignoreCase = true) }
            .map { it.value }
        AuthTokenExtractor.mediaUserTokenFromCookieHeaders(cookieHeaders)?.let { token ->
            if (state.mediaUserToken.compareAndSet(null, token) &&
                state.developerToken.get() == null
            ) {
                status("已识别登录 Cookie，正在等待开发者令牌…")
            }
        }
    }

    private fun updateMediaToken(state: TokenState, value: String) {
        if (state.mediaUserToken.compareAndSet(null, value) &&
            state.developerToken.get() == null
        ) {
            status("已识别登录 Cookie，正在等待开发者令牌…")
        }
    }

    private fun readCapturedTokens(state: TokenState): AmTokens? {
        val developerToken = state.developerToken.get()
        val mediaUserToken = state.mediaUserToken.get()
        if (developerToken.isNullOrBlank() || mediaUserToken.isNullOrBlank()) return null
        return AmTokens(developerToken, mediaUserToken)
    }

    private suspend fun attachToLoginPage(cdp: CdpConnection): String {
        repeat(TARGET_LOOKUP_ATTEMPTS) {
            checkOwnedBrowserAlive(cdp)
            val targets = cdp.command("Target.getTargets")
                .optJSONArray("targetInfos")
                ?: JSONArray()
            val selected = (0 until targets.length())
                .mapNotNull { targets.optJSONObject(it) }
                .firstOrNull { target ->
                    target.optString("type") == "page" &&
                        AuthTokenExtractor.isAppleMusicUrl(target.optString("url"))
                }
            if (selected != null) {
                val attached = cdp.command(
                    "Target.attachToTarget",
                    JSONObject()
                        .put("targetId", selected.optString("targetId"))
                        .put("flatten", true)
                )
                val sessionId = attached.optString("sessionId")
                if (sessionId.isNotBlank()) return sessionId
            }
            delay(TARGET_POLL_INTERVAL_MILLIS)
        }
        throw BrowserLoginException("未能找到 Apple Music 登录页面，请重新登录。")
    }

    private fun checkOwnedBrowserAlive(cdp: CdpConnection) {
        if (!cdp.isOpen) {
            throw BrowserLoginException("浏览器登录连接已断开，请重新登录。")
        }
        val process = synchronized(resourceLock) { browserProcess }
        if (process != null && !process.isAlive) {
            throw BrowserLoginException("登录窗口已关闭，未获取到完整登录信息。")
        }
    }

    private suspend fun launchBrowser(): LaunchedBrowser {
        val candidates = findBrowsers()
        if (candidates.isEmpty()) {
            throw BrowserLoginException(
                "未找到 Microsoft Edge 或 Google Chrome。请安装其中一个浏览器后重试。"
            )
        }

        var lastFailure: BrowserLoginException? = null
        for ((index, candidate) in candidates.withIndex()) {
            ensureOpen()
            status(
                if (index == 0) "正在启动专用的 ${candidate.displayName} 登录窗口…"
                else "正在尝试使用 ${candidate.displayName} 登录…"
            )
            val profile = createProfileDirectory()
            var process: Process? = null
            try {
                val logFile = profile.resolve("browser-launch.log")
                process = ProcessBuilder(
                    candidate.executable.toString(),
                    "--user-data-dir=${profile.toAbsolutePath()}",
                    "--remote-debugging-port=0",
                    "--remote-debugging-address=127.0.0.1",
                    "--app=https://music.apple.com",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-sync",
                    "--disable-extensions"
                )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .start()
                runCatching { process.outputStream.close() }

                val endpoint = waitForDevToolsEndpoint(profile, process)
                return LaunchedBrowser(candidate, process, profile, endpoint)
            } catch (cancelled: CancellationException) {
                stopOwnedProcess(process)
                deleteOwnedProfile(profile)
                throw cancelled
            } catch (failure: BrowserLoginException) {
                lastFailure = failure
                stopOwnedProcess(process)
                deleteOwnedProfile(profile)
                if (!isOpen()) throw failure
            } catch (_: Exception) {
                lastFailure = BrowserLoginException(
                    "浏览器启动失败。请确认 Edge 或 Chrome 可以正常运行。"
                )
                stopOwnedProcess(process)
                deleteOwnedProfile(profile)
            }
        }
        throw lastFailure ?: BrowserLoginException("无法启动浏览器，请重试。")
    }

    private suspend fun waitForDevToolsEndpoint(
        profile: Path,
        process: Process
    ): DevToolsEndpoint {
        val activePortFile = profile.resolve("DevToolsActivePort")
        val deadline = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(BROWSER_START_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            ensureOpen()
            readDevToolsEndpoint(activePortFile)?.let { return it }
            if (!process.isAlive) {
                throw BrowserLoginException("浏览器登录窗口提前退出，未获取到登录信息。")
            }
            delay(BROWSER_PORT_POLL_INTERVAL_MILLIS)
        }
        throw BrowserLoginException(
            "浏览器未能开启本地调试端口。企业安全策略可能禁用了远程调试，" +
                "请检查 RemoteDebuggingAllowed 限制后重试。"
        )
    }

    private fun readDevToolsEndpoint(path: Path): DevToolsEndpoint? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (size !in 1L..4096L) return null
        val lines = runCatching { Files.readAllLines(path, StandardCharsets.UTF_8) }
            .getOrNull() ?: return null
        if (lines.size < 2) return null
        val port = lines[0].trim().toIntOrNull() ?: return null
        val browserPath = lines[1].trim()
        if (port !in 1..65535 || !BROWSER_PATH_PATTERN.matches(browserPath)) return null
        return DevToolsEndpoint(port, browserPath)
    }

    private fun findBrowsers(): List<BrowserCandidate> {
        val paths = ArrayList<BrowserCandidate>()
        val programFiles = System.getenv("ProgramFiles")
        val programFilesX86 = System.getenv("ProgramFiles(x86)")
        val localAppData = System.getenv("LOCALAPPDATA")

        fun add(base: String?, relative: String, displayName: String) {
            if (base.isNullOrBlank()) return
            val candidate = Path.of(base).resolve(relative)
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                paths += BrowserCandidate(displayName, candidate)
            }
        }

        add(programFilesX86, "Microsoft/Edge/Application/msedge.exe", "Microsoft Edge")
        add(programFiles, "Microsoft/Edge/Application/msedge.exe", "Microsoft Edge")
        add(localAppData, "Microsoft/Edge/Application/msedge.exe", "Microsoft Edge")
        add(programFiles, "Google/Chrome/Application/chrome.exe", "Google Chrome")
        add(programFilesX86, "Google/Chrome/Application/chrome.exe", "Google Chrome")
        add(localAppData, "Google/Chrome/Application/chrome.exe", "Google Chrome")
        return paths.distinctBy {
            it.executable.toAbsolutePath().normalize().toString().lowercase()
        }
    }

    private fun createProfileDirectory(): Path {
        val tempRoot = temporaryDirectoryRoot()
        var created: Path? = null
        return try {
            created = Files.createTempDirectory(tempRoot, PROFILE_PREFIX)
            val realRoot = tempRoot.toRealPath()
            val realCreated = created.toRealPath(LinkOption.NOFOLLOW_LINKS)
            if (realCreated.parent != realRoot ||
                !realCreated.fileName.toString().startsWith(PROFILE_PREFIX) ||
                Files.isSymbolicLink(created)
            ) {
                throw BrowserLoginException("无法安全创建临时浏览器目录。")
            }
            created
        } catch (failure: BrowserLoginException) {
            deleteOwnedProfile(created)
            throw failure
        } catch (_: Exception) {
            deleteOwnedProfile(created)
            throw BrowserLoginException("无法创建临时浏览器目录，请检查临时目录权限。")
        }
    }

    private fun deleteOwnedProfile(path: Path?) {
        if (path == null) return
        repeat(PROFILE_DELETE_ATTEMPTS) {
            if (!isVerifiedOwnedProfile(path)) return
            val deleted = runCatching {
                Files.walk(path).use { entries ->
                    entries.sorted(Comparator.reverseOrder()).forEach { entry ->
                        Files.deleteIfExists(entry)
                    }
                }
            }.isSuccess
            if (deleted && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            Thread.sleep(PROFILE_DELETE_RETRY_MILLIS)
        }
    }

    private fun isVerifiedOwnedProfile(path: Path): Boolean {
        val root = runCatching { temporaryDirectoryRoot().toRealPath() }.getOrNull() ?: return false
        val normalized = path.toAbsolutePath().normalize()
        if (normalized.parent != root) return false
        if (!normalized.fileName.toString().startsWith(PROFILE_PREFIX)) return false
        if (Files.isSymbolicLink(normalized)) return false
        val real = runCatching { normalized.toRealPath(LinkOption.NOFOLLOW_LINKS) }
            .getOrNull() ?: return false
        return real.parent == root
    }

    private fun temporaryDirectoryRoot(): Path =
        Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()

    private fun adoptBrowser(process: Process, profile: Path) {
        val accepted = synchronized(resourceLock) {
            if (closed) false else {
                browserProcess = process
                profileDirectory = profile
                true
            }
        }
        if (!accepted) {
            stopOwnedProcess(process)
            deleteOwnedProfile(profile)
            throw BrowserLoginException("登录已取消。")
        }
    }

    private fun adoptConnection(value: CdpConnection) {
        val accepted = synchronized(resourceLock) {
            if (closed) false else {
                connection = value
                true
            }
        }
        if (!accepted) {
            value.close()
            throw BrowserLoginException("登录已取消。")
        }
    }

    private fun closeInternal() {
        synchronized(resourceLock) {
            if (closed && browserProcess == null && profileDirectory == null && connection == null) {
                return
            }
            closed = true
            val process = browserProcess
            val profile = profileDirectory
            val cdp = connection
            browserProcess = null
            profileDirectory = null
            connection = null

            val descendants = process?.descendants()?.toList().orEmpty()
            runCatching { cdp?.requestBrowserClose() }
            runCatching { cdp?.close() }
            stopOwnedProcess(process, descendants)
            deleteOwnedProfile(profile)
        }
    }

    private fun stopOwnedProcess(process: Process?) {
        if (process == null) return
        stopOwnedProcess(process, process.descendants().toList())
    }

    private fun stopOwnedProcess(process: Process?, descendants: List<ProcessHandle>) {
        if (process == null) return
        if (process.isAlive) runCatching { process.destroy() }
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }

        descendants.asReversed().forEach { handle ->
            if (handle.isAlive) runCatching { handle.destroy() }
        }
        Thread.sleep(100)
        descendants.asReversed().forEach { handle ->
            if (handle.isAlive) runCatching { handle.destroyForcibly() }
        }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
    }

    private fun cookieUrls(): JSONObject = JSONObject().put(
        "urls",
        JSONArray()
            .put("https://music.apple.com/")
            .put("https://beta.music.apple.com/")
            .put("https://api.music.apple.com/")
            .put("https://amp-api.music.apple.com/")
            .put("https://idmsa.apple.com/")
            .put("https://account.apple.com/")
    )

    private fun ensureOpen() {
        if (!isOpen()) throw BrowserLoginException("登录窗口已关闭。")
    }

    private fun isOpen(): Boolean = synchronized(resourceLock) { !closed }

    private fun status(value: String) {
        synchronized(statusLock) { runCatching { onStatus(value) } }
    }

    private data class BrowserCandidate(val displayName: String, val executable: Path)

    private data class DevToolsEndpoint(val port: Int, val browserPath: String) {
        val uri: URI = URI.create("ws://127.0.0.1:$port$browserPath")
    }

    private data class LaunchedBrowser(
        val candidate: BrowserCandidate,
        val process: Process,
        val profileDirectory: Path,
        val endpoint: DevToolsEndpoint
    )

    private class TokenState {
        val developerToken = AtomicReference<String?>(null)
        val mediaUserToken = AtomicReference<String?>(null)
        val appleMusicRequestIds = ConcurrentHashMap.newKeySet<String>()
        val pendingRelevantHeaders = ConcurrentHashMap<String, Map<String, String>>()
    }

    private companion object {
        const val PROFILE_PREFIX = "amdl-apple-login-"
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val LOGIN_TIMEOUT_MILLIS = 10 * 60 * 1000L
        const val BROWSER_START_TIMEOUT_MILLIS = 15_000L
        const val BROWSER_PORT_POLL_INTERVAL_MILLIS = 100L
        const val TARGET_POLL_INTERVAL_MILLIS = 200L
        const val COOKIE_POLL_INTERVAL_MILLIS = 500L
        const val TARGET_LOOKUP_ATTEMPTS = 75
        const val PROFILE_DELETE_ATTEMPTS = 12
        const val PROFILE_DELETE_RETRY_MILLIS = 100L
        const val MAX_PENDING_REQUESTS = 1_024
        val BROWSER_PATH_PATTERN = Regex("^/devtools/browser/[A-Za-z0-9._-]+$")
    }
}

internal class BrowserLoginException(message: String) : IOException(message)
