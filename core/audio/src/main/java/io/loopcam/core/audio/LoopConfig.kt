package io.loopcam.core.audio

import kotlin.math.roundToInt

/** Longitud fija del loop para toda la sesión. */
data class LoopConfig(
    val loopSeconds: Double,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) {
    init {
        require(loopSeconds in MIN_LOOP_SECONDS..MAX_LOOP_SECONDS) {
            "loopSeconds debe estar entre $MIN_LOOP_SECONDS y $MAX_LOOP_SECONDS (fue $loopSeconds)"
        }
        require(sampleRate > 0) { "sampleRate debe ser positivo" }
    }

    val loopFrames: Int get() = (loopSeconds * sampleRate).roundToInt()

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val MIN_LOOP_SECONDS = 0.5
        const val MAX_LOOP_SECONDS = 60.0

        fun fromTempo(
            bpm: Double,
            bars: Int,
            beatsPerBar: Int = 4,
            sampleRate: Int = DEFAULT_SAMPLE_RATE,
        ): LoopConfig {
            require(bpm > 0) { "bpm debe ser positivo" }
            require(bars > 0 && beatsPerBar > 0) { "bars y beatsPerBar deben ser positivos" }
            return LoopConfig(60.0 / bpm * beatsPerBar * bars, sampleRate)
        }
    }
}
