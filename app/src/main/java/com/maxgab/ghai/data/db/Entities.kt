package com.maxgab.ghai.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val titleGenerated: Boolean
)

@Entity(
    tableName = "messages",
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val reasoning: String,
    val toolCallsJson: String?,
    val toolCallId: String?,
    val toolName: String?,
    val status: String,
    val thinkStartedAt: Long,
    val thinkingMillis: Long,
    val errorMessage: String?,
    val createdAt: Long,
    val orderIndex: Long
)
