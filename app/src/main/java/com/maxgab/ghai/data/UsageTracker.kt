package com.maxgab.ghai.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val Context.usageDataStore by preferencesDataStore(name = "ghai_usage")

data class UsageState(
    val requestsToday: Int,
    val dailyLimit: Int,
    val dayKey: String
) {
    val remaining: Int get() = (dailyLimit - requestsToday).coerceAtLeast(0)
    val fraction: Float get() = if (dailyLimit <= 0) 0f else (requestsToday.toFloat() / dailyLimit).coerceIn(0f, 1f)
}

/**
 * Tracks each provider's free-tier daily request cap locally (OpenRouter: 1000/day,
 * Gemini: 500/day), since the app has no server to hold this state. Each provider
 * gets its own counter, keyed by provider name, and resets whenever the local
 * calendar day (device timezone) changes.
 */
class UsageTracker(private val context: Context) {

    private fun dayKeyPref(provider: LlmProvider) = stringPreferencesKey("day_key_${provider.name}")
    private fun countPref(provider: LlmProvider) = longPreferencesKey("count_${provider.name}")

    fun observeUsage(provider: LlmProvider): Flow<UsageState> = context.usageDataStore.data.map { prefs ->
        val today = todayKey()
        val storedDay = prefs[dayKeyPref(provider)]
        val count = if (storedDay == today) (prefs[countPref(provider)] ?: 0L).toInt() else 0
        UsageState(requestsToday = count, dailyLimit = provider.dailyLimit, dayKey = today)
    }

    suspend fun recordRequest(provider: LlmProvider) {
        val today = todayKey()
        context.usageDataStore.edit { prefs ->
            val storedDay = prefs[dayKeyPref(provider)]
            val current = if (storedDay == today) (prefs[countPref(provider)] ?: 0L) else 0L
            prefs[dayKeyPref(provider)] = today
            prefs[countPref(provider)] = current + 1
        }
    }

    private fun todayKey(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(System.currentTimeMillis())
    }
}
