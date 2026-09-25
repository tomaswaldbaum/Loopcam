package io.loopcam.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import io.loopcam.core.audio.LoopConfig

@Composable
fun RecordingScreen(viewModel: SessionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val loopSeconds by viewModel.loopSeconds.collectAsStateWithLifecycle()
    val isRecording = sessionState is SessionState.Recording

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
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            (sessionState as? SessionState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.headphones_hint), color = Color.White, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.loop_length, loopSeconds), color = Color.White)
            Slider(
                value = loopSeconds.toFloat(),
                onValueChange = { viewModel.setLoopSeconds(it.toDouble()) },
                valueRange = LoopConfig.MIN_LOOP_SECONDS.toFloat()..LoopConfig.MAX_LOOP_SECONDS.toFloat(),
                enabled = !isRecording,
            )
            Button(onClick = viewModel::toggleRecording) {
                Text(stringResource(if (isRecording) R.string.stop else R.string.record))
            }
        }
    }
}
