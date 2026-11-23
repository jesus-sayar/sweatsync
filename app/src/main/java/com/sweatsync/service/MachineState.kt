package com.sweatsync.service

import com.sweatsync.ble.LiveMetrics
import com.sweatsync.ble.MachineType

enum class Phase { DISCONNECTED, CONNECTING, CONNECTED }

enum class ConnectionError { BLUETOOTH_OFF, NO_FTMS, CONNECT_FAILED, LINK_LOST }

/** What the screen shows of a running activity. */
data class SessionSnapshot(val paused: Boolean, val elapsedSec: Long, val distanceM: Double, val kcal: Double)

/** Immutable picture of the machine connection and the activity, published to the screens. */
data class MachineState(
    val phase: Phase = Phase.DISCONNECTED,
    val deviceName: String = "",
    val type: MachineType = MachineType.TREADMILL,
    val live: LiveMetrics = LiveMetrics(),
    val session: SessionSnapshot? = null,
    val error: ConnectionError? = null
) {
    /** True while a connection is being made or kept, or an activity is recording. */
    val isBusy: Boolean get() = phase != Phase.DISCONNECTED || session != null
}
