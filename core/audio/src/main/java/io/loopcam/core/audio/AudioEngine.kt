package io.loopcam.core.audio

import java.io.File
import kotlinx.coroutines.flow.StateFlow

sealed interface AudioEngineState {
    data object Idle : AudioEngineState

    data class Running(val config: LoopConfig, val output: File) : AudioEngineState

    data class Error(val message: String) : AudioEngineState
}

/** Foto del motor, leída periódicamente desde la UI. */
data class AudioStatus(
    val committedLayers: Int,
    val maxLayers: Int,
    val position: Int,
    val loopFrames: Int,
    val sampleRate: Int,
    val latencyFrames: Int,
    val recordingLayer: Boolean,
    val disconnected: Boolean,
    val droppedFrames: Long,
    /** Captura (CLOCK_MONOTONIC, mismo reloj que System.nanoTime()) del primer sample del WAV; 0 si no se conoce. */
    val fileStartNanos: Long,
) {
    val progress: Float get() = if (loopFrames > 0) position.toFloat() / loopFrames else 0f
    val latencyMillis: Double get() = if (sampleRate > 0) 1000.0 * latencyFrames / sampleRate else 0.0
}

/** Decodifica el LongArray de nativeGetStatus (orden del enum StatusField de jni_bridge.cpp). */
internal fun audioStatusOf(v: LongArray) = AudioStatus(
    committedLayers = v[0].toInt(),
    maxLayers = v[1].toInt(),
    position = v[2].toInt(),
    loopFrames = v[3].toInt(),
    sampleRate = v[4].toInt(),
    latencyFrames = v[5].toInt(),
    recordingLayer = v[6] != 0L,
    disconnected = v[7] != 0L,
    droppedFrames = v[8],
    fileStartNanos = v[9],
)

interface AudioEngine {
    val state: StateFlow<AudioEngineState>

    /**
     * Arranca el loop y la escritura de [output] (WAV PCM 16-bit mono). Bloquea unos
     * cientos de ms mientras mide la latencia: no llamar desde el hilo principal.
     *
     * @param latencyOffsetFrames corrección manual sumada a la latencia medida.
     */
    fun start(config: LoopConfig, output: File, latencyOffsetFrames: Int = 0): Boolean

    /** Detiene el audio y cierra el WAV (bloquea hasta vaciar el buffer). */
    fun stop()

    /** Si está activo, cada vuelta graba una capa nueva. Aplica desde la próxima vuelta. */
    fun setOverdub(enabled: Boolean)

    fun setClickEnabled(enabled: Boolean)

    /** Descarta la última capa y la que se está grabando. */
    fun undoLastLayer()

    fun status(): AudioStatus?

    fun release()
}
