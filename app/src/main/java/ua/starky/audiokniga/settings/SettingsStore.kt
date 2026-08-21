package ua.starky.audiokniga.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

/** 0 — как в системе, 1 — светлая, 2 — тёмная. */
class SettingsStore(private val context: Context) {

    val themeMode: Flow<Int> = context.dataStore.data.map { it[KEY_THEME] ?: 0 }
    val playbackSpeed: Flow<Float> = context.dataStore.data.map { it[KEY_SPEED] ?: 1.0f }

    suspend fun setThemeMode(mode: Int) = edit(KEY_THEME, mode)

    suspend fun setPlaybackSpeed(speed: Float) = edit(KEY_SPEED, speed)

    private suspend fun <T> edit(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    companion object {
        private val KEY_THEME = intPreferencesKey("theme_mode")
        private val KEY_SPEED = floatPreferencesKey("playback_speed")
    }
}
