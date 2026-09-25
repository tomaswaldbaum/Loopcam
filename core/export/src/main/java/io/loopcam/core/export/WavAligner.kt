package io.loopcam.core.export

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Formato de un WAV PCM. */
data class WavFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val bytesPerFrame: Int get() = channels * bitsPerSample / 8
    val frames: Long get() = dataBytes / bytesPerFrame
}

/**
 * Deja la mezcla de audio alineada con el video: ambos empiezan en t = 0 y
 * duran lo mismo. Así el export solo tiene que juntar las dos pistas.
 */
object WavAligner {

    fun readFormat(file: File): WavFormat = RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(12)
        raf.readFully(header)
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw IOException("${file.name} no es un WAV")
        }
        var sampleRate = 0
        var channels = 0
        var bits = 0
        val chunkHeader = ByteArray(8)
        while (raf.filePointer + 8 <= raf.length()) {
            raf.readFully(chunkHeader)
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(16)
                    raf.readFully(fmt)
                    val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = b.short.toInt()
                    if (audioFormat != 1) throw IOException("Solo se soporta WAV PCM (formato $audioFormat)")
                    channels = b.short.toInt()
                    sampleRate = b.int
                    b.int // byte rate
                    b.short // block align
                    bits = b.short.toInt()
                    raf.seek(raf.filePointer + size - 16 + (size and 1))
                }
                "data" -> {
                    if (sampleRate == 0) throw IOException("Chunk data antes que fmt")
                    // Si el header no se completó (sesión interrumpida), usar el tamaño real.
                    val available = raf.length() - raf.filePointer
                    val dataBytes = if (size == 0L || size > available) available else size
                    return WavFormat(sampleRate, channels, bits, raf.filePointer, dataBytes)
                }
                else -> raf.seek(raf.filePointer + size + (size and 1))
            }
        }
        throw IOException("${file.name} no tiene chunk data")
    }

    /** Frames equivalentes a [nanos] (redondeado). */
    fun nanosToFrames(nanos: Long, sampleRate: Int): Long =
        Math.round(nanos.toDouble() * sampleRate / 1_000_000_000.0)

    /**
     * @param offsetFrames cuántos frames después del inicio del video empieza el audio.
     *   Positivo: se agregan silencios al principio. Negativo: se descarta el principio.
     * @param targetFrames duración final (se recorta o se completa con silencio).
     */
    fun align(input: File, output: File, offsetFrames: Long, targetFrames: Long): WavFormat {
        val format = readFormat(input)
        val frameSize = format.bytesPerFrame
        val skipFrames = maxOf(0L, -offsetFrames)
        val leadingSilence = minOf(maxOf(0L, offsetFrames), targetFrames)
        val copyFrames = minOf(maxOf(0L, format.frames - skipFrames), targetFrames - leadingSilence)
        val trailingSilence = targetFrames - leadingSilence - copyFrames

        BufferedOutputStream(FileOutputStream(output)).use { out ->
            writeHeader(out, format, targetFrames * frameSize)
            writeSilence(out, leadingSilence * frameSize)
            BufferedInputStream(FileInputStream(input)).use { inp ->
                skipFully(inp, format.dataOffset + skipFrames * frameSize)
                copy(inp, out, copyFrames * frameSize)
            }
            writeSilence(out, trailingSilence * frameSize)
        }
        return format.copy(dataOffset = 44, dataBytes = targetFrames * frameSize)
    }

    private fun writeHeader(out: OutputStream, format: WavFormat, dataBytes: Long) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1)
        b.putShort(format.channels.toShort())
        b.putInt(format.sampleRate)
        b.putInt(format.sampleRate * format.bytesPerFrame)
        b.putShort(format.bytesPerFrame.toShort())
        b.putShort(format.bitsPerSample.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes.toInt())
        out.write(b.array())
    }

    private fun writeSilence(out: OutputStream, bytes: Long) {
        val zeros = ByteArray(8192)
        var remaining = bytes
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size.toLong()).toInt()
            out.write(zeros, 0, n)
            remaining -= n
        }
    }

    private fun skipFully(inp: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = inp.skip(remaining)
            if (skipped <= 0) {
                if (inp.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun copy(inp: BufferedInputStream, out: OutputStream, bytes: Long) {
        val buffer = ByteArray(8192)
        var remaining = bytes
        while (remaining > 0) {
            val n = inp.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
    }
}
