package com.xlollx.songport.server

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedGroups
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared playlists, as a member (the app's Shared tab, [SharedGroups]): this Trackhop joins a group
 * from its invite link, as the phone does, with one of your playlists here (a new one by default):
 * songs added on either side reach the others at each run (every 6 hours, or Run now). Groups that
 * meet on an encrypted ntfy channel or in a Trackhop server's mailbox; a shared folder's group needs
 * the phone. Collections (several playlists shared as one) stay in the app for now.
 */
internal object SharedServer {
    /** The groups this Trackhop is in: not the multi syncs ([Nway]) nor a collection's own list. */
    fun groups(env: ServerEnv): List<SharedGroup> = env.store.data.groups.filter { !Nway.isGroup(it) && it.jobId.isNotBlank() }

    fun join(env: ServerEnv, link: String, myName: String, service: String, playlist: String?): SharedGroup {
        val invite = Invite.parse(link) ?: throw IllegalArgumentException("Not an invite link (https://trackhop.org/join#…).")
        if (invite.collection) throw IllegalArgumentException("That is a collection's invite: join collections from the app for now.")
        if (invite.transport != Invite.NTFY && invite.transport != Invite.SERVER) throw IllegalArgumentException("That group meets in a shared folder: join it from the phone.")
        val p = (Providers.connectors() + LocalFilesProvider).firstOrNull { it.id == service && it.canWrite && (!it.requiresAuth || it.isConnected(env)) }
            ?: throw IllegalArgumentException("Pick a connected service to keep it on.")
        val local = PlaylistRef(p.id, playlist?.takeIf { it.isNotBlank() }, invite.name)
        return SharedGroups.join(env, invite, "", "", myName.ifBlank { "Trackhop server" }, p.serviceId, local).first
    }
}

internal fun Web.sharedPage(r: Web.Req) {
    val back = "/tools/shared"
    if (r.method == "POST") {
        val res = runCatching {
            when (r.form["action"]) {
                "leave" -> {
                    val g = SharedServer.groups(env).firstOrNull { it.id == r.form["group"] } ?: throw IllegalArgumentException("Not in that group.")
                    SharedGroups.leave(env, g); "Left ${g.name}. Your playlist stays as it is."
                }
                "run" -> {
                    val g = SharedServer.groups(env).firstOrNull { it.id == r.form["group"] } ?: throw IllegalArgumentException("Not in that group.")
                    startSyncs(env.store.data.jobs.filter { SharedGroups.sharedSide(it) == g.id }); "Syncing ${g.name}."
                }
                else -> {
                    val g = SharedServer.join(env, r.form["link"].orEmpty(), r.form["name"].orEmpty(), r.form["service"].orEmpty(), r.form["playlist"])
                    startSyncs(env.store.data.jobs.filter { SharedGroups.sharedSide(it) == g.id })
                    "Joined ${g.name}: its songs are coming."
                }
            }
        }
        return redirect(r.ex, "$back?" + res.fold({ "ok=${q(it)}" }, { "err=${q(it.message ?: "Error")}" }))
    }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">A playlist shared with friends: songs added on either side reach the others. Paste the invite link a member sent you (Shared › Invite in the app), and pick where to keep it here. Groups meeting in a shared folder need the phone.</p>""")
    val targets = (Providers.connectors() + LocalFilesProvider).distinctBy { it.id }.filter { it.canWrite && !it.linkOnly && (!it.requiresAuth || it.isConnected(env)) }
    body.append("""<form method="post" action="$back" class="card"><input type="hidden" name="action" value="join"><h3>Join with an invite link</h3>
<label for="slink">Invite link</label><input type="url" id="slink" name="link" placeholder="https://trackhop.org/join#…" required>
<label for="sname">Your name in the group</label><input type="text" id="sname" name="name" maxlength="40" placeholder="Anna">
<label>Keep it on</label><select name="service">${targets.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }}</select>
<p class="muted">A new playlist there, named as the group.</p><p><button>Join</button></p></form>""")
    val groups = SharedServer.groups(env)
    for (g in groups) {
        val s = runCatching { SharedGroups.load(env, g.id) }.getOrNull()
        val members = s?.let { SharedGroups.members(g, it) }.orEmpty().filter { !it.left }
        val job = env.store.job(g.jobId)
        body.append("""<div class="card"><h3>${h(g.name)}</h3><p class="muted">${members.size} ${if (members.size == 1) "member" else "members"}: ${h(members.joinToString(", ") { it.name + if (it.me) " (here)" else "" })}${job?.let { " · kept on ${h(label(it.target.provider))}" } ?: ""}${job?.lastRunEpoch?.takeIf { it > 0 }?.let { " · last run ${Html.date(it)}" } ?: ""}</p>
<div class="actions">${Html.postButton(back, "Run now", "secondary", mapOf("action" to "run", "group" to g.id))} ${Html.postButton(back, "Leave", "danger", mapOf("action" to "leave", "group" to g.id), confirm = "Leave ${g.name}? Your playlist stays as it is.")}</div></div>""")
    }
    send(r.ex, 200, page("Shared playlists", body.toString()))
}

/** GET /shared, POST /shared (join), DELETE /shared/{group} (leave). */
internal fun Web.apiShared(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest.firstOrNull() != "shared" || rest.size > 2) return false
    when {
        rest.size == 1 && method == "GET" -> json(200, JsonArray(SharedServer.groups(env).map { g ->
            val members = runCatching { SharedGroups.members(g, SharedGroups.load(env, g.id)) }.getOrDefault(emptyList()).filter { !it.left }
            buildJsonObject {
                put("id", g.id); put("name", g.name); put("transport", g.transport); put("syncId", g.jobId)
                put("members", JsonArray(members.map { m -> buildJsonObject { put("name", m.name); put("service", m.service); put("curator", m.curator); put("me", m.me) } }))
            }
        }))
        rest.size == 1 && method == "POST" -> {
            val b = body() ?: return true.also { error(400, "Send JSON: {\"link\", \"service\", \"name\"?, \"playlist\"?}.") }
            val g = runCatching { SharedServer.join(env, b.text("link").orEmpty(), b.text("name").orEmpty(), b.text("service").orEmpty(), b.text("playlist")) }
                .getOrElse { return true.also { _ -> error(if (it is IllegalArgumentException) 400 else 502, it.message ?: "Error") } }
            startSyncs(env.store.data.jobs.filter { SharedGroups.sharedSide(it) == g.id })
            json(201, buildJsonObject { put("id", g.id); put("name", g.name); put("syncId", g.jobId) })
        }
        rest.size == 2 && method == "DELETE" -> {
            val g = SharedServer.groups(env).firstOrNull { it.id == rest[1] } ?: return true.also { error(404, "Not in that group.") }
            runCatching { SharedGroups.leave(env, g) }.getOrElse { return true.also { _ -> error(502, it.message ?: "Error") } }
            json(200, buildJsonObject { put("left", g.id) })
        }
        else -> return false
    }
    return true
}
