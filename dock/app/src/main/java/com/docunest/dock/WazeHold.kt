package com.docunest.dock

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast

/**
 * A Waze button (owner 2026-11-25): **tap** = send as usual; **hold 3 s** = restart Waze and send
 * ([WazeLinkCheck.restartAndSend]). While held, a light bar fills the button left → right; let go
 * early = nothing. The owner decides when Waze needs the restart — no guessing.
 */
object WazeHold {
    const val HOLD_MS = 3_000L
    const val TAP_MS = 350L

    private class Fill : Drawable() {
        var progress = 0f
            set(v) { field = v; invalidateSelf() }
        private val paint = Paint().apply { color = 0x66FFFFFF }
        override fun draw(c: Canvas) {
            if (progress <= 0f) return
            val b = bounds
            c.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + b.width() * progress, b.bottom.toFloat(), paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attach(v: View, onTap: () -> Unit, onRestart: () -> Unit) {
        val fill = Fill()
        v.foreground = fill
        var downAt = 0L
        var fired = false
        var anim: ValueAnimator? = null
        fun reset() { anim?.cancel(); anim = null; fill.progress = 0f }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = System.currentTimeMillis(); fired = false
                    reset()
                    anim = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = HOLD_MS
                        interpolator = LinearInterpolator()
                        // The bar only shows after a tap would have ended: a normal tap looks normal.
                        addUpdateListener { a -> fill.progress = if (System.currentTimeMillis() - downAt < TAP_MS) 0f else a.animatedValue as Float }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: android.animation.Animator) {
                                if (fill.progress >= 0.99f && !fired) {
                                    fired = true
                                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    fill.progress = 0f
                                    onRestart()
                                }
                            }
                        })
                        start()
                    }
                    v.isPressed = true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    val held = System.currentTimeMillis() - downAt
                    if (!fired) {
                        reset()
                        if (held < TAP_MS) onTap()
                        else Toast.makeText(v.context, "Hold 3 s to restart Waze and send it again", Toast.LENGTH_SHORT).show()
                    }
                }
                MotionEvent.ACTION_CANCEL -> { v.isPressed = false; if (!fired) reset() }
            }
            true
        }
    }
}
