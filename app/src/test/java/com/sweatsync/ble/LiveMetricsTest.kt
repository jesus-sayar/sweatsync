package com.sweatsync.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMetricsTest {

    @Test
    fun mergeKeepsPreviousValuesForFieldsNotReported() {
        val first = LiveMetrics().merge(MachineData(speedKmh = 8.0, distanceM = 100, cadenceRpm = 80))
        val second = first.merge(MachineData(speedKmh = 9.5, distanceM = null))
        assertEquals(9.5, second.speedKmh, 0.0)
        assertEquals(100, second.distanceM)
        assertEquals(80, second.cadenceRpm)
    }
}
