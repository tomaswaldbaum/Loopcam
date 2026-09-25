package io.loopcam.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.loopcam.core.audio.LengthMode
import io.loopcam.core.audio.SessionConfig
import io.loopcam.core.video.CameraLens
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Lo que el usuario configura antes de grabar. */
data class SessionSettings(
    val config: SessionConfig = SessionConfig(),
    val lens: CameraLens = CameraLens.BACK,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "session_settings")

/** Recuerda la última configuración entre sesiones. */
@Singleton
class SessionSettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val store = context.settingsDataStore

    val settings: Flow<SessionSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun current(): SessionSettings = settings.first()

    suspend fun update(transform: (SessionSettings) -> SessionSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated.copy(config = updated.config.coerced()))
        }
    }

    private fun Preferences.toSettings(): SessionSettings {
        val defaults = SessionConfig()
        val config = SessionConfig(
            lengthMode = this[Keys.LENGTH_MODE]?.let { runCatching { LengthMode.valueOf(it) }.getOrNull() }
                ?: defaults.lengthMode,
            seconds = this[Keys.SECONDS] ?: defaults.seconds,
            bpm = this[Keys.BPM] ?: defaults.bpm,
            beatsPerBar = this[Keys.BEATS_PER_BAR] ?: defaults.beatsPerBar,
            bars = this[Keys.BARS] ?: defaults.bars,
            countInBars = this[Keys.COUNT_IN_BARS] ?: defaults.countInBars,
            metronomeInCountIn = this[Keys.METRONOME_COUNT_IN] ?: defaults.metronomeInCountIn,
            metronomeWhileRecording = this[Keys.METRONOME_RECORDING] ?: defaults.metronomeWhileRecording,
            maxLayers = this[Keys.MAX_LAYERS] ?: defaults.maxLayers,
        ).coerced()
        val lens = this[Keys.LENS]?.let { runCatching { CameraLens.valueOf(it) }.getOrNull() } ?: CameraLens.BACK
        return SessionSettings(config, lens)
    }

    private fun MutablePreferences.write(settings: SessionSettings) {
        val c = settings.config
        this[Keys.LENGTH_MODE] = c.lengthMode.name
        this[Keys.SECONDS] = c.seconds
        this[Keys.BPM] = c.bpm
        this[Keys.BEATS_PER_BAR] = c.beatsPerBar
        this[Keys.BARS] = c.bars
        this[Keys.COUNT_IN_BARS] = c.countInBars
        this[Keys.METRONOME_COUNT_IN] = c.metronomeInCountIn
        this[Keys.METRONOME_RECORDING] = c.metronomeWhileRecording
        this[Keys.MAX_LAYERS] = c.maxLayers
        this[Keys.LENS] = settings.lens.name
    }

    private object Keys {
        val LENGTH_MODE = stringPreferencesKey("length_mode")
        val SECONDS = doublePreferencesKey("seconds")
        val BPM = doublePreferencesKey("bpm")
        val BEATS_PER_BAR = intPreferencesKey("beats_per_bar")
        val BARS = intPreferencesKey("bars")
        val COUNT_IN_BARS = intPreferencesKey("count_in_bars")
        val METRONOME_COUNT_IN = booleanPreferencesKey("metronome_count_in")
        val METRONOME_RECORDING = booleanPreferencesKey("metronome_recording")
        val MAX_LAYERS = intPreferencesKey("max_layers")
        val LENS = stringPreferencesKey("lens")
    }
}
