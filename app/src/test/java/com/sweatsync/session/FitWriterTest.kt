package com.sweatsync.session

import com.sweatsync.ble.MachineType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitWriterTest {

    private val start = 1_700_000_000L

    private fun samples(count: Int) =
        (0 until count).map { FitWriter.Sample(start + it, it * 3.0, 3.0, cadenceRpm = 80, powerW = 150, heartRate = 130) }

    private fun fit(type: MachineType, pauses: List<Pair<Long, Long>> = emptyList()) =
        FitWriter.build(samples(10), start, start + 10, 10, type, pauses)

    @Test
    fun headerDescribesTheFile() {
        val bytes = fit(MachineType.TREADMILL)
        assertEquals(14, bytes[0].toInt())
        assertEquals(".FIT", String(bytes, 8, 4))
        val dataSize = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8) or
            ((bytes[6].toInt() and 0xFF) shl 16) or ((bytes[7].toInt() and 0xFF) shl 24)
        assertEquals(bytes.size - 14 - 2, dataSize)
    }

    @Test
    fun checksumsAreValid() {
        val bytes = fit(MachineType.BIKE)
        val headerCrc = (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8)
        assertEquals(FitWriter.crc(bytes.copyOfRange(0, 12)), headerCrc)
        val fileCrc = (bytes[bytes.size - 2].toInt() and 0xFF) or ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
        assertEquals(FitWriter.crc(bytes.copyOfRange(0, bytes.size - 2)), fileCrc)
    }

    @Test
    fun bikesCarryMoreFieldsThanTreadmills() {
        assertTrue(fit(MachineType.BIKE).size > fit(MachineType.TREADMILL).size)
    }

    @Test
    fun pausesAddTimerEvents() {
        val withPause = fit(MachineType.TREADMILL, listOf(start + 3 to start + 6))
        val without = fit(MachineType.TREADMILL)
        // each pause adds a stop event and a start event
        assertTrue(withPause.size > without.size)
    }

    @Test
    fun crcOfAKnownValue() {
        // CRC-16 used by FIT over the ASCII bytes "123456789" is 0xBB3D (CRC-16/ARC)
        assertEquals(0xBB3D, FitWriter.crc("123456789".toByteArray()))
    }

    @Test
    fun theSameInputGivesTheSameFile() {
        assertArrayEquals(fit(MachineType.BIKE), fit(MachineType.BIKE))
    }
}
