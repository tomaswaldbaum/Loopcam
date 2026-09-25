package io.loopcam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.loopcam.app.R
import io.loopcam.core.audio.AudioStatus

/**
 * Indicadores sobre el preview. CameraX graba lo que ve el sensor, no la pantalla,
 * así que nada de esto queda en el video.
 */
@Composable
fun RecordingOverlay(status: AudioStatus, modifier: Modifier = Modifier) {
    val beatFrames = if (status.beatsPerLoop > 0) status.loopFrames / status.beatsPerLoop else 0
    // 1 al empezar cada tiempo y se apaga hacia el final: da el "golpe" visual.
    val beatPulse = if (beatFrames > 0) 1f - (status.position % beatFrames).toFloat() / beatFrames else 0f

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BeatDots(status.beatsPerBar, status.beatInBar, beatPulse)
            if (!status.isCountingIn) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.loop_number, status.cycle + 1), color = Color.White)
                    Text(stringResource(R.string.layers, status.committedLayers, status.maxLayers), color = Color.White)
                    Text(
                        stringResource(if (status.recordingLayer) R.string.layer_recording else R.string.layer_playing),
                        color = if (status.recordingLayer) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                if (status.layersFull) {
                    Text(stringResource(R.string.layers_full), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (status.isCountingIn) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.get_ready), color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = status.countInBeatsRemaining.toString(),
                    color = Color.White,
                    fontSize = 160.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alpha(0.4f + 0.6f * beatPulse),
                )
            }
        }
    }
}

@Composable
private fun BeatDots(beatsPerBar: Int, activeBeat: Int, pulse: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(beatsPerBar) { beat ->
            val active = beat == activeBeat
            val size = if (beat == 0) 22.dp else 16.dp
            val color = when {
                active && beat == 0 -> MaterialTheme.colorScheme.primary
                active -> Color.White
                else -> Color.White.copy(alpha = 0.25f)
            }
            Box(
                modifier = Modifier
                    .size(size)
                    .alpha(if (active) 0.5f + 0.5f * pulse else 1f)
                    .background(color, CircleShape),
            )
        }
    }
}
