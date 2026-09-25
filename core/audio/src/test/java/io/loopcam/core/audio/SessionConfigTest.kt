package io.loopcam.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionConfigTest {

    @Test
    fun tempoModeDerivesLoopSecondsFromBpm() {
        val config = SessionConfig(lengthMode = LengthMode.TEMPO, bpm = 120.0, beatsPerBar = 4, bars = 2)
        assertEquals(8, config.beatsPerLoop)
        assertEquals(4.0, config.loopSeconds, 1e-9)
        assertEquals(120.0, config.effectiveBpm, 1e-9)
    }

    @Test
    fun secondsModeDerivesBpmFromLength() {
        val config = SessionConfig(lengthMode = LengthMode.SECONDS, seconds = 6.0, beatsPerBar = 3, bars = 2)
        assertEquals(6, config.beatsPerLoop)
        assertEquals(6.0, config.loopSeconds, 1e-9)
        assertEquals(60.0, config.effectiveBpm, 1e-9)
    }

    @Test
    fun countInIsMeasuredInBars() {
        assertEquals(8, SessionConfig(beatsPerBar = 4, countInBars = 2).countInBeats)
        assertEquals(0, SessionConfig(countInBars = 0).countInBeats)
    }

    @Test
    fun maxLayersIsLimitedByMemoryForLongLoops() {
        assertEquals(24, SessionConfig.maxLayersFor(loopSeconds = 10.0))
        assertEquals(8, SessionConfig.maxLayersFor(loopSeconds = 60.0))
        val longLoop = SessionConfig(lengthMode = LengthMode.SECONDS, seconds = 60.0, maxLayers = 24)
        assertEquals(8, longLoop.effectiveMaxLayers)
        assertEquals(5, SessionConfig(maxLayers = 5).effectiveMaxLayers)
    }

    @Test
    fun coercedClampsEveryValue() {
        val config = SessionConfig(
            seconds = 500.0,
            bpm = 5.0,
            beatsPerBar = 12,
            bars = 0,
            countInBars = 9,
            maxLayers = 99,
        ).coerced()
        assertEquals(SessionConfig.MAX_LOOP_SECONDS, config.seconds, 0.0)
        assertEquals(SessionConfig.MIN_BPM, config.bpm, 0.0)
        assertEquals(SessionConfig.MAX_BEATS_PER_BAR, config.beatsPerBar)
        assertEquals(1, config.bars)
        assertEquals(SessionConfig.MAX_COUNT_IN_BARS, config.countInBars)
        assertEquals(SessionConfig.MAX_LAYERS, config.maxLayers)
    }

    @Test
    fun slowTempoWithManyBarsIsFlaggedAsTooLong() {
        val tooLong = SessionConfig(lengthMode = LengthMode.TEMPO, bpm = 30.0, beatsPerBar = 7, bars = 16)
        assertFalse(tooLong.isLoopLengthValid)
        assertTrue(SessionConfig().isLoopLengthValid)
    }
}
