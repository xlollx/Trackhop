package com.xlollx.songport.server

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.TokenVault
import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.KeyValues
import com.xlollx.songport.providers.MusicProvider
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The engine's platform on a server: everything lives in [dataDir] (the container's volume), texts
 * come from the app's strings.xml, the log goes to standard output and to a file, and a sign-in
 * is a page of the web interface rather than an Android screen.
 */
class ServerEnv(
    override val dataDir: File,
    private val strings: Strings,
    /** Where the web interface is reached from the browser, without a trailing slash. */
    val baseUrl: String,
    /** What this person may do on a shared server ([Permissions]); null: everything. */
    val permissions: Set<String>? = null,
) : Env {
    /** True when this person may use [permission] ([Permissions]). */
    fun may(permission: String) = permissions == null || permission in permissions

    /** Who this is among the people of a shared server: each has their own data folder. */
    val owner: String get() = dataDir.absolutePath
    /** Whose background runs ([Tasks]) this person sees: their own, or everyone's (null) for an admin. */
    val sees: String? get() = if (may(Permissions.ADMIN)) null else owner

    override val store: FileStore = FileStore(dataDir).also { FileStore.install(it) }
    override val tokens: TokenVault = JsonTokenVault(File(dataDir, "tokens.json"))

    init {
        store.damagedCopy?.let { Log.add("storage", "store.json (the syncs, their history and the settings) could not be read, so Trackhop started empty; the unreadable file is kept as ${it.name}") }
    }

    /**
     * What went wrong with the saved state, in plain words for the pages; null when all is well: the
     * state or the sign-ins could not be read at the start (kept aside, never written over), or the
     * last write failed (a full disk), so what is on the screen is not saved yet.
     */
    fun storageTrouble(): String? = when {
        store.damagedCopy != null -> "Trackhop could not read its saved syncs and settings, so it started empty. Nothing was deleted: the unreadable file is kept as ${store.damagedCopy?.name} in the data folder. Restore a backup from Tools › Backup & restore."
        (tokens as? JsonTokenVault)?.damaged == true -> "Trackhop could not read its saved sign-ins, so the services have to be connected again. The unreadable file is kept beside tokens.json in the data folder."
        com.xlollx.songport.shared.SharedGroups.unreadable.keys.any { id -> store.data.groups.any { it.id == id } } ->
            "Trackhop could not read the saved state of a shared playlist (${store.data.groups.filter { it.id in com.xlollx.songport.shared.SharedGroups.unreadable.keys }.joinToString(", ") { it.name }}). Nothing was deleted: the unreadable file is kept aside in the data folder, and the state is read again from the group at the next sync."
        store.writeError != null -> "Trackhop cannot save its changes (${store.writeError}): the disk may be full. What you see is kept only until Trackhop stops; free some space and it is saved by itself."
        else -> null
    }
    override val redirectUri: String get() = "$baseUrl/callback"

    override fun text(name: String, vararg args: Any): String = SERVER_TEXTS[name]?.let { if (args.isEmpty()) it else String.format(it, *args) } ?: strings.text(name, *args)
    fun textArray(name: String): List<String> = strings.array(name)
    fun hasText(name: String) = strings.has(name)
    /** The plural form of [name] for [count] ([Strings.plural]). */
    fun plural(name: String, count: Int, vararg args: Any): String = strings.plural(name, count, *args)
    /** The language the pages speak. */
    val locale: java.util.Locale get() = strings.locale

    override fun log(tag: String, message: String) = Log.add(tag, message)

    /** "No internet" here is the server's own connection: the reports say so. */
    override val onServer: Boolean get() = true

    /**
     * A connector asks to sign in. Nothing can be opened from here: the request is noted for the web
     * page that asked ([requestedAuth]), which then shows the service's page or the credentials form.
     */
    override fun startAuth(provider: MusicProvider, mode: AuthMode) {
        requested.set(provider to mode)
    }

    private val requested = ThreadLocal<Pair<MusicProvider, AuthMode>?>()

    /** How [provider] wants to sign in, asked to the connector itself (YouTube picks its own way). */
    fun requestedAuth(provider: MusicProvider): AuthMode? {
        requested.set(null)
        provider.startAuth(this)
        return requested.get()?.second.also { requested.set(null) }
    }

    /** What the start of the server finished by itself (a song put back after "Remove duplicates" was cut short), for the Tools page; null when nothing. */
    @Volatile var startNote: String? = null

    /** What runs now, written down so the next start knows what a stop of the server cut short ([RunMarks]). */
    val marks: RunMarks by lazy { RunMarks(keyValues("running")) }

    private val kv = ConcurrentHashMap<String, KeyValues>()
    override fun keyValues(name: String): KeyValues =
        kv.getOrPut(name) { JsonKeyValues(File(File(dataDir, "kv"), "${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")) }

    override fun squareJpeg(raw: ByteArray, minSide: Int, maxSide: Int, maxBytes: Int): ByteArray? {
        val src = runCatching { ImageIO.read(ByteArrayInputStream(raw)) }.getOrNull() ?: return null
        val sq = com.xlollx.songport.sync.Covers.square(src.width, src.height, minSide, maxSide)
        val img = BufferedImage(sq.side, sq.side, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, sq.side, sq.side, sq.x, sq.y, sq.x + sq.size, sq.y + sq.size, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        try {
            // Quality steps down until the bytes fit; a cover that never fits is not sent.
            for (q in floatArrayOf(0.85f, 0.70f, 0.55f, 0.40f)) {
                val out = ByteArrayOutputStream()
                ImageIO.createImageOutputStream(out).use { ios ->
                    writer.output = ios
                    val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = q }
                    writer.write(null, IIOImage(img, null, null), param)
                }
                if (out.size() <= maxBytes) return out.toByteArray()
            }
        } finally {
            writer.dispose()
        }
        return null
    }
}

/**
 * The engine's texts that point to the app's own screens, said the way the web page works instead.
 * In English, like the rest of the page.
 */
private val SERVER_TEXTS = mapOf(
    "provider_note_liveone" to "LiveOne through its web player, signed in with the token of a browser session. Unofficial, beta: a free account syncs its presets (add only).",
    "provider_note_anghami_web" to "Anghami through its web player, signed in with the session of a browser. Unofficial, beta: playlists of your own and liked songs, read and written.",
    "provider_note_gaana_web" to "Gaana through its website, signed in with the session of a browser. Unofficial, alpha: playlists of your own and favourite songs, read and written; matching by ISRC.",
    "provider_note_qobuz_web" to "Qobuz through its web player, signed in with the session of a browser. Unofficial, beta: playlists of your own and favourite tracks, read and written; search by ISRC.",
    "note_file_target" to "Saved inside Trackhop as a CSV file: download it from Services › File.",
    // The app says "from the preview"; here the sync's card has the button.
    "error_target_shrunk" to "%1\$s lists only %2\$d tracks, but the last run left %3\$d there: probably an incomplete answer. Nothing was changed. " +
        "Try again later; if the playlist really is that small now, choose Sync anyway.",
)
