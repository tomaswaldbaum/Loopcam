package io.loopcam.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LoopConfigTest {

    @Test
    fun loopFrames_isSecondsTimesSampleRate() {
        assertEquals(96_000, LoopConfig(loopSeconds = 2.0).loopFrames)
        assertEquals(22_050, LoopConfig(loopSeconds = 0.5, sampleRate = 44_100).loopFrames)
    }

    @Test
    fun fromTempo_twoBarsAt120Bpm_isFourSeconds() {
        val config = LoopConfig.fromTempo(bpm = 120.0, bars = 2)
        assertEquals(4.0, config.loopSeconds, 1e-9)
        assertEquals(192_000, config.loopFrames)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLoopsShorterThanMinimum() {
        LoopConfig(loopSeconds = 0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLoopsLongerThanMaximum() {
        LoopConfig(loopSeconds = 120.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveBpm() {
        LoopConfig.fromTempo(bpm = 0.0, bars = 1)
    }
}
