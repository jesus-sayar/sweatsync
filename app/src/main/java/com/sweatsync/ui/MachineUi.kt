package com.sweatsync.ui

import android.content.Context
import com.sweatsync.R
import com.sweatsync.ble.MachineType
import com.sweatsync.data.ActivityStore
import com.sweatsync.util.Formats
import java.util.Calendar

val MachineType?.iconRes: Int
    get() = if (this == MachineType.BIKE) R.drawable.ic_machine_bike else R.drawable.ic_machine_treadmill

val MachineType.nameRes: Int
    get() = if (this == MachineType.BIKE) R.string.machine_bike else R.string.machine_treadmill

/** Strava-style name from the machine and the time of day: "Cinta por la tarde", "Morning ride". */
fun ActivityStore.Item.title(context: Context): String {
    val hour = Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.HOUR_OF_DAY)
    val bike = type == MachineType.BIKE
    val res = when (hour) {
        in MORNING -> if (bike) R.string.title_bike_morning else R.string.title_treadmill_morning
        in AFTERNOON -> if (bike) R.string.title_bike_afternoon else R.string.title_treadmill_afternoon
        else -> if (bike) R.string.title_bike_night else R.string.title_treadmill_night
    }
    return context.getString(res)
}

/** "21 de noviembre de 2026 a las 18:19". */
fun ActivityStore.Item.whenText(context: Context): String =
    context.getString(R.string.fmt_date_time, Formats.date(startMs), Formats.time(startMs))

/** The average that matters for the machine: pace for running, speed for cycling. [short] labels fit a feed card. */
fun ActivityStore.Item.averageSpec(short: Boolean): StatSpec {
    val bike = type == MachineType.BIKE
    val label = when {
        bike -> if (short) R.string.label_speed else R.string.detail_speed
        else -> if (short) R.string.label_pace else R.string.detail_pace
    }
    return if (bike) StatSpec("avg", label, R.string.unit_kmh, Formats.speed(avgSpeedKmh))
    else StatSpec("avg", label, R.string.unit_pace, Formats.pace(avgSpeedKmh))
}

private val MORNING = 5..11
private val AFTERNOON = 12..19
