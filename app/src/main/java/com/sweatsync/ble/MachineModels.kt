package com.sweatsync.ble

enum class MachineType { TREADMILL, BIKE }

/** One decoded FTMS notification. A field is null when the machine does not report it. */
class MachineData(
    val speedKmh: Double?,
    val distanceM: Int?,
    val cadenceRpm: Int? = null,
    val powerW: Int? = null,
    val heartRate: Int? = null
)

/** The latest known reading of every metric, merged from successive notifications. */
data class LiveMetrics(
    val speedKmh: Double = 0.0,
    val distanceM: Int? = null,
    val cadenceRpm: Int? = null,
    val powerW: Int? = null,
    val heartRate: Int? = null
) {
    fun merge(data: MachineData) = LiveMetrics(
        speedKmh = data.speedKmh ?: speedKmh,
        distanceM = data.distanceM ?: distanceM,
        cadenceRpm = data.cadenceRpm ?: cadenceRpm,
        powerW = data.powerW ?: powerW,
        heartRate = data.heartRate ?: heartRate
    )
}

/** A machine seen while scanning. */
data class MachineDevice(
    val address: String,
    val name: String,
    val type: MachineType?,
    val rssi: Int,
    val lastSeenMs: Long
)
