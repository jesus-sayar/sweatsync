package com.sweatsync.session

import com.sweatsync.ble.LiveMetrics
import com.sweatsync.ble.MachineType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One recorded activity: stopwatch with pauses, distance, calories and the samples for the FIT file.
 * Pure logic (no Android types), driven by [tick] with the latest [LiveMetrics].
 */
class ActivitySession(
    private val clock: Clock,
    private val type: MachineType,
    private val weightKg: Double,
    initial: LiveMetrics
) {

    /** What is left once the activity ends. */
    class Summary(
        val fit: ByteArray,
        val fileName: String,
        val type: MachineType,
        val startMs: Long,
        val durationSec: Long,
        val distanceM: Double,
        val kcal: Double,
        val avgSpeedKmh: Double
    )

    private val startWallSec = clock.wallMs() / 1000
    private val startMono = clock.monotonicMs()
    private var lastTickMono = startMono
    private var pausedTotalMs = 0L
    private var pauseStartMono = 0L
    private var pauseStartWallSec = 0L
    private val pauses = ArrayList<Pair<Long, Long>>()
    private val samples = ArrayList<FitWriter.Sample>()

    // The machine's own distance counter may not start at zero: only the part after the start counts.
    private var baselineM = initial.distanceM ?: 0
    private var integratedM = 0.0

    var distanceM = 0.0
        private set
    var kcal = 0.0
        private set
    var isPaused = false
        private set

    init {
        samples += sample(initial)
    }

    /** Recorded time, without the paused periods. */
    fun elapsedSec(): Long {
        val end = if (isPaused) pauseStartMono else clock.monotonicMs()
        return (end - startMono - pausedTotalMs) / 1000
    }

    fun tick(live: LiveMetrics) {
        if (isPaused) return
        val now = clock.monotonicMs()
        val dt = (now - lastTickMono) / 1000.0
        lastTickMono = now
        integratedM += live.speedKmh / 3.6 * dt
        kcal += Calories.forMachine(type, live.speedKmh, live.powerW, weightKg, dt)
        distanceM = maxOf(distanceM, bestDistance(live))
        samples += sample(live)
    }

    fun pause(live: LiveMetrics) {
        if (isPaused) return
        tick(live)
        isPaused = true
        pauseStartMono = clock.monotonicMs()
        pauseStartWallSec = clock.wallMs() / 1000
    }

    fun resume(live: LiveMetrics) {
        if (isPaused) closePause(live)
    }

    /** Ends the activity. Returns null when it was too short to keep. */
    fun finish(live: LiveMetrics): Summary? {
        if (isPaused) closePause(live) else tick(live)
        val movingSec = (clock.monotonicMs() - startMono - pausedTotalMs) / 1000
        if (movingSec < MIN_SAVED_SECONDS) return null
        val endSec = clock.wallMs() / 1000
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startWallSec * 1000))
        return Summary(
            fit = FitWriter.build(samples, startWallSec, endSec, movingSec, type, pauses),
            fileName = "sweatsync_${type.name.lowercase(Locale.US)}_$stamp.fit",
            type = type,
            startMs = startWallSec * 1000,
            durationSec = movingSec,
            distanceM = distanceM,
            kcal = kcal,
            avgSpeedKmh = if (movingSec > 0) distanceM / movingSec * 3.6 else 0.0
        )
    }

    private fun closePause(live: LiveMetrics) {
        val now = clock.monotonicMs()
        pausedTotalMs += now - pauseStartMono
        pauses += pauseStartWallSec to clock.wallMs() / 1000
        isPaused = false
        lastTickMono = now
        // The belt or pedals may have kept counting while paused: continue from where the distance stopped.
        live.distanceM?.let { baselineM = it - distanceM.toInt() }
    }

    /** The machine's counter when it reports one, otherwise the distance integrated from speed. */
    private fun bestDistance(live: LiveMetrics): Double {
        val device = live.distanceM ?: return integratedM
        if (device < baselineM) baselineM = 0
        val fromDevice = (device - baselineM).toDouble()
        return if (fromDevice > 0) fromDevice else integratedM
    }

    private fun sample(live: LiveMetrics) = FitWriter.Sample(
        unixSec = clock.wallMs() / 1000,
        distanceM = distanceM,
        speedMs = live.speedKmh / 3.6,
        cadenceRpm = live.cadenceRpm,
        powerW = live.powerW,
        heartRate = live.heartRate
    )

    private companion object {
        /** Anything shorter is treated as an accidental start and is not kept. */
        const val MIN_SAVED_SECONDS = 5
    }
}
