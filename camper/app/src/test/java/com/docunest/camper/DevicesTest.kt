package com.docunest.camper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DevicesTest {
    private val setpoint = Control("setpoint", "Setpoint", Control.Type.NUMBER, 21.0, 5.0, 28.0, 0.5, "°C") {}
    private val mode = Control("mode", "Mode", Control.Type.CHOICE, "home", options = listOf(Option("home"), Option("auto"))) {}
    private val power = Control("power", "Power", Control.Type.TOGGLE, false) {}

    @Test
    fun numbersWithinRangeAndStep() {
        assertEquals(21.5, setpoint.coerce(21.5))
        assertEquals(22.0, setpoint.coerce("22"))
        assertEquals(5.0, setpoint.coerce(5))
        assertEquals(28.0, setpoint.coerce(28.0))
    }

    @Test
    fun numbersOutsideRangeOrStepAreRefused() {
        assertEquals("Setpoint must be 5–28°C", assertThrows(IllegalArgumentException::class.java) { setpoint.coerce(29.0) }.message)
        assertThrows(IllegalArgumentException::class.java) { setpoint.coerce(4.5) }
        assertEquals("Setpoint: steps of 0.5", assertThrows(IllegalArgumentException::class.java) { setpoint.coerce(21.3) }.message)
        assertThrows(IllegalArgumentException::class.java) { setpoint.coerce("warm") }
    }

    @Test
    fun choicesAndToggles() {
        assertEquals("auto", mode.coerce("auto"))
        assertThrows(IllegalArgumentException::class.java) { mode.coerce("leave") }
        assertEquals(true, power.coerce("on"))
        assertEquals(false, power.coerce(false))
        assertThrows(IllegalArgumentException::class.java) { power.coerce("maybe") }
    }

    @Test
    fun describesItself() {
        val j = setpoint.json()
        assertEquals("number", j.getString("type"))
        assertEquals(0.5, j.getDouble("step"), 0.0)
        assertEquals(28.0, j.getDouble("max"), 0.0)
        assertEquals("auto", mode.json().getJSONArray("options").getJSONObject(1).getString("value"))
    }
}
