package com.maxgab.ghai

import android.app.Application
import com.maxgab.ghai.agent.AgentEngine
import com.maxgab.ghai.agent.SessionTitler
import com.maxgab.ghai.data.ChatRepository
import com.maxgab.ghai.data.SettingsRepository
import com.maxgab.ghai.data.UsageTracker
import com.maxgab.ghai.data.db.AppDatabase
import com.maxgab.ghai.network.GithubToolExecutor
import com.maxgab.ghai.network.OpenRouterClient

class GhAiApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var usageTracker: UsageTracker
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var openRouterClient: OpenRouterClient
        private set
    lateinit var githubToolExecutor: GithubToolExecutor
        private set
    lateinit var agentEngine: AgentEngine
        private set
    lateinit var sessionTitler: SessionTitler
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        usageTracker = UsageTracker(this)
        val db = AppDatabase.get(this)
        chatRepository = ChatRepository(db.sessionDao(), db.messageDao())
        openRouterClient = OpenRouterClient(settingsRepository, usageTracker)
        githubToolExecutor = GithubToolExecutor(settingsRepository)
        agentEngine = AgentEngine(openRouterClient, githubToolExecutor)
        sessionTitler = SessionTitler(openRouterClient)
    }
}
