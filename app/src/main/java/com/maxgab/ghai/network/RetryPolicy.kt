package com.maxgab.ghai.network

import kotlinx.coroutines.delay

/**
 * Backoff schedule in seconds, indexed by retry number (index 0 = wait before the
 * 2nd attempt, since the 1st attempt is always instantaneous). Grows 3, 5, 10, 15,
 * then doubles up to a 60s ceiling so the agent keeps trying indefinitely without
 * hammering the API at a fixed high frequency.
 */
private val BACKOFF_SCHEDULE_SECONDS = longArrayOf(3, 5, 10, 15, 30, 60)

internal fun backoffDelayMillis(retryNumber: Int): Long {
    val index = (retryNumber - 1).coerceAtMost(BACKOFF_SCHEDULE_SECONDS.lastIndex)
    return BACKOFF_SCHEDULE_SECONDS[index] * 1000L
}

/**
 * Runs [block] with unlimited retries and the schedule above: the first attempt is
 * instantaneous, every attempt after that waits according to [backoffDelayMillis].
 * [isRetryable] decides whether a caught throwable deserves another attempt at all;
 * non-retryable failures are re-thrown immediately so callers (the agent loop / the
 * model) can react instead of hammering a call that will never succeed (e.g. an
 * invalid API key or a malformed request). Transient failures (rate limits, 5xx,
 * timeouts, dropped connections) are retried forever until they succeed.
 */
suspend fun <T> retryWithBackoff(
    isRetryable: (Throwable) -> Boolean = { true },
    onRetry: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    block: suspend (attempt: Int) -> T
): T {
    var attempt = 0
    while (true) {
        attempt++
        try {
            return block(attempt)
        } catch (t: Throwable) {
            if (!isRetryable(t)) throw t
            val delayMs = backoffDelayMillis(attempt)
            onRetry(attempt, delayMs, t)
            delay(delayMs)
        }
    }
}

class HttpStatusException(val code: Int, message: String) : Exception(message)

/**
 * Only genuinely transient failures are retried forever: rate limits, server
 * errors, and network/IO problems (timeouts, dropped connections, DNS hiccups).
 * Anything else (bad arguments, malformed JSON, programming errors) is NOT
 * retryable — with unlimited attempts, retrying those would spin forever on a
 * failure that will never fix itself, instead of letting the model see the
 * error and correct its next call.
 */
fun isTransientHttpError(t: Throwable): Boolean = when (t) {
    is HttpStatusException -> t.code == 429 || t.code in 500..599
    is java.io.IOException -> true
    else -> false
}
