package io.loopcam.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStatusTest {

    @Test
    fun decodesNativeStatusArrayInOrder() {
        val status = audioStatusOf(longArrayOf(3, 16, 24_000, 96_000, 48_000, 960, 1, 0, 7, 123_456_789))
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
    }

    @Test
    fun derivesProgressAndLatencyMillis() {
        val status = audioStatusOf(longArrayOf(0, 1, 24_000, 96_000, 48_000, 960, 0, 0, 0, 0))
        assertEquals(0.25f, status.progress, 1e-6f)
        assertEquals(20.0, status.latencyMillis, 1e-9)
    }
}
