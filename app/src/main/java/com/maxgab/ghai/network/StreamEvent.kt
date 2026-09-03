package com.maxgab.ghai.network

sealed interface StreamEvent {
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ContentDelta(val text: String) : StreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String?
    ) : StreamEvent
    data class Finished(val reason: String?) : StreamEvent
    data class Failed(val message: String, val retryable: Boolean) : StreamEvent
    /** A transient failure is about to be retried; lets the UI show it's still alive instead of looking frozen. */
    data class Retrying(val attempt: Int, val delayMs: Long, val message: String) : StreamEvent
}
