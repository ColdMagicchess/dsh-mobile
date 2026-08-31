package com.example.DSH_Mobile.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

val DSH_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
fun JsonElement?.asArr(): JsonArray? = this as? JsonArray
fun JsonElement?.asPrim(): JsonPrimitive? = this as? JsonPrimitive

private tailrec fun walk(root: JsonObject, path: List<String>): JsonElement? {
    if (path.isEmpty()) return root
    val next = root[path.first()] ?: return null
    val rest = path.drop(1)
    if (rest.isEmpty()) return next
    val nextObj = next.asObj() ?: return null
    return walk(nextObj, rest)
}

fun JsonObject.prim(vararg path: String): JsonPrimitive? = walk(this, path.toList()).asPrim()
fun JsonObject.obj(vararg path: String): JsonObject? = walk(this, path.toList()).asObj()
fun JsonObject.arr(vararg path: String): JsonArray? = walk(this, path.toList()).asArr()

fun JsonObject.str(vararg path: String): String? = prim(*path)?.let { p ->
    if (p.booleanOrNull != null) null else p.content
}
fun JsonObject.long(vararg path: String): Long? = prim(*path)?.longOrNull
fun JsonObject.int(vararg path: String): Int? = prim(*path)?.intOrNull
fun JsonObject.bool(vararg path: String): Boolean? = prim(*path)?.booleanOrNull

class DshApiException(
    val code: String?,
    message: String,
    val httpStatus: Int? = null,
) : Exception(message)
