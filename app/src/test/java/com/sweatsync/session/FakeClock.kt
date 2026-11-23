package com.sweatsync.session

/** A clock the tests move by hand. */
class FakeClock(private var wall: Long = 1_700_000_000_000L) : Clock {
    private var mono = 0L

    override fun wallMs() = wall
    override fun monotonicMs() = mono

    fun advanceSeconds(seconds: Int) {
        wall += seconds * 1000L
        mono += seconds * 1000L
    }
}
