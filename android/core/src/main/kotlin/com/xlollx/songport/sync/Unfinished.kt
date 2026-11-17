package com.xlollx.songport.sync

import com.xlollx.songport.data.AtomicFiles
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.json
import com.xlollx.songport.platform.Env
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * What a tool was in the middle of, written on the disk before the step that a closed app, a
 * restart or a dead battery could cut in two, and taken away once the step is whole. What is still
 * there at the next start is finished then ([LibraryTools.resumePutBacks]) or offered to the
 * person ([LibraryTools.continueHalfMade]).
 *
 * Files of their own (`unfinished/<name>.json` in the data folder), written whole and pushed to the
 * disk ([AtomicFiles]) before the call returns: the app's small key-value store writes in the
 * background, which is too late for a process killed a moment after.
 */
object Unfinished {
    /** A song whose every copy "Remove duplicates" takes out of [playlistId], to put back once. */
    @Serializable
    data class PutBack(val provider: String, val playlistId: String, val track: Track, val at: Long = 0)

    /** A playlist a tool made on [provider] and what it meant to put in it, in order. */
    @Serializable
    data class HalfMade(val provider: String, val playlistId: String, val name: String, val tracks: List<Track>, val at: Long = 0)

    internal class Journal<T>(private val name: String, serializer: KSerializer<T>, private val max: Int, private val at: (T) -> Long) {
        private val list = ListSerializer(serializer)
        private fun file(ctx: Env) = File(File(ctx.dataDir, DIR), "$name.json")

        /** The entries, the oldest first; none when the file is absent or cannot be read. Old ones are left out. */
        fun read(ctx: Env, now: Long = System.currentTimeMillis()): List<T> = synchronized(this) {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            runCatching { json.decodeFromString(list, f.readText()) }.getOrDefault(emptyList()).filter { now - at(it) < KEEP_MS }
        }

        fun change(ctx: Env, fn: (List<T>) -> List<T>) = synchronized(this) {
            val before = read(ctx)
            val after = fn(before).takeLast(max)
            if (after != before) AtomicFiles.write(file(ctx), json.encodeToString(list, after))
        }
    }

    internal val putBacks = Journal("put-back", PutBack.serializer(), 200) { it.at }
    internal val halfMade = Journal("half-made", HalfMade.serializer(), 10) { it.at }

    private const val DIR = "unfinished"
    /** An entry nobody could finish in a month is dropped: the playlist has moved on since. */
    private const val KEEP_MS = 30L * 24 * 3_600_000
}
