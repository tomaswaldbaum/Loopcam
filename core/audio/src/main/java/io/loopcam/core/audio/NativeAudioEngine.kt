package io.loopcam.core.audio

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Puente JNI hacia el LoopEngine en C++ (Oboe full-duplex). */
class NativeAudioEngine : AudioEngine {

    private val _state = MutableStateFlow<AudioEngineState>(AudioEngineState.Idle)
    override val state: StateFlow<AudioEngineState> = _state.asStateFlow()

    private var handle: Long = nativeCreate()

    @Synchronized
    override fun start(config: LoopConfig, output: File, latencyOffsetFrames: Int): Boolean {
        check(handle != 0L) { "AudioEngine ya fue liberado" }
        if (_state.value is AudioEngineState.Running) return true
        output.parentFile?.mkdirs()
        val started = nativeStart(handle, config.sampleRate, config.loopSeconds, output.absolutePath, latencyOffsetFrames)
        _state.value = if (started) {
            AudioEngineState.Running(config, output)
        } else {
            AudioEngineState.Error("No se pudo abrir el audio. ¿Otra app está usando el micrófono?")
        }
        return started
    }

    @Synchronized
    override fun stop() {
        if (handle == 0L) return
        nativeStop(handle)
        _state.value = AudioEngineState.Idle
    }

    override fun setOverdub(enabled: Boolean) {
        if (handle != 0L) nativeSetOverdub(handle, enabled)
    }

    override fun setClickEnabled(enabled: Boolean) {
        if (handle != 0L) nativeSetClickEnabled(handle, enabled)
    }

    override fun undoLastLayer() {
        if (handle != 0L) nativeUndo(handle)
    }

    override fun status(): AudioStatus? {
        if (handle == 0L || _state.value !is AudioEngineState.Running) return null
        return audioStatusOf(nativeGetStatus(handle))
    }

    @Synchronized
    override fun release() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
        _state.value = AudioEngineState.Idle
    }

    private external fun nativeCreate(): Long
    private external fun nativeStart(
        handle: Long,
        sampleRate: Int,
        loopSeconds: Double,
        wavPath: String,
        latencyOffsetFrames: Int,
    ): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeSetOverdub(handle: Long, enabled: Boolean)
    private external fun nativeSetClickEnabled(handle: Long, enabled: Boolean)
    private external fun nativeUndo(handle: Long)
    private external fun nativeGetStatus(handle: Long): LongArray
    private external fun nativeDestroy(handle: Long)

    private companion object {
        init {
            System.loadLibrary("loopcam_audio")
        }
    }
}
