package com.xlollx.songport.sync

import com.xlollx.songport.providers.*
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.Store
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Backup fuori dal telefono: i file di backup (una versione datata per playlist, vedi
 * [LocalFilesProvider.writeBackup]) vengono copiati in una cartella scelta dall'utente con il
 * selettore di sistema: scheda SD, Nextcloud, Drive, qualunque app che offra una cartella. Si copia
 * solo cio' che nella cartella ancora manca, per nome, quindi ripetere l'operazione costa poco e la
 * cartella conserva tutte le versioni che il telefono ha avuto. Ripristinare = importare un file da li'.
 */
object BackupExport {

    data class Result(val services: Int, val copied: Int, val failed: List<String>, val folderMissing: Boolean = false)

    /** L'esito dell'ultima copia, per la schermata: quando e' andata bene, o cosa non e' andato. */
    data class State(val lastOk: Long, val lastError: String?)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("backup_state", Context.MODE_PRIVATE)
    fun state(ctx: Context): State = prefs(ctx).let {
        // Versions up to 1.2.1 stored a cancelled copy (screen left mid-way) as an error; it never was one.
        val error = it.getString("error", null)?.takeUnless { e -> "left the composition" in e || e.startsWith("Job was cancelled") }
        State(it.getLong("ok", 0), error)
    }
    private fun remember(ctx: Context, error: String?) {
        prefs(ctx).edit().apply { if (error == null) putLong("ok", System.currentTimeMillis()).remove("error") else putString("error", error) }.apply()
    }

    /**
     * La cartella e' ancora raggiungibile: esiste e il permesso persistente vale ancora. Una cartella
     * cancellata, una scheda SD tolta o un'app di cloud disinstallata rispondono qui, non a meta' copia.
     */
    fun folderReachable(ctx: Context, tree: Uri): Boolean = runCatching {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /**
     * Solo la copia: i file locali nuovi vanno nella cartella scelta, se ce n'e' una. Lo usa il backup
     * di un singolo servizio in Strumenti, che aggiorna i file da se'. @return quanti ne ha copiati.
     * @throws ProviderException se la cartella non e' piu' raggiungibile.
     */
    suspend fun copyNow(ctx: Context): Int {
        val folder = Store.get(ctx).data.settings.backupFolder.takeIf { it.isNotBlank() } ?: return 0
        if (!folderReachable(ctx, Uri.parse(folder))) {
            val msg = ctx.getString(com.xlollx.songport.R.string.backup_folder_missing)
            Diagnostics.log(ctx, "backup", "folder not reachable: $folder")
            remember(ctx, msg)
            throw com.xlollx.songport.model.ProviderException(msg)
        }
        return try {
            copyNew(ctx, Uri.parse(folder)).also { remember(ctx, null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log(ctx, "backup", "folder copy failed: ${e.message}")
            remember(ctx, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * "Copy now" from Settings, in the app's own scope: it keeps going when the user leaves the
     * screen (it used to be cancelled with it, and the cancellation was then shown as the last
     * error). [running] and [step] drive the row; [finished] carries the outcome for a snackbar.
     */
    val running = MutableStateFlow(false)
    val step = MutableStateFlow("")
    val finished = MutableStateFlow<Result?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(ctx: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        step.value = ""
        scope.launch {
            try {
                finished.value = run(app) { step.value = it }
            } finally {
                running.value = false
                step.value = ""
            }
        }
    }

    /**
     * One run at a time: the scheduled one and "Copy now" used to overlap, each writing the same
     * playlists' versions within the same minutes while the connectors served one listing at a time.
     */
    private val runLock = Mutex()

    /**
     * Aggiorna i backup locali di ogni servizio collegato; con una cartella scelta, ci copia i file
     * nuovi e la configurazione del giorno (Backup & restore).
     */
    suspend fun run(ctx: Context, onProgress: (String) -> Unit = {}): Result = runLock.withLock { runAlone(ctx, onProgress) }

    private suspend fun runAlone(ctx: Context, onProgress: (String) -> Unit): Result {
        val folder = Store.get(ctx).data.settings.backupFolder.takeIf { it.isNotBlank() }
        if (folder != null && !folderReachable(ctx, Uri.parse(folder))) {
            val msg = ctx.getString(com.xlollx.songport.R.string.backup_folder_missing)
            Diagnostics.log(ctx, "backup", "folder not reachable: $folder")
            remember(ctx, msg)
            return Result(0, 0, listOf(msg), folderMissing = true)
        }
        val failed = ArrayList<String>()
        var services = 0
        for (p in Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx) }) {
            onProgress(p.label(ctx))
            try {
                val r = Tools.backupAll(ctx, p)
                // The screen has one line: how many the service would not hand over, not each reason.
                if (r.failures.isNotEmpty()) failed += p.displayName + ": " + ctx.resources.getQuantityString(com.xlollx.songport.R.plurals.tools_backup_unread, r.failures.size, r.failures.size)
                services++
            } catch (e: CancellationException) {
                // Stopped, not failed: nothing to report, and the next run carries on.
                throw e
            } catch (e: Exception) {
                failed += "${p.displayName}: ${e.message}"
            }
        }
        var copyError: String? = null
        val copied = if (folder == null) 0 else try { copyNew(ctx, Uri.parse(folder)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Diagnostics.log(ctx, "backup", "folder copy failed: ${e.message}")
            copyError = e.message ?: e.javaClass.simpleName
            failed += copyError
            0
        }
        Diagnostics.log(ctx, "backup", "export: $services services, $copied new files" + (if (failed.isNotEmpty()) ", ${failed.size} failed" else ""))
        // A copy that reached the folder counts as good, even if one service failed to answer.
        remember(ctx, copyError ?: failed.firstOrNull()?.takeIf { services == 0 })
        return Result(services, copied, failed)
    }

    /**
     * Whether [name] is to be written to the folder: it is not there yet, or it is the one whose copy
     * was under way when the app was closed ([cutShort]): what the folder holds under that name may
     * be half a file, which "already there, by name" would otherwise keep for good.
     */
    internal fun needsCopy(name: String, inFolder: Set<String>, cutShort: String?): Boolean = name !in inFolder || name == cutShort

    /** The file being copied right now, kept until its copy ends: found at the next run, the app was closed half way. */
    private const val COPYING = "copying"

    /** I file locali che nella cartella non ci sono ancora, per nome. @return quanti ne ha scritti. */
    private suspend fun copyNew(ctx: Context, tree: Uri): Int = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val dirId = DocumentsContract.getTreeDocumentId(tree)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val existing = HashMap<String, String>() // name to document id
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
        )?.use { c -> while (c.moveToNext()) existing[c.getString(0)] = c.getString(1) }
        val cutShort = prefs(ctx).getString(COPYING, null)
        if (cutShort != null) Diagnostics.log(ctx, "backup", "the copy of $cutShort was stopped half way last time: made again")

        /** [name] written whole, or not there: a failure removes what was written and is thrown on (DocumentWrite). */
        fun put(name: String, mime: String, write: (java.io.OutputStream) -> Unit): Boolean {
            if (!needsCopy(name, existing.keys, cutShort)) return false
            // Half a file from last time: taken away first, so the new one keeps the name.
            existing[name]?.let { id -> runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } }
            prefs(ctx).edit().putString(COPYING, name).commit()
            try {
                val doc = DocumentsContract.createDocument(resolver, dirUri, mime, name) ?: return false
                com.xlollx.songport.data.DocumentWrite.to(ctx, doc, write)
            } finally {
                // Reached unless the app is closed meanwhile: then the name stays noted for the next run.
                prefs(ctx).edit().remove(COPYING).commit()
            }
            return true
        }

        var copied = 0
        for (f in LocalFilesProvider.dir(ctx).listFiles { it -> it.extension == "csv" }.orEmpty()) {
            if (put(f.name, "text/csv") { out -> f.inputStream().use { it.copyTo(out) } }) copied++
        }
        // The set-up of the day, once a day: restoring the folder gives the syncs back, not only the playlists.
        val config = com.xlollx.songport.data.ConfigBackup.fileName()
        if (needsCopy(config, existing.keys, cutShort)) {
            val env = com.xlollx.songport.platform.AndroidEnv.of(ctx)
            val text = com.xlollx.songport.notify.Notify.intoBackup(env, com.xlollx.songport.data.ConfigBackup.export(env, "Trackhop ${com.xlollx.songport.BuildConfig.VERSION_NAME} (Android)", deviceName = android.os.Build.MODEL.orEmpty()))
            if (put(config, "application/octet-stream") { it.write(text.toByteArray()) }) copied++
        }
        copied
    }

    /** Nome leggibile della cartella scelta, dall'ultimo pezzo del suo id ("primary:Music/Trackhop" -> "Music/Trackhop"). */
    fun folderLabel(uriString: String): String = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(uriString)).substringAfter(':').ifBlank { uriString }
    }.getOrDefault(uriString)
}
