package com.sweatsync.util

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Number and date formatting shared by the screens and the notification. Decimals follow the phone's
 * language ("5,02 km" in Spanish, "5.02 km" in English); [locale] is only passed by tests.
 */
object Formats {

    /** Running pace as m:ss per kilometre, or "--:--" when standing still. */
    fun pace(speedKmh: Double): String {
        if (speedKmh < MIN_MOVING_KMH) return "--:--"
        val seconds = (3600 / speedKmh).toInt()
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    /** HH:MM:SS. */
    fun clock(totalSec: Long): String =
        String.format(Locale.ROOT, "%02d:%02d:%02d", totalSec / 3600, (totalSec / 60) % 60, totalSec % 60)

    /** "14 s", "23 min" or "1 h 05 min": short activities keep their seconds instead of showing "0 min". */
    fun duration(totalSec: Long): String = when {
        totalSec < 60 -> "$totalSec s"
        totalSec < 3600 -> "${totalSec / 60} min"
        else -> String.format(Locale.ROOT, "%d h %02d min", totalSec / 3600, (totalSec / 60) % 60)
    }

    fun kilometres(meters: Double, decimals: Int = 2, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.${decimals}f", meters / 1000)

    fun speed(kmh: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", kmh)

    /** "59" or "59,5". */
    fun weight(kg: Float, locale: Locale = Locale.getDefault()): String =
        if (kg % 1f == 0f) String.format(locale, "%.0f", kg) else String.format(locale, "%.1f", kg)

    /** "21 de noviembre de 2026" / "November 21, 2026". */
    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.LONG).format(ms)

    fun time(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(ms)

    fun birthDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(ms)

    private const val MIN_MOVING_KMH = 0.5
}
