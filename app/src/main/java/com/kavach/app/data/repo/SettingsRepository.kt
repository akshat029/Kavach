package com.kavach.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kavach.app.core.model.Zone
import com.kavach.app.vpn.DohEndpoints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Everything the user can change that is not per-app. */
data class KavachSettings(
    val autoStart: Boolean = true,
    val paused: Boolean = false,
    val loggingEnabled: Boolean = true,
    val dohEndpointId: String = DohEndpoints.CLOUDFLARE.id,
    val defaultZoneId: String = Zone.DEFAULT.id,
    val blocklistAutoUpdate: Boolean = true,
    val showSystemApps: Boolean = false,
) {
    val defaultZone: Zone get() = Zone.fromId(defaultZoneId)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kavach_settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val flow: Flow<KavachSettings> = store.data
        .catch { cause ->
            // A corrupt preferences file must not brick the app; fall back to defaults.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs ->
            KavachSettings(
                autoStart = prefs[KEY_AUTO_START] ?: true,
                paused = prefs[KEY_PAUSED] ?: false,
                loggingEnabled = prefs[KEY_LOGGING] ?: true,
                dohEndpointId = prefs[KEY_DOH] ?: DohEndpoints.CLOUDFLARE.id,
                defaultZoneId = prefs[KEY_DEFAULT_ZONE] ?: Zone.DEFAULT.id,
                blocklistAutoUpdate = prefs[KEY_AUTO_UPDATE] ?: true,
                showSystemApps = prefs[KEY_SHOW_SYSTEM] ?: false,
            )
        }

    suspend fun current(): KavachSettings = flow.first()

    suspend fun setAutoStart(value: Boolean) = put(KEY_AUTO_START, value)
    suspend fun setPaused(value: Boolean) = put(KEY_PAUSED, value)
    suspend fun setLoggingEnabled(value: Boolean) = put(KEY_LOGGING, value)
    suspend fun setBlocklistAutoUpdate(value: Boolean) = put(KEY_AUTO_UPDATE, value)
    suspend fun setShowSystemApps(value: Boolean) = put(KEY_SHOW_SYSTEM, value)

    suspend fun setDohEndpoint(id: String) {
        store.edit { it[KEY_DOH] = DohEndpoints.byId(id).id }
    }

    suspend fun setDefaultZone(zone: Zone) {
        store.edit { it[KEY_DEFAULT_ZONE] = zone.id }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }

    private companion object {
        val KEY_AUTO_START = booleanPreferencesKey("auto_start")
        val KEY_PAUSED = booleanPreferencesKey("paused")
        val KEY_LOGGING = booleanPreferencesKey("logging_enabled")
        val KEY_AUTO_UPDATE = booleanPreferencesKey("blocklist_auto_update")
        val KEY_SHOW_SYSTEM = booleanPreferencesKey("show_system_apps")
        val KEY_DOH = stringPreferencesKey("doh_endpoint")
        val KEY_DEFAULT_ZONE = stringPreferencesKey("default_zone")
    }
}
