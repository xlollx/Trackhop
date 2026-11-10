package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import com.xlollx.songport.server.Html.h

/**
 * Settings › Notifications: the channels (Notify.kt), added, changed, turned off, removed and tried
 * with "Send test", which answers on the page whether the message arrived.
 */
internal fun Web.notificationsPage(r: Web.Req) {
    if (!env.may(Permissions.API)) return send(r.ex, 403, page("Notifications", NOT_ALLOWED))
    val here = "/settings/notifications"
    if (r.method == "POST") {
        val id = r.form["id"].orEmpty()
        when (r.form["action"]) {
            "save" -> {
                val kind = Notify.Kind.of(r.form["kind"]) ?: return redirect(r.ex, "$here?err=${q("Choose a kind of channel.")}")
                val old = Notify.channel(env, id)
                val events = r.rawForm.filter { it.first == "event" }.mapNotNull { Notify.Event.of(it.second) }.toSet()
                val given = kind.fields.associate { f -> f.key to r.form["f_${f.key}"].orEmpty() }
                val built = Notify.build(old?.id ?: Notify.newId(), kind, r.form["name"].orEmpty(), r.form["enabled"] == "on", events, given, old)
                val c = built.getOrElse {
                    val back = if (old != null) "edit=${q(old.id)}" else "add=${q(kind.id)}"
                    return redirect(r.ex, "$here?$back&err=${q(it.message ?: "Not saved.")}")
                }
                Notify.put(env, c)
                env.log("notify", "${if (old == null) "added" else "changed"} ${c.name} (${c.kind.label})")
                return redirect(r.ex, "$here?ok=${q("Saved ${c.name}. Send a test to see it arrive.")}")
            }
            "remove" -> {
                val c = Notify.channel(env, id)
                if (c != null && Notify.remove(env, id)) env.log("notify", "removed ${c.name} (${c.kind.label})")
                return redirect(r.ex, "$here?ok=${q("Removed.")}")
            }
            "toggle" -> {
                val c = Notify.channel(env, id) ?: return redirect(r.ex, here)
                Notify.put(env, c.copy(enabled = !c.enabled))
                return redirect(r.ex, here)
            }
            "test" -> {
                val c = Notify.channel(env, id) ?: return redirect(r.ex, here)
                // Here, not in the background: the person waits for the answer (10 seconds at most).
                val error = Notify.test(c)
                env.log("notify", "test to ${c.name} (${c.kind.label}): ${error ?: "sent"}")
                return redirect(r.ex, if (error == null) "$here?ok=${q("Sent to ${c.name}: check that it arrived.")}" else "$here?err=${q("${c.name}: $error")}")
            }
        }
        return redirect(r.ex, here)
    }
    val body = StringBuilder(notice(r))
    val editing = r.query["edit"]?.let { Notify.channel(env, it) }
    val adding = Notify.Kind.of(r.query["add"])
    if (editing != null || adding != null) {
        body.append(channelForm(editing, editing?.kind ?: adding!!))
        return send(r.ex, 200, page(if (editing != null) "Edit ${editing.name}" else "Add ${adding!!.label}", body.toString()))
    }
    body.append(
        """<div class="card"><p class="muted">A short message when a sync ends with something to know: the sync's name, what it added and removed, its error. Never your passwords or your server's address. The <a href="/tools/api">webhook</a> sends the whole run as JSON instead.</p></div>""",
    )
    val list = Notify.channels(env)
    if (list.isEmpty()) body.append("""<p class="muted">No channels yet.</p>""")
    for (c in list) body.append(channelCard(c, here))
    val options = Notify.Kind.entries.joinToString("") { """<option value="${it.id}">${h(it.label)}</option>""" }
    body.append(
        """<form method="get" action="$here" class="card"><h3>Add a channel</h3><label>Send to</label><select name="add">$options</select><p><button>Next</button></p></form>""",
    )
    send(r.ex, 200, page("Notifications", body.toString()))
}

/** A channel as the list shows it: its settings (secrets masked), its events and its buttons. */
private fun channelCard(c: Notify.Channel, here: String): String = buildString {
    append("""<div class="card"><h3>${h(c.name)} <span class="muted">${h(c.kind.label)}${if (!c.enabled) " · off" else ""}</span></h3>""")
    val shown = c.kind.fields.filter { c.value(it.key).isNotEmpty() }.joinToString(" · ") { f ->
        "${h(f.label)}: <code>${h(if (f.secret) Notify.mask(c.value(f.key)) else c.value(f.key))}</code>"
    }
    if (shown.isNotEmpty()) append("""<p class="muted">$shown</p>""")
    val events = Notify.Event.entries.filter { it in c.events }
    append("""<p>${if (events.isEmpty()) "No events chosen: nothing is sent." else "When: " + h(events.joinToString("; ") { it.label.lowercase() })}</p>""")
    val missing = c.missing()
    if (missing.isNotEmpty()) append("""<p class="err">${h(missing.joinToString(", ") { it.label })}: enter it again (backups never hold it).</p>""")
    val fields = mapOf("id" to c.id)
    append("""<div class="actions"><a class="button secondary" href="$here?edit=${h(c.id)}">Edit</a> """)
    if (missing.isEmpty()) append(Html.postButton(here, "Send test", "secondary", fields + ("action" to "test")))
    append(Html.postButton(here, if (c.enabled) "Turn off" else "Turn on", "secondary", fields + ("action" to "toggle")))
    append(Html.postButton(here, "Remove", "danger", fields + ("action" to "remove"), confirm = "Remove ${c.name}?"))
    append("</div></div>")
}

/** The form that adds a channel of [kind] or changes [c]: a secret is never written back into it. */
private fun channelForm(c: Notify.Channel?, kind: Notify.Kind): String = buildString {
    append("""<form method="post" action="/settings/notifications" class="card"><input type="hidden" name="action" value="save">""")
    append("""<input type="hidden" name="kind" value="${kind.id}"><input type="hidden" name="id" value="${h(c?.id.orEmpty())}">""")
    append("""<p class="muted">${h(kind.help)}</p>""")
    append("""<label>Name</label><input type="text" name="name" value="${h(c?.name ?: kind.label)}">""")
    for (f in kind.fields) {
        val v = c?.value(f.key).orEmpty()
        if (f.secret) {
            val kept = if (v.isNotEmpty()) "Kept: ${Notify.mask(v)} (leave empty to keep it)" else f.placeholder
            append("""<label>${h(f.label)}</label><input type="password" name="f_${f.key}" autocomplete="off" placeholder="${h(kept)}">""")
        } else {
            append("""<label>${h(f.label)}</label><input type="text" name="f_${f.key}" value="${h(v.ifEmpty { if (c == null) f.default else "" })}" placeholder="${h(f.placeholder)}">""")
        }
    }
    append("<label>Send a message when</label>")
    for (e in Notify.Event.entries) {
        val on = c?.events?.contains(e) ?: true
        append("""<label class="check"><input type="checkbox" name="event" value="${e.key}"${if (on) " checked" else ""}> ${h(e.label)}</label>""")
    }
    append("""<label class="check"><input type="checkbox" name="enabled"${if (c?.enabled != false) " checked" else ""}> On</label>""")
    append("""<p><button>Save</button> <a class="button secondary" href="/settings/notifications">Cancel</a></p></form>""")
}
