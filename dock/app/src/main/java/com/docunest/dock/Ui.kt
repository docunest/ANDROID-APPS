package com.docunest.dock

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Tiny view helpers — everything is built in code, sized for a dashboard. */
fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).toInt()
fun View.dp(v: Number): Int = context.dp(v)

fun rounded(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
    if (strokeColor != null) setStroke(strokePx, strokeColor)
}

object Palette {
    val BG = Color.parseColor("#0B1220")
    val CARD = Color.parseColor("#1F2937")
    val CARD_HI = Color.parseColor("#374151")
    val TEXT = Color.WHITE
    val MUTED = Color.parseColor("#9CA3AF")
    val BLUE = Color.parseColor("#2563EB")
    val WAZE = Color.parseColor("#0EA5E9")
    val GMAPS = Color.parseColor("#16A34A")
    val AMBER = Color.parseColor("#F59E0B")
    val RED = Color.parseColor("#DC2626")
}

fun Context.label(text: String, sizeSp: Float = 16f, color: Int = Palette.TEXT, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
}

/** A big, finger-sized button. */
fun Context.bigButton(text: String, color: Int, sizeSp: Float = 18f, onClick: (View) -> Unit) = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(Color.WHITE)
    setTypeface(typeface, android.graphics.Typeface.BOLD)
    gravity = Gravity.CENTER
    minHeight = dp(56)
    minWidth = dp(56)
    setPadding(dp(16), dp(8), dp(16), dp(8))
    background = rounded(color, dp(14).toFloat())
    isClickable = true
    setOnClickListener(onClick)
}

fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m} min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

fun km(meters: Float): String = if (meters < 1000) "${meters.toInt()} m" else if (meters < 20000) "%.1f km".format(meters / 1000) else "${(meters / 1000).toInt()} km"
