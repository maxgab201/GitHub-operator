package com.maxgab.ghai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.maxgab.ghai.GhAiApp
import com.maxgab.ghai.agent.AgentEvent
import com.maxgab.ghai.agent.buildOrMessages
import com.maxgab.ghai.data.AppSettings
import com.maxgab.ghai.data.AppTheme
import com.maxgab.ghai.data.ChatRepository
import com.maxgab.ghai.data.SettingsRepository
import com.maxgab.ghai.data.UsageState
import com.maxgab.ghai.data.UsageTracker
import com.maxgab.ghai.data.model.ChatMessage
import com.maxgab.ghai.data.model.ChatSession
import com.maxgab.ghai.data.model.MessageRole
import com.maxgab.ghai.data.model.MessageStatus
import com.maxgab.ghai.data.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val editingMessageId: String? = null,
    val isGenerating: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val usage: UsageState = UsageState(0, UsageTracker.DAILY_LIMIT, ""),
    val errorMessage: String? = null
)

class MainViewModel(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val usageTracker: UsageTracker,
    private val app: GhAiApp
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            chatRepository.observeSessions().collect { sessions ->
                _state.update { it.copy(sessions = sessions) }
                if (_state.value.currentSessionId == null) {
                    val target = sessions.firstOrNull()?.id ?: chatRepository.createSession().id
                    selectSession(target)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
        viewModelScope.launch {
            usageTracker.usage.collect { u -> _state.update { it.copy(usage = u) } }
        }
    }

    fun selectSession(id: String) {
        if (state.value.isGenerating) return
        viewModelScope.launch {
            val messages = chatRepository.getMessages(id)
            _state.update { it.copy(currentSessionId = id, messages = messages, inputText = "", editingMessageId = null) }
        }
    }

    fun newSession() {
        if (state.value.isGenerating) return
        viewModelScope.launch {
            val session = chatRepository.createSession()
            _state.update { it.copy(currentSessionId = session.id, messages = emptyList(), inputText = "", editingMessageId = null) }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            chatRepository.deleteSession(id)
            if (state.value.currentSessionId == id) {
                val next = state.value.sessions.firstOrNull { it.id != id }?.id
                if (next != null) selectSession(next) else newSession()
            }
        }
    }

    fun renameSession(id: String, title: String) {
        viewModelScope.launch { chatRepository.renameSession(id, title.trim().ifBlank { "Nuevo chat" }) }
    }

    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun beginEdit(message: ChatMessage) {
        if (state.value.isGenerating) return
        _state.update { it.copy(inputText = message.content, editingMessageId = message.id) }
    }

    fun cancelEdit() {
        _state.update { it.copy(inputText = "", editingMessageId = null) }
    }

    fun stopGeneration() {
        generationJob?.cancel()
    }

    fun updateSettings(transform: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { settingsRepository.transform() }
    }

    suspend fun getOpenRouterKey(): String = withContext(Dispatchers.IO) { settingsRepository.getOpenRouterKey() }
    suspend fun getGithubToken(): String = withContext(Dispatchers.IO) { settingsRepository.getGithubToken() }

    fun setOpenRouterKey(value: String) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepository.setOpenRouterKey(value) }
    }

    fun setGithubToken(value: String) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepository.setGithubToken(value) }
    }

    fun clearAllSessions() {
        viewModelScope.launch {
            chatRepository.deleteAllSessions()
            newSession()
        }
    }

    fun send() {
        val text = state.value.inputText.trim()
        val sessionId = state.value.currentSessionId
        if (text.isBlank() || state.value.isGenerating || sessionId == null) return

        viewModelScope.launch {
            var history = state.value.messages
            val editingId = state.value.editingMessageId
            if (editingId != null) {
                val idx = history.indexOfFirst { it.id == editingId }
                if (idx >= 0) {
                    chatRepository.truncateFrom(sessionId, history[idx].orderIndex)
                    history = history.subList(0, idx)
                }
            }

            val userMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = MessageRole.USER,
                content = text,
                status = MessageStatus.DONE,
                orderIndex = history.size.toLong()
            )
            chatRepository.saveMessage(userMsg)
            chatRepository.touchSession(sessionId)
            val newHistory = history + userMsg
            _state.update { it.copy(messages = newHistory, inputText = "", editingMessageId = null) }
            runAgent(sessionId, newHistory)
        }
    }

    private fun runAgent(sessionId: String, historyAtStart: List<ChatMessage>) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(isGenerating = true, errorMessage = null) }
            val settings = state.value.settings
            var orderCounter = historyAtStart.size.toLong()
            var currentAssistant: ChatMessage? = null
            val toolMessages = mutableMapOf<String, ChatMessage>()
            val isFirstExchange = historyAtStart.size == 1

            fun updateUiMessage(message: ChatMessage) {
                _state.update { s ->
                    val idx = s.messages.indexOfFirst { it.id == message.id }
                    val list = if (idx >= 0) s.messages.toMutableList().apply { set(idx, message) }
                    else s.messages + message
                    s.copy(messages = list)
                }
            }

            try {
                agentEngine().run(buildOrMessages(historyAtStart), settings).collect { event ->
                    when (event) {
                        is AgentEvent.Reasoning -> {
                            if (currentAssistant == null || currentAssistant!!.toolCalls.isNotEmpty()) {
                                currentAssistant = ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    role = MessageRole.ASSISTANT,
                                    status = MessageStatus.STREAMING,
                                    thinkStartedAt = System.currentTimeMillis(),
                                    orderIndex = orderCounter++
                                )
                            }
                            currentAssistant = currentAssistant!!.copy(reasoning = currentAssistant!!.reasoning + event.delta)
                            updateUiMessage(currentAssistant!!)
                        }
                        is AgentEvent.Content -> {
                            if (currentAssistant == null || currentAssistant!!.toolCalls.isNotEmpty()) {
                                currentAssistant = ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    role = MessageRole.ASSISTANT,
                                    status = MessageStatus.STREAMING,
                                    thinkStartedAt = System.currentTimeMillis(),
                                    orderIndex = orderCounter++
                                )
                            }
                            val cur = currentAssistant!!
                            val thinkingMillis = if (cur.thinkingMillis == 0L && cur.thinkStartedAt > 0L) {
                                System.currentTimeMillis() - cur.thinkStartedAt
                            } else cur.thinkingMillis
                            currentAssistant = cur.copy(content = cur.content + event.delta, thinkingMillis = thinkingMillis)
                            updateUiMessage(currentAssistant!!)
                        }
                        is AgentEvent.ToolCallBegin -> {
                            if (currentAssistant == null) {
                                currentAssistant = ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    role = MessageRole.ASSISTANT,
                                    status = MessageStatus.DONE,
                                    orderIndex = orderCounter++
                                )
                            }
                            val cur = currentAssistant!!.copy(
                                status = MessageStatus.DONE,
                                toolCalls = currentAssistant!!.toolCalls + ToolCall(event.id, event.name, event.arguments)
                            )
                            currentAssistant = cur
                            updateUiMessage(cur)
                            chatRepository.saveMessage(cur)

                            val toolMsg = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                sessionId = sessionId,
                                role = MessageRole.TOOL,
                                content = "",
                                toolCallId = event.id,
                                toolName = event.name,
                                status = MessageStatus.PENDING,
                                orderIndex = orderCounter++
                            )
                            toolMessages[event.id] = toolMsg
                            updateUiMessage(toolMsg)
                        }
                        is AgentEvent.ToolCallEnd -> {
                            val pending = toolMessages[event.id] ?: return@collect
                            val done = pending.copy(
                                content = event.resultJson,
                                status = if (event.success) MessageStatus.DONE else MessageStatus.ERROR
                            )
                            toolMessages[event.id] = done
                            updateUiMessage(done)
                            chatRepository.saveMessage(done)
                        }
                        is AgentEvent.TurnFinished -> {
                            val cur = (currentAssistant ?: ChatMessage(
                                id = UUID.randomUUID().toString(),
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                orderIndex = orderCounter++
                            )).copy(content = event.finalContent, status = MessageStatus.DONE, thinkingMillis = event.thinkingMillis)
                            currentAssistant = cur
                            updateUiMessage(cur)
                            chatRepository.saveMessage(cur)
                            chatRepository.touchSession(sessionId)
                            maybeAutoTitle(sessionId, isFirstExchange, historyAtStart, event.finalContent, settings)
                        }
                        is AgentEvent.Failed -> {
                            val cur = (currentAssistant ?: ChatMessage(
                                id = UUID.randomUUID().toString(),
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                orderIndex = orderCounter++
                            )).copy(status = MessageStatus.ERROR, errorMessage = event.message)
                            currentAssistant = cur
                            updateUiMessage(cur)
                            chatRepository.saveMessage(cur)
                            _state.update { it.copy(errorMessage = event.message) }
                        }
                    }
                }
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    currentAssistant?.let { cur ->
                        val stopped = cur.copy(status = MessageStatus.STOPPED)
                        updateUiMessage(stopped)
                        chatRepository.saveMessage(stopped)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    _state.update { it.copy(isGenerating = false) }
                }
            }
        }
    }

    private fun agentEngine() = app.agentEngine

    private fun maybeAutoTitle(
        sessionId: String,
        isFirstExchange: Boolean,
        historyAtStart: List<ChatMessage>,
        finalAssistantContent: String,
        settings: AppSettings
    ) {
        if (!isFirstExchange || !settings.autoTitleSessions) return
        val session = state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        if (session.titleGenerated) return
        val userText = historyAtStart.lastOrNull { it.role == MessageRole.USER }?.content ?: return
        viewModelScope.launch {
            val title = app.sessionTitler.generateTitle(userText, finalAssistantContent, settings)
            if (!title.isNullOrBlank()) {
                chatRepository.setGeneratedTitle(sessionId, title)
            }
        }
    }

    companion object {
        fun factory(app: GhAiApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(app.chatRepository, app.settingsRepository, app.usageTracker, app) as T
        }
    }
}
