package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "localshare_settings")

data class AppSettings(
    val serverPort: Int = 8080,
    val passwordProtected: Boolean = false,
    val serverPassword: String = "",
    val maxConcurrentConnections: Int = 10,
    val themeMode: String = "DARK", // DARK, LIGHT, SYSTEM
    val startOnLaunch: Boolean = false,
    val allowFolderZipDownload: Boolean = true
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER_PORT = intPreferencesKey("server_port")
        val PASSWORD_PROTECTED = booleanPreferencesKey("password_protected")
        val SERVER_PASSWORD = stringPreferencesKey("server_password")
        val MAX_CONNECTIONS = intPreferencesKey("max_connections")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val START_ON_LAUNCH = booleanPreferencesKey("start_on_launch")
        val ALLOW_ZIP = booleanPreferencesKey("allow_zip")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            serverPort = prefs[Keys.SERVER_PORT] ?: 8080,
            passwordProtected = prefs[Keys.PASSWORD_PROTECTED] ?: false,
            serverPassword = prefs[Keys.SERVER_PASSWORD] ?: "",
            maxConcurrentConnections = prefs[Keys.MAX_CONNECTIONS] ?: 10,
            themeMode = prefs[Keys.THEME_MODE] ?: "DARK",
            startOnLaunch = prefs[Keys.START_ON_LAUNCH] ?: false,
            allowFolderZipDownload = prefs[Keys.ALLOW_ZIP] ?: true
        )
    }

    suspend fun updatePort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_PORT] = port
        }
    }

    suspend fun setPasswordProtection(enabled: Boolean, password: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PASSWORD_PROTECTED] = enabled
            prefs[Keys.SERVER_PASSWORD] = password
        }
    }

    suspend fun updateThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode
        }
    }

    suspend fun updateMaxConnections(max: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MAX_CONNECTIONS] = max
        }
    }

    suspend fun updateStartOnLaunch(start: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.START_ON_LAUNCH] = start
        }
    }

    suspend fun resetSettings() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
