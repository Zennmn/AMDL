package com.kyant.amdl.core

import com.kyant.amdl.api.AmTokens
import com.kyant.amdl.api.AppleMusicHttp
import com.kyant.amdl.api.AppleMusicHttpException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleMusicHttpTest {
    private val tokens = AmTokens("test-developer-token", "test-user-token")

    @Test
    fun `Origin reaches the server rather than being silently filtered by Java`() = runBlocking {
        val origin = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/catalog") { exchange ->
            origin.set(exchange.requestHeaders.getFirst("Origin"))
            val bytes = "ok".toByteArray()
            exchange.sendResponseHeaders(if (origin.get() == "https://music.apple.com") 200 else 401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val result = AppleMusicHttp.request("GET", "http://127.0.0.1:${server.address.port}/catalog", null, tokens)
            assertEquals("ok", result.decodeToString())
            assertEquals("https://music.apple.com", origin.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `credentials are attached only to encrypted Apple endpoints`() {
        val apple = AppleMusicHttp.createRequest("GET", URI("https://amp-api.music.apple.com/v1/me/storefront"), null, tokens)
        assertEquals("Bearer ${tokens.devToken}", apple.headers().firstValue("Authorization").get())
        assertEquals(tokens.mediaUserToken, apple.headers().firstValue("Music-User-Token").get())
        assertEquals("media-user-token=${tokens.mediaUserToken}", apple.headers().firstValue("Cookie").get())
        for (url in listOf("https://audio.example/music.m4a", "https://music.apple.com.example/path", "http://api.music.apple.com/v1")) {
            val request = AppleMusicHttp.createRequest("GET", URI(url), null, tokens)
            assertFalse(request.headers().firstValue("Authorization").isPresent)
            assertFalse(request.headers().firstValue("Cookie").isPresent)
            assertFalse(request.headers().firstValue("Music-User-Token").isPresent)
        }
    }

    @Test
    fun `rejected authentication keeps the typed status and a useful Chinese message`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { it.sendResponseHeaders(401, -1); it.close() }
        server.start()
        try {
            val failure = assertFailsWith<AppleMusicHttpException> {
                AppleMusicHttp.request("GET", "http://127.0.0.1:${server.address.port}/", null, tokens)
            }
            assertEquals(401, failure.status)
            assertTrue(failure.message.orEmpty().contains("重新登录"))
            assertFalse(failure.message.orEmpty().contains(tokens.devToken))
        } finally { server.stop(0) }
    }
}
