package io.loopcam.app.session

import android.content.Context
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import io.loopcam.core.audio.AudioEngine
import io.loopcam.core.audio.AudioStatus
import io.loopcam.core.audio.LoopConfig
import io.loopcam.core.export.ExportRequest
import io.loopcam.core.export.GallerySaver
import io.loopcam.core.export.SessionExporter
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Lo que queda al terminar una sesión. */
data class SessionResult(
    val directory: File,
    val video: File,
    val audioMix: File,
    /** Cuánto después del inicio del video empieza el WAV (negativo: antes). Null si no se pudo medir. */
    val audioOffsetNanos: Long?,
    /** true si el inicio del video viene del timestamp de sensor del primer frame. */
    val videoStartFrameAccurate: Boolean,
    val layers: Int,
    val interruptedReason: String? = null,
    /** MP4 final en la galería; null si el export falló o no se hizo. */
    val galleryUri: Uri? = null,
    val exportError: String? = null,
)

sealed interface SessionState {
    data object Idle : SessionState
    data object Starting : SessionState
    data class Recording(val config: LoopConfig, val directory: File) : SessionState
    data class Exporting(val progress: Float) : SessionState
    data class Finished(val result: SessionResult) : SessionState
    data class Error(val message: String) : SessionState
}

/** Orquesta el arranque y la parada conjunta de video y audio. */
@Singleton
class SessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioEngine: AudioEngine,
    val videoRecorder: CameraVideoRecorder,
    private val exporter: SessionExporter,
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
        if (current is SessionState.Recording || current is SessionState.Starting || current is SessionState.Exporting) {
            return@withLock
        }
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

        videoRecorder.stop()
        withContext(Dispatchers.Default) { audioEngine.stop() }
        _audioStatus.value = null
        // El MP4 recién es válido cuando CameraX lo finaliza.
        val video = withTimeoutOrNull(VIDEO_FINALIZE_TIMEOUT_MS) {
            videoRecorder.state.first { it is VideoState.Finished || it is VideoState.Error }
        } as? VideoState.Finished

        val audioOffset = if (video != null && audio != null && audio.fileStartNanos > 0) {
            audio.fileStartNanos - video.startNanos
        } else {
            null
        }
        var result = SessionResult(
            directory = recording.directory,
            video = File(recording.directory, VIDEO_FILE),
            audioMix = File(recording.directory, AUDIO_FILE),
            audioOffsetNanos = audioOffset,
            videoStartFrameAccurate = video?.frameAccurate ?: false,
            layers = audio?.committedLayers ?: 0,
            interruptedReason = interruptedReason,
        )
        withContext(Dispatchers.IO) { writeMetadata(result, recording.config, audio) }

        result = if (video == null) {
            result.copy(exportError = "El video no se finalizó correctamente")
        } else {
            export(result, video.durationNanos)
        }
        _state.value = SessionState.Finished(result)
    }

    private suspend fun export(result: SessionResult, videoDurationNanos: Long): SessionResult {
        _state.value = SessionState.Exporting(0f)
        val output = File(result.directory, EXPORT_FILE)
        val request = ExportRequest(
            video = result.video,
            audioMix = result.audioMix,
            audioOffsetNanos = result.audioOffsetNanos ?: 0L,
            videoDurationNanos = videoDurationNanos,
            output = output,
        )
        return exporter.export(request) { progress -> _state.value = SessionState.Exporting(progress) }
            .mapCatching { file -> GallerySaver.saveVideo(context, file, "LoopCam_${result.directory.name}.mp4") }
            .fold(
                onSuccess = { uri -> result.copy(galleryUri = uri) },
                onFailure = { e -> result.copy(exportError = e.message ?: e.javaClass.simpleName) },
            )
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
            add("videoStartFrameAccurate=${result.videoStartFrameAccurate}")
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
        const val EXPORT_FILE = "loopcam.mp4"
        const val VIDEO_FINALIZE_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 33L
    }
}
