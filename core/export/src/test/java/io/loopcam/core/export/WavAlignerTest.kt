package io.loopcam.core.export

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavAlignerTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** WAV mono 16-bit con los samples 1, 2, 3... (igual formato que escribe el motor). */
    private fun wav(samples: ShortArray, sampleRate: Int = 1000, dataSizeInHeader: Int? = null): File {
        val file = temp.newFile()
        val b = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
            .putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataSizeInHeader ?: (samples.size * 2))
        samples.forEach { b.putShort(it) }
        file.writeBytes(b.array())
        return file
    }

    private fun samplesOf(file: File): ShortArray {
        val format = WavAligner.readFormat(file)
        val b = ByteBuffer.wrap(file.readBytes(), format.dataOffset.toInt(), format.dataBytes.toInt())
            .order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((format.dataBytes / 2).toInt()) { b.short }
    }

    private val input = shortArrayOf(1, 2, 3, 4, 5)

    @Test
    fun readsFormat() {
        val format = WavAligner.readFormat(wav(input))
        assertEquals(1000, format.sampleRate)
        assertEquals(1, format.channels)
        assertEquals(16, format.bitsPerSample)
        assertEquals(44L, format.dataOffset)
        assertEquals(5L, format.frames)
    }

    @Test
    fun positiveOffsetPrependsSilence() {
        val out = temp.newFile()
        WavAligner.align(wav(input), out, offsetFrames = 2, targetFrames = 7)
        assertArrayEquals(shortArrayOf(0, 0, 1, 2, 3, 4, 5), samplesOf(out))
    }

    @Test
    fun negativeOffsetSkipsTheBeginning() {
        val out = temp.newFile()
        WavAligner.align(wav(input), out, offsetFrames = -2, targetFrames = 3)
        assertArrayEquals(shortArrayOf(3, 4, 5), samplesOf(out))
    }

    @Test
    fun padsOrTrimsToTargetDuration() {
        val padded = temp.newFile()
        WavAligner.align(wav(input), padded, offsetFrames = 0, targetFrames = 7)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 0, 0), samplesOf(padded))

        val trimmed = temp.newFile()
        WavAligner.align(wav(input), trimmed, offsetFrames = 1, targetFrames = 3)
        assertArrayEquals(shortArrayOf(0, 1, 2), samplesOf(trimmed))
    }

    @Test
    fun offsetLongerThanTargetIsAllSilence() {
        val out = temp.newFile()
        WavAligner.align(wav(input), out, offsetFrames = 10, targetFrames = 4)
        assertArrayEquals(shortArrayOf(0, 0, 0, 0), samplesOf(out))
    }

    @Test
    fun unfinishedHeaderUsesActualFileSize() {
        // Si la sesión se cortó antes de cerrar el WAV, el header dice 0 bytes de datos.
        val format = WavAligner.readFormat(wav(input, dataSizeInHeader = 0))
        assertEquals(5L, format.frames)
    }

    @Test
    fun convertsNanosToFrames() {
        assertEquals(480L, WavAligner.nanosToFrames(10_000_000, 48_000))
        assertEquals(-24L, WavAligner.nanosToFrames(-500_000, 48_000))
    }
}
