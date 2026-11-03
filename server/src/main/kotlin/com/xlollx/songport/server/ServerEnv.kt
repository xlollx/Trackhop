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

    override val store: FileStore = FileStore(dataDir).also { FileStore.install(it) }
    override val tokens: TokenVault = JsonTokenVault(File(dataDir, "tokens.json"))
    override val redirectUri: String get() = "$baseUrl/callback"

    override fun text(name: String, vararg args: Any): String = SERVER_TEXTS[name]?.let { if (args.isEmpty()) it else String.format(it, *args) } ?: strings.text(name, *args)
    fun textArray(name: String): List<String> = strings.array(name)
    fun hasText(name: String) = strings.has(name)

    override fun log(tag: String, message: String) = Log.add(tag, message)

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

    private val kv = ConcurrentHashMap<String, KeyValues>()
    override fun keyValues(name: String): KeyValues =
        kv.getOrPut(name) { JsonKeyValues(File(File(dataDir, "kv"), "${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")) }

    override fun shrinkJpeg(raw: ByteArray, maxSide: Int, maxBytes: Int): ByteArray? {
        val src = runCatching { ImageIO.read(ByteArrayInputStream(raw)) }.getOrNull() ?: return null
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null)
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
    "provider_note_liveone" to "LiveOne through its web player, signed in with the token of a browser session. Unofficial, alpha: a free account syncs its presets (add only).",
    "provider_note_anghami_web" to "Anghami through its web player, signed in with the session of a browser. Unofficial, alpha: playlists of your own and liked songs, read and written.",
    "note_file_target" to "Saved inside Trackhop as a CSV file: download it from Services › File.",
    // The app says "from the preview"; here the sync's card has the button.
    "error_target_shrunk" to "%1\$s lists only %2\$d tracks, but the last run left %3\$d there: probably an incomplete answer. Nothing was changed. " +
        "Try again later; if the playlist really is that small now, choose Sync anyway.",
)
