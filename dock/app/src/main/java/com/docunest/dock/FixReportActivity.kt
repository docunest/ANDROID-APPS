package com.docunest.dock

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

/** 📊 Fix report (from the GPS doctor): [FixReport] as a screen to read or screenshot. */
class FixReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("📊  GPS fix report", 22f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(bigButton("✕", Palette.CARD, 20f) { finish() }, LinearLayout.LayoutParams(dp(64), dp(64)))
        root.addView(head)
        val report = FixReport.text(
            GpsDoctor.remedyIds.map { FixReport.Fix(it, GpsDoctor.title(it)) },
            FixHistory.attempts(this), FixHistory.runs(this), GpsDoctor::symptomLabel,
        )
        DebugLog.log("GPS", "fix report opened:\n$report")
        val text = label(report, 13f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(ScrollView(this).apply { addView(HorizontalScrollView(this@FixReportActivity).apply { addView(text) }) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
