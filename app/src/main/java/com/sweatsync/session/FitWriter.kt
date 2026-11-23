package com.sweatsync.session

import com.sweatsync.ble.MachineType
import java.io.ByteArrayOutputStream
import java.util.TimeZone

/** Encodes a treadmill or indoor-bike activity as a FIT file (importable in Garmin Connect and Strava). */
object FitWriter {

    class Sample(
        val unixSec: Long,
        val distanceM: Double,
        val speedMs: Double,
        val cadenceRpm: Int? = null,
        val powerW: Int? = null,
        val heartRate: Int? = null
    )

    private const val FIT_EPOCH_OFFSET = 631065600L

    private const val ENUM = 0x00
    private const val UINT8 = 0x02
    private const val UINT16 = 0x84
    private const val UINT32 = 0x86
    private const val UINT32Z = 0x8C

    private class Field(val num: Int, val size: Int, val type: Int)

    private class Out {
        val data = ByteArrayOutputStream()

        fun u8(v: Int) = data.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }

        fun definition(local: Int, global: Int, fields: List<Field>) {
            u8(0x40 or local)
            u8(0)
            u8(0) // little endian
            u16(global)
            u8(fields.size)
            fields.forEach { u8(it.num); u8(it.size); u8(it.type) }
        }
    }

    private fun fitTime(unixSec: Long) = unixSec - FIT_EPOCH_OFFSET

    fun build(
        samples: List<Sample>,
        startUnix: Long,
        endUnix: Long,
        movingSec: Long,
        machine: MachineType = MachineType.TREADMILL,
        pauses: List<Pair<Long, Long>> = emptyList()
    ): ByteArray {
        val bike = machine == MachineType.BIKE
        val totalDist = samples.lastOrNull()?.distanceM ?: 0.0
        val avgSpeed = if (movingSec > 0) totalDist / movingSec else 0.0
        val timerMs = movingSec * 1000
        val elapsedMs = (endUnix - startUnix) * 1000
        val distCm = Math.round(totalDist * 100)
        val avgSpeedMm = Math.round(avgSpeed * 1000).toInt()
        val o = Out()

        // file_id (type 4 = activity, manufacturer 255 = development)
        o.definition(0, 0, listOf(Field(0, 1, ENUM), Field(1, 2, UINT16), Field(2, 2, UINT16),
            Field(3, 4, UINT32Z), Field(4, 4, UINT32)))
        o.u8(0); o.u8(4); o.u16(255); o.u16(0); o.u32(startUnix and 0xFFFFFFFFL); o.u32(fitTime(startUnix))

        // event: timer start
        o.definition(1, 21, listOf(Field(253, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM)))
        o.u8(1); o.u32(fitTime(startUnix)); o.u8(0); o.u8(0)

        // records (bikes also carry heart rate, cadence and power; 0xFF / 0xFFFF mean "no value")
        val recordFields = mutableListOf(Field(253, 4, UINT32), Field(5, 4, UINT32), Field(6, 2, UINT16))
        if (bike) {
            recordFields += Field(3, 1, UINT8)
            recordFields += Field(4, 1, UINT8)
            recordFields += Field(7, 2, UINT16)
        }
        o.definition(2, 20, recordFields)
        // Records and pause/resume events, in time order (event 0 = timer; type 4 = stop_all, 0 = start).
        class Entry(val unix: Long, val sample: Sample?, val eventType: Int)
        val entries = ArrayList<Entry>()
        samples.forEach { entries += Entry(it.unixSec, it, 0) }
        pauses.forEach { (from, to) ->
            entries += Entry(from, null, 4)
            entries += Entry(to, null, 0)
        }
        entries.sortBy { it.unix }
        for (e in entries) {
            val s = e.sample
            if (s == null) {
                o.u8(1); o.u32(fitTime(e.unix)); o.u8(0); o.u8(e.eventType)
                continue
            }
            o.u8(2)
            o.u32(fitTime(s.unixSec))
            o.u32(Math.round(s.distanceM * 100))
            o.u16(Math.round(s.speedMs * 1000).toInt())
            if (bike) {
                o.u8(s.heartRate ?: 0xFF)
                o.u8(s.cadenceRpm ?: 0xFF)
                o.u16(s.powerW ?: 0xFFFF)
            }
        }

        // event: timer stop_all
        o.u8(1); o.u32(fitTime(endUnix)); o.u8(0); o.u8(4)

        // lap
        o.definition(3, 19, listOf(Field(253, 4, UINT32), Field(2, 4, UINT32), Field(7, 4, UINT32),
            Field(8, 4, UINT32), Field(9, 4, UINT32)))
        o.u8(3); o.u32(fitTime(endUnix)); o.u32(fitTime(startUnix)); o.u32(elapsedMs); o.u32(timerMs); o.u32(distCm)

        // session: treadmill = sport 11 (walking) / sub_sport 1; bike = sport 2 (cycling) / sub_sport 6 (indoor cycling)
        o.definition(4, 18, listOf(Field(253, 4, UINT32), Field(2, 4, UINT32), Field(7, 4, UINT32),
            Field(8, 4, UINT32), Field(9, 4, UINT32), Field(5, 1, ENUM), Field(6, 1, ENUM),
            Field(14, 2, UINT16), Field(25, 2, UINT16), Field(26, 2, UINT16), Field(254, 2, UINT16)))
        o.u8(4); o.u32(fitTime(endUnix)); o.u32(fitTime(startUnix)); o.u32(elapsedMs); o.u32(timerMs)
        o.u32(distCm); o.u8(if (bike) 2 else 11); o.u8(if (bike) 6 else 1); o.u16(avgSpeedMm); o.u16(0); o.u16(1); o.u16(0)

        // activity (event 26 = activity, event_type 1 = stop)
        o.definition(5, 34, listOf(Field(253, 4, UINT32), Field(0, 4, UINT32), Field(1, 2, UINT16),
            Field(2, 1, ENUM), Field(3, 1, ENUM), Field(4, 1, ENUM), Field(5, 4, UINT32)))
        val tzOffset = TimeZone.getDefault().getOffset(endUnix * 1000) / 1000
        o.u8(5); o.u32(fitTime(endUnix)); o.u32(timerMs); o.u16(1); o.u8(0); o.u8(26); o.u8(1)
        o.u32(fitTime(endUnix) + tzOffset)

        val body = o.data.toByteArray()
        val head = Out()
        head.u8(14); head.u8(0x20); head.u16(2132); head.u32(body.size.toLong())
        ".FIT".forEach { head.u8(it.code) }
        head.u16(crc(head.data.toByteArray()))

        val all = head.data.toByteArray() + body
        val c = crc(all)
        return all + byteArrayOf((c and 0xFF).toByte(), ((c shr 8) and 0xFF).toByte())
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    internal fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            val byte = b.toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        }
        return crc
    }
}
