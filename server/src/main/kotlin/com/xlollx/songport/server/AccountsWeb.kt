package com.xlollx.songport.server

import com.xlollx.songport.providers.CredentialsProvider
import com.xlollx.songport.providers.JellyfinProvider
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.OAuthProvider
import com.xlollx.songport.server.Html.h
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A connector's own name, and one more account of its service (Accounts.kt), at the foot of its page.
 * Nothing for File and the sources with nothing to sign in to.
 */
internal fun Web.accountCard(p: MusicProvider): String {
    if (p is LocalFilesProvider || !p.requiresAuth) return ""
    val id = q(p.id)
    val sb = StringBuilder("""<form method="post" action="/connectors/$id/rename" class="card">""")
    sb.append("""<label>${h(env.text("connector_name_label"))}</label><input type="text" name="name" maxlength="80" autocomplete="off" value="${h(com.xlollx.songport.providers.Providers.customName(p.id).orEmpty())}" placeholder="${h(p.displayName)}">""")
    sb.append("""<p><button class="secondary">${h(env.text("connector_rename"))}</button></p></form>""")
    sb.append(colorForm(p))
    if (p is OAuthProvider && p.slot.isNotEmpty()) {
        sb.append("""<p class="muted">The key above is the one of every ${h(p.displayName)} account on this server: each account signs in on its own.</p>""")
    }
    if (Accounts.allowsAnother(p)) {
        sb.append(
            """<div class="card row"><div class="muted">Another ${h(p.displayName)} account or server, with its own sign-in: syncs can go from one to the other.</div>""" +
                Html.postButton("/connectors/add", env.text("account_add"), "secondary", fields = mapOf("service" to p.serviceId + Accounts.ANOTHER)) + "</div>",
        )
    }
    return sb.toString()
}

/** The colour of an account: a ring round its logo here and in the app (AccountColors), seven colours or none. Plain radio buttons, no script. */
internal fun Web.colorForm(p: MusicProvider): String {
    val current = com.xlollx.songport.data.AccountColors.of(env.store.data.settings, p.id)
    fun choice(value: String, name: String, hex: String?) =
        """<label title="$name"><input type="radio" name="color" value="$value"${if ((current ?: "none") == value) " checked" else ""} aria-label="$name"><span class="dot${if (hex == null) " none" else ""}"${hex?.let { " style=\"background:$it\"" }.orEmpty()}></span></label>"""
    val choices = choice("none", "None", null) + com.xlollx.songport.data.AccountColors.ALL.joinToString("") { (k, hex) -> choice(k, k.replaceFirstChar { it.uppercase() }, hex) }
    return """<form method="post" action="/connectors/${q(p.id)}/color" class="card"><label>Colour</label>
<p class="muted">A ring round this account's logo, here and in the app, to tell it apart.</p>
<div class="swatches">$choices</div>
<p><button class="secondary">Save colour</button></p></form>"""
}

// ------------------------------------------------------------------ OpenSubsonic API key

/** The second sign-in under the password form: the server's address and an API key, no user or password. */
internal fun Web.apiKeyForm(p: CredentialsProvider): String =
    """<form method="post" action="/connectors/${q(p.id)}/connect" class="card"><h3>${h(env.text("login_apikey"))}</h3>
<p class="muted">${h(env.text("login_apikey_hint"))}</p>
<input type="hidden" name="mode" value="apikey">
<label>${h(env.text("login_url"))}</label><input type="url" name="url" required>
${if (p.offersSecondAddress) awayField() else ""}
<label>${h(env.text("login_apikey"))}</label><input type="password" name="apiKey" autocomplete="off" required>
<p><button class="secondary">${h(env.text("login_submit"))}</button></p></form>"""

// ------------------------------------------------------------------ Two addresses (2.2)

/** The second address of a sign-in form: optional, tried when the first does not answer. */
internal fun Web.awayField(value: String = ""): String =
    """<label>${h(env.text("login_url_away"))}</label><input type="url" name="url2" value="${h(value)}" placeholder="https://…">
<p class="muted">${h(env.text("login_url_away_hint"))}</p>"""

/** On a connected server: which address the requests go to now, and its second address to set or take away. */
internal fun Web.addressCard(p: CredentialsProvider): String {
    val away = p.awayAddress(env)
    val now = p.addressInUse(env)
    val via = if (away != null && now != null) """<p>${h(env.text(if (now == away) "account_via_away" else "account_via_home", now.removePrefix("https://").removePrefix("http://")))}</p>""" else ""
    return """<form method="post" action="/connectors/${q(p.id)}/address" class="card">$via${awayField(away.orEmpty())}<p><button class="secondary">Save</button></p></form>"""
}

// ------------------------------------------------------------------ Jellyfin Quick Connect

/** The button under Jellyfin's sign-in form: the server's address, then a code to approve. */
internal fun Web.quickConnectForm(p: JellyfinProvider): String =
    """<form method="post" action="/connectors/${q(p.id)}/quickconnect" class="card"><h3>${h(env.text("login_qc_title"))}</h3>
<p class="muted">${h(env.text("login_qc_hint"))}</p>
<label>${h(env.text("login_url"))}</label><input type="url" name="url" required>
<p><button class="secondary">${h(env.text("login_qc_button"))}</button></p></form>"""

private fun quickConnectOf(p: MusicProvider): JellyfinProvider? = (p as? JellyfinProvider)?.takeIf { it.supportsQuickConnect }

/** Asks the Jellyfin server for a code, then shows it ([quickConnectPage]). */
internal fun Web.quickConnectStart(r: Web.Req, p: MusicProvider) {
    val back = "/connectors/${q(p.id)}"
    val jf = quickConnectOf(p) ?: return send(r.ex, 404, errorPage(404))
    val qc = runCatching { runBlocking { jf.quickConnectStart(env, r.form["url"].orEmpty()) } }
        .getOrElse { return redirect(r.ex, "$back?err=${q(it.message ?: it.javaClass.simpleName)}") }
    val token = quickConnects.put(p.id, qc)
    redirect(r.ex, "$back/quickconnect?t=${q(token)}")
}

/** The code to approve, and a small script asking every 3 seconds whether it was ([quickConnectPoll]). */
internal fun Web.quickConnectPage(r: Web.Req, p: MusicProvider) {
    val back = "/connectors/${q(p.id)}"
    if (quickConnectOf(p) == null) return send(r.ex, 404, errorPage(404))
    val token = r.query["t"].orEmpty()
    val pending = quickConnects.get(token, p.id) ?: return redirect(r.ex, "$back?err=${q(env.text("jellyfin_qc_expired"))}")
    val poll = "$back/quickconnect/poll"
    val body = """<div class="card" id="qc" data-poll="${h(poll)}" data-token="${h(token)}">
<p>${h(env.text("login_qc_body"))}</p>
<p style="font-size:2.2em;font-weight:700;letter-spacing:.25em;margin:.4em 0"><code>${h(pending.qc.code)}</code></p>
<p class="muted" id="qc-status">${h(env.text("login_qc_waiting"))}</p>
<div class="actions">${Html.postButton(poll, "Check now", "secondary", fields = mapOf("t" to token))} <a class="button secondary" href="$back">Cancel</a></div></div>
<script>(function(){var e=document.getElementById('qc');if(!e||!window.fetch)return;var u=e.getAttribute('data-poll'),t=e.getAttribute('data-token');
function ask(){fetch(u,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json'},body:'t='+encodeURIComponent(t)})
.then(function(r){return r.json()}).then(function(j){if(j.state==='waiting')setTimeout(ask,3000);else location.href=j.next})
.catch(function(){setTimeout(ask,6000)})}setTimeout(ask,3000)})();</script>"""
    send(r.ex, 200, page(env.text("login_qc_title"), body))
}

/**
 * Whether the code was approved: the sign-in is saved by the engine when it was. Answers JSON to
 * the page's script ({"state":"waiting"|"done"|"error","next":…}), a redirect to a plain form.
 */
internal fun Web.quickConnectPoll(r: Web.Req, p: MusicProvider) {
    val back = "/connectors/${q(p.id)}"
    val jf = quickConnectOf(p) ?: return send(r.ex, 404, errorPage(404))
    val asJson = r.ex.requestHeaders.getFirst("Accept").orEmpty().contains("application/json")
    val token = r.form["t"].orEmpty()
    fun answer(state: String, next: String, message: String? = null) {
        if (asJson) send(r.ex, 200, buildJsonObject { put("state", state); put("next", next); message?.let { put("message", it) } }.toString(), "application/json")
        else redirect(r.ex, next)
    }
    val pending = quickConnects.get(token, p.id)
        ?: return answer("error", "$back?err=${q(env.text("jellyfin_qc_expired"))}", env.text("jellyfin_qc_expired"))
    // One question at a time per code, and an answer kept once there is one: two tabs (or a click
    // during the script's turn) sign in once and both hear how it went.
    val result = synchronized(pending.lock) {
        pending.outcome ?: runCatching { runBlocking { jf.quickConnectPoll(env, pending.qc) } }.also { res ->
            if (res.getOrNull() != false) pending.outcome = res
        }
    }
    result.exceptionOrNull()?.let { e ->
        val message = e.message ?: e.javaClass.simpleName
        return answer("error", "$back?err=${q(message)}", message)
    }
    if (result.getOrNull() == true) {
        if (pending.logged.compareAndSet(false, true)) env.log("auth", "${p.id} connected (Quick Connect)")
        return answer("done", "$back?ok=${q("Connected.")}")
    }
    answer("waiting", "$back/quickconnect?t=${q(token)}")
}
