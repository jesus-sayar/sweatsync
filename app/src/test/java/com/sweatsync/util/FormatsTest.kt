package com.sweatsync.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatsTest {

    private val spanish = Locale.forLanguageTag("es-ES")

    @Test
    fun paceFromSpeed() {
        assertEquals("6:00", Formats.pace(10.0))
        assertEquals("12:00", Formats.pace(5.0))
        assertEquals("--:--", Formats.pace(0.0))
    }

    @Test
    fun clock() {
        assertEquals("00:00:00", Formats.clock(0))
        assertEquals("01:02:03", Formats.clock(3723))
    }

    @Test
    fun duration() {
        assertEquals("14 s", Formats.duration(14))
        assertEquals("1 min", Formats.duration(60))
        assertEquals("23 min", Formats.duration(23 * 60 + 40L))
        assertEquals("1 h 05 min", Formats.duration(3900))
    }

    @Test
    fun kilometresFollowTheLanguage() {
        assertEquals("1.50", Formats.kilometres(1500.0, locale = Locale.US))
        assertEquals("1,50", Formats.kilometres(1500.0, locale = spanish))
        assertEquals("1.5", Formats.kilometres(1500.0, decimals = 1, locale = Locale.US))
    }

    @Test
    fun speed() {
        assertEquals("9.9", Formats.speed(9.94, Locale.US))
        assertEquals("9,9", Formats.speed(9.94, spanish))
    }

    @Test
    fun weight() {
        assertEquals("59", Formats.weight(59f, Locale.US))
        assertEquals("59.5", Formats.weight(59.5f, Locale.US))
        assertEquals("59,5", Formats.weight(59.5f, spanish))
    }
}
