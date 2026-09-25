package io.loopcam.core.video

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface VideoState {
    data object Idle : VideoState

    /** [startNanos] en el reloj de System.nanoTime() (CLOCK_MONOTONIC). */
    data class Recording(val output: File, val startNanos: Long) : VideoState

    data class Finished(val output: File, val durationNanos: Long) : VideoState

    data class Error(val code: Int, val message: String?) : VideoState
}

/**
 * Preview + grabación de video continuo con CameraX. El audio NO se graba acá:
 * lo produce el motor de loops y se muxea en el export.
 */
class CameraVideoRecorder(private val context: Context) {

    private val recorder = Recorder.Builder()
        .setQualitySelector(
            QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
        )
        .build()
    private val videoCapture = VideoCapture.withOutput(recorder)
    private var recording: Recording? = null

    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    val state: StateFlow<VideoState> = _state.asStateFlow()

    suspend fun bind(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        val provider = cameraProvider()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)
    }

    fun start(output: File) {
        if (recording != null) return
        val options = FileOutputOptions.Builder(output).build()
        recording = videoCapture.output
            .prepareRecording(context, options)
            .start(ContextCompat.getMainExecutor(context)) { event -> onEvent(event, output) }
    }

    fun stop() {
        recording?.stop()
    }

    private fun onEvent(event: VideoRecordEvent, output: File) {
        when (event) {
            // TODO(video): usar el timestamp del primer frame para una sincronía exacta.
            is VideoRecordEvent.Start -> _state.value = VideoState.Recording(output, System.nanoTime())
            is VideoRecordEvent.Finalize -> {
                recording = null
                _state.value = if (event.hasError()) {
                    VideoState.Error(event.error, event.cause?.message)
                } else {
                    VideoState.Finished(output, event.recordingStats.recordedDurationNanos)
                }
            }
            else -> Unit
        }
    }

    private suspend fun cameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { cont.resume(it) }
                        .onFailure { cont.resumeWithException(it) }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
}
