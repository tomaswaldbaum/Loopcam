package io.loopcam.core.audio

import java.io.File
import kotlinx.coroutines.flow.StateFlow

sealed interface AudioEngineState {
    data object Idle : AudioEngineState

    data class Running(val config: SessionConfig, val output: File) : AudioEngineState

    data class Error(val message: String) : AudioEngineState
}

enum class AudioPhase { COUNT_IN, LOOPING }

/** Foto del motor, leída periódicamente desde la UI. */
data class AudioStatus(
    val phase: AudioPhase,
    val countInBeatsRemaining: Int,
    /** Tiempo actual dentro del loop (o de la cuenta regresiva). */
    val currentBeat: Int,
    /** Vueltas completas del loop. */
    val cycle: Long,
    val committedLayers: Int,
    val maxLayers: Int,
    val layersFull: Boolean,
    val position: Int,
    val loopFrames: Int,
    val beatsPerLoop: Int,
    val beatsPerBar: Int,
    val countInBeats: Int,
    val sampleRate: Int,
    val latencyFrames: Int,
    val recordingLayer: Boolean,
    val disconnected: Boolean,
    val droppedFrames: Long,
    /** Captura (CLOCK_MONOTONIC, mismo reloj que System.nanoTime()) del primer sample del WAV; 0 si no se conoce. */
    val fileStartNanos: Long,
) {
    val isCountingIn: Boolean get() = phase == AudioPhase.COUNT_IN
    val beatInBar: Int get() = if (beatsPerBar > 0) currentBeat % beatsPerBar else 0
    val progress: Float get() = if (loopFrames > 0 && !isCountingIn) position.toFloat() / loopFrames else 0f
    val latencyMillis: Double get() = if (sampleRate > 0) 1000.0 * latencyFrames / sampleRate else 0.0

    /** Cuánto falta para que arranque el loop; 0 fuera de la cuenta regresiva. */
    val countInRemainingNanos: Long
        get() {
            if (!isCountingIn || sampleRate <= 0 || beatsPerLoop <= 0) return 0L
            val countInFrames = countInBeats.toLong() * (loopFrames / beatsPerLoop)
            return maxOf(0L, countInFrames - position) * 1_000_000_000L / sampleRate
        }
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
    phase = if (v[10] == 0L) AudioPhase.COUNT_IN else AudioPhase.LOOPING,
    countInBeatsRemaining = v[11].toInt(),
    currentBeat = v[12].toInt(),
    cycle = v[13],
    layersFull = v[14] != 0L,
    beatsPerLoop = v[15].toInt(),
    beatsPerBar = v[16].toInt(),
    countInBeats = v[17].toInt(),
)

interface AudioEngine {
    val state: StateFlow<AudioEngineState>

    /**
     * Arranca la cuenta regresiva (si hay) y después el loop, con la escritura de
     * [output] (WAV PCM 16-bit mono). Bloquea unos cientos de ms mientras mide la
     * latencia: no llamar desde el hilo principal.
     *
     * @param latencyOffsetFrames corrección manual sumada a la latencia medida.
     */
    fun start(config: SessionConfig, output: File, latencyOffsetFrames: Int = 0): Boolean

    /** Detiene el audio y cierra el WAV (bloquea hasta vaciar el buffer). */
    fun stop()

    /** Si está activo, cada vuelta graba una capa nueva. Aplica desde la próxima vuelta. */
    fun setOverdub(enabled: Boolean)

    /** El metrónomo solo suena por la salida (auriculares); nunca se graba. */
    fun setMetronome(inCountIn: Boolean, whileRecording: Boolean)

    /** Descarta la última capa y la que se está grabando. */
    fun undoLastLayer()

    fun status(): AudioStatus?

    fun release()
}
