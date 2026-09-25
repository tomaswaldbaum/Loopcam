package io.loopcam.app.session

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import io.loopcam.core.audio.AudioEngine
import io.loopcam.core.audio.AudioStatus
import io.loopcam.core.audio.LoopConfig
import io.loopcam.core.video.CameraVideoRecorder
import io.loopcam.core.video.VideoState
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Lo que queda en disco al terminar una sesión; es la entrada del export. */
data class SessionResult(
    val directory: File,
    val video: File,
    val audioMix: File,
    /** Cuánto después del inicio del video empieza el WAV (negativo: antes). Null si no se pudo medir. */
    val audioOffsetNanos: Long?,
    val layers: Int,
    val interruptedReason: String? = null,
)

sealed interface SessionState {
    data object Idle : SessionState
    data object Starting : SessionState
    data class Recording(val config: LoopConfig, val directory: File) : SessionState
    data class Finished(val result: SessionResult) : SessionState
    data class Error(val message: String) : SessionState
}

/** Orquesta el arranque y la parada conjunta de video y audio. */
@Singleton
class SessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioEngine: AudioEngine,
    val videoRecorder: CameraVideoRecorder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var pollJob: Job? = null

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _audioStatus = MutableStateFlow<AudioStatus?>(null)
    val audioStatus: StateFlow<AudioStatus?> = _audioStatus.asStateFlow()

    private val _overdub = MutableStateFlow(true)
    val overdub: StateFlow<Boolean> = _overdub.asStateFlow()

    private val _click = MutableStateFlow(true)
    val click: StateFlow<Boolean> = _click.asStateFlow()

    suspend fun start(config: LoopConfig) = mutex.withLock {
        val current = _state.value
        if (current is SessionState.Recording || current is SessionState.Starting) return@withLock
        _state.value = SessionState.Starting

        val directory = newSessionDirectory()
        audioEngine.setOverdub(_overdub.value)
        audioEngine.setClickEnabled(_click.value)
        // El audio arranca primero (mide latencia) y el video después; el offset lo compensa.
        val audioStarted = withContext(Dispatchers.Default) {
            audioEngine.start(config, File(directory, AUDIO_FILE))
        }
        if (!audioStarted) {
            _state.value = SessionState.Error("No se pudo abrir el audio. ¿Otra app está usando el micrófono?")
            return@withLock
        }
        videoRecorder.start(File(directory, VIDEO_FILE))
        _state.value = SessionState.Recording(config, directory)
        startPolling()
    }

    suspend fun stop(interruptedReason: String? = null) = mutex.withLock {
        val recording = _state.value as? SessionState.Recording ?: return@withLock
        pollJob?.cancel()
        val audio = audioEngine.status()
        val videoStartNanos = (videoRecorder.state.value as? VideoState.Recording)?.startNanos

        videoRecorder.stop()
        withContext(Dispatchers.Default) { audioEngine.stop() }
        _audioStatus.value = null

        val audioOffset = if (videoStartNanos != null && audio != null && audio.fileStartNanos > 0) {
            audio.fileStartNanos - videoStartNanos
        } else {
            null
        }
        val result = SessionResult(
            directory = recording.directory,
            video = File(recording.directory, VIDEO_FILE),
            audioMix = File(recording.directory, AUDIO_FILE),
            audioOffsetNanos = audioOffset,
            layers = audio?.committedLayers ?: 0,
            interruptedReason = interruptedReason,
        )
        withContext(Dispatchers.IO) { writeMetadata(result, recording.config, audio) }
        _state.value = SessionState.Finished(result)
    }

    /** Para usar desde callbacks sin corrutina (p. ej. onDestroy del servicio). */
    fun stopAsync(interruptedReason: String? = null) {
        scope.launch { stop(interruptedReason) }
    }

    fun setOverdub(enabled: Boolean) {
        _overdub.value = enabled
        audioEngine.setOverdub(enabled)
    }

    fun setClickEnabled(enabled: Boolean) {
        _click.value = enabled
        audioEngine.setClickEnabled(enabled)
    }

    fun undoLastLayer() = audioEngine.undoLastLayer()

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val status = audioEngine.status()
                _audioStatus.value = status
                if (status?.disconnected == true) {
                    stopAsync("Se desconectó el dispositivo de audio")
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun newSessionDirectory(): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(root, "sessions/${System.currentTimeMillis()}").apply { mkdirs() }
    }

    private fun writeMetadata(result: SessionResult, config: LoopConfig, audio: AudioStatus?) {
        val lines = buildList {
            add("loopSeconds=${config.loopSeconds}")
            add("layers=${result.layers}")
            add("audioOffsetNanos=${result.audioOffsetNanos ?: ""}")
            audio?.let {
                add("sampleRate=${it.sampleRate}")
                add("latencyFrames=${it.latencyFrames}")
                add("droppedFrames=${it.droppedFrames}")
            }
            result.interruptedReason?.let { add("interruptedReason=$it") }
        }
        File(result.directory, METADATA_FILE).writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val VIDEO_FILE = "video.mp4"
        const val AUDIO_FILE = "mix.wav"
        const val METADATA_FILE = "session.properties"
        const val POLL_INTERVAL_MS = 33L
    }
}
