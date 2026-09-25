package io.loopcam.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.loopcam.app.R
import io.loopcam.app.session.SessionState
import io.loopcam.core.audio.AudioStatus
import io.loopcam.core.video.CameraLens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(viewModel: SessionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val audioStatus by viewModel.audioStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val availableLenses by viewModel.availableLenses.collectAsStateWithLifecycle()
    val overdub by viewModel.overdub.collectAsStateWithLifecycle()
    val metronomeWhileRecording by viewModel.metronomeWhileRecording.collectAsStateWithLifecycle()
    val headphonesConnected by viewModel.headphonesConnected.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val isCountingIn = sessionState is SessionState.CountingIn
    val isRecording = sessionState is SessionState.Recording
    val isBusy = isCountingIn || isRecording ||
        sessionState is SessionState.Starting || sessionState is SessionState.Exporting
    val config = settings.config

    val previewView = remember { PreviewView(context) }
    LaunchedEffect(previewView, lifecycleOwner, settings.lens) {
        viewModel.bindCamera(lifecycleOwner, previewView.surfaceProvider, settings.lens)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        audioStatus?.let { RecordingOverlay(it) }

        if (!isBusy && availableLenses.size > 1) {
            OutlinedButton(
                onClick = {
                    viewModel.setLens(if (settings.lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.4f)),
            ) {
                Text("⟲ " + stringResource(settings.lens.labelRes()), color = Color.White)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SessionMessage(sessionState)

            val metronomeAudible = if (isBusy) metronomeWhileRecording else config.metronomeWhileRecording
            if (!headphonesConnected && metronomeAudible) {
                Text(
                    stringResource(R.string.speaker_metronome_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (!isBusy) {
                Text(stringResource(R.string.headphones_hint), color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        R.string.settings_summary,
                        config.beatsPerLoop,
                        config.effectiveBpm,
                        config.loopSeconds,
                        config.countInBars,
                        config.effectiveMaxLayers,
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { showSettings = true }) { Text(stringResource(R.string.settings)) }
            }

            if (isRecording) {
                audioStatus?.let { LatencyAndDrops(it) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LabeledSwitch(stringResource(R.string.overdub), overdub, viewModel::setOverdub, color = Color.White)
                    LabeledSwitch(
                        stringResource(R.string.metronome),
                        metronomeWhileRecording,
                        viewModel::setMetronomeWhileRecording,
                        color = Color.White,
                    )
                }
                OutlinedButton(onClick = viewModel::undoLastLayer) { Text(stringResource(R.string.undo)) }
            }

            Button(
                onClick = viewModel::toggleRecording,
                enabled = sessionState !is SessionState.Starting && sessionState !is SessionState.Exporting &&
                    (isBusy || config.isLoopLengthValid),
            ) {
                Text(
                    stringResource(
                        when {
                            isCountingIn -> R.string.cancel
                            isRecording -> R.string.stop
                            else -> R.string.record
                        },
                    ),
                )
            }
        }
    }

    if (showSettings && !isBusy) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SettingsPanel(
                settings = settings,
                availableLenses = availableLenses,
                onConfigChange = viewModel::updateConfig,
                onLensChange = viewModel::setLens,
            )
        }
    }
}

@Composable
private fun LatencyAndDrops(status: AudioStatus) {
    Text(stringResource(R.string.latency, status.latencyMillis), color = Color.White, style = MaterialTheme.typography.bodySmall)
    if (status.droppedFrames > 0) {
        Text(
            stringResource(R.string.dropped_frames, status.droppedFrames),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SessionMessage(state: SessionState) {
    val context = LocalContext.current
    when (state) {
        SessionState.Starting -> Text(stringResource(R.string.starting), color = Color.White)
        is SessionState.Exporting -> {
            Text(stringResource(R.string.exporting, (state.progress * 100).toInt()), color = Color.White)
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        is SessionState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
        is SessionState.Finished -> {
            val result = state.result
            result.interruptedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                stringResource(R.string.session_saved, result.layers, result.directory.name),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                result.audioOffsetNanos == null -> SmallNote(stringResource(R.string.session_offset_unknown))
                !result.videoStartFrameAccurate -> SmallNote(stringResource(R.string.session_offset_approximate))
            }
            result.exportError?.let {
                Text(stringResource(R.string.export_failed, it), color = MaterialTheme.colorScheme.error)
            }
            result.galleryUri?.let { uri ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.exported), color = Color.White)
                    OutlinedButton(onClick = { context.openVideo(uri) }) { Text(stringResource(R.string.open_video)) }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun SmallNote(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall)
}

private fun Context.openVideo(uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(intent) }
}
