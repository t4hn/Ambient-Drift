package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.SceneCatalog
import com.example.model.SceneSpec
import com.example.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.driftDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "drift_preferences"
)

interface PreferencesStorage {
    val preferencesFlow: Flow<UserPreferences>
    suspend fun readInitialPreferences(): UserPreferences
    suspend fun setLastSceneId(sceneId: String)
    suspend fun setMasterVolume(volume: Float)
    suspend fun setChannelVolume(channelId: String, volume: Float)
    suspend fun setChannelMuted(channelId: String, muted: Boolean)
    suspend fun setChannelPlaying(channelId: String, playing: Boolean)
    suspend fun setAutoPlayOnSceneSwitch(enabled: Boolean)
    suspend fun setSmoothCrossfadeEnabled(enabled: Boolean)
    suspend fun resetSceneDefaults(scene: SceneSpec)
    suspend fun resetAllDefaults(scenes: List<SceneSpec>)
}

class DriftPreferencesRepository(
    private val context: Context
) : PreferencesStorage {

    private object Keys {
        val LAST_SCENE_ID = stringPreferencesKey("last_scene_id")
        val MASTER_VOLUME = floatPreferencesKey("master_volume")
        val AUTO_PLAY_ON_SWITCH = booleanPreferencesKey("auto_play_on_scene_switch")
        val SMOOTH_CROSSFADE = booleanPreferencesKey("smooth_crossfade_enabled")

        fun channelVolume(channelId: String) = floatPreferencesKey("vol_$channelId")
        fun channelMute(channelId: String) = booleanPreferencesKey("mute_$channelId")
        fun channelPlaying(channelId: String) = booleanPreferencesKey("play_$channelId")
    }

    override val preferencesFlow: Flow<UserPreferences> = context.driftDataStore.data.map { prefs ->
        mapPreferences(prefs)
    }

    override suspend fun readInitialPreferences(): UserPreferences {
        return mapPreferences(context.driftDataStore.data.first())
    }

    private fun mapPreferences(prefs: Preferences): UserPreferences {
        val allChannels = SceneCatalog.scenes.flatMap { it.channels }
        val volumes = buildMap {
            for (ch in allChannels) {
                put(ch.id, (prefs[Keys.channelVolume(ch.id)] ?: ch.defaultVolume).coerceIn(0f, 1f))
            }
        }
        val mutes = buildMap {
            for (ch in allChannels) {
                put(ch.id, prefs[Keys.channelMute(ch.id)] ?: false)
            }
        }
        val playStates = buildMap {
            for (ch in allChannels) {
                put(ch.id, prefs[Keys.channelPlaying(ch.id)] ?: true)
            }
        }

        return UserPreferences(
            lastSceneId = prefs[Keys.LAST_SCENE_ID] ?: SceneCatalog.defaultScene.id,
            masterVolume = (prefs[Keys.MASTER_VOLUME] ?: 0.85f).coerceIn(0f, 1f),
            autoPlayOnSceneSwitch = prefs[Keys.AUTO_PLAY_ON_SWITCH] ?: true,
            smoothCrossfadeEnabled = prefs[Keys.SMOOTH_CROSSFADE] ?: true,
            channelVolumes = volumes,
            channelMutes = mutes,
            channelPlaybackStates = playStates
        )
    }

    override suspend fun setLastSceneId(sceneId: String) {
        context.driftDataStore.edit { it[Keys.LAST_SCENE_ID] = sceneId }
    }

    override suspend fun setMasterVolume(volume: Float) {
        context.driftDataStore.edit { it[Keys.MASTER_VOLUME] = volume.coerceIn(0f, 1f) }
    }

    override suspend fun setChannelVolume(channelId: String, volume: Float) {
        context.driftDataStore.edit { it[Keys.channelVolume(channelId)] = volume.coerceIn(0f, 1f) }
    }

    override suspend fun setChannelMuted(channelId: String, muted: Boolean) {
        context.driftDataStore.edit { it[Keys.channelMute(channelId)] = muted }
    }

    override suspend fun setChannelPlaying(channelId: String, playing: Boolean) {
        context.driftDataStore.edit { it[Keys.channelPlaying(channelId)] = playing }
    }

    override suspend fun setAutoPlayOnSceneSwitch(enabled: Boolean) {
        context.driftDataStore.edit { it[Keys.AUTO_PLAY_ON_SWITCH] = enabled }
    }

    override suspend fun setSmoothCrossfadeEnabled(enabled: Boolean) {
        context.driftDataStore.edit { it[Keys.SMOOTH_CROSSFADE] = enabled }
    }

    override suspend fun resetSceneDefaults(scene: SceneSpec) {
        context.driftDataStore.edit { prefs ->
            for (ch in scene.channels) {
                prefs[Keys.channelVolume(ch.id)] = ch.defaultVolume
                prefs[Keys.channelMute(ch.id)] = false
                prefs[Keys.channelPlaying(ch.id)] = true
            }
        }
    }

    override suspend fun resetAllDefaults(scenes: List<SceneSpec>) {
        context.driftDataStore.edit { prefs ->
            prefs[Keys.MASTER_VOLUME] = 0.85f
            for (scene in scenes) {
                for (ch in scene.channels) {
                    prefs[Keys.channelVolume(ch.id)] = ch.defaultVolume
                    prefs[Keys.channelMute(ch.id)] = false
                    prefs[Keys.channelPlaying(ch.id)] = true
                }
            }
        }
    }
}
