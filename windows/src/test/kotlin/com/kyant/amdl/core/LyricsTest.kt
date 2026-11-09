package com.kyant.amdl.core

import com.kyant.amdl.api.toLrcTime
import com.kyant.amdl.api.ttmlToLrc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LyricsTest {

    @Test
    fun convertsTimedParagraphsAndSkipsBackgroundVocals() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div>
                <p begin="1.25s"><span>First </span><span>line</span></p>
                <p begin="62.5s">Second<span ttm:role="x-bg"><span> hidden</span></span> line</p>
                <p begin="500ms"><![CDATA[Half & second]]></p>
              </div></body>
            </tt>
        """.trimIndent()

        assertEquals(
            "[00:01.25]First line\n[01:02.50]Second line\n[00:00.50]Half & second\n",
            ttmlToLrc(ttml)
        )
    }

    @Test
    fun convertsTimestampsUsingLocaleIndependentFormatting() {
        assertEquals("01:02.35", "1:02.345".toLrcTime())
        assertEquals("00:00.50", "500ms".toLrcTime())
        assertEquals("01:00.00", "1m".toLrcTime())
    }

    @Test
    fun rejectsMalformedTtml() {
        assertFailsWith<IllegalArgumentException> {
            ttmlToLrc("<tt><body><p begin=\"1s\">unfinished")
        }
    }
}
