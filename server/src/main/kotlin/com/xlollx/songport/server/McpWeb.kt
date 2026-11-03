package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `POST /mcp`: Trackhop's MCP tools ([Mcp]) over the Streamable HTTP transport, answered in plain
 * JSON (no event stream: every tool answers once). The same token as the API (Tools › API), as
 * `Authorization: Bearer …`, or the command line's local key from this machine. Each tool call goes
 * to this server's own API on the loopback address, with that same token: what a call may do here
 * is exactly what the API lets that token do.
 */
/** An MCP message is a few kilobytes; a long list of tracks to add, a few hundred. */
private const val MAX_MCP_BODY = 2_000_000

internal fun Web.mcp(ex: HttpExchange) {
    fun error(code: Int, message: String) = send(ex, code, buildJsonObject { put("error", message) }.toString(), "application/json")
    val method = ex.requestMethod.uppercase()
    // No stream to open and no session to end: the server never speaks first.
    if (method == "GET" || method == "DELETE") {
        ex.responseHeaders.set("Allow", "POST")
        return send(ex, 405, "", "text/plain")
    }
    if (method != "POST") return error(405, "Send the MCP messages with POST.")
    // A page in a browser must not reach a Trackhop on this machine through its own address (DNS rebinding).
    val origin = ex.requestHeaders.getFirst("Origin")
    if (origin != null && !sameOrigin({ ex.requestHeaders.getFirst(it) }, env.baseUrl)) return error(403, "Requests from other sites are refused.")
    val auth = ex.requestHeaders.getFirst("Authorization")
    if (!env.may(Permissions.API)) return error(403, "The API is not turned on for you on this server: ask its admin.")
    if (!LocalKey.matches(auth, ex.remoteAddress?.address)) {
        if (!Api.enabled(env)) return error(403, "The API is off: make a token on the page, Tools › API.")
        if (!Api.authorized(env, auth)) return error(401, "Send the API token as: Authorization: Bearer <token>")
    }
    val token = auth!!.substring(7).trim()
    val body = ex.requestBody.readNBytes(MAX_MCP_BODY + 1)
    if (body.size > MAX_MCP_BODY) return error(413, "Too large.")
    val local = "http://127.0.0.1:${ex.localAddress.port}"
    val answer = Mcp.handleAny(String(body, Charsets.UTF_8), version) { Cli.Client(local, token) }
    if (answer == null) {
        // Only notifications: accepted, nothing to answer.
        ex.sendResponseHeaders(202, -1)
        return ex.close()
    }
    send(ex, 200, answer.toString(), "application/json")
}
