package com.sweatsync.session

import com.sweatsync.ble.MachineType
import org.junit.Assert.assertEquals
import org.junit.Test

class CaloriesTest {

    @Test
    fun runningTenKmhForAnHour() {
        // v = 166.7 m/min, VO2 = 0.2 * v + 3.5 = 36.83 ml/kg/min -> 12.89 kcal/min for 70 kg
        assertEquals(773.5, Calories.treadmill(10.0, 70.0, 3600.0), 1.0)
    }

    @Test
    fun walkingFiveKmhForAnHour() {
        // v = 83.3 m/min, VO2 = 0.1 * v + 3.5 = 11.83 ml/kg/min -> 4.14 kcal/min for 70 kg
        assertEquals(248.4, Calories.treadmill(5.0, 70.0, 3600.0), 1.0)
    }

    @Test
    fun standingStillBurnsNothingExtra() {
        assertEquals(0.0, Calories.treadmill(0.0, 70.0, 60.0), 0.0)
        assertEquals(0.0, Calories.bike(0.0, null, 70.0, 60.0), 0.0)
    }

    @Test
    fun bikeFromPower() {
        // 200 W for an hour = 720 kJ of work; at 24 % efficiency about 717 kcal
        assertEquals(717.0, Calories.bike(30.0, 200, 70.0, 3600.0), 1.0)
    }

    @Test
    fun bikeFromSpeedWhenNoPower() {
        // 17 km/h is 6.8 MET: 6.8 * 70 kg * 1 h = 476 kcal
        assertEquals(476.0, Calories.bike(17.0, null, 70.0, 3600.0), 0.5)
    }

    @Test
    fun forMachinePicksTheRightModel() {
        assertEquals(Calories.treadmill(10.0, 70.0, 60.0), Calories.forMachine(MachineType.TREADMILL, 10.0, null, 70.0, 60.0), 0.0)
        assertEquals(Calories.bike(30.0, 200, 70.0, 60.0), Calories.forMachine(MachineType.BIKE, 30.0, 200, 70.0, 60.0), 0.0)
    }
}
