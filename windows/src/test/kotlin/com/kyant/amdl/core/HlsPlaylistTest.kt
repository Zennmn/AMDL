package com.kyant.amdl.core

import com.kyant.amdl.api.selectVariantHref
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HlsPlaylistTest {

    @Test
    fun observedAacOnlyPlaylistSelectsHighestBitrateForBothPreferences() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=278657,BANDWIDTH=290895,CODECS="mp4a.40.2"
            high.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=137789,BANDWIDTH=148201,CODECS="mp4a.40.2"
            middle.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=70004,BANDWIDTH=82117,CODECS="mp4a.40.5"
            low.m3u8
        """.trimIndent()

        assertEquals("high.m3u8", selectVariantHref(playlist, preferAtmos = false))
        assertEquals("high.m3u8", selectVariantHref(playlist, preferAtmos = true))
    }

    @Test
    fun prefersEc3AtmosWhenRequestedAndUsesAacOtherwise() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=320000,CODECS="mp4a.40.2",AUDIO="audio-stereo-256"
            stereo.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="ec-3"
            atmos.m3u8
        """.trimIndent()

        assertEquals("stereo.m3u8", selectVariantHref(playlist, preferAtmos = false))
        assertEquals("atmos.m3u8", selectVariantHref(playlist, preferAtmos = true))
    }

    @Test
    fun recognizesLegacyAtmosGroupWhenCodecMetadataIsAbsent() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=320000,CODECS="mp4a.40.2",AUDIO="audio-other"
            high-aac.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=64000,AUDIO="audio-atmos-256"
            legacy-atmos.m3u8
        """.trimIndent()

        assertEquals("high-aac.m3u8", selectVariantHref(playlist, preferAtmos = false))
        assertEquals("legacy-atmos.m3u8", selectVariantHref(playlist, preferAtmos = true))
    }

    @Test
    fun preservesStereo256PriorityWithinAacForBothFallbackPreferences() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=384000,CODECS="mp4a.40.2",AUDIO="audio-other"
            high-aac.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2",AUDIO="audio-stereo-256"
            stereo.m3u8
        """.trimIndent()

        assertEquals("stereo.m3u8", selectVariantHref(playlist, preferAtmos = false))
        assertEquals("stereo.m3u8", selectVariantHref(playlist, preferAtmos = true))
    }

    @Test
    fun returnsNullWhenOnlyUnknownAudioCodecsAreAvailable() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS="opus"
            unsupported.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="flac"
            also-unsupported.m3u8
        """.trimIndent()

        assertNull(selectVariantHref(playlist, preferAtmos = false))
        assertNull(selectVariantHref(playlist, preferAtmos = true))
    }

    @Test
    fun rejectsMixedAudiovisualAndResolutionBearingVariants() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.4d401f,mp4a.40.2"
            mixed.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=1920x1080,CODECS="mp4a.40.2"
            video.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=96000,CODECS="mp4a.40.2"
            audio.m3u8
        """.trimIndent()

        assertEquals("audio.m3u8", selectVariantHref(playlist, preferAtmos = false))
        assertEquals("audio.m3u8", selectVariantHref(playlist, preferAtmos = true))
    }

    @Test
    fun doesNotBorrowUriFromTheNextStreamHeader() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=999999,CODECS="mp4a.40.2"
            #EXT-X-STREAM-INF:BANDWIDTH=100,CODECS="opus"
            unknown.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=120000,CODECS="mp4a.40.2"
            valid.m3u8
        """.trimIndent()

        assertEquals("valid.m3u8", selectVariantHref(playlist, preferAtmos = false))
    }

    @Test
    fun parsesBomQuotedCodecListsReorderedFieldsAndWhitespace() {
        val playlist = "\uFEFF  " + """
            #EXTM3U
            #EXT-X-STREAM-INF: CODECS = "avc1.4d401f, mp4a.40.2" , BANDWIDTH = 900000
            mixed-video.m3u8
            #EXT-X-STREAM-INF: CODECS = "mp4a.40.2, mp4a.40.5" , AUDIO = "audio-stereo-256" , BANDWIDTH = 96000
            audio.m3u8
        """.trimIndent()

        assertEquals("audio.m3u8", selectVariantHref(playlist, preferAtmos = false))
    }

    @Test
    fun choosesHighestBandwidthAacRegardlessOfPlaylistOrder() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:CODECS="mp4a.40.2",AVERAGE-BANDWIDTH=80000,BANDWIDTH=96000
            first.m3u8
            #EXT-X-STREAM-INF:CODECS="mp4a.40.2",AVERAGE-BANDWIDTH=260000,BANDWIDTH=320000
            highest.m3u8
            #EXT-X-STREAM-INF:CODECS="mp4a.40.2",AVERAGE-BANDWIDTH=160000,BANDWIDTH=192000
            middle.m3u8
        """.trimIndent()

        assertEquals("highest.m3u8", selectVariantHref(playlist, preferAtmos = false))
    }
}
