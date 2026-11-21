package com.docunest.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Waze log block format from monorepo docs/ksw/docs/waze-debug-log.md §3 (seen 2026-11-15). */
class PoliceTest {
    @Test fun parsesPoliceBlock() {
        Police.reports.clear()
        val log = """
[23:20:01.100 23403 D] NVG_LOG: distance_to_next_turn=277, distance_to_prev_turn=0, current_road_type=-1
[23:20:02.200 23403 D] add_alert_action {
  realtime_alert {
    id: 741061085
    alert_info {
      type: POLICE
      police { type: POLICE_DEFAULT }
      position { lon_times_1000000: -9123527  lat_times_1000000: 38713632 }
      seg_direction: FORWARDS
      on_route: true
    }
    alert_reporting_info {
      alert_address { street: "Lg. dos Caminhos de Ferro"  city: "LISBOA" }
      thumbs_up_count: 7
    }
  }
}
[23:20:02.300 23403 D] add_alert_action {
  realtime_alert {
    id: 5
    alert_info {
      type: ACCIDENT
      position { lon_times_1000000: -9100000  lat_times_1000000: 38700000 }
    }
  }
}
""".trimIndent()
        log.lines().forEach { Police.line(it) }
        assertEquals(1, Police.reports.size)
        val r = Police.reports["741061085"]!!
        assertEquals(38.713632, r.lat, 1e-9); assertEquals(-9.123527, r.lng, 1e-9)
        assertTrue(r.wazeOnRoute)
        assertEquals("Lg. dos Caminhos de Ferro", r.street); assertEquals("LISBOA", r.city); assertEquals(7, r.thumbs)
    }

    @Test fun recordsWazeLines() {
        listOf(
            "[23:15:45.810 23403 D] NVG_LOG: distance_to_next_turn=277, distance_to_prev_turn=0, current_road_type=3",
            "[23:15:46.000 23403 D] [LANES] ------ Route map ------",
            "[23:15:46.001 23403 D] [LANES]-->SEG #0   INSTR:0  NODE:150312569 TEXT:\"NONE\")",
            "[23:15:46.002 23403 D] [LANES]   SEG #5   INSTR:2  NODE:340146872 TEXT:\"TURN_RIGHT\")",
            "[23:15:46.100 23403 D] route changed new_generation=1, prev_generation=-1",
        ).forEach { WazeTap.line(it) }
        val got = DebugLog.lines("WAZE").map { it.msg }.joinToString("\n")
        assertTrue(got, got.contains("road type") && got.contains("3"))
        assertTrue(got, got.contains("route turns (2)") && got.contains("TURN_RIGHT"))
        assertTrue(got, got.contains("route changed new_generation=1"))
    }
}
