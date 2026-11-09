package com.kyant.amdl.api

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

internal sealed interface AppleMusicLink {
    data class Song(val storefront: String, val id: String) : AppleMusicLink
    data class Album(val storefront: String, val id: String) : AppleMusicLink
}

internal fun parseAppleMusicLink(input: String): AppleMusicLink {
    val value = input.trim()
    require(value.isNotEmpty()) { "Paste an Apple Music song or album link." }

    val normalized = if (SchemePrefix.containsMatchIn(value)) value else "https://$value"
    val uri = try {
        URI(normalized)
    } catch (exception: Exception) {
        throw IllegalArgumentException("The Apple Music link is not valid.", exception)
    }
    require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
        "Only HTTP or HTTPS Apple Music links are supported."
    }
    require(uri.userInfo == null) { "Apple Music links containing user information are not supported." }
    val host = uri.host?.lowercase()
    require(host != null && host in SupportedHosts) {
        "Only music.apple.com links are supported."
    }

    val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
    require(segments.size >= 2) { "The link does not contain a song or album." }
    val storefront = segments[0]
    require(storefront.equals("library", ignoreCase = true).not()) {
        "Apple Music Library links are not supported; open the item in the store first."
    }
    require(Storefront.matches(storefront)) { "The Apple Music storefront is missing or invalid." }

    return when (segments[1].lowercase()) {
        "song" -> AppleMusicLink.Song(storefront.lowercase(), requireId(segments.last()))
        "album" -> {
            val songId = uri.queryParameter("i")
            if (songId != null) {
                AppleMusicLink.Song(storefront.lowercase(), requireId(songId))
            } else {
                AppleMusicLink.Album(storefront.lowercase(), requireId(segments.last()))
            }
        }
        else -> throw IllegalArgumentException("The Apple Music link is not a song or album link.")
    }
}

private fun requireId(value: String): String {
    val id = value.trim()
    require(AppleMusicId.matches(id)) { "The Apple Music link does not contain a valid song or album ID." }
    return id
}

private fun URI.queryParameter(name: String): String? {
    return rawQuery.orEmpty().split('&').firstNotNullOfOrNull { field ->
        val parts = field.split('=', limit = 2)
        if (parts[0] == name) {
            URLDecoder.decode(parts.getOrElse(1) { "" }, UTF_8).takeIf(String::isNotBlank)
        } else {
            null
        }
    }
}

private val SupportedHosts = setOf("music.apple.com", "beta.music.apple.com")
private val SchemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val Storefront = Regex("[A-Za-z]{2}")
private val AppleMusicId = Regex("[0-9]+")
