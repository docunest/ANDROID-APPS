package com.docunest.camper

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RulesTest {
    private fun lowBattery(forMin: Double = 5.0, cooldown: Double = 0.0) = JSONObject()
        .put("name", "Low battery: AC off")
        .put("when", JSONArray().put(JSONObject().put("device", "mppt").put("field", "battery_voltage").put("op", "<").put("value", 12.4)))
        .put("then", JSONArray().put(JSONObject().put("device", "ac").put("control", "power").put("value", false)))
        .put("for_min", forMin).put("cooldown_min", cooldown)

    @Test
    fun compares() {
        assertTrue(Rules.compare(12.3, "<", 12.4))
        assertFalse(Rules.compare(12.4, "<", 12.4))
        assertTrue(Rules.compare("12.5", ">=", 12.4))
        assertTrue(Rules.compare(true, "==", "on"))
        assertTrue(Rules.compare(false, "!=", true))
        assertTrue(Rules.compare("Storage", "==", "Storage"))
        assertFalse(Rules.compare(null, "<", 1))
        assertFalse(Rules.compare("Bulk", "<", 3))
    }

    @Test
    fun timeWindowsWrapPastMidnight() {
        assertTrue(Rules.inWindow(23 * 60, "22:00", "07:00"))
        assertTrue(Rules.inWindow(6 * 60 + 59, "22:00", "07:00"))
        assertFalse(Rules.inWindow(7 * 60, "22:00", "07:00"))
        assertTrue(Rules.inWindow(7 * 60, "07:00", "07:30"))
        assertFalse(Rules.inWindow(12 * 60, "07:00", "07:30"))
    }

    @Test
    fun validationRefusesIncompleteRules() {
        assertEquals("Give the rule a name", assertThrows(IllegalArgumentException::class.java) { Rules.validate(lowBattery().put("name", " ")) }.message)
        assertThrows(IllegalArgumentException::class.java) { Rules.validate(lowBattery().put("then", JSONArray())) }
        assertThrows(IllegalArgumentException::class.java) {
            Rules.validate(lowBattery().put("when", JSONArray().put(JSONObject().put("device", "mppt").put("field", "x").put("op", "~").put("value", 1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Rules.validate(lowBattery().put("when", JSONArray().put(JSONObject().put("time", JSONObject().put("from", "25:00").put("to", "07:00")))))
        }
    }

    @Test
    fun firesOnceAfterHoldingThenRearms() {
        val r = Rules(Files.createTempDirectory("rules").toFile())
        r.put(lowBattery(forMin = 5.0))
        var volts = 12.3
        val runs = ArrayList<JSONObject>()
        val eval = { t: Long -> r.evaluate(t, { _, _ -> volts }) { a, _ -> runs.add(a); null } }
        val m = 60_000L
        assertEquals(0, eval(0).size)            // condition starts holding
        assertEquals(0, eval(4 * m).size)        // not yet 5 min
        assertEquals(1, eval(5 * m).size)        // fires
        assertEquals(0, eval(6 * m).size)        // only once while it keeps holding
        volts = 12.8; eval(7 * m)                // re-arms
        volts = 12.2; eval(8 * m)
        assertEquals(1, eval(13 * m).size)       // fires again after another 5 min
        assertEquals(2, runs.size)
        assertEquals("ac", runs[0].getString("device"))
    }

    @Test
    fun cooldownAndDisabled() {
        val r = Rules(Files.createTempDirectory("rules").toFile())
        val saved = r.put(lowBattery(forMin = 0.0, cooldown = 30.0))
        var volts = 12.0
        val m = 60_000L
        val eval = { t: Long -> r.evaluate(t, { _, _ -> volts }) { _, _ -> null }.size }
        assertEquals(1, eval(100 * m))
        volts = 13.0; eval(101 * m); volts = 12.0
        assertEquals(0, eval(110 * m))           // inside the 30 min cooldown
        volts = 13.0; eval(111 * m); volts = 12.0
        assertEquals(1, eval(131 * m))           // cooldown over
        r.put(JSONObject(saved.toString()).put("enabled", false))
        volts = 13.0; eval(132 * m); volts = 12.0
        assertEquals(0, eval(200 * m))
    }

    @Test
    fun keepsRulesAcrossRestarts() {
        val dir = Files.createTempDirectory("rules").toFile()
        val id = Rules(dir).put(lowBattery()).getString("id")
        assertEquals("Low battery: AC off", Rules(dir).get(id)?.getString("name"))
        assertTrue(Rules(dir).delete(id))
        assertEquals(null, Rules(dir).get(id))
    }
}
