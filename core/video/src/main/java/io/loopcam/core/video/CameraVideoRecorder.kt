package io.loopcam.core.video

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface VideoState {
    data object Idle : VideoState

    /**
     * @param startNanos inicio del video en el reloj de System.nanoTime() (CLOCK_MONOTONIC).
     * @param frameAccurate true si viene del timestamp de sensor del primer frame; false si es
     *   la hora aproximada del evento Start de CameraX.
     */
    data class Recording(val output: File, val startNanos: Long, val frameAccurate: Boolean) : VideoState

    data class Finished(
        val output: File,
        val startNanos: Long,
        val frameAccurate: Boolean,
        val durationNanos: Long,
    ) : VideoState

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
    private val awaitingFirstFrame = AtomicBoolean(false)
    private val videoCapture = buildVideoCapture()
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
            is VideoRecordEvent.Start -> {
                // Aproximación hasta que llegue el timestamp de sensor del primer frame.
                _state.value = VideoState.Recording(output, System.nanoTime(), frameAccurate = false)
                awaitingFirstFrame.set(true)
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                awaitingFirstFrame.set(false)
                val started = _state.value as? VideoState.Recording
                _state.value = if (event.hasError() || started == null) {
                    VideoState.Error(event.error, event.cause?.message)
                } else {
                    VideoState.Finished(
                        output = output,
                        startNanos = started.startNanos,
                        frameAccurate = started.frameAccurate,
                        durationNanos = event.recordingStats.recordedDurationNanos,
                    )
                }
            }
            else -> Unit
        }
    }

    /** Hilo de la cámara: se llama por cada frame capturado. */
    private fun onFrameCaptured(sensorTimestampNanos: Long) {
        if (!awaitingFirstFrame.compareAndSet(true, false)) return
        val startNanos = SensorClock.toMonotonic(
            sensorNanos = sensorTimestampNanos,
            nowMonotonicNanos = System.nanoTime(),
            nowBoottimeNanos = SystemClock.elapsedRealtimeNanos(),
        )
        val current = _state.value as? VideoState.Recording ?: return
        _state.compareAndSet(current, current.copy(startNanos = startNanos, frameAccurate = true))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildVideoCapture(): VideoCapture<Recorder> {
        val builder = VideoCapture.Builder(recorder)
        Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let(::onFrameCaptured)
                }
            },
        )
        return builder.build()
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
