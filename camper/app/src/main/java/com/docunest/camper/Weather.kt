package com.docunest.camper

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Open-Meteo (free, no key) turned into the few answers the screen needs. Same output shape as the
 * Python agent's weather.py, so the UI is shared. Location is rounded to 0.1 deg (~10 km).
 */
object Weather {
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun fetch(lat: Double, lon: Double): JSONObject {
        val q = "latitude=%.1f&longitude=%.1f&timezone=auto&forecast_days=8".format(java.util.Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code,cloud_cover,is_day&hourly=temperature_2m,precipitation" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,wind_gusts_10m_max,sunrise,sunset"
        val c = URL("https://api.open-meteo.com/v1/forecast?$q").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        return c.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }

    fun rainWord(mm: Double): Pair<String, Int> = when {
        mm < 0.2 -> "Dry" to 0
        mm < 1 -> "A few drops" to 1
        mm < 5 -> "Light rain" to 2
        mm < 15 -> "Rainy" to 3
        else -> "Pouring" to 4
    }

    fun condition(code: Int): String = when {
        code == 0 || code == 1 -> "Clear"
        code == 2 -> "Partly cloudy"
        code == 45 || code == 48 -> "Fog"
        code in 51..67 || code in 80..82 -> "Rain"
        code in 71..77 || code == 85 || code == 86 -> "Snow"
        code >= 95 -> "Thunderstorms"
        else -> "Cloudy"
    }

    fun deg(v: Double): String {
        val r = v.roundToInt()
        return (if (r < 0) "−${-r}" else "$r") + "°"
    }

    private fun JSONArray.d(i: Int): Double = if (isNull(i)) 0.0 else getDouble(i)
    private fun r1(v: Double) = (v * 10).roundToInt() / 10.0

    private data class Hour(val t: LocalDateTime, val temp: Double, val mm: Double)

    fun summarize(raw: JSONObject): JSONObject {
        val cur = raw.getJSONObject("current")
        val hourly = raw.getJSONObject("hourly")
        val daily = raw.getJSONObject("daily")
        val now = LocalDateTime.parse(cur.getString("time"))
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val rain = hourly.getJSONArray("precipitation")
        val hours = (0 until times.length()).map { Hour(LocalDateTime.parse(times.getString(it)), temps.d(it), rain.d(it)) }
        fun window(a: LocalDateTime, b: LocalDateTime) = hours.filter { !it.t.isBefore(a) && it.t.isBefore(b) }

        val today = now.toLocalDate().atStartOfDay()
        val evening = now.hour >= 17 || now.hour < 5
        val line: String
        val lineCold: Boolean
        val detail: List<Hour>
        val detailTitle: String
        if (evening) {
            val nightEnd = (if (now.hour < 5) today else today.plusDays(1)).plusHours(8)
            val night = window(now, nightEnd)
            val low = night.minByOrNull { it.temp }
            val mm = night.sumOf { it.mm }
            var l = if (low != null) "Tonight · low ${deg(low.temp)}" else "Tonight"
            if (low != null && low.temp <= 2) l += if (low.t.hour >= 3) ", frost at dawn" else ", frost"
            l += if (mm >= 0.2) ", ${r1(mm)} mm rain" else ", dry"
            line = l
            lineCold = low != null && low.temp <= 5
            detail = night.filter { it.t.hour % 2 == 1 }.take(7)
            detailTitle = "Tonight"
        } else {
            val rest = window(now, today.plusDays(1))
            val hi = rest.maxOfOrNull { it.temp } ?: cur.getDouble("temperature_2m")
            val mm = rest.sumOf { it.mm }
            val wet = rest.filter { it.mm >= 0.2 }
            line = "Today · high ${deg(hi)}, " + if (wet.isNotEmpty()) "${r1(mm)} mm rain from %02d:00".format(wet[0].t.hour) else "dry"
            lineCold = hi < 12
            detail = rest.filter { it.t.hour % 2 == 0 }.take(7)
            detailTitle = "Today"
        }

        val maxT = daily.getJSONArray("temperature_2m_max")
        val minT = daily.getJSONArray("temperature_2m_min")
        val rainSum = daily.getJSONArray("precipitation_sum")
        val tMm = rainSum.d(1)
        val (word, sev) = rainWord(tMm)
        val tomorrow = JSONObject()
            .put("label", if (sev == 0) word else "$word · ${tMm.roundToInt()} mm")
            .put("severity", sev)
            .put("hi", deg(maxT.d(1))).put("lo", deg(minT.d(1)))
            .put("gusts", daily.getJSONArray("wind_gusts_10m_max").d(1).roundToInt())
            .put("condition", condition(daily.getJSONArray("weather_code").optInt(1)))
        val tStart = today.plusDays(1)
        val tHours = JSONArray()
        val buckets = mutableListOf<Pair<Int, Double>>()
        for (h in 6..22 step 2) {
            val block = window(tStart.plusHours(h.toLong()), tStart.plusHours(h + 2L))
            if (block.isNotEmpty()) {
                val mm = r1(block.sumOf { it.mm })
                buckets += h to mm
                tHours.put(JSONObject().put("time", "%02d".format(h)).put("temp", deg(block[0].temp)).put("mm", mm))
            }
        }
        val wetB = buckets.filter { it.second >= 1 }
        if (wetB.isNotEmpty()) {
            val last = wetB.last().first
            tomorrow.put("rain_window", "%02d:00–%02d:00".format(wetB.first().first, last + 2))
            if (last + 2 <= 22) tomorrow.put("dry_after", "%02d:00".format(last + 2))
        }

        val base = maxT.d(0)
        val week = JSONArray()
        val weekList = (1..7).map { i ->
            val day = DAYS[java.time.LocalDate.parse(daily.getJSONArray("time").getString(i)).dayOfWeek.value - 1]
            val hi = maxT.d(i)
            Triple(day, hi, rainSum.d(i)).also { (d, h, mm) ->
                week.put(JSONObject().put("day", d).put("hi", deg(h)).put("lo", deg(minT.d(i)))
                    .put("delta", (h - base).roundToInt()).put("mm", r1(mm)))
            }
        }
        var trend = "Steady week"
        var trendDetail = ""
        val shift = weekList.indexOfFirst { abs(it.second - base) >= 5 }
        if (shift >= 0) {
            val w = weekList[shift]
            if (w.second < base) {
                val his = weekList.drop(shift).filter { it.second - base <= -4 }.map { it.second.roundToInt() }
                trend = "Colder from ${w.first}"
                trendDetail = "highs drop from ${deg(base)} to " + if (his.min() == his.max()) "${his.min()}°" else "${his.min()}–${his.max()}°"
            } else {
                trend = "Warmer from ${w.first}"
                trendDetail = "highs up to ${deg(w.second)}"
            }
        } else {
            val wettest = weekList.maxBy { it.third }
            if (wettest.third >= 10) { trend = "Heavy rain ${wettest.first}"; trendDetail = "${wettest.third.roundToInt()} mm" }
        }
        val wettest = weekList.maxBy { it.third }

        val sunrise = LocalDateTime.parse(daily.getJSONArray("sunrise").getString(0))
        val sunset = LocalDateTime.parse(daily.getJSONArray("sunset").getString(0))
        val detailArr = JSONArray()
        detail.forEach {
            detailArr.put(JSONObject().put("time", "%02d:00".format(it.t.hour)).put("temp", deg(it.temp))
                .put("mm", r1(it.mm)).put("frost", it.temp <= 2))
        }
        return JSONObject()
            .put("updated", "%02d:%02d".format(now.hour, now.minute))
            .put("now", deg(cur.getDouble("temperature_2m")))
            .put("now_temp", cur.getDouble("temperature_2m"))
            .put("now_condition", condition(cur.optInt("weather_code")))
            .put("line", line).put("line_cold", lineCold).put("evening", evening)
            .put("detail_title", detailTitle).put("detail_hours", detailArr)
            .put("tomorrow", tomorrow).put("tomorrow_hours", tHours)
            .put("week", week).put("trend", trend).put("trend_detail", trendDetail)
            .put("wettest", if (wettest.third >= 2) "${wettest.first} (${wettest.third.roundToInt()} mm)" else "")
            .put("is_night", now.isBefore(sunrise) || !now.isBefore(sunset))
            .put("sunny", cur.optInt("is_day") == 1 && cur.optDouble("cloud_cover", 100.0) < 40)
    }
}
