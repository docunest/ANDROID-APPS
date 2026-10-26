package com.docunest.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One thing you can slide onto in the press-and-slide menu. */
class FanTarget(
    val label: String,
    val icon: Drawable?,
    val color: Int,
    /** Places get wide labelled pills; apps get round icons. */
    val pill: Boolean,
    val subtitle: String? = null,
    val action: () -> Unit,
) {
    val rect = RectF()
}

/**
 * Full-screen, touch-through overlay drawn while the finger is held on the
 * button. The finger never lifts: the bubble keeps receiving the gesture and
 * tells us where it is ([hover]); lifting on a target runs it.
 * Apps sit on an arc around the button; places stack as pills beyond it,
 * always toward the middle of the screen.
 */
class FanView(context: Context, private val targets: List<FanTarget>) : View(context) {
    private var hovered: FanTarget? = null
    private val origin = IntArray(2)
    private val shade = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
    private var cx = 0f
    private var cy = 0f

    /** Lay out around the button's centre (screen coordinates). */
    fun layoutAround(screenCx: Float, screenCy: Float, screenW: Int, screenH: Int) {
        cx = screenCx; cy = screenCy
        val apps = targets.filter { !it.pill }
        val pills = targets.filter { it.pill }
        val iconSize = dp(68).toFloat()
        val toward = atan2(screenH / 2f - cy, screenW / 2f - cx)
        val radius = max(dp(115).toFloat(), apps.size * dp(80) / 3.0f)
        val step = (dp(82) / radius).toDouble()
        apps.forEachIndexed { i, t ->
            val a = toward + (i - (apps.size - 1) / 2.0) * step
            val x = (cx + radius * cos(a)).toFloat()
            val y = (cy + radius * sin(a)).toFloat()
            t.rect.set(x - iconSize / 2, y - iconSize / 2, x + iconSize / 2, y + iconSize / 2)
            clamp(t.rect, screenW, screenH)
        }
        val pw = min(dp(330), screenW - dp(24)).toFloat()
        val ph = dp(62).toFloat()
        val gap = dp(10).toFloat()
        val right = cx < screenW / 2f
        val px = if (right) cx + radius + iconSize / 2 + dp(24) else cx - radius - iconSize / 2 - dp(24) - pw
        val total = pills.size * ph + (pills.size - 1).coerceAtLeast(0) * gap
        var py = (cy - total / 2).coerceIn(dp(12).toFloat(), max(dp(12).toFloat(), screenH - total - dp(12)))
        pills.forEach { t ->
            t.rect.set(px, py, px + pw, py + ph)
            clamp(t.rect, screenW, screenH)
            py += ph + gap
        }
        invalidate()
    }

    private fun clamp(r: RectF, w: Int, h: Int) {
        val m = dp(8).toFloat()
        if (r.left < m) r.offset(m - r.left, 0f)
        if (r.right > w - m) r.offset(w - m - r.right, 0f)
        if (r.top < m) r.offset(0f, m - r.top)
        if (r.bottom > h - m) r.offset(0f, h - m - r.bottom)
    }

    /** Finger position in screen coordinates (target rects are kept in screen coordinates too). */
    fun hover(x: Float, y: Float) {
        val slack = dp(14).toFloat()
        val hit = targets.firstOrNull { RectF(it.rect).apply { inset(-slack, -slack) }.contains(x, y) }
        if (hit !== hovered) {
            hovered = hit
            if (hit != null) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
        }
    }

    fun selected(): FanTarget? = hovered

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origin)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        stroke.strokeWidth = dp(3).toFloat()
        for (t in targets) {
            val on = t === hovered
            val r = RectF(t.rect).apply { offset(-origin[0].toFloat(), -origin[1].toFloat()) }
            if (on) r.inset(-dp(6).toFloat(), -dp(6).toFloat())
            fill.color = if (on) Palette.BLUE else t.color
            if (t.pill) {
                canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                if (on) canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke)
                val pad = dp(18).toFloat()
                text.textSize = dp(18).toFloat()
                sub.textSize = dp(13).toFloat()
                val avail = r.width() - pad * 2
                val title = TextUtils.ellipsize(t.label, text, avail, TextUtils.TruncateAt.END).toString()
                if (t.subtitle != null) {
                    canvas.drawText(title, r.left + pad, r.centerY() - dp(2), text)
                    canvas.drawText(TextUtils.ellipsize(t.subtitle, sub, avail, TextUtils.TruncateAt.END).toString(), r.left + pad, r.centerY() + dp(16), sub)
                } else {
                    canvas.drawText(title, r.left + pad, r.centerY() + dp(6), text)
                }
            } else {
                canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill)
                if (on) canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, stroke)
                val icon = t.icon
                if (icon != null) {
                    val inset = (r.width() * 0.18f).toInt()
                    icon.setBounds(r.left.toInt() + inset, r.top.toInt() + inset, r.right.toInt() - inset, r.bottom.toInt() - inset)
                    icon.draw(canvas)
                } else {
                    text.textSize = dp(24).toFloat()
                    val w = text.measureText(t.label.take(2))
                    canvas.drawText(t.label.take(2), r.centerX() - w / 2, r.centerY() + dp(8), text)
                }
                if (on) {
                    text.textSize = dp(15).toFloat()
                    val w = text.measureText(t.label)
                    canvas.drawText(t.label, r.centerX() - w / 2, r.bottom + dp(20), text)
                }
            }
        }
    }
}
