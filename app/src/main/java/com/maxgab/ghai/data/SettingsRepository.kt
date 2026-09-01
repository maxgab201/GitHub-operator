package com.maxgab.ghai.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.maxgab.ghai.data.model.EffortLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ghai_settings")

enum class AppTheme { LIGHT, DARK, SYSTEM }

data class AppSettings(
    val model: String = DEFAULT_MODEL,
    val effort: EffortLevel = EffortLevel.MEDIUM,
    val temperature: Double = 0.7,
    val maxRetries: Int = 5,
    val maxToolIterations: Int = 25,
    val theme: AppTheme = AppTheme.SYSTEM,
    val autoTitleSessions: Boolean = true
) {
    companion object {
        const val DEFAULT_MODEL = "nvidia/nemotron-3.5-lightning:free"
    }
}

private object Keys {
    val MODEL = stringPreferencesKey("model")
    val EFFORT = stringPreferencesKey("effort")
    val TEMPERATURE = doublePreferencesKey("temperature")
    val MAX_RETRIES = intPreferencesKey("max_retries")
    val MAX_ITERATIONS = intPreferencesKey("max_iterations")
    val THEME = stringPreferencesKey("theme")
    val AUTO_TITLE = booleanPreferencesKey("auto_title")
}

class SettingsRepository(private val context: Context) {

    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ghai_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            model = prefs[Keys.MODEL] ?: AppSettings.DEFAULT_MODEL,
            effort = EffortLevel.fromName(prefs[Keys.EFFORT]),
            temperature = prefs[Keys.TEMPERATURE] ?: 0.7,
            maxRetries = prefs[Keys.MAX_RETRIES] ?: 5,
            maxToolIterations = prefs[Keys.MAX_ITERATIONS] ?: 25,
            theme = prefs[Keys.THEME]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: AppTheme.SYSTEM,
            autoTitleSessions = prefs[Keys.AUTO_TITLE] ?: true
        )
    }

    suspend fun setModel(model: String) = context.dataStore.edit { it[Keys.MODEL] = model }
    suspend fun setEffort(effort: EffortLevel) = context.dataStore.edit { it[Keys.EFFORT] = effort.name }
    suspend fun setTemperature(value: Double) = context.dataStore.edit { it[Keys.TEMPERATURE] = value }
    suspend fun setMaxRetries(value: Int) = context.dataStore.edit { it[Keys.MAX_RETRIES] = value }
    suspend fun setMaxToolIterations(value: Int) = context.dataStore.edit { it[Keys.MAX_ITERATIONS] = value }
    suspend fun setTheme(theme: AppTheme) = context.dataStore.edit { it[Keys.THEME] = theme.name }
    suspend fun setAutoTitleSessions(value: Boolean) = context.dataStore.edit { it[Keys.AUTO_TITLE] = value }

    fun getOpenRouterKey(): String = securePrefs.getString(KEY_OPENROUTER, "") ?: ""
    fun setOpenRouterKey(value: String) = securePrefs.edit().putString(KEY_OPENROUTER, value).apply()

    fun getGithubToken(): String = securePrefs.getString(KEY_GITHUB, "") ?: ""
    fun setGithubToken(value: String) = securePrefs.edit().putString(KEY_GITHUB, value).apply()

    companion object {
        private const val KEY_OPENROUTER = "openrouter_api_key"
        private const val KEY_GITHUB = "github_token"

        val MODEL_PRESETS = listOf(
            "nvidia/nemotron-3.5-lightning:free",
            "nvidia/nemotron-3.5-lightning",
            "nvidia/llama-3.1-nemotron-70b-instruct:free",
            "nvidia/nemotron-nano-9b-v2:free"
        )
    }
}
