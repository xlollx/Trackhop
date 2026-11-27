package com.xlollx.songport.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The file Google Cloud offers with **Download JSON** when a client is created: the client ID and
 * secret read from it, so nobody has to copy two long strings by hand (the usual cause of
 * `invalid_client`). A "Desktop app" client keeps them under `installed`, a "Web application" one
 * under `web`.
 */
object GoogleClientFile {
    data class Client(val id: String, val secret: String)

    /** The client in [text], or null when it is not a Google client file. */
    fun parse(text: String): Client? {
        val root = try { Json.parseToJsonElement(text.trim().removePrefix("﻿")).jsonObject } catch (_: Exception) { return null }
        val section = (root["installed"] ?: root["web"]) as? JsonObject ?: root
        fun field(name: String): String? = section[name]?.let { runCatching { it.jsonPrimitive.content.trim() }.getOrNull() }
        val id = field("client_id")?.takeIf { it.endsWith(".apps.googleusercontent.com") } ?: return null
        val secret = field("client_secret").orEmpty()
        return Client(id, secret)
    }
}
