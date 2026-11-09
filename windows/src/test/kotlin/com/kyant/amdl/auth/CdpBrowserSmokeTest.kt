package com.kyant.amdl.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIfSystemProperty(named = "amdl.browser.smoke", matches = "true")
class CdpBrowserSmokeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `isolated browser accepts the production CDP transport on about blank`() = runBlocking {
        val browser = listOfNotNull(
            System.getenv("ProgramFiles(x86)")?.let { Path.of(it, "Microsoft/Edge/Application/msedge.exe") },
            System.getenv("ProgramFiles")?.let { Path.of(it, "Microsoft/Edge/Application/msedge.exe") },
            System.getenv("ProgramFiles")?.let { Path.of(it, "Google/Chrome/Application/chrome.exe") }
        ).firstOrNull(Files::isRegularFile)
        assumeTrue(browser != null, "Edge or Chrome is required for the optional browser smoke test")
        val profile = Files.createDirectory(directory.resolve("profile"))
        val process = ProcessBuilder(
            browser.toString(), "--headless=new", "--disable-gpu", "--no-first-run",
            "--no-default-browser-check", "--disable-background-networking", "--disable-extensions",
            "--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1",
            "--user-data-dir=$profile", "about:blank"
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        var connection: CdpConnection? = null
        try {
            val endpoint = withTimeout(20_000) {
                val activePort = profile.resolve("DevToolsActivePort")
                var lines: List<String> = emptyList()
                while (lines.size < 2) {
                    check(process.isAlive) { "Isolated browser exited before its debug port was ready" }
                    lines = runCatching { Files.readAllLines(activePort) }.getOrDefault(emptyList())
                    if (lines.size < 2) delay(100)
                }
                val port = lines[0].toInt()
                require(port in 1..65535 && lines[1].startsWith("/devtools/browser/"))
                URI("ws://127.0.0.1:$port${lines[1]}")
            }
            val cdp = CdpConnection.open(endpoint) { }
            connection = cdp
            val version = cdp.command("Browser.getVersion", JSONObject(), null)
            assertTrue(version.getString("product").isNotBlank())
            val targets = cdp.command("Target.getTargets", JSONObject(), null).getJSONArray("targetInfos")
            val page = (0 until targets.length()).map { targets.getJSONObject(it) }
                .first { it.optString("type") == "page" && it.optString("url") == "about:blank" }
            val session = cdp.command("Target.attachToTarget", JSONObject()
                .put("targetId", page.getString("targetId")).put("flatten", true), null).getString("sessionId")
            cdp.command("Network.enable", JSONObject(), session)
            val answer = cdp.command("Runtime.evaluate", JSONObject().put("expression", "1+1"), session)
            assertEquals(2, answer.getJSONObject("result").getInt("value"))
            assertTrue(cdp.command("Network.getCookies", JSONObject(), session).has("cookies"))
        } finally {
            val children = process.descendants().toList()
            runCatching { connection?.requestBrowserClose() }
            runCatching { connection?.close() }
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            children.filter(ProcessHandle::isAlive).forEach { it.destroyForcibly() }
            process.waitFor(3, TimeUnit.SECONDS)
        }
    }
}
