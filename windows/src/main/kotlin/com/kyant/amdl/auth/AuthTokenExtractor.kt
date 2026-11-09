package com.kyant.amdl.auth

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Pure parsing helpers kept separate from the browser/CDP lifecycle. */
internal object AuthTokenExtractor {
    private const val MEDIA_USER_TOKEN = "media-user-token"
    private val developerTokenPattern =
        Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    fun isAppleMusicUrl(value: String): Boolean {
        val host = runCatching { URI(value).host }.getOrNull()?.lowercase() ?: return false
        return host == "music.apple.com" || host.endsWith(".music.apple.com")
    }

    /**
     * Finds the developer token in Apple Music request data. The query value follows the
     * original Android LoginScene behavior; the Bearer header is the equivalent used by
     * Apple Music API calls.
     */
    fun developerTokenFromRequest(url: String, headers: Map<String, String>): String? {
        if (!isAppleMusicUrl(url)) return null

        runCatching { developerTokenFromUrl(url) }.getOrNull()?.let { return it }
        val authorization = headers.entries
            .firstOrNull { (name, _) -> name.equals("Authorization", ignoreCase = true) }
            ?.value
            ?: return null
        val parts = authorization.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        return validDeveloperToken(parts[1])
    }

    fun developerTokenFromUrl(url: String): String? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        for (part in query.split('&')) {
            val separator = part.indexOf('=')
            val rawName = if (separator >= 0) part.substring(0, separator) else part
            if (decodeQueryComponent(rawName) != "devToken") continue
            if (separator < 0) continue
            validDeveloperToken(decodeQueryComponent(part.substring(separator + 1)))
                ?.let { return it }
        }
        return null
    }

    fun mediaUserTokenFromCookieHeaders(headers: Iterable<String>): String? {
        for (header in headers) {
            for (pair in header.split(';')) {
                cookieValue(pair)?.let { return it }
            }
        }
        return null
    }

    fun mediaUserTokenFromCookieJson(cookies: JSONArray?): String? {
        if (cookies == null) return null
        for (index in 0 until cookies.length()) {
            val cookie = cookies.optJSONObject(index) ?: continue
            if (cookie.optString("name") != MEDIA_USER_TOKEN) continue
            normalizedCookieValue(cookie.optString("value"))?.let { return it }
        }
        return null
    }

    fun headersFromJson(headers: JSONObject?): Map<String, String> {
        if (headers == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val names = headers.keys()
        while (names.hasNext()) {
            val name = names.next()
            val value = headers.opt(name)
            if (value is String) result[name] = value
        }
        return result
    }

    private fun decodeQueryComponent(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun validDeveloperToken(value: String): String? {
        val token = value.trim()
        return token.takeIf { developerTokenPattern.matches(it) }
    }

    private fun cookieValue(pair: String): String? {
        val separator = pair.indexOf('=')
        if (separator <= 0) return null
        if (pair.substring(0, separator).trim() != MEDIA_USER_TOKEN) return null
        return normalizedCookieValue(pair.substring(separator + 1))
    }

    private fun normalizedCookieValue(raw: String): String? {
        var value = raw.trim()
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value = value.substring(1, value.length - 1)
        }
        return value.takeIf { it.isNotBlank() }
    }
}
