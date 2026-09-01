package com.maxgab.ghai.agent

import com.maxgab.ghai.local.AppLauncher
import com.maxgab.ghai.local.LocalFileExecutor
import com.maxgab.ghai.local.LocalGitExecutor
import com.maxgab.ghai.network.GithubToolExecutor

/**
 * Dispatches a tool call by name to whichever executor implements it: GitHub's REST
 * and GraphQL APIs, the on-device workspace (files + local git), or the app
 * launcher. Keeps [AgentEngine] itself agnostic of how many tool backends exist.
 */
class ToolRouter(
    private val githubToolExecutor: GithubToolExecutor,
    private val localFileExecutor: LocalFileExecutor,
    private val localGitExecutor: LocalGitExecutor,
    private val appLauncher: AppLauncher
) {
    suspend fun execute(toolName: String, argumentsJson: String): String = when (toolName) {
        "github_api", "github_graphql" -> githubToolExecutor.execute(toolName, argumentsJson)
        "local_files" -> localFileExecutor.execute(argumentsJson)
        "local_git" -> localGitExecutor.execute(argumentsJson)
        "local_list_apps", "local_open_app" -> appLauncher.execute(toolName, argumentsJson)
        else -> """{"error":"Herramienta desconocida: $toolName"}"""
    }
}
