package com.sweatsync.ble

/** Decodes the FTMS "Treadmill Data" (0x2ACD) and "Indoor Bike Data" (0x2AD2) characteristics (Bluetooth SIG spec). */
object FtmsParser {

    // Treadmill Data flags (bit 0 means "more data": speed is present when it is 0)
    private const val T_AVG_SPEED = 0x002
    private const val T_DISTANCE = 0x004
    private const val T_INCLINATION = 0x008
    private const val T_ELEVATION = 0x010
    private const val T_PACE = 0x020
    private const val T_AVG_PACE = 0x040
    private const val T_ENERGY = 0x080
    private const val T_HEART_RATE = 0x100

    // Indoor Bike Data flags
    private const val B_AVG_SPEED = 0x002
    private const val B_CADENCE = 0x004
    private const val B_AVG_CADENCE = 0x008
    private const val B_DISTANCE = 0x010
    private const val B_RESISTANCE = 0x020
    private const val B_POWER = 0x040
    private const val B_AVG_POWER = 0x080
    private const val B_ENERGY = 0x100
    private const val B_HEART_RATE = 0x200

    fun parseTreadmill(bytes: ByteArray): MachineData? {
        if (bytes.size < 2) return null
        val r = Reader(bytes)
        var speed: Double? = null
        var distance: Int? = null
        var heartRate: Int? = null
        try {
            val flags = r.u16()
            if (flags and 0x001 == 0) speed = r.u16() * SPEED_UNIT
            if (flags and T_AVG_SPEED != 0) r.skip(2)
            if (flags and T_DISTANCE != 0) distance = r.u24()
            if (flags and T_INCLINATION != 0) r.skip(4)
            if (flags and T_ELEVATION != 0) r.skip(4)
            if (flags and T_PACE != 0) r.skip(1)
            if (flags and T_AVG_PACE != 0) r.skip(1)
            if (flags and T_ENERGY != 0) r.skip(5)
            if (flags and T_HEART_RATE != 0) heartRate = r.u8()
        } catch (e: IndexOutOfBoundsException) {
            // Truncated packet: keep the fields already read.
        }
        return MachineData(speed, distance, heartRate = heartRate.valid())
    }

    fun parseBike(bytes: ByteArray): MachineData? {
        if (bytes.size < 2) return null
        val r = Reader(bytes)
        var speed: Double? = null
        var cadence: Int? = null
        var distance: Int? = null
        var power: Int? = null
        var heartRate: Int? = null
        try {
            val flags = r.u16()
            if (flags and 0x001 == 0) speed = r.u16() * SPEED_UNIT
            if (flags and B_AVG_SPEED != 0) r.skip(2)
            if (flags and B_CADENCE != 0) cadence = r.u16() / 2
            if (flags and B_AVG_CADENCE != 0) r.skip(2)
            if (flags and B_DISTANCE != 0) distance = r.u24()
            if (flags and B_RESISTANCE != 0) r.skip(2)
            if (flags and B_POWER != 0) power = r.s16()
            if (flags and B_AVG_POWER != 0) r.skip(2)
            if (flags and B_ENERGY != 0) r.skip(5)
            if (flags and B_HEART_RATE != 0) heartRate = r.u8()
        } catch (e: IndexOutOfBoundsException) {
            // Truncated packet: keep the fields already read.
        }
        return MachineData(speed, distance, cadence, power, heartRate.valid())
    }

    /** A heart rate of 0 means "no sensor". */
    private fun Int?.valid() = this?.takeIf { it > 0 }

    private const val SPEED_UNIT = 0.01 // km/h per count

    private class Reader(private val b: ByteArray) {
        private var i = 0
        fun u8() = b[i++].toInt() and 0xFF
        fun u16() = u8() or (u8() shl 8)
        fun u24() = u8() or (u8() shl 8) or (u8() shl 16)
        fun s16() = u16().toShort().toInt()
        fun skip(n: Int) {
            i += n
        }
    }
}
