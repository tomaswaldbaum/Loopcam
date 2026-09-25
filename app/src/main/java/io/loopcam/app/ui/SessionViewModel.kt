package io.loopcam.app.ui

import android.app.Application
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.loopcam.app.service.RecordingService
import io.loopcam.app.session.SessionController
import io.loopcam.app.session.SessionState
import io.loopcam.core.audio.AudioStatus
import io.loopcam.core.audio.LoopConfig
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SessionViewModel @Inject constructor(
    application: Application,
    private val sessionController: SessionController,
) : AndroidViewModel(application) {

    val sessionState: StateFlow<SessionState> = sessionController.state
    val audioStatus: StateFlow<AudioStatus?> = sessionController.audioStatus
    val overdub: StateFlow<Boolean> = sessionController.overdub
    val click: StateFlow<Boolean> = sessionController.click

    private val _loopSeconds = MutableStateFlow(RecordingService.DEFAULT_LOOP_SECONDS)
    val loopSeconds: StateFlow<Double> = _loopSeconds.asStateFlow()

    fun setLoopSeconds(seconds: Double) {
        _loopSeconds.value = seconds.coerceIn(LoopConfig.MIN_LOOP_SECONDS, LoopConfig.MAX_LOOP_SECONDS)
    }

    fun setOverdub(enabled: Boolean) = sessionController.setOverdub(enabled)

    fun setClickEnabled(enabled: Boolean) = sessionController.setClickEnabled(enabled)

    fun undoLastLayer() = sessionController.undoLastLayer()

    // TODO(video): para grabar con la pantalla apagada, ligar la cámara al ciclo de vida del servicio.
    fun bindCamera(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        viewModelScope.launch {
            sessionController.videoRecorder.bind(lifecycleOwner, surfaceProvider)
        }
    }

    fun toggleRecording() {
        val context = getApplication<Application>()
        when (sessionState.value) {
            is SessionState.Recording -> RecordingService.stop(context)
            SessionState.Starting -> Unit
            else -> RecordingService.start(context, _loopSeconds.value)
        }
    }
}
