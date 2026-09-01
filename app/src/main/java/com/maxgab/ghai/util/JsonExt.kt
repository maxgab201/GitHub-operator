package com.maxgab.ghai.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
fun JsonElement.jsonPrimitiveOrNull(): String? = (this as? JsonPrimitive)?.content
