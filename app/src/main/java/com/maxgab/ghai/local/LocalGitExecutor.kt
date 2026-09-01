package com.maxgab.ghai.local

import android.content.Context
import com.maxgab.ghai.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Local git tool (`local_git`): lets the model clone a real repo into the app's
 * sandboxed workspace, edit it with [LocalFileExecutor], then stage/commit/push the
 * changes back to GitHub — the actual git plumbing (JGit) instead of a shell, since
 * Android apps don't have a real terminal/git binary available.
 */
class LocalGitExecutor(
    context: Context,
    private val settings: SettingsRepository
) {
    private val root: File = File(context.filesDir, "workspace").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(argumentsJson: String): String = runInterruptible(Dispatchers.IO) {
        try {
            val args = json.parseToJsonElement(argumentsJson).let { it as? JsonObject } ?: JsonObject(emptyMap())
            val repoDir = resolveRepoDir(args.str("repoName") ?: throw IllegalArgumentException("Falta 'repoName'"))

            when (args.str("action")) {
                "clone" -> clone(repoDir, args.str("url") ?: throw IllegalArgumentException("Falta 'url'"), args.str("branch"))
                "init" -> init(repoDir)
                "status" -> withGit(repoDir) { status(it) }
                "add" -> withGit(repoDir) { add(it, args.str("pattern") ?: ".") }
                "commit" -> withGit(repoDir) {
                    commit(
                        it,
                        args.str("message") ?: throw IllegalArgumentException("Falta 'message'"),
                        args.str("authorName") ?: "GH AI",
                        args.str("authorEmail") ?: "ghai@device.local"
                    )
                }
                "push" -> withGit(repoDir) { push(it, args.str("remote") ?: "origin") }
                "pull" -> withGit(repoDir) { pull(it) }
                "checkout" -> withGit(repoDir) {
                    checkout(it, args.str("branch") ?: throw IllegalArgumentException("Falta 'branch'"), args.bool("createNew"))
                }
                "branches" -> withGit(repoDir) { branches(it) }
                "log" -> withGit(repoDir) { log(it, (args["maxCount"]?.jsonPrimitive?.content?.toIntOrNull()) ?: 20) }
                "diff" -> withGit(repoDir) { diff(it) }
                "removeRepo" -> removeRepo(repoDir)
                else -> errorJson(
                    "Acción desconocida. Usa: clone, init, status, add, commit, push, pull, checkout, branches, log, diff, removeRepo."
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson("Fallo en local_git: ${e.message}")
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.content?.toBoolean() ?: false

    private fun resolveRepoDir(repoName: String): File {
        val target = File(root, repoName).canonicalFile
        val rootCanonical = root.canonicalFile
        if (target != rootCanonical && !target.path.startsWith(rootCanonical.path + File.separator)) {
            throw SecurityException("'$repoName' está fuera del workspace permitido.")
        }
        return target
    }

    private fun credentials() = UsernamePasswordCredentialsProvider(settings.getGithubToken(), "")

    private fun clone(repoDir: File, url: String, branch: String?): String {
        repoDir.parentFile?.mkdirs()
        val cmd = Git.cloneRepository()
            .setURI(url)
            .setDirectory(repoDir)
            .setCredentialsProvider(credentials())
        branch?.let { cmd.setBranch(it) }
        cmd.call().use { }
        return buildJsonObject {
            put("cloned", JsonPrimitive(true))
            put("path", JsonPrimitive(repoDir.name))
        }.toString()
    }

    private fun init(repoDir: File): String {
        repoDir.mkdirs()
        Git.init().setDirectory(repoDir).call().use { }
        return buildJsonObject { put("initialized", JsonPrimitive(true)) }.toString()
    }

    private inline fun withGit(repoDir: File, block: (Git) -> String): String {
        if (!File(repoDir, ".git").exists()) return errorJson("No hay un repo git en '${repoDir.name}'. Cloná o inicializá primero.")
        return Git.open(repoDir).use { git -> block(git) }
    }

    private fun status(git: Git): String {
        val s = git.status().call()
        return buildJsonObject {
            put("added", s.added.toJsonArray())
            put("changed", s.changed.toJsonArray())
            put("removed", s.removed.toJsonArray())
            put("modified", s.modified.toJsonArray())
            put("untracked", s.untracked.toJsonArray())
            put("missing", s.missing.toJsonArray())
            put("conflicting", s.conflicting.toJsonArray())
            put("clean", JsonPrimitive(s.isClean))
        }.toString()
    }

    private fun add(git: Git, pattern: String): String {
        git.add().addFilepattern(pattern).call()
        return buildJsonObject { put("added", JsonPrimitive(pattern)) }.toString()
    }

    private fun commit(git: Git, message: String, authorName: String, authorEmail: String): String {
        val result = git.commit()
            .setMessage(message)
            .setAuthor(authorName, authorEmail)
            .call()
        return buildJsonObject {
            put("commitId", JsonPrimitive(result.id.name))
            put("message", JsonPrimitive(message))
        }.toString()
    }

    private fun push(git: Git, remote: String): String {
        val results = git.push().setRemote(remote).setCredentialsProvider(credentials()).call()
        val messages = mutableListOf<String>()
        results.forEach { r -> r.messages?.takeIf { it.isNotBlank() }?.let { messages += it } }
        return buildJsonObject {
            put("pushed", JsonPrimitive(true))
            put("remote", JsonPrimitive(remote))
            put("messages", buildJsonArray { messages.forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }

    private fun pull(git: Git): String {
        val result = git.pull().setCredentialsProvider(credentials()).call()
        return buildJsonObject {
            put("successful", JsonPrimitive(result.isSuccessful))
        }.toString()
    }

    private fun checkout(git: Git, branch: String, createNew: Boolean): String {
        git.checkout().setName(branch).setCreateBranch(createNew).call()
        return buildJsonObject {
            put("branch", JsonPrimitive(branch))
            put("created", JsonPrimitive(createNew))
        }.toString()
    }

    private fun branches(git: Git): String {
        val names = git.branchList().call().map { it.name }
        return buildJsonObject { put("branches", names.toJsonArray()) }.toString()
    }

    private fun log(git: Git, maxCount: Int): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val commits = git.log().setMaxCount(maxCount).call().map { c ->
            buildJsonObject {
                put("id", JsonPrimitive(c.id.name))
                put("shortMessage", JsonPrimitive(c.shortMessage))
                put("author", JsonPrimitive(c.authorIdent.name))
                put("date", JsonPrimitive(fmt.format(c.authorIdent.`when`)))
            }
        }
        return buildJsonObject {
            put("commits", buildJsonArray { commits.forEach { add(it) } })
        }.toString()
    }

    private fun diff(git: Git): String {
        val entries = git.diff().call().map { d ->
            buildJsonObject {
                put("changeType", JsonPrimitive(d.changeType.name))
                put("oldPath", JsonPrimitive(d.oldPath))
                put("newPath", JsonPrimitive(d.newPath))
            }
        }
        return buildJsonObject {
            put("changes", buildJsonArray { entries.forEach { add(it) } })
        }.toString()
    }

    private fun removeRepo(repoDir: File): String {
        val deleted = repoDir.deleteRecursively()
        return buildJsonObject { put("deleted", JsonPrimitive(deleted)) }.toString()
    }

    private fun Set<String>.toJsonArray() = buildJsonArray { this@toJsonArray.forEach { add(JsonPrimitive(it)) } }
    private fun List<String>.toJsonArray() = buildJsonArray { this@toJsonArray.forEach { add(JsonPrimitive(it)) } }

    private fun errorJson(message: String): String = buildJsonObject {
        put("error", JsonPrimitive(message))
    }.toString()
}
