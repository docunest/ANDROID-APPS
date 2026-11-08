package com.docunest.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The floating button: dark circle, 2×2 "apps" glyph, amber badge for new
 * places, and its outline as a status ring ([Ring]): left half GPS, right
 * half Wi-Fi — the normal white outline while all is fine, a thicker
 * coloured half only when something is off.
 */
class BubbleView(context: Context) : View(context) {
    var badge: Int = 0
        set(value) { field = value; invalidate() }
    var pressedLook: Boolean = false
        set(value) { field = value; invalidate() }
    /** Idle see-through look (⚙ opacity) for the button body; the GPS dot always stays fully visible. */
    var fade: Float = 1f
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Left half of the ring (GPS). */
    var gpsArc: Ring.Arc = Ring.FINE
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Right half of the ring (Wi-Fi). */
    var wifiArc: Ring.Arc = Ring.FINE
        set(value) { if (field != value) { field = value; invalidate() } }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(200, 255, 255, 255) }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.AMBER }
    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val r = w / 2f
        val layer = canvas.saveLayerAlpha(0f, 0f, w, height.toFloat(), (fade.coerceIn(0f, 1f) * 255).toInt())
        bg.color = if (pressedLook) Palette.BLUE else Color.argb(235, 17, 24, 39)
        canvas.drawCircle(r, r, r - 1, bg)
        ring.strokeWidth = w * 0.04f
        val oval = RectF(ring.strokeWidth, ring.strokeWidth, w - ring.strokeWidth, w - ring.strokeWidth)
        when {
            gpsArc.fine && wifiArc.fine -> canvas.drawCircle(r, r, r - ring.strokeWidth, ring) // exactly the old look
            gpsArc.fine -> canvas.drawArc(oval, 90f, 180f, false, ring)
            wifiArc.fine -> canvas.drawArc(oval, 270f, 180f, false, ring)
        }
        val s = w * 0.15f
        val gap = w * 0.06f
        for (dx in listOf(-1, 1)) for (dy in listOf(-1, 1)) {
            val cx = r + dx * (s / 2 + gap / 2)
            val cy = r + dy * (s / 2 + gap / 2)
            canvas.drawRoundRect(RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), s * 0.25f, s * 0.25f, glyph)
        }
        if (badge > 0) {
            val br = w * 0.2f
            canvas.drawCircle(w - br, br, br, badgePaint)
            badgeText.textSize = br * 1.3f
            canvas.drawText(if (badge > 9) "9+" else badge.toString(), w - br, br + badgeText.textSize * 0.36f, badgeText)
        }
        canvas.restoreToCount(layer)
        // A half that's off: thicker, coloured, never faded with the idle button. Left = GPS (90°→270°), right = Wi-Fi.
        val sw = w * 0.085f
        val o = RectF(sw / 2, sw / 2, w - sw / 2, w - sw / 2)
        for ((arc, start) in listOf(gpsArc to 90f, wifiArc to 270f)) {
            if (arc.fine) continue
            alertPaint.strokeWidth = sw
            alertPaint.color = arc.color
            alertPaint.pathEffect = if (arc.dashed) DashPathEffect(floatArrayOf(sw * 1.1f, sw * 0.9f), 0f) else null
            canvas.drawArc(o, start + 6f, 168f, false, alertPaint)
        }
    }
}
