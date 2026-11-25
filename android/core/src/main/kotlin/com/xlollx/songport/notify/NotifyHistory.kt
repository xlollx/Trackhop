package com.xlollx.songport.notify

import com.xlollx.songport.data.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The notices Trackhop gave, newest first: the app's bell and the server's Notifications page.
 * The app keeps every notice it posts, also when Android's notifications are off for it; the
 * server keeps what it sent to the notification channels, with which channel took it.
 *
 * One small file (`notifications.json`) in the data folder, beside the store and apart from it: it
 * is not part of any backup (a recent log of notices, whose links point at things of this device).
 * Read and written on a thread of its own, never the caller's: [entries] starts empty and fills in
 * once the file is read; changes asked before that wait for it, so they apply on top of what it held.
 * [KEEP_DAYS] days and at most [MAX] entries are kept.
 */
class NotifyHistory(private val file: File, private val now: () -> Long = System::currentTimeMillis) {

    /** Where one notice went on the server: a channel's name, and why it did not arrive (null when it did). */
    @Serializable
    data class Sent(val channel: String, val error: String? = null)

    @Serializable
    data class Entry(
        val id: String,
        val at: Long,
        /** What it is about, for its icon: "sync", "failed", "review", "unplayable", "alerts", "concert", "record", "signin", "notice", "update"… */
        val kind: String,
        val title: String,
        val text: String = "",
        /** [NotifTarget.encode]: where a tap leads; null opens nothing. */
        val target: String? = null,
        val read: Boolean = false,
        val sent: List<Sent> = emptyList(),
    )

    @Serializable
    private data class Saved(val entries: List<Entry> = emptyList())

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "notify-history").apply { isDaemon = true } }
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> get() = _entries

    init {
        io.execute { _entries.value = prune(load(), now()) }
    }

    private fun load(): List<Entry> = runCatching {
        if (!file.isFile) emptyList() else JSON.decodeFromString(Saved.serializer(), file.readText()).entries
    }.getOrDefault(emptyList())

    private fun change(f: (List<Entry>) -> List<Entry>) {
        io.execute {
            val next = prune(f(_entries.value), now())
            _entries.value = next
            runCatching { AtomicFiles.write(file, JSON.encodeToString(Saved.serializer(), Saved(next))) }
        }
    }

    /** A new notice on top; its id (a tap on the app's notification marks it read by it). */
    fun add(kind: String, title: String, text: String = "", target: NotifTarget? = null, sent: List<Sent> = emptyList(), read: Boolean = false): String {
        val e = Entry(java.util.UUID.randomUUID().toString(), now(), kind, title, text, target?.encode(), read, sent)
        change { listOf(e) + it }
        return e.id
    }

    /**
     * As [add], unless the same notice (kind, title, target) is still unread there: its id then. For notices a background
     * check may repeat many times while it cannot reach the user (a "sign in again" with Android's notifications off).
     */
    fun addOnce(kind: String, title: String, text: String = "", target: NotifTarget? = null): String {
        val t = target?.encode()
        fun same(e: Entry) = !e.read && e.kind == kind && e.title == title && e.target == t
        _entries.value.firstOrNull(::same)?.let { return it.id }
        val e = Entry(java.util.UUID.randomUUID().toString(), now(), kind, title, text, t, false, emptyList())
        change { l -> if (l.any(::same)) l else listOf(e) + l }
        return e.id
    }

    fun markRead(id: String) = change { l -> l.map { if (it.id == id && !it.read) it.copy(read = true) else it } }
    fun markAllRead() = change { l -> if (l.none { !it.read }) l else l.map { if (it.read) it else it.copy(read = true) } }
    fun clear() = change { emptyList() }

    /** Waits for the changes asked so far to be read and written (tests, and the server before it answers). */
    fun settle() {
        runCatching { io.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) }
    }

    companion object {
        const val FILE = "notifications.json"
        const val KEEP_DAYS = 30
        const val MAX = 200
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val all = ConcurrentHashMap<String, NotifyHistory>()

        /** The history of the data folder [dir]: one per folder (each person's engine has its own on a shared server). */
        fun of(dir: File): NotifyHistory = all.getOrPut(dir.absolutePath) { NotifyHistory(File(dir, FILE)) }

        fun of(env: com.xlollx.songport.platform.Env): NotifyHistory = of(env.dataDir)

        /** Newest first, nothing older than [KEEP_DAYS] days, at most [MAX]. */
        fun prune(l: List<Entry>, now: Long): List<Entry> {
            val since = now - KEEP_DAYS * 24L * 60 * 60 * 1000
            return l.filter { it.at >= since }.sortedByDescending { it.at }.distinctBy { it.id }.take(MAX)
        }

        fun unread(l: List<Entry>): Int = l.count { !it.read }

        /** What the bell shows on its corner: the update's arrow wins over the count; "99+" past 99. */
        fun badge(unread: Int, update: Boolean): String? = when {
            update -> "↑"
            unread <= 0 -> null
            unread > 99 -> "99+"
            else -> unread.toString()
        }
    }
}
