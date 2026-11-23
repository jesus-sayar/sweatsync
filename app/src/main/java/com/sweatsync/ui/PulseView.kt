package com.sweatsync.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.sweatsync.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Search indicator: a radar pulse. Rings expand from a central dot and fade out, like a signal looking for
 * nearby machines. When [isActive] is false it rests as a still, grey dot. Scales with its size.
 */
class PulseView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val accent = context.getColor(R.color.accent)
    private val resting = context.getColor(R.color.outline)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var progress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CYCLE_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedFraction
            invalidate()
        }
    }

    var isActive = true
        set(value) {
            if (field == value) return
            field = value
            updateAnimation()
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        if (isActive && isAttachedToWindow && visibility == VISIBLE) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f
        val dotRadius = max(maxRadius * 0.1f, resources.displayMetrics.density * 2f)
        ring.strokeWidth = max(maxRadius * 0.025f, resources.displayMetrics.density * 1.5f)

        if (!isActive) {
            dot.color = resting
            canvas.drawCircle(cx, cy, dotRadius, dot)
            return
        }
        ring.color = accent
        dot.color = accent
        for (i in 0 until RINGS) {
            val p = (progress + i / RINGS.toFloat()) % 1f
            val eased = 1f - (1f - p) * (1f - p)
            ring.alpha = (MAX_ALPHA * (1f - p).pow(1.4f)).toInt()
            canvas.drawCircle(cx, cy, dotRadius * 1.8f + (maxRadius - dotRadius * 1.8f - ring.strokeWidth) * eased, ring)
        }
        val beat = 1f + 0.12f * sin(progress * 2f * Math.PI.toFloat() * RINGS)
        canvas.drawCircle(cx, cy, dotRadius * beat, dot)
    }

    private companion object {
        const val RINGS = 3
        const val CYCLE_MS = 2600L
        const val MAX_ALPHA = 230
    }
}
