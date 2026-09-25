package io.loopcam.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Puente JNI hacia el LoopEngine en C++ (Oboe). */
class NativeAudioEngine : AudioEngine {

    private val _state = MutableStateFlow<AudioEngineState>(AudioEngineState.Idle)
    override val state: StateFlow<AudioEngineState> = _state.asStateFlow()

    private var handle: Long = nativeCreate()

    override fun start(config: LoopConfig): Boolean {
        check(handle != 0L) { "AudioEngine ya fue liberado" }
        if (_state.value is AudioEngineState.Running) return true
        val started = nativeStart(handle, config.sampleRate, config.loopFrames)
        // TODO(audio): reemplazar por el timestamp de AAudio del frame 0.
        _state.value = if (started) {
            AudioEngineState.Running(config, System.nanoTime())
        } else {
            AudioEngineState.Error("No se pudo abrir el stream de audio")
        }
        return started
    }

    override fun stop() {
        if (handle == 0L) return
        nativeStop(handle)
        _state.value = AudioEngineState.Idle
    }

    override fun release() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
        _state.value = AudioEngineState.Idle
    }

    private external fun nativeCreate(): Long
    private external fun nativeStart(handle: Long, sampleRate: Int, loopFrames: Int): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)

    private companion object {
        init {
            System.loadLibrary("loopcam_audio")
        }
    }
}
