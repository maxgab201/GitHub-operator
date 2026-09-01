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
 * Tracks OpenRouter's free-tier daily request cap (1000 req/day) locally, since the
 * app has no server to hold this state. Counter resets whenever the local calendar
 * day (device timezone) changes.
 */
class UsageTracker(private val context: Context) {

    private object Keys {
        val DAY_KEY = stringPreferencesKey("day_key")
        val COUNT = longPreferencesKey("count")
    }

    val usage: Flow<UsageState> = context.usageDataStore.data.map { prefs ->
        val today = todayKey()
        val storedDay = prefs[Keys.DAY_KEY]
        val count = if (storedDay == today) (prefs[Keys.COUNT] ?: 0L).toInt() else 0
        UsageState(requestsToday = count, dailyLimit = DAILY_LIMIT, dayKey = today)
    }

    suspend fun recordRequest() {
        val today = todayKey()
        context.usageDataStore.edit { prefs ->
            val storedDay = prefs[Keys.DAY_KEY]
            val current = if (storedDay == today) (prefs[Keys.COUNT] ?: 0L) else 0L
            prefs[Keys.DAY_KEY] = today
            prefs[Keys.COUNT] = current + 1
        }
    }

    private fun todayKey(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(System.currentTimeMillis())
    }

    companion object {
        const val DAILY_LIMIT = 1000
    }
}
