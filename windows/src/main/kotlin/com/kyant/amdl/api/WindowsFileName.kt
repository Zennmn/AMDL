package com.kyant.amdl.api

private val InvalidWindowsNameCharacter = Regex("[\\u0000-\\u001F\\u007F\\\\/:*?\"<>|]")
private val ReservedWindowsName = Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")
private const val MaxWindowsNameLength = 120

internal fun String.sanitizeWindowsFileNameComponent(): String? {
    var sanitized = this
        .trim()
        .replace(InvalidWindowsNameCharacter, "-")
        .trimEnd(' ', '.')
    if (sanitized.isBlank()) return null

    sanitized = sanitized.truncateCodePoints(MaxWindowsNameLength).trimEnd(' ', '.')
    if (sanitized.isBlank()) return null

    val stem = sanitized.substringBefore('.').trimEnd(' ', '.')
    if (ReservedWindowsName.matches(stem)) sanitized = "_$sanitized"
    return sanitized
}

private fun String.truncateCodePoints(maxCodePoints: Int): String {
    if (codePointCount(0, length) <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints)).trimEnd(' ', '.')
}
