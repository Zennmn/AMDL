package com.kyant.amdl.api

import java.io.StringReader
import java.util.Locale
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.CDATA
import javax.xml.stream.XMLStreamConstants.CHARACTERS
import javax.xml.stream.XMLStreamConstants.END_ELEMENT
import javax.xml.stream.XMLStreamConstants.START_ELEMENT
import kotlin.math.roundToLong

fun ttmlToLrc(xml: String): String {
    val factory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_COALESCING, false)
    }
    val reader = factory.createXMLStreamReader(StringReader(xml))
    val output = StringBuilder()
    val text = StringBuilder()
    var paragraphDepth = 0
    var skipDepth = 0
    var begin: String? = null

    try {
        while (reader.hasNext()) {
            when (reader.next()) {
                START_ELEMENT -> when (reader.localName) {
                    "p" -> {
                        if (paragraphDepth == 0) {
                            begin = reader.attributeNamed("begin")
                            text.clear()
                        }
                        paragraphDepth++
                    }

                    "br" -> if (paragraphDepth > 0 && skipDepth == 0) text.append('\n')

                    "span" -> {
                        if (paragraphDepth > 0 && (skipDepth > 0 || reader.isBackgroundSpan())) {
                            skipDepth++
                        }
                    }
                }

                CHARACTERS, CDATA -> {
                    if (paragraphDepth > 0 && skipDepth == 0) text.append(reader.text)
                }

                END_ELEMENT -> when (reader.localName) {
                    "span" -> if (skipDepth > 0) skipDepth--

                    "p" -> {
                        paragraphDepth--
                        if (paragraphDepth == 0) {
                            val timestamp = begin?.let { runCatching { it.toLrcTime() }.getOrNull() }
                            if (timestamp != null) output.append('[').append(timestamp).append(']')
                            output.append(trimInner(text)).append('\n')
                            begin = null
                        }
                    }
                }
            }
        }
    } catch (exception: RuntimeException) {
        throw IllegalArgumentException("Invalid TTML lyrics", exception)
    } catch (exception: javax.xml.stream.XMLStreamException) {
        throw IllegalArgumentException("Invalid TTML lyrics", exception)
    } finally {
        reader.close()
    }
    return output.toString()
}

private fun trimInner(value: CharSequence): String {
    return value.toString().trim()
}

private fun javax.xml.stream.XMLStreamReader.attributeNamed(localName: String): String? {
    for (index in 0 until attributeCount) {
        if (getAttributeLocalName(index) == localName) return getAttributeValue(index)
    }
    return null
}

private fun javax.xml.stream.XMLStreamReader.isBackgroundSpan(): Boolean {
    for (index in 0 until attributeCount) {
        if (getAttributeLocalName(index) == "role" && getAttributeValue(index) == "x-bg") {
            return true
        }
    }
    return false
}

internal fun String.toLrcTime(): String {
    val value = trim()
    require(value.isNotEmpty()) { "Lyric timestamp is empty" }
    val seconds = if (':' in value) {
        value.split(':').fold(0.0) { total, part ->
            total * 60.0 + (part.toDoubleOrNull() ?: error("Invalid lyric timestamp: $value"))
        }
    } else {
        when {
            value.endsWith("ms") -> value.dropLast(2).toFiniteSeconds(value) / 1_000.0
            value.endsWith('s') -> value.dropLast(1).toFiniteSeconds(value)
            value.endsWith('m') -> value.dropLast(1).toFiniteSeconds(value) * 60.0
            value.endsWith('h') -> value.dropLast(1).toFiniteSeconds(value) * 3_600.0
            else -> value.toFiniteSeconds(value)
        }
    }
    require(seconds.isFinite() && seconds >= 0.0) { "Invalid lyric timestamp: $value" }

    val totalHundredths = (seconds * 100.0).roundToLong()
    val minutes = totalHundredths / 6_000
    val secondsPart = (totalHundredths % 6_000) / 100.0
    return String.format(Locale.ROOT, "%02d:%05.2f", minutes, secondsPart)
}

private fun String.toFiniteSeconds(source: String): Double {
    return toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("Invalid lyric timestamp: $source")
}
