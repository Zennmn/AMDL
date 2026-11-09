package com.kyant.amdl.core

import com.kyant.amdl.api.AppleMusicLink
import com.kyant.amdl.api.parseAppleMusicLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AppleMusicUrlTest {

    @Test
    fun parsesSongLinkWithoutNetwork() {
        val link = assertIs<AppleMusicLink.Song>(
            parseAppleMusicLink("https://music.apple.com/cn/song/example-title/1440833098?l=en-GB")
        )

        assertEquals("cn", link.storefront)
        assertEquals("1440833098", link.id)
    }

    @Test
    fun parsesAlbumAndAlbumTrackLinks() {
        val album = assertIs<AppleMusicLink.Album>(
            parseAppleMusicLink("https://beta.music.apple.com/us/album/example/123456789/")
        )
        val track = assertIs<AppleMusicLink.Song>(
            parseAppleMusicLink("https://music.apple.com/us/album/example/123456789?i=987654321&uo=4")
        )

        assertEquals(AppleMusicLink.Album("us", "123456789"), album)
        assertEquals(AppleMusicLink.Song("us", "987654321"), track)
    }

    @Test
    fun acceptsClipboardFriendlyLinkWithoutScheme() {
        val link = assertIs<AppleMusicLink.Song>(
            parseAppleMusicLink("music.apple.com/cn/song/example/12345")
        )

        assertEquals(AppleMusicLink.Song("cn", "12345"), link)
    }

    @Test
    fun rejectsUnsupportedAndMalformedLinksWithUsefulInputErrors() {
        assertFailsWith<IllegalArgumentException> { parseAppleMusicLink("   ") }
        assertFailsWith<IllegalArgumentException> {
            parseAppleMusicLink("https://example.com/us/song/example/123")
        }
        assertFailsWith<IllegalArgumentException> {
            parseAppleMusicLink("https://music.apple.com/library/song/example/123")
        }
        assertFailsWith<IllegalArgumentException> {
            parseAppleMusicLink("https://music.apple.com/us/browse/featured")
        }
        assertFailsWith<IllegalArgumentException> {
            parseAppleMusicLink("https://music.apple.com/us/song/example/not-a-number")
        }
    }
}
