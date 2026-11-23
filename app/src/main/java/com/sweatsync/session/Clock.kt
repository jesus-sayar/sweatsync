package com.sweatsync.session

import android.os.SystemClock

/** Time source for a session, replaceable in tests. */
interface Clock {
    /** Wall-clock time in milliseconds since the epoch. */
    fun wallMs(): Long

    /** Monotonic milliseconds, unaffected by clock changes. */
    fun monotonicMs(): Long
}

object SystemClockSource : Clock {
    override fun wallMs() = System.currentTimeMillis()
    override fun monotonicMs() = SystemClock.elapsedRealtime()
}
