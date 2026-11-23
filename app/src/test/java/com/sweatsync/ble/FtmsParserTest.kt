package com.sweatsync.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtmsParserTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun treadmillSpeedAndDistance() {
        // flags 0x0404: distance (bit 2) + elapsed time (bit 10); speed present (bit 0 = 0)
        // speed 3.50 km/h (350 = 0x015E), distance 1234 m (0x0004D2)
        val d = FtmsParser.parseTreadmill(bytes(0x04, 0x04, 0x5E, 0x01, 0xD2, 0x04, 0x00, 0xF2, 0x02))!!
        assertEquals(3.5, d.speedKmh!!, 0.001)
        assertEquals(1234, d.distanceM)
    }

    @Test
    fun treadmillSkipsIntermediateFields() {
        // flags 0x048E: average speed(1) + distance(2) + inclination(3) + energy(7) + elapsed time(10)
        val d = FtmsParser.parseTreadmill(
            bytes(0x8E, 0x04, 0xC8, 0x00, 0x64, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x02, 0x00, 0x03, 0x3C, 0x00)
        )!!
        assertEquals(2.0, d.speedKmh!!, 0.001)
        assertEquals(16, d.distanceM)
    }

    @Test
    fun treadmillWithoutDistance() {
        val d = FtmsParser.parseTreadmill(bytes(0x00, 0x00, 0x2C, 0x01))!!
        assertEquals(3.0, d.speedKmh!!, 0.001)
        assertNull(d.distanceM)
    }

    @Test
    fun treadmillTruncatedPacketKeepsReadFields() {
        // distance declared but its last byte is missing: the speed is still returned
        val d = FtmsParser.parseTreadmill(bytes(0x04, 0x00, 0x5E, 0x01, 0xD2, 0x04))!!
        assertEquals(3.5, d.speedKmh!!, 0.001)
        assertNull(d.distanceM)
    }

    @Test
    fun treadmillIgnoresTinyPackets() {
        assertNull(FtmsParser.parseTreadmill(bytes(0x00)))
    }

    @Test
    fun bikeSpeedOnly() {
        val d = FtmsParser.parseBike(bytes(0x00, 0x00, 0xC4, 0x09))!!
        assertEquals(25.0, d.speedKmh!!, 0.001)
        assertNull(d.cadenceRpm)
        assertNull(d.powerW)
    }

    @Test
    fun bikeSpeedCadenceDistancePower() {
        // flags 0x0054: cadence(2) + distance(4) + power(6); cadence counts half-rpm
        val d = FtmsParser.parseBike(bytes(0x54, 0x00, 0xB8, 0x0B, 0xB4, 0x00, 0x88, 0x13, 0x00, 0xD2, 0x00))!!
        assertEquals(30.0, d.speedKmh!!, 0.001)
        assertEquals(90, d.cadenceRpm)
        assertEquals(5000, d.distanceM)
        assertEquals(210, d.powerW)
    }

    @Test
    fun bikeSkipsFieldsAndReadsHeartRate() {
        // flags 0x02A2: average speed(1) + resistance(5) + average power(7) + heart rate(9)
        val d = FtmsParser.parseBike(bytes(0xA2, 0x02, 0xC4, 0x09, 0x64, 0x00, 0x05, 0x00, 0x96, 0x00, 0x8C))!!
        assertEquals(25.0, d.speedKmh!!, 0.001)
        assertEquals(140, d.heartRate)
    }

    @Test
    fun bikeNegativePowerIsSigned() {
        // flags 0x0040: power only after speed; 0xFFF6 is -10 W
        val d = FtmsParser.parseBike(bytes(0x40, 0x00, 0xC4, 0x09, 0xF6, 0xFF))!!
        assertEquals(-10, d.powerW)
    }

    @Test
    fun bikeTruncatedPacketKeepsReadFields() {
        val d = FtmsParser.parseBike(bytes(0x44, 0x00, 0xC4, 0x09, 0xB4, 0x00))!!
        assertEquals(90, d.cadenceRpm)
        assertNull(d.powerW)
    }

    @Test
    fun zeroHeartRateMeansNoSensor() {
        val d = FtmsParser.parseBike(bytes(0x00, 0x02, 0xC4, 0x09, 0x00))!!
        assertNull(d.heartRate)
    }
}
