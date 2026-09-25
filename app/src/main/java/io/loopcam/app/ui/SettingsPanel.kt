package io.loopcam.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.loopcam.app.R
import io.loopcam.app.settings.SessionSettings
import io.loopcam.core.audio.LengthMode
import io.loopcam.core.audio.SessionConfig
import io.loopcam.core.video.CameraLens
import kotlin.math.roundToInt

/** Ajustes de la sesión, antes de grabar. */
@Composable
fun SettingsPanel(
    settings: SessionSettings,
    availableLenses: List<CameraLens>,
    onConfigChange: ((SessionConfig) -> SessionConfig) -> Unit,
    onLensChange: (CameraLens) -> Unit,
) {
    val config = settings.config
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(stringResource(R.string.loop_length))
        ChipRow {
            FilterChip(
                selected = config.lengthMode == LengthMode.TEMPO,
                onClick = { onConfigChange { it.copy(lengthMode = LengthMode.TEMPO) } },
                label = { Text(stringResource(R.string.mode_tempo)) },
            )
            FilterChip(
                selected = config.lengthMode == LengthMode.SECONDS,
                onClick = { onConfigChange { it.copy(lengthMode = LengthMode.SECONDS) } },
                label = { Text(stringResource(R.string.mode_seconds)) },
            )
        }

        when (config.lengthMode) {
            LengthMode.TEMPO -> {
                Stepper(
                    label = stringResource(R.string.bpm_value, config.bpm),
                    onChange = { delta -> onConfigChange { it.copy(bpm = it.bpm + delta) } },
                    steps = listOf(-5.0, -1.0, 1.0, 5.0),
                )
                Slider(
                    value = config.bpm.toFloat(),
                    onValueChange = { v -> onConfigChange { it.copy(bpm = v.roundToInt().toDouble()) } },
                    valueRange = SessionConfig.MIN_BPM.toFloat()..SessionConfig.MAX_BPM.toFloat(),
                )
            }
            LengthMode.SECONDS -> {
                Text(stringResource(R.string.seconds_value, config.seconds))
                Slider(
                    value = config.seconds.toFloat(),
                    onValueChange = { v -> onConfigChange { it.copy(seconds = (v * 10).roundToInt() / 10.0) } },
                    valueRange = SessionConfig.MIN_LOOP_SECONDS.toFloat()..SessionConfig.MAX_LOOP_SECONDS.toFloat(),
                )
            }
        }

        SectionTitle(stringResource(R.string.time_signature))
        ChipRow {
            timeSignatures.forEach { (beats, label) ->
                FilterChip(
                    selected = config.beatsPerBar == beats,
                    onClick = { onConfigChange { it.copy(beatsPerBar = beats) } },
                    label = { Text(label) },
                )
            }
        }

        Stepper(
            label = "${stringResource(R.string.bars)}: ${config.bars}",
            onChange = { delta -> onConfigChange { it.copy(bars = it.bars + delta.toInt()) } },
            steps = listOf(-1.0, 1.0),
        )

        Text(
            stringResource(R.string.derived_length, config.beatsPerLoop, config.effectiveBpm, config.loopSeconds),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!config.isLoopLengthValid) {
            Text(
                stringResource(R.string.loop_too_long, SessionConfig.MIN_LOOP_SECONDS, SessionConfig.MAX_LOOP_SECONDS),
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()
        SectionTitle(stringResource(R.string.count_in))
        ChipRow {
            listOf(
                0 to R.string.count_in_none,
                1 to R.string.count_in_one,
                2 to R.string.count_in_two,
            ).forEach { (bars, label) ->
                FilterChip(
                    selected = config.countInBars == bars,
                    onClick = { onConfigChange { it.copy(countInBars = bars) } },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        LabeledSwitch(
            label = stringResource(R.string.metronome_in_count_in),
            checked = config.metronomeInCountIn,
            onCheckedChange = { v -> onConfigChange { it.copy(metronomeInCountIn = v) } },
        )
        LabeledSwitch(
            label = stringResource(R.string.metronome_while_recording),
            checked = config.metronomeWhileRecording,
            onCheckedChange = { v -> onConfigChange { it.copy(metronomeWhileRecording = v) } },
        )

        HorizontalDivider()
        SectionTitle(stringResource(R.string.max_layers, config.maxLayers))
        Slider(
            value = config.maxLayers.toFloat(),
            onValueChange = { v -> onConfigChange { it.copy(maxLayers = v.roundToInt()) } },
            valueRange = 1f..SessionConfig.MAX_LAYERS.toFloat(),
            steps = SessionConfig.MAX_LAYERS - 2,
        )
        if (config.memoryMaxLayers < config.maxLayers) {
            Text(
                stringResource(R.string.max_layers_memory, config.memoryMaxLayers),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (availableLenses.size > 1) {
            HorizontalDivider()
            SectionTitle(stringResource(R.string.camera))
            ChipRow {
                availableLenses.forEach { lens ->
                    FilterChip(
                        selected = settings.lens == lens,
                        onClick = { onLensChange(lens) },
                        label = { Text(stringResource(lens.labelRes())) },
                    )
                }
            }
        }
    }
}

private val timeSignatures = listOf(2 to "2/4", 3 to "3/4", 4 to "4/4", 5 to "5/4", 6 to "6/8", 7 to "7/8")

fun CameraLens.labelRes(): Int = when (this) {
    CameraLens.BACK -> R.string.camera_back
    CameraLens.FRONT -> R.string.camera_front
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Stepper(label: String, onChange: (Double) -> Unit, steps: List<Double>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        steps.filter { it < 0 }.forEach { step ->
            OutlinedButton(onClick = { onChange(step) }) { Text(formatStep(step)) }
        }
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        steps.filter { it > 0 }.forEach { step ->
            OutlinedButton(onClick = { onChange(step) }) { Text(formatStep(step)) }
        }
    }
}

private fun formatStep(step: Double): String {
    val value = step.roundToInt()
    return if (value > 0) "+$value" else "$value"
}

@Composable
fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = color, modifier = Modifier.weight(1f, fill = false))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
