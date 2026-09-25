package io.loopcam.app.session

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import io.loopcam.core.audio.AudioEngine
import io.loopcam.core.audio.AudioEngineState
import io.loopcam.core.audio.LoopConfig
import io.loopcam.core.video.CameraVideoRecorder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionState {
    data object Idle : SessionState
    data class Recording(val config: LoopConfig, val directory: File) : SessionState
    data class Error(val message: String) : SessionState
}

/** Orquesta el arranque y la parada conjunta de video y audio. */
@Singleton
class SessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioEngine: AudioEngine,
    val videoRecorder: CameraVideoRecorder,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun start(config: LoopConfig) {
        if (_state.value is SessionState.Recording) return
        val directory = newSessionDirectory()
        if (!audioEngine.start(config)) {
            val error = audioEngine.state.value as? AudioEngineState.Error
            _state.value = SessionState.Error(error?.message ?: "Error de audio")
            return
        }
        videoRecorder.start(File(directory, VIDEO_FILE))
        _state.value = SessionState.Recording(config, directory)
    }

    fun stop() {
        if (_state.value !is SessionState.Recording) return
        videoRecorder.stop()
        audioEngine.stop()
        // TODO(export): combinar video + mezcla con SessionExporter.
        _state.value = SessionState.Idle
    }

    private fun newSessionDirectory(): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(root, "sessions/${System.currentTimeMillis()}").apply { mkdirs() }
    }

    private companion object {
        const val VIDEO_FILE = "video.mp4"
    }
}
