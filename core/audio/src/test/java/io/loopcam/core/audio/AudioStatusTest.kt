package io.loopcam.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStatusTest {

    private fun raw(phase: Long = 1, position: Long = 24_000, currentBeat: Long = 5) = longArrayOf(
        3, 16, position, 96_000, 48_000, 960, 1, 0, 7, 123_456_789,
        phase, 2, currentBeat, 4, 1, 8, 4, 4,
    )

    @Test
    fun decodesNativeStatusArrayInOrder() {
        val status = audioStatusOf(raw())
        assertEquals(3, status.committedLayers)
        assertEquals(16, status.maxLayers)
        assertEquals(24_000, status.position)
        assertEquals(96_000, status.loopFrames)
        assertEquals(48_000, status.sampleRate)
        assertEquals(960, status.latencyFrames)
        assertTrue(status.recordingLayer)
        assertFalse(status.disconnected)
        assertEquals(7L, status.droppedFrames)
        assertEquals(123_456_789L, status.fileStartNanos)
        assertEquals(AudioPhase.LOOPING, status.phase)
        assertEquals(2, status.countInBeatsRemaining)
        assertEquals(5, status.currentBeat)
        assertEquals(4L, status.cycle)
        assertTrue(status.layersFull)
        assertEquals(8, status.beatsPerLoop)
        assertEquals(4, status.beatsPerBar)
        assertEquals(4, status.countInBeats)
    }

    @Test
    fun derivesBeatInBarProgressAndLatency() {
        val status = audioStatusOf(raw())
        assertEquals(1, status.beatInBar)
        assertEquals(0.25f, status.progress, 1e-6f)
        assertEquals(20.0, status.latencyMillis, 1e-9)
    }

    @Test
    fun progressIsZeroDuringCountIn() {
        val status = audioStatusOf(raw(phase = 0))
        assertTrue(status.isCountingIn)
        assertEquals(0f, status.progress, 0f)
        // 4 tiempos de 12 000 frames = 48 000; en la posición 24 000 falta medio segundo.
        assertEquals(500_000_000L, status.countInRemainingNanos)
    }
}
