package io.loopcam.core.audio

import kotlin.math.floor

enum class LengthMode { SECONDS, TEMPO }

/**
 * Configuración de una sesión, elegida antes de grabar.
 *
 * El loop siempre se divide en `beatsPerBar × bars` tiempos:
 * - En modo [LengthMode.TEMPO] la duración sale del BPM.
 * - En modo [LengthMode.SECONDS] el BPM sale de la duración.
 */
data class SessionConfig(
    val lengthMode: LengthMode = LengthMode.TEMPO,
    val seconds: Double = 4.0,
    val bpm: Double = 100.0,
    val beatsPerBar: Int = 4,
    val bars: Int = 2,
    val countInBars: Int = 1,
    val metronomeInCountIn: Boolean = true,
    val metronomeWhileRecording: Boolean = false,
    val maxLayers: Int = 8,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) {
    val beatsPerLoop: Int get() = beatsPerBar * bars

    val loopSeconds: Double
        get() = when (lengthMode) {
            LengthMode.SECONDS -> seconds
            LengthMode.TEMPO -> 60.0 / bpm * beatsPerLoop
        }

    val effectiveBpm: Double get() = 60.0 * beatsPerLoop / loopSeconds

    val countInBeats: Int get() = countInBars * beatsPerBar

    /** Tope de capas que entra en memoria para este largo de loop. */
    val memoryMaxLayers: Int get() = maxLayersFor(loopSeconds, sampleRate)

    /** Capas que se van a poder grabar realmente. */
    val effectiveMaxLayers: Int get() = maxLayers.coerceIn(1, memoryMaxLayers)

    val isLoopLengthValid: Boolean get() = loopSeconds in MIN_LOOP_SECONDS..MAX_LOOP_SECONDS

    /** Lleva cada valor a su rango permitido. */
    fun coerced(): SessionConfig = copy(
        seconds = seconds.coerceIn(MIN_LOOP_SECONDS, MAX_LOOP_SECONDS),
        bpm = bpm.coerceIn(MIN_BPM, MAX_BPM),
        beatsPerBar = beatsPerBar.coerceIn(MIN_BEATS_PER_BAR, MAX_BEATS_PER_BAR),
        bars = bars.coerceIn(1, MAX_BARS),
        countInBars = countInBars.coerceIn(0, MAX_COUNT_IN_BARS),
        maxLayers = maxLayers.coerceIn(1, MAX_LAYERS),
    )

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val MIN_LOOP_SECONDS = 0.5
        const val MAX_LOOP_SECONDS = 60.0
        const val MIN_BPM = 30.0
        const val MAX_BPM = 300.0
        const val MIN_BEATS_PER_BAR = 2
        const val MAX_BEATS_PER_BAR = 7
        const val MAX_BARS = 16
        const val MAX_COUNT_IN_BARS = 2
        const val MAX_LAYERS = 24

        /** Debe coincidir con kLayerBudgetSamples de LoopEngine.cpp (96 MB en float). */
        const val LAYER_BUDGET_SAMPLES = 24L * 1024 * 1024

        fun maxLayersFor(loopSeconds: Double, sampleRate: Int = DEFAULT_SAMPLE_RATE): Int {
            val loopFrames = maxOf(1.0, loopSeconds * sampleRate)
            return floor(LAYER_BUDGET_SAMPLES / loopFrames).toInt().coerceIn(1, MAX_LAYERS)
        }
    }
}
