package com.xlollx.songport.server

import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.net.json
import com.xlollx.songport.platform.KeyValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * What is running, written down (kv/running.json) so a restart knows what it cut short: a sync run
 * by hand, a long tool ([Tasks]). A mark is written when the run begins and taken away when it ends,
 * however it ends; one still there at the next start ([afterRestart]) is a run the stop of the
 * server interrupted. It is then said ("Interrupted by a restart") with a Retry button, on the web
 * page, in the API and in the command line; nothing starts again by itself, since what the run was
 * for may no longer be wanted.
 *
 * Scheduled runs are not marked: their schedule starts them again.
 */
class RunMarks(private val kv: KeyValues) {
    /** [kind]: [SYNC] ([id] is the sync's) or [TASK] ([id] is the task's, [back] the tool's page, where it is started again). */
    data class Mark(val kind: String, val id: String, val title: String, val back: String = "", val at: Long = 0, val interrupted: Boolean = false)

    private val lock = Any()

    private fun read(): List<Mark> = kv[KEY]?.let { text ->
        runCatching {
            (json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                Mark(s("kind"), s("id"), s("title"), s("back"), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0, (o["interrupted"] as? JsonPrimitive)?.booleanOrNull == true)
                    .takeIf { it.kind.isNotEmpty() && it.id.isNotEmpty() }
            }
        }.getOrNull()
    }.orEmpty()

    private fun write(marks: List<Mark>) {
        kv[KEY] = buildJsonArray {
            marks.forEach { m -> add(buildJsonObject { put("kind", m.kind); put("id", m.id); put("title", m.title); put("back", m.back); put("at", m.at); put("interrupted", m.interrupted) }) }
        }.toString()
    }

    /** A run begins: marked before it does anything. A mark of an earlier, interrupted run of the same thing gives way to it. */
    fun begin(kind: String, id: String, title: String, back: String = "", now: Long = System.currentTimeMillis()) = synchronized(lock) {
        // Never failing the run for a mark that cannot be written (a full disk says so on every page).
        runCatching { write((read().filterNot { it.kind == kind && it.id == id } + Mark(kind, id, title, back, now)).takeLast(MAX)) }
        Unit
    }

    /** The run ended (well, with an error, stopped by the person), or its interruption was read: no mark any more. */
    fun end(kind: String, id: String) = synchronized(lock) {
        val all = read()
        if (all.any { it.kind == kind && it.id == id }) runCatching { write(all.filterNot { it.kind == kind && it.id == id }) }
        Unit
    }

    /** The runs a restart interrupted, the latest first; they stay until tried again or dismissed. */
    fun interrupted(): List<Mark> = synchronized(lock) { read().filter { it.interrupted }.sortedByDescending { it.at } }

    fun interrupted(kind: String, id: String): Mark? = interrupted().firstOrNull { it.kind == kind && it.id == id }

    /**
     * At the start of the server: every mark left by the previous one is a run it cut short, unless
     * [live] says it still runs (a person's engine started again inside a server that kept going).
     * Returns the ones newly found; nothing is started.
     */
    fun afterRestart(live: (Mark) -> Boolean = { false }): List<Mark> = synchronized(lock) {
        val all = read()
        val found = all.filter { !it.interrupted && !live(it) }
        if (found.isNotEmpty()) runCatching { write(all.map { if (it in found) it.copy(interrupted = true) else it }) }
        found
    }

    companion object {
        const val SYNC = "sync"
        const val TASK = "task"
        const val TEXT = "Interrupted by a restart"
        private const val KEY = "marks"
        private const val MAX = 50

        /**
         * [env]'s runs the last stop of the server cut short, found once at its start: each is written
         * in the log, and an interrupted sync gets a report in its history saying so (its card, the
         * API and the command line then show it as its last result). Nothing is run again.
         */
        fun afterRestart(env: ServerEnv, now: Long = System.currentTimeMillis()): List<Mark> {
            val found = env.marks.afterRestart { m ->
                if (m.kind == SYNC) m.id in com.xlollx.songport.sync.SyncState.running.value else Tasks.get(m.id)?.done == false
            }
            for (m in found) {
                env.log("server", "$TEXT: ${m.title} (${if (m.kind == SYNC) "a sync run by hand" else "a tool"}); it is not started again by itself")
                if (m.kind != SYNC) continue
                val job = env.store.job(m.id)
                // A sync deleted meanwhile has nothing to retry.
                if (job == null) { env.marks.end(SYNC, m.id); continue }
                env.store.addReport(
                    SyncReport(
                        id = UUID.randomUUID().toString(), jobId = job.id, jobName = job.name,
                        startedEpoch = m.at.takeIf { it > 0 } ?: now, durationMs = (now - m.at).coerceAtLeast(0).takeIf { m.at > 0 } ?: 0,
                        error = "$TEXT of Trackhop: this run did not finish. What it had already added stays; nothing was removed by the stop. Run it again when you want.",
                    ),
                )
            }
            if (found.isNotEmpty()) env.store.flush()
            return found
        }
    }
}
