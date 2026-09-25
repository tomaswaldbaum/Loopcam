package io.loopcam.core.audio

import kotlinx.coroutines.flow.StateFlow

sealed interface AudioEngineState {
    data object Idle : AudioEngineState

    /** [startNanos] en el reloj de System.nanoTime() (CLOCK_MONOTONIC). */
    data class Running(val config: LoopConfig, val startNanos: Long) : AudioEngineState

    data class Error(val message: String) : AudioEngineState
}

interface AudioEngine {
    val state: StateFlow<AudioEngineState>

    fun start(config: LoopConfig): Boolean

    fun stop()

    fun release()
}
