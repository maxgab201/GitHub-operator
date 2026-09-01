package com.maxgab.ghai.network

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

/**
 * Runs [block] with exponential backoff. [maxAttempts] is the total number of tries
 * (1 = no retry). [isRetryable] decides whether a caught throwable deserves another
 * attempt; non-retryable failures are re-thrown immediately so callers (the agent
 * loop / the model) can react instead of hammering a call that will never succeed.
 */
suspend fun <T> retryWithBackoff(
    maxAttempts: Int,
    baseDelayMillis: Long = 1000L,
    maxDelayMillis: Long = 20_000L,
    isRetryable: (Throwable) -> Boolean = { true },
    onRetry: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    block: suspend (attempt: Int) -> T
): T {
    var lastError: Throwable? = null
    val attempts = maxAttempts.coerceAtLeast(1)
    for (attempt in 1..attempts) {
        try {
            return block(attempt)
        } catch (t: Throwable) {
            lastError = t
            if (attempt == attempts || !isRetryable(t)) throw t
            val delayMs = min(maxDelayMillis, (baseDelayMillis * 2.0.pow(attempt - 1)).toLong())
            onRetry(attempt, delayMs, t)
            delay(delayMs)
        }
    }
    throw lastError ?: IllegalStateException("retryWithBackoff: unreachable")
}

class HttpStatusException(val code: Int, message: String) : Exception(message)

fun isTransientHttpError(t: Throwable): Boolean = when (t) {
    is HttpStatusException -> t.code == 429 || t.code in 500..599
    else -> true // network/timeout/IO errors are transient
}
