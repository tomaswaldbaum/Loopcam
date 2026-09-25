package io.loopcam.core.video

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorClockTest {

    private val monotonicNow = 5_000_000_000_000L
    private val sleptNanos = 3_600_000_000_000L // el equipo estuvo dormido 1 h
    private val boottimeNow = monotonicNow + sleptNanos
    private val frameAge = 40_000_000L

    @Test
    fun monotonicSensorTimestampIsKept() {
        val sensor = monotonicNow - frameAge
        assertEquals(sensor, SensorClock.toMonotonic(sensor, monotonicNow, boottimeNow))
    }

    @Test
    fun boottimeSensorTimestampIsShiftedToMonotonic() {
        val sensor = boottimeNow - frameAge
        assertEquals(monotonicNow - frameAge, SensorClock.toMonotonic(sensor, monotonicNow, boottimeNow))
    }

    @Test
    fun whenBothClocksAgreeTimestampIsKept() {
        val sensor = monotonicNow - frameAge
        assertEquals(sensor, SensorClock.toMonotonic(sensor, monotonicNow, monotonicNow + 1_000))
    }
}
