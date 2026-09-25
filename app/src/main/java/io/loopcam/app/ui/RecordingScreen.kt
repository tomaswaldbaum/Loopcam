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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import io.loopcam.core.audio.LoopConfig

@Composable
fun RecordingScreen(viewModel: SessionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val audioStatus by viewModel.audioStatus.collectAsStateWithLifecycle()
    val loopSeconds by viewModel.loopSeconds.collectAsStateWithLifecycle()
    val overdub by viewModel.overdub.collectAsStateWithLifecycle()
    val click by viewModel.click.collectAsStateWithLifecycle()
    val isRecording = sessionState is SessionState.Recording
    val isBusy = isRecording || sessionState is SessionState.Starting || sessionState is SessionState.Exporting

    val previewView = remember { PreviewView(context) }
    LaunchedEffect(previewView, lifecycleOwner) {
        viewModel.bindCamera(lifecycleOwner, previewView.surfaceProvider)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SessionMessage(sessionState)
            audioStatus?.let { LoopStatus(it) }

            if (!isBusy) {
                Text(stringResource(R.string.headphones_hint), color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.loop_length, loopSeconds), color = Color.White)
                Slider(
                    value = loopSeconds.toFloat(),
                    onValueChange = { viewModel.setLoopSeconds(it.toDouble()) },
                    valueRange = LoopConfig.MIN_LOOP_SECONDS.toFloat()..LoopConfig.MAX_LOOP_SECONDS.toFloat(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LabeledSwitch(stringResource(R.string.overdub), overdub, viewModel::setOverdub)
                LabeledSwitch(stringResource(R.string.click), click, viewModel::setClickEnabled)
                OutlinedButton(onClick = viewModel::undoLastLayer, enabled = isRecording) {
                    Text(stringResource(R.string.undo))
                }
            }

            Button(
                onClick = viewModel::toggleRecording,
                enabled = sessionState !is SessionState.Starting && sessionState !is SessionState.Exporting,
            ) {
                Text(stringResource(if (isRecording) R.string.stop else R.string.record))
            }
        }
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

@Composable
private fun LoopStatus(status: AudioStatus) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.layers, status.committedLayers, status.maxLayers), color = Color.White)
            Text(
                stringResource(if (status.recordingLayer) R.string.layer_recording else R.string.layer_playing),
                color = if (status.recordingLayer) MaterialTheme.colorScheme.primary else Color.White,
            )
            Text(stringResource(R.string.latency, status.latencyMillis), color = Color.White)
        }
        LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
        if (status.droppedFrames > 0) {
            Text(
                stringResource(R.string.dropped_frames, status.droppedFrames),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Color.White)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
