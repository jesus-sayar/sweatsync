package com.sweatsync.ui

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/** Fades a status dot in and out while [blinking]: the "recording" dot during a workout. */
class Blink(private val view: View) {

    private var animator: ObjectAnimator? = null

    var blinking = false
        set(value) {
            if (field == value) return
            field = value
            animator?.cancel()
            animator = null
            view.alpha = 1f
            if (value) {
                animator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.25f).apply {
                    duration = PULSE_MS
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        }

    private companion object {
        const val PULSE_MS = 900L
    }
}
