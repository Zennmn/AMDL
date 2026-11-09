package com.kyant.amdl.api

/** Select an audio variant even when a master playlist has no separate AUDIO groups. */
internal fun selectVariantHref(playlist: String, preferAtmos: Boolean): String? {
    val variants = buildList {
        var pending: Map<String, String>? = null
        for (rawLine in playlist.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF").trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    pending = parseHlsAttributes(line.substringAfter(':'))
                }
                line.isNotEmpty() && !line.startsWith('#') -> {
                    pending?.let { attributes ->
                        audioVariant(line, attributes)?.let(::add)
                    }
                    pending = null
                }
            }
        }
    }
    val order = if (preferAtmos) {
        listOf(HlsAudioKind.Atmos, HlsAudioKind.Aac, HlsAudioKind.Legacy)
    } else {
        listOf(HlsAudioKind.Aac, HlsAudioKind.Atmos, HlsAudioKind.Legacy)
    }
    for (kind in order) {
        variants.filter { it.kind == kind }
            .maxWithOrNull(compareBy<HlsAudioVariant>(
                { kind == HlsAudioKind.Aac && it.audioGroup == "audio-stereo-256" },
                { it.bitrate }
            ))?.let { return it.href }
    }
    return null
}

private fun parseHlsAttributes(value: String): Map<String, String> =
    HlsAttribute.findAll(value).associate { match ->
        match.groupValues[1] to (match.groups[2]?.value ?: match.groupValues[3]).trim()
    }

private fun audioVariant(href: String, attributes: Map<String, String>): HlsAudioVariant? {
    if ("RESOLUTION" in attributes || "VIDEO" in attributes) return null
    val audioGroup = attributes["AUDIO"].orEmpty()
    val codecs = attributes["CODECS"]?.split(',')?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }.orEmpty()
    val kind = when {
        codecs.isNotEmpty() && codecs.all { it.startsWith("mp4a.") } -> HlsAudioKind.Aac
        codecs.isNotEmpty() && codecs.all { it == "ec-3" } -> HlsAudioKind.Atmos
        // A group label is retained as a fallback for older playlists without CODECS.
        codecs.isNotEmpty() -> return null
        audioGroup.startsWith("audio-atmos-") -> HlsAudioKind.Atmos
        audioGroup.startsWith("audio-stereo-") -> HlsAudioKind.Aac
        audioGroup.isNotBlank() -> HlsAudioKind.Legacy
        else -> return null
    }
    val bitrate = sequenceOf("AVERAGE-BANDWIDTH", "BANDWIDTH")
        .mapNotNull { attributes[it]?.toLongOrNull()?.takeIf { value -> value > 0 } }
        .firstOrNull() ?: 0L
    return HlsAudioVariant(href, audioGroup, kind, bitrate)
}

private enum class HlsAudioKind { Aac, Atmos, Legacy }

private data class HlsAudioVariant(
    val href: String,
    val audioGroup: String,
    val kind: HlsAudioKind,
    val bitrate: Long
)

private val HlsAttribute = Regex("""([A-Z0-9-]+)\s*=\s*(?:"([^"]*)"|([^,]*))""")
