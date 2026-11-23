package com.sweatsync.session

import com.sweatsync.ble.MachineType

/**
 * Energy estimates. Gross values (they include resting metabolism), computed from the user's weight.
 * These are estimates, not measurements.
 */
object Calories {

    /** ACSM walking/running equations: VO2 in ml/kg/min from speed, then 5 kcal per litre of O2. */
    fun treadmill(speedKmh: Double, weightKg: Double, seconds: Double): Double {
        if (speedKmh < 0.5 || weightKg <= 0 || seconds <= 0) return 0.0
        val metersPerMin = speedKmh * 1000.0 / 60.0
        val vo2 = if (speedKmh <= WALK_MAX_KMH) 0.1 * metersPerMin + 3.5 else 0.2 * metersPerMin + 3.5
        return vo2 * weightKg / 1000.0 * 5.0 * seconds / 60.0
    }

    /** From mechanical power when the bike reports it (about 24 % gross efficiency), otherwise from speed. */
    fun bike(speedKmh: Double, powerW: Int?, weightKg: Double, seconds: Double): Double {
        if (seconds <= 0) return 0.0
        if (powerW != null && powerW > 0) return powerW * seconds / (4184.0 * 0.24)
        if (speedKmh < 2.0 || weightKg <= 0) return 0.0
        val met = when {
            speedKmh < 16 -> 4.0
            speedKmh < 19 -> 6.8
            speedKmh < 22 -> 8.0
            speedKmh < 25 -> 10.0
            else -> 12.0
        }
        return met * weightKg * seconds / 3600.0
    }

    fun forMachine(type: MachineType, speedKmh: Double, powerW: Int?, weightKg: Double, seconds: Double) =
        if (type == MachineType.BIKE) bike(speedKmh, powerW, weightKg, seconds)
        else treadmill(speedKmh, weightKg, seconds)

    private const val WALK_MAX_KMH = 6.4
}
