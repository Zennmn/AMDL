package com.kyant.amdl.auth

import org.json.JSONArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthTokenExtractorTest {
    private val developerToken = "eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJURUFNMTIzNDU2In0.c2lnbmF0dXJl"
    private val alternateToken = "eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJURUFNOTk5OTk5In0.c2lnbmF0dXJlMg"

    @Test
    fun extractsDeveloperTokenFromExactQueryParameter() {
        val url = "https://js-cdn.music.apple.com/app.js?x=1&devToken=$developerToken&y=2"

        assertEquals(developerToken, AuthTokenExtractor.developerTokenFromUrl(url))
    }

    @Test
    fun decodesDeveloperTokenQueryValue() {
        val encoded = developerToken.replace(".", "%2E")

        assertEquals(
            developerToken,
            AuthTokenExtractor.developerTokenFromUrl(
                "https://amp-api.music.apple.com/v1/catalog?devToken=$encoded"
            )
        )
    }

    @Test
    fun ignoresMissingEmptyOrMalformedDeveloperTokens() {
        assertNull(
            AuthTokenExtractor.developerTokenFromUrl(
                "https://music.apple.com/?devToken=&developerToken=$developerToken"
            )
        )
        assertNull(
            AuthTokenExtractor.developerTokenFromUrl(
                "https://music.apple.com/?devToken=not-a-jwt"
            )
        )
    }

    @Test
    fun extractsBearerTokenOnlyForAppleMusicRequests() {
        val musicHeaders = mapOf("authorization" to "Bearer $developerToken")
        val accountHeaders = mapOf("Authorization" to "Bearer $alternateToken")

        assertEquals(
            developerToken,
            AuthTokenExtractor.developerTokenFromRequest(
                "https://api.music.apple.com/v1/test",
                musicHeaders
            )
        )
        assertNull(
            AuthTokenExtractor.developerTokenFromRequest(
                "https://idmsa.apple.com/appleauth/auth",
                accountHeaders
            )
        )
    }

    @Test
    fun queryDeveloperTokenTakesPrecedenceOverBearerHeader() {
        assertEquals(
            developerToken,
            AuthTokenExtractor.developerTokenFromRequest(
                "https://music.apple.com/path?devToken=$developerToken",
                mapOf("Authorization" to "Bearer $alternateToken")
            )
        )
    }

    @Test
    fun extractsMediaUserTokenFromCookieAndSetCookieHeaders() {
        assertEquals(
            "cookie.value-1",
            AuthTokenExtractor.mediaUserTokenFromCookieHeaders(
                listOf("country=cn; media-user-token=cookie.value-1; language=zh")
            )
        )
        assertEquals(
            "signed-in-user",
            AuthTokenExtractor.mediaUserTokenFromCookieHeaders(
                listOf("media-user-token=\"signed-in-user\"; Path=/; Secure; HttpOnly")
            )
        )
        assertNull(
            AuthTokenExtractor.mediaUserTokenFromCookieHeaders(
                listOf("x-media-user-token=wrong; media-user-token=; Path=/")
            )
        )
    }

    @Test
    fun extractsMediaUserTokenFromCdpCookieObjects() {
        val cookies = JSONArray()
            .put(
                org.json.JSONObject()
                    .put("name", "session-id")
                    .put("value", "other")
            )
            .put(
                org.json.JSONObject()
                    .put("name", "media-user-token")
                    .put("value", "cdp-cookie-value")
                    .put("domain", ".music.apple.com")
            )

        assertEquals(
            "cdp-cookie-value",
            AuthTokenExtractor.mediaUserTokenFromCookieJson(cookies)
        )
    }
}
