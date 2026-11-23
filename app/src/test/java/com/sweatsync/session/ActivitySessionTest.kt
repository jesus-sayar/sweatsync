package com.sweatsync.session

import com.sweatsync.ble.LiveMetrics
import com.sweatsync.ble.MachineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivitySessionTest {

    private val clock = FakeClock()
    private val running = LiveMetrics(speedKmh = 10.8) // 3 m/s

    private fun session(initial: LiveMetrics = LiveMetrics()) =
        ActivitySession(clock, MachineType.TREADMILL, 70.0, initial)

    private fun ActivitySession.runFor(seconds: Int, live: LiveMetrics = running) {
        repeat(seconds) {
            clock.advanceSeconds(1)
            tick(live)
        }
    }

    @Test
    fun distanceIsIntegratedFromSpeedWhenTheMachineHasNoCounter() {
        val s = session()
        s.runFor(10)
        assertEquals(30.0, s.distanceM, 0.5)
        assertEquals(10, s.elapsedSec())
    }

    @Test
    fun machineCounterWinsAndStartsFromZero() {
        // The machine already shows 500 m when the activity starts: only what comes after counts.
        val s = session(LiveMetrics(distanceM = 500))
        clock.advanceSeconds(10)
        s.tick(LiveMetrics(speedKmh = 10.8, distanceM = 530))
        assertEquals(30.0, s.distanceM, 0.0)
    }

    @Test
    fun pausedTimeIsNotCounted() {
        val s = session()
        s.runFor(10)
        s.pause(running)
        assertTrue(s.isPaused)
        clock.advanceSeconds(30)
        assertEquals(10, s.elapsedSec())
        s.resume(running)
        s.runFor(5)
        assertEquals(15, s.elapsedSec())
    }

    @Test
    fun nothingAccumulatesWhilePaused() {
        val s = session()
        s.runFor(10)
        s.pause(running)
        val distance = s.distanceM
        val kcal = s.kcal
        clock.advanceSeconds(20)
        s.tick(running)
        assertEquals(distance, s.distanceM, 0.0)
        assertEquals(kcal, s.kcal, 0.0)
    }

    @Test
    fun distanceContinuesAfterAPauseEvenIfTheCounterKeptRunning() {
        val s = session(LiveMetrics(distanceM = 0))
        clock.advanceSeconds(10)
        s.tick(LiveMetrics(speedKmh = 10.8, distanceM = 30))
        s.pause(LiveMetrics(speedKmh = 10.8, distanceM = 30))
        // the belt keeps running 100 m while paused
        s.resume(LiveMetrics(speedKmh = 10.8, distanceM = 130))
        clock.advanceSeconds(10)
        s.tick(LiveMetrics(speedKmh = 10.8, distanceM = 160))
        assertEquals(60.0, s.distanceM, 0.0)
    }

    @Test
    fun caloriesGrowWithTimeAndSpeed() {
        val s = session()
        s.runFor(60)
        assertEquals(Calories.treadmill(10.8, 70.0, 60.0), s.kcal, 0.5)
    }

    @Test
    fun finishReturnsASummaryWithMovingTime() {
        val s = session()
        s.runFor(60)
        s.pause(running)
        clock.advanceSeconds(120)
        s.resume(running)
        s.runFor(60)
        val summary = s.finish(running)
        assertNotNull(summary)
        assertEquals(120, summary!!.durationSec)
        assertEquals(MachineType.TREADMILL, summary.type)
        assertEquals(10.8, summary.avgSpeedKmh, 0.2)
        assertTrue(summary.fileName.startsWith("sweatsync_treadmill_") && summary.fileName.endsWith(".fit"))
        assertTrue(summary.fit.isNotEmpty())
    }

    @Test
    fun finishingWhilePausedClosesThePause() {
        val s = session()
        s.runFor(30)
        s.pause(running)
        clock.advanceSeconds(10)
        val summary = s.finish(running)
        assertNotNull(summary)
        assertFalse(s.isPaused)
        assertEquals(30, summary!!.durationSec)
    }

    @Test
    fun anAccidentalStartIsDiscarded() {
        val s = session()
        s.runFor(2)
        assertNull(s.finish(running))
    }
}
