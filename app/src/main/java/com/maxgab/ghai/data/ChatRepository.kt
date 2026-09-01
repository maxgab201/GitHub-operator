package com.maxgab.ghai.data

import com.maxgab.ghai.data.db.MessageDao
import com.maxgab.ghai.data.db.MessageEntity
import com.maxgab.ghai.data.db.SessionDao
import com.maxgab.ghai.data.db.SessionEntity
import com.maxgab.ghai.data.model.ChatMessage
import com.maxgab.ghai.data.model.ChatSession
import com.maxgab.ghai.data.model.MessageRole
import com.maxgab.ghai.data.model.MessageStatus
import com.maxgab.ghai.data.model.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

class ChatRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao
) {
    fun observeSessions(): Flow<List<ChatSession>> =
        sessionDao.observeSessions().map { list -> list.map { it.toDomain() } }

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        messageDao.observeMessages(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun getMessages(sessionId: String): List<ChatMessage> =
        messageDao.getMessages(sessionId).map { it.toDomain() }

    suspend fun createSession(title: String = "Nuevo chat"): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(UUID.randomUUID().toString(), title, now, now, titleGenerated = false)
        sessionDao.upsert(session.toEntity())
        return session
    }

    suspend fun renameSession(id: String, title: String) {
        sessionDao.getSession(id)?.let {
            sessionDao.update(it.copy(title = title))
        }
    }

    suspend fun setGeneratedTitle(id: String, title: String) {
        sessionDao.setGeneratedTitle(id, title)
    }

    suspend fun touchSession(id: String) {
        sessionDao.touch(id, System.currentTimeMillis())
    }

    suspend fun deleteSession(id: String) {
        messageDao.deleteForSession(id)
        sessionDao.delete(id)
    }

    suspend fun deleteAllSessions() {
        sessionDao.deleteAll()
    }

    suspend fun saveMessage(message: ChatMessage) {
        messageDao.upsert(message.toEntity())
    }

    suspend fun nextOrderIndex(sessionId: String): Long =
        messageDao.countForSession(sessionId).toLong()

    /** Removes this message and everything after it (used when the user edits a prior turn). */
    suspend fun truncateFrom(sessionId: String, orderIndex: Long) {
        messageDao.deleteFromIndex(sessionId, orderIndex)
    }
}

private fun SessionEntity.toDomain() = ChatSession(id, title, createdAt, updatedAt, titleGenerated)
private fun ChatSession.toEntity() = SessionEntity(id, title, createdAt, updatedAt, titleGenerated)

private fun MessageEntity.toDomain(): ChatMessage {
    val calls: List<ToolCall> = toolCallsJson?.let {
        runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrDefault(emptyList())
    } ?: emptyList()
    return ChatMessage(
        id = id,
        sessionId = sessionId,
        role = MessageRole.valueOf(role),
        content = content,
        reasoning = reasoning,
        toolCalls = calls,
        toolCallId = toolCallId,
        toolName = toolName,
        status = MessageStatus.valueOf(status),
        thinkStartedAt = thinkStartedAt,
        thinkingMillis = thinkingMillis,
        errorMessage = errorMessage,
        createdAt = createdAt,
        orderIndex = orderIndex
    )
}

private fun ChatMessage.toEntity(): MessageEntity {
    val callsJson = if (toolCalls.isEmpty()) null else json.encodeToString(toolCalls)
    return MessageEntity(
        id = id,
        sessionId = sessionId,
        role = role.name,
        content = content,
        reasoning = reasoning,
        toolCallsJson = callsJson,
        toolCallId = toolCallId,
        toolName = toolName,
        status = status.name,
        thinkStartedAt = thinkStartedAt,
        thinkingMillis = thinkingMillis,
        errorMessage = errorMessage,
        createdAt = createdAt,
        orderIndex = orderIndex
    )
}
