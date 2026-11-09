package com.kyant.amdl.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AppleMusicHttpException(val status: Int, val requiresLogin: Boolean = false) : IOException(when (status) {
    401 -> "Apple Music 登录凭据未通过验证，请重新登录（401）。"
    403 -> "Apple Music 拒绝了此请求，请检查订阅和账号地区（403）。"
    404 -> "未找到这首音乐，它可能未在链接对应地区上架（404）。"
    429 -> "请求过于频繁，请稍后重试（429）。"
    else -> "Apple Music 请求失败（HTTP $status）。"
})

/** HttpClient preserves Origin, which desktop HttpURLConnection silently drops. */
internal object AppleMusicHttp {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    suspend fun request(method: String, url: String, body: String?, tokens: AmTokens): ByteArray {
        var target = URI(url)
        repeat(6) {
            val response = withTimeoutOrNull(60_000) {
                client.sendAsync(createRequest(method, target, body, tokens), HttpResponse.BodyHandlers.ofByteArray()).await()
            } ?: throw SocketTimeoutException("请求超时，请检查网络后重试。")
            val status = response.statusCode()
            if (status in 200..299) return response.body()
            if (status in setOf(301, 302, 303, 307, 308) && method == "GET") {
                val location = response.headers().firstValue("Location").orElse(null)
                    ?: throw IOException("Apple Music 返回了无效的跳转地址。")
                val next = target.resolve(location)
                require(next.scheme.equals("https", true) ||
                    (target.scheme.equals("http", true) && next.scheme.equals("http", true))) {
                    "下载地址发生了不安全的跳转。"
                }
                target = next
            } else {
                throw AppleMusicHttpException(status, status == 401 && acceptsAppleCredentials(target.host))
            }
        }
        throw IOException("下载地址跳转次数过多。")
    }

    internal fun createRequest(method: String, target: URI, body: String?, tokens: AmTokens): HttpRequest {
        require(target.scheme.equals("https", true) || target.scheme.equals("http", true)) {
            "仅支持 HTTP 或 HTTPS 下载地址。"
        }
        val builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(60))
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("Accept", "application/json, */*")
        if (target.scheme.equals("https", true) && acceptsAppleCredentials(target.host)) {
            if (tokens.devToken.isNotBlank()) builder.header("Authorization", "Bearer ${tokens.devToken}")
            if (tokens.mediaUserToken.isNotBlank()) {
                builder.header("Music-User-Token", tokens.mediaUserToken)
                builder.header("Cookie", "media-user-token=${tokens.mediaUserToken}")
            }
        }
        if (body != null) builder.header("Content-Type", "application/json; charset=utf-8")
        return builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString)
            ?: HttpRequest.BodyPublishers.noBody()).build()
    }

    private fun acceptsAppleCredentials(host: String?): Boolean {
        val name = host?.lowercase() ?: return false
        return name == "music.apple.com" || name.endsWith(".music.apple.com") || name == "play.itunes.apple.com"
    }

    private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        whenComplete { value, failure ->
            if (continuation.isActive) {
                if (failure == null) continuation.resume(value)
                else continuation.resumeWithException((failure as? CompletionException)?.cause ?: failure)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
}
