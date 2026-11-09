package com.kyant.amdl.core

import com.kyant.amdl.api.TrackMetadata
import com.kyant.amdl.api.sanitizeWindowsFileNameComponent
import com.kyant.amdl.downloader.UserConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsFileNameTest {

    @Test
    fun replacesWindowsForbiddenCharacters() {
        val sanitized = requireNotNull("Artist / Album: Deluxe?*\"<>|".sanitizeWindowsFileNameComponent())

        assertEquals("Artist - Album- Deluxe------", sanitized)
        assertFalse(sanitized.any { it in "\\/:*?\"<>|" })
    }

    @Test
    fun stripsTrailingDotsAndSpaces() {
        assertEquals("Album", "Album. . ".sanitizeWindowsFileNameComponent())
        assertNull(" . . ".sanitizeWindowsFileNameComponent())
    }

    @Test
    fun prefixesWindowsReservedDeviceNames() {
        assertEquals("_CON", "CON".sanitizeWindowsFileNameComponent())
        assertEquals("_con.txt", "con.txt".sanitizeWindowsFileNameComponent())
        assertEquals("_LPT9", "LPT9 ".sanitizeWindowsFileNameComponent())
        assertEquals("console", "console".sanitizeWindowsFileNameComponent())
    }

    @Test
    fun preservesSafeUnicodeAndAnchorsAlbumPathsInsideTheChosenRoot() {
        assertEquals("夜に駆ける", "夜に駆ける".sanitizeWindowsFileNameComponent())

        val metadata = metadata(
            name = "Title",
            albumArtistName = "Artist/Name",
            albumName = "Album: Deluxe",
            albumReleaseDate = "2024-06-01",
            trackNumber = 1,
            trackCount = 10
        )
        val path = metadata.getFileName(config(saveByAlbum = true))

        assertEquals("Artist-Name/2024 - Album- Deluxe/01 - Title", path)
        assertTrue(path.split('/').none { it.isBlank() })
    }

    private fun config(saveByAlbum: Boolean): UserConfig = UserConfig(
        language = "en-GB",
        preferAtmos = false,
        downloadPath = "C:\\Music",
        saveByAlbum = saveByAlbum,
        mergeSingles = false,
        saveTtml = true
    )

    private fun metadata(
        name: String? = null,
        albumArtistName: String? = null,
        albumName: String? = null,
        albumReleaseDate: String? = null,
        trackNumber: Int? = null,
        trackCount: Int? = null
    ): TrackMetadata = TrackMetadata(
        name = name,
        artistName = null,
        albumName = albumName,
        albumArtistName = albumArtistName,
        composerName = null,
        genreNames = null,
        sortName = null,
        sortArtist = null,
        sortAlbum = null,
        sortAlbumArtist = null,
        sortComposer = null,
        releaseDate = null,
        discNumber = null,
        discCount = null,
        trackNumber = trackNumber,
        trackCount = trackCount,
        isrc = null,
        copyright = null,
        albumReleaseDate = albumReleaseDate,
        isSingle = null,
        isCompilation = null,
        lyrics = null,
        syllableLyrics = null
    )
}
