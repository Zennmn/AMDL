package com.kyant.amdl.api

import com.kyant.amdl.engine.Cdm
import com.kyant.amdl.engine.RustLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

data class AmApi(
    private val tokens: AmTokens,
    private val language: String
) {

    suspend fun getBytes(url: String): ByteArray = httpGet(url) { readBytes() }

    suspend fun getText(url: String): String = httpGet(url) { readBytes().decodeToString() }

    suspend fun validateSession(): String {
        require(tokens.devToken.isNotBlank() && tokens.mediaUserToken.isNotBlank()) {
            "请先登录 Apple Music。"
        }
        val data = httpGet(apiUrl("v1/me/storefront")) {
            JsonObject(readBytes().decodeToString())
        }.getArrayOrNull("data")?.getObjectOrNull(0)
        return data?.getStringOrNull("id")?.takeIf { it.isNotBlank() }
            ?: throw IOException("Apple Music 未返回有效的账号地区，登录验证失败。")
    }

    suspend fun getTracks(url: String): List<Track> {
        return when (val link = parseAppleMusicLink(url)) {
            is AppleMusicLink.Song -> getTracksFromSong(link.storefront, link.id)
            is AppleMusicLink.Album -> getTracksFromAlbum(link.storefront, link.id)
        }
    }

    suspend fun getTrackMetadata(track: Track): TrackMetadata {
        val songData =
            httpGet(
                apiUrl(
                    "v1/catalog/${track.storefront}/songs/${track.id}",
                    "l" to language,
                    "include" to "albums,lyrics,syllable-lyrics",
                    "extend" to "ttmlLocalizations"
                )
            ) { JsonObject(readBytes().decodeToString()) }
                .getArrayOrNull("data")?.getObjectOrNull(0)
                ?: throw IOException("Apple Music returned no metadata for this song.")

        val attr = songData.getObjectOrNull("attributes")
        val relationships = songData.getObjectOrNull("relationships")
        val albumAttr =
            relationships?.getObjectOrNull("albums")
                ?.getArrayOrNull("data")?.getObjectOrNull(0)
                ?.getObjectOrNull("attributes")

        val webMetadata =
            httpPost(
                playItunesUrl("WebObjects/MZPlay.woa/wa/webPlayback", "l" to language),
                buildJsonObject {
                    put("salableAdamId", track.id)
                }.toString()
            ) { JsonObject(readBytes().decodeToString()) }
                .getArrayOrNull("songList")?.getObjectOrNull(0)
                ?.getArrayOrNull("assets")?.asSequence<JsonObject>()
                ?.firstOrNull()
                ?.getObjectOrNull("metadata")

        return TrackMetadata(
            name = attr?.getStringOrNull("name"),
            artistName = attr?.getStringOrNull("artistName"),
            albumName = attr?.getStringOrNull("albumName"),
            albumArtistName = attr?.getStringOrNull("albumArtistName"),
            composerName = attr?.getStringOrNull("composerName"),
            genreNames = attr?.getArrayOrNull("genreNames")?.asSequence<String>()?.toList(),
            sortName = webMetadata?.getStringOrNull("sort-name"),
            sortAlbum = webMetadata?.getStringOrNull("sort-album"),
            sortArtist = webMetadata?.getStringOrNull("sort-artist"),
            sortAlbumArtist = null,
            sortComposer = webMetadata?.getStringOrNull("sort-composer"),
            releaseDate = attr?.getStringOrNull("releaseDate"),
            discNumber = webMetadata?.getIntOrNull("discNumber"),
            discCount = webMetadata?.getIntOrNull("discCount"),
            trackNumber = webMetadata?.getIntOrNull("trackNumber"),
            trackCount = webMetadata?.getIntOrNull("trackCount"),
            isrc = attr?.getStringOrNull("isrc"),
            copyright = webMetadata?.getStringOrNull("copyright"),
            albumReleaseDate = albumAttr?.getStringOrNull("releaseDate"),
            isSingle = albumAttr?.getBooleanOrNull("isSingle"),
            isCompilation = albumAttr?.getBooleanOrNull("isCompilation"),
            lyrics =
                relationships?.getObjectOrNull("lyrics")
                    ?.getArrayOrNull("data")?.getObjectOrNull(0)
                    ?.getObjectOrNull("attributes")
                    ?.getStringOrNull("ttml"),
            syllableLyrics =
                relationships?.getObjectOrNull("syllable-lyrics")
                    ?.getArrayOrNull("data")?.getObjectOrNull(0)
                    ?.getObjectOrNull("attributes")
                    ?.getStringOrNull("ttmlLocalizations")
        )
    }

    suspend fun getM4a(id: String, cdm: Cdm, atmos: Boolean): ByteArray {
        val assetUrl =
            httpGet(
                apiUrl(
                    "v1/play/assets",
                    "id" to id,
                    "kind" to "song",
                    "includeLicenseUrls" to "true",
                    "hlsEncryption" to "CBC",
                    "hlsProfile" to "enhancedHls"
                )
            ) { JsonObject(readBytes().decodeToString()) }
                .getObjectOrNull("results")
                ?.getArrayOrNull("assets")?.getObjectOrNull(0)
                ?.getStringOrNull("url")
                ?.replace(AssetVariantSuffix, "$1_default$3")
                ?: throw IOException("Apple Music returned no downloadable audio asset.")

        val assetPlaylist = getText(assetUrl)
        val variantHref = selectVariantHref(assetPlaylist, atmos)
            ?: throw IOException("播放列表中没有可用的 AAC 或杜比全景声音轨。")
        val mediaPlaylistUrl = resolveUrl(assetUrl, variantHref)
        val mediaPlaylist = getText(mediaPlaylistUrl)

        val contentUri = mediaPlaylist.lineSequence()
            .firstOrNull {
                it.contains(WidevineKeyFormat) && !it.contains(DummyPssh)
            }
            ?.substringAfter("URI=\"")
            ?.substringBefore('"')
            ?: throw IOException("The audio stream does not contain a Widevine content key.")

        val pssh = try {
            Base64.getDecoder().decode(contentUri.substringAfter(','))
        } catch (exception: IllegalArgumentException) {
            throw IOException("The audio content key is invalid.", exception)
        }
        val contentKey = cdm.createLicenseRequest(pssh).use { licenseRequest ->
            val challenge = licenseRequest.getChallenge()
                ?: throw IOException("The native engine returned an empty license challenge.")
            val licenseResponse = getLicense(challenge, contentUri, id)
            licenseRequest.getContentKey(licenseResponse)
                ?: throw IOException("The native engine could not read the content key.")
        }

        val songHref = mediaPlaylist.lineSequence()
            .firstOrNull { it.startsWith("#EXT-X-MAP:URI=\"") }
            ?.substringAfter("#EXT-X-MAP:URI=\"")
            ?.substringBefore('"')
            ?: throw IOException("The audio stream does not contain a media file.")
        val encryptedData = getBytes(resolveUrl(mediaPlaylistUrl, songHref))
        return RustLib.decryptM4a(contentKey, encryptedData)
            ?: throw IOException("The native engine could not decrypt the audio file.")
    }

    private suspend fun getTracksFromSong(storefront: String, id: String): List<Track> {
        val json = httpGet(apiUrl("v1/catalog/$storefront/songs/$id", "l" to language)) {
            JsonObject(readBytes().decodeToString())
        }.getArrayOrNull("data")?.getObjectOrNull(0)
            ?: throw IOException("Apple Music returned no metadata for this song.")
        val attr = json.getObjectOrNull("attributes")
        val trackId = json.getStringOrNull("id")
            ?: throw IOException("Apple Music returned a song without an ID.")
        return listOf(
            Track(
                storefront = storefront,
                id = trackId,
                name = attr?.getStringOrNull("name").orEmpty(),
                artistName = attr?.getStringOrNull("artistName").orEmpty(),
                artwork = attr?.getObjectOrNull("artwork")
                    ?.let(::resolveArtworkUrl)
                    ?.let(::Artwork)
            )
        )
    }

    private suspend fun getTracksFromAlbum(storefront: String, id: String): List<Track> {
        val json = httpGet(apiUrl("v1/catalog/$storefront/albums/$id", "l" to language)) {
            JsonObject(readBytes().decodeToString())
        }.getArrayOrNull("data")?.getObjectOrNull(0)
            ?: throw IOException("Apple Music returned no metadata for this album.")
        val artwork = json.getObjectOrNull("attributes")
            ?.getObjectOrNull("artwork")
            ?.let(::resolveArtworkUrl)
            ?.let(::CachedArtwork)
        val tracks = json.getObjectOrNull("relationships")
            ?.getObjectOrNull("tracks")
            ?.getArrayOrNull("data")?.asSequence<JsonObject>()
            ?.mapNotNull { trackJson ->
                val attr = trackJson.getObjectOrNull("attributes") ?: return@mapNotNull null
                val trackId = trackJson.getStringOrNull("id") ?: return@mapNotNull null
                Track(
                    storefront = storefront,
                    id = trackId,
                    name = attr.getStringOrNull("name").orEmpty(),
                    artistName = attr.getStringOrNull("artistName").orEmpty(),
                    artwork = artwork
                )
            }
            ?.toList()
            .orEmpty()
        if (tracks.isEmpty()) throw IOException("Apple Music returned no downloadable tracks for this album.")
        return tracks
    }

    private suspend fun getLicense(challenge: ByteArray, uri: String, adamId: String): ByteArray {
        val license = httpPost(
            "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense",
            buildJsonObject {
                put("challenge", Base64.getEncoder().encodeToString(challenge))
                put("key-system", "com.widevine.alpha")
                put("uri", uri)
                put("adamId", adamId)
                put("user-initiated", true)
            }.toString()
        ) { JsonObject(readBytes().decodeToString()) }
            .getStringOrNull("license")
            ?: throw IOException("Apple Music returned no playback license.")
        return try {
            Base64.getDecoder().decode(license)
        } catch (exception: IllegalArgumentException) {
            throw IOException("Apple Music returned an invalid playback license.", exception)
        }
    }

    private suspend inline fun <T> httpGet(
        url: String,
        crossinline parse: InputStream.() -> T
    ): T = request("GET", url, null, parse)

    private suspend inline fun <T> httpPost(
        url: String,
        body: String,
        crossinline parse: InputStream.() -> T
    ): T = request("POST", url, body, parse)

    private suspend inline fun <T> request(
        method: String,
        url: String,
        body: String?,
        crossinline parse: InputStream.() -> T
    ): T = withContext(Dispatchers.IO) {
        AppleMusicHttp.request(method, url, body, tokens).inputStream().use { it.parse() }
    }
}

private fun apiUrl(path: String, vararg query: Pair<String, String>): String {
    return "https://amp-api.music.apple.com/" + path + queryString(query)
}

private fun playItunesUrl(path: String, vararg query: Pair<String, String>): String {
    return "https://play.itunes.apple.com/" + path + queryString(query)
}

private fun queryString(query: Array<out Pair<String, String>>): String {
    if (query.isEmpty()) return ""
    return query.joinToString(separator = "&", prefix = "?") { (name, value) ->
        URLEncoder.encode(name, UTF_8) + "=" + URLEncoder.encode(value, UTF_8)
    }
}

private fun resolveUrl(base: String, href: String): String {
    return try {
        URI(base).resolve(href).toString()
    } catch (exception: Exception) {
        throw IOException("The audio asset contains an invalid URL.", exception)
    }
}

private fun resolveArtworkUrl(artwork: JsonObject): String? {
    var url = artwork.getStringOrNull("url") ?: return null
    url = url
        .replace("{c}", "bb")
        .replace("{f}", "png")
    artwork.getStringOrNull("width")?.let { url = url.replace("{w}", it) }
    artwork.getStringOrNull("height")?.let { url = url.replace("{h}", it) }
    return url
}

private val AssetVariantSuffix = Regex("(P\\d+)_([^/]+)(\\.m3u8)")
private val WidevineKeyFormat = "KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\""
private const val DummyPssh = "AAAAOHBzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAABgSEAAAAAAAAAAAczEvZTEgICBI88aJmwY="
