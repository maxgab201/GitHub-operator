package com.maxgab.ghai.local

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lets the model see which apps are installed and launch one of them, using
 * Android's official Intent/PackageManager APIs — the closest legitimate
 * equivalent to "open an app" a sandboxed Android app can do without root.
 */
class AppLauncher(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(toolName: String, argumentsJson: String): String = withContext(Dispatchers.IO) {
        try {
            when (toolName) {
                "local_list_apps" -> listApps()
                "local_open_app" -> {
                    val args = json.parseToJsonElement(argumentsJson).let { it as? JsonObject } ?: JsonObject(emptyMap())
                    val packageName = args["packageName"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Falta 'packageName'")
                    openApp(packageName)
                }
                else -> errorJson("Herramienta desconocida: $toolName")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson("Fallo ejecutando $toolName: ${e.message}")
        }
    }

    private fun listApps(): String {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcherIntent, 0)
            .map { resolveInfo ->
                val appLabel = resolveInfo.loadLabel(pm).toString()
                val packageName = resolveInfo.activityInfo.packageName
                appLabel to packageName
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        return buildJsonObject {
            put("apps", buildJsonArray {
                apps.forEach { (label, pkg) ->
                    add(buildJsonObject {
                        put("label", JsonPrimitive(label))
                        put("packageName", JsonPrimitive(pkg))
                    })
                }
            })
        }.toString()
    }

    private fun openApp(packageName: String): String {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return errorJson("No se encontró una app lanzable con el paquete '$packageName'.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return buildJsonObject {
            put("opened", JsonPrimitive(true))
            put("packageName", JsonPrimitive(packageName))
        }.toString()
    }

    private fun errorJson(message: String): String = buildJsonObject {
        put("error", JsonPrimitive(message))
    }.toString()
}
