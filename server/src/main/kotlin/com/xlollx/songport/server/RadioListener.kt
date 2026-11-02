package com.xlollx.songport.server

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.RadioTitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Radio syncs: while a sync's window is open, Trackhop listens to its station's stream, reads the
 * title of each song from the metadata Icecast and Shoutcast send between blocks of audio (the
 * audio itself is read and dropped, never kept), notes every new song in the sync's source (a File
 * playlist) and runs the sync now and then, so the songs reach the target during the window, plus
 * once when it closes.
 */
class RadioListener(private val env: ServerEnv, private val runner: Runner) {

    /** What a listening sync is doing, for its card. */
    data class Status(val since: Long, @Volatile var lastTitle: String? = null, @Volatile var error: String? = null, @Volatile var songs: Int = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listening = ConcurrentHashMap<String, Job>()
    val status = ConcurrentHashMap<String, Status>()
    /** When each radio sync last ran because of new songs: not more than once every [RUN_EVERY_MS]. */
    private val lastPush = ConcurrentHashMap<String, Long>()

    fun stop() = scope.cancel()

    fun start() {
        scope.launch {
            while (isActive) {
                runCatching { tick(LocalDateTime.now()) }.onFailure { env.log("radio", "tick: ${it.message}") }
                delay(TICK_MS)
            }
        }
    }

    /** Starts the syncs whose window is open, stops the others (with a last run as their window closes). */
    fun tick(now: LocalDateTime) {
        val radios = env.store.data.jobs.filter { it.radio != null }
        val open = radios.filter { it.enabled && RadioTitles.inWindow(it.radio!!, now) }.map { it.id }.toSet()
        for (id in listening.keys - open) {
            listening.remove(id)?.cancel()
            status.remove(id)
            env.store.job(id)?.let { job -> env.log("radio", "${job.name}: window closed"); runner.start(job, scheduled = true) }
        }
        for (job in radios.filter { it.id in open && !listening.containsKey(it.id) }) {
            status[job.id] = Status(System.currentTimeMillis())
            // Registered before it starts: the loop runs while its id is in the map.
            val listener = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { listen(job.id) }
            listening[job.id] = listener
            listener.start()
            env.log("radio", "${job.name}: listening to ${job.radio!!.station}")
        }
    }

    fun isListening(jobId: String) = listening.containsKey(jobId)

    private suspend fun listen(jobId: String) {
        var wait = 15_000L
        while (scope.isActive && listening.containsKey(jobId)) {
            val job = env.store.job(jobId) ?: return
            val st = status.getOrPut(jobId) { Status(System.currentTimeMillis()) }
            try {
                read(job) { title -> onTitle(job.id, title) }
                wait = 15_000L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                st.error = e.message ?: e.javaClass.simpleName
                env.log("radio", "${job.name}: ${st.error}; trying again in ${wait / 1000} s")
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(5 * 60_000L)
        }
    }

    /** Reads [job]'s stream until it ends, handing each new StreamTitle to [onTitle]. */
    private fun read(job: SyncJob, onTitle: (String) -> Unit) {
        val conn = URI(job.radio!!.stream).toURL().openConnection() as HttpURLConnection
        conn.setRequestProperty("Icy-MetaData", "1")
        conn.setRequestProperty("User-Agent", "Trackhop")
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("the station answered ${conn.responseCode}")
            val metaInt = conn.getHeaderField("icy-metaint")?.trim()?.toIntOrNull()
                ?: throw IllegalStateException("this stream sends no song titles (no ICY metadata)")
            status[job.id]?.error = null
            val input = conn.inputStream.buffered()
            var last: String? = null
            while (listening.containsKey(job.id)) {
                skip(input, metaInt.toLong())
                val len = input.read().takeIf { it >= 0 } ?: return
                if (len == 0) continue
                val block = ByteArray(len * 16)
                readFully(input, block)
                val title = RadioTitles.streamTitle(String(block, Charsets.UTF_8)) ?: continue
                if (title != last) { last = title; onTitle(title) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) { if (input.read() < 0) throw IllegalStateException("the stream ended"); left-- } else left -= skipped
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalStateException("the stream ended")
            off += n
        }
    }

    /** A new title on air: noted in the sync's source when it is a song not seen before. */
    private fun onTitle(jobId: String, title: String) {
        val job = env.store.job(jobId) ?: return
        val st = status[jobId]
        st?.lastTitle = RadioTitles.display(title, job.radio!!.station)
        val track = RadioTitles.track(title, job.radio!!.station) ?: return
        val playlist = job.source.playlistId ?: return
        val added = runBlocking {
            val seen = LocalFilesProvider.tracks(env, playlist).map { RadioTitles.key(it) }.toHashSet()
            if (RadioTitles.key(track) in seen) false else { LocalFilesProvider.addTracks(env, playlist, listOf(track)); true }
        }
        if (!added) return
        st?.songs = (st?.songs ?: 0) + 1
        env.log("radio", "${job.name}: ${track.artistLine} - ${track.title}")
        val now = System.currentTimeMillis()
        if (now - (lastPush[jobId] ?: 0) >= RUN_EVERY_MS) {
            lastPush[jobId] = now
            runner.start(job, scheduled = true)
        }
    }

    companion object {
        private const val TICK_MS = 30_000L
        /** New songs reach the target in batches: a run at most every 10 minutes while listening. */
        const val RUN_EVERY_MS = 10 * 60_000L
    }
}
