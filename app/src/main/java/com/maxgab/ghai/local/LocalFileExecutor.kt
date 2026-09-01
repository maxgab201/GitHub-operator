package com.maxgab.ghai.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Local file/folder tool. Everything happens inside a single sandboxed workspace
 * directory in the app's private storage (no OS permission dialog needed to read or
 * write there), so the model can freely create folders, write/edit/read/delete files
 * — e.g. to prepare a local git checkout (see [LocalGitExecutor]) — without ever
 * touching anything outside its own workspace.
 */
class LocalFileExecutor(context: Context) {

    private val root: File = File(context.filesDir, "workspace").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        try {
            val args = json.parseToJsonElement(argumentsJson).let { it as? JsonObject } ?: JsonObject(emptyMap())
            when (args["action"]?.jsonPrimitive?.content) {
                "list" -> list(args.pathOrRoot())
                "read" -> read(args.path())
                "write" -> write(args.path(), args.contentOrEmpty(), append = false)
                "append" -> write(args.path(), args.contentOrEmpty(), append = true)
                "mkdir" -> mkdir(args.path())
                "delete" -> delete(args.path())
                "move" -> move(args.path(), args["newPath"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Falta 'newPath'"))
                "exists" -> exists(args.path())
                else -> errorJson("Acción desconocida. Usa: list, read, write, append, mkdir, delete, move, exists.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson("Fallo en local_files: ${e.message}")
        }
    }

    private fun JsonObject.path(): String =
        this["path"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Falta 'path'")

    private fun JsonObject.pathOrRoot(): String = this["path"]?.jsonPrimitive?.content ?: "."

    private fun JsonObject.contentOrEmpty(): String = this["content"]?.jsonPrimitive?.content ?: ""

    /** Resolves [relativePath] against the workspace root, rejecting any escape via "..". */
    private fun resolve(relativePath: String): File {
        val target = File(root, relativePath).canonicalFile
        val rootCanonical = root.canonicalFile
        if (target != rootCanonical && !target.path.startsWith(rootCanonical.path + File.separator)) {
            throw SecurityException("La ruta '$relativePath' está fuera del workspace permitido.")
        }
        return target
    }

    private fun list(relativePath: String): String {
        val dir = resolve(relativePath)
        if (!dir.exists()) return errorJson("No existe: $relativePath")
        if (!dir.isDirectory) return errorJson("No es una carpeta: $relativePath")
        val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("entries", buildJsonArray {
                entries.forEach { f ->
                    add(buildJsonObject {
                        put("name", JsonPrimitive(f.name))
                        put("isDirectory", JsonPrimitive(f.isDirectory))
                        put("sizeBytes", JsonPrimitive(if (f.isFile) f.length() else 0L))
                    })
                }
            })
        }.toString()
    }

    private fun read(relativePath: String): String {
        val file = resolve(relativePath)
        if (!file.exists() || !file.isFile) return errorJson("No existe el archivo: $relativePath")
        if (file.length() > MAX_READ_BYTES) {
            return errorJson("El archivo pesa ${file.length()} bytes, supera el máximo legible de $MAX_READ_BYTES bytes.")
        }
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("content", JsonPrimitive(file.readText()))
        }.toString()
    }

    private fun write(relativePath: String, content: String, append: Boolean): String {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        if (append) file.appendText(content) else file.writeText(content)
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("bytesWritten", JsonPrimitive(content.toByteArray().size))
            put("appended", JsonPrimitive(append))
        }.toString()
    }

    private fun mkdir(relativePath: String): String {
        val dir = resolve(relativePath)
        val created = dir.mkdirs()
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("created", JsonPrimitive(created || dir.isDirectory))
        }.toString()
    }

    private fun delete(relativePath: String): String {
        val target = resolve(relativePath)
        if (!target.exists()) return errorJson("No existe: $relativePath")
        val deleted = target.deleteRecursively()
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("deleted", JsonPrimitive(deleted))
        }.toString()
    }

    private fun move(fromPath: String, toPath: String): String {
        val from = resolve(fromPath)
        val to = resolve(toPath)
        to.parentFile?.mkdirs()
        if (!from.exists()) return errorJson("No existe: $fromPath")
        val moved = from.renameTo(to)
        return buildJsonObject {
            put("from", JsonPrimitive(fromPath))
            put("to", JsonPrimitive(toPath))
            put("moved", JsonPrimitive(moved))
        }.toString()
    }

    private fun exists(relativePath: String): String {
        val file = resolve(relativePath)
        return buildJsonObject {
            put("path", JsonPrimitive(relativePath))
            put("exists", JsonPrimitive(file.exists()))
            put("isDirectory", JsonPrimitive(file.isDirectory))
        }.toString()
    }

    private fun errorJson(message: String): String = buildJsonObject {
        put("error", JsonPrimitive(message))
    }.toString()

    companion object {
        private const val MAX_READ_BYTES = 2 * 1024 * 1024 // 2 MB safety cap to avoid OOM, not a feature limit
    }
}
