package io.loopcam.app.ui

import android.app.Application
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.loopcam.app.audio.HeadphoneMonitor
import io.loopcam.app.service.RecordingService
import io.loopcam.app.session.SessionController
import io.loopcam.app.session.SessionState
import io.loopcam.app.settings.SessionSettings
import io.loopcam.app.settings.SessionSettingsRepository
import io.loopcam.core.audio.AudioStatus
import io.loopcam.core.audio.SessionConfig
import io.loopcam.core.video.CameraLens
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SessionViewModel @Inject constructor(
    application: Application,
    private val sessionController: SessionController,
    private val settingsRepository: SessionSettingsRepository,
    headphoneMonitor: HeadphoneMonitor,
) : AndroidViewModel(application) {

    val sessionState: StateFlow<SessionState> = sessionController.state
    val audioStatus: StateFlow<AudioStatus?> = sessionController.audioStatus
    val overdub: StateFlow<Boolean> = sessionController.overdub
    val metronomeWhileRecording: StateFlow<Boolean> = sessionController.metronomeWhileRecording
    val headphonesConnected: StateFlow<Boolean> = headphoneMonitor.connected

    val settings: StateFlow<SessionSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionSettings())

    private val _availableLenses = MutableStateFlow(listOf(CameraLens.BACK))
    val availableLenses: StateFlow<List<CameraLens>> = _availableLenses.asStateFlow()

    init {
        viewModelScope.launch {
            _availableLenses.value = runCatching { sessionController.videoRecorder.availableLenses() }
                .getOrDefault(listOf(CameraLens.BACK))
                .ifEmpty { listOf(CameraLens.BACK) }
        }
    }

    fun updateConfig(transform: (SessionConfig) -> SessionConfig) {
        if (sessionController.isBusy) return
        viewModelScope.launch { settingsRepository.update { it.copy(config = transform(it.config)) } }
    }

    fun setLens(lens: CameraLens) {
        if (sessionController.isBusy) return
        viewModelScope.launch { settingsRepository.update { it.copy(lens = lens) } }
    }

    fun setOverdub(enabled: Boolean) = sessionController.setOverdub(enabled)

    /** Se puede cambiar en vivo; también queda guardado para la próxima sesión. */
    fun setMetronomeWhileRecording(enabled: Boolean) {
        sessionController.setMetronomeWhileRecording(enabled)
        viewModelScope.launch {
            settingsRepository.update { it.copy(config = it.config.copy(metronomeWhileRecording = enabled)) }
        }
    }

    fun undoLastLayer() = sessionController.undoLastLayer()

    // TODO(video): para grabar con la pantalla apagada, ligar la cámara al ciclo de vida del servicio.
    fun bindCamera(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider, lens: CameraLens) {
        if (sessionController.isBusy) return
        viewModelScope.launch {
            sessionController.videoRecorder.bind(lifecycleOwner, surfaceProvider, lens)
        }
    }

    fun toggleRecording() {
        val context = getApplication<Application>()
        when (sessionState.value) {
            is SessionState.Recording, is SessionState.CountingIn -> RecordingService.stop(context)
            SessionState.Starting, is SessionState.Exporting -> Unit
            else -> if (settings.value.config.isLoopLengthValid) RecordingService.start(context)
        }
    }
}
