package com.xlollx.songport.sync

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Who is reading a whole playlist through a connector that lists one playlist at a time
 * ([com.xlollx.songport.providers.MusicProvider.readGate]: the web connectors keep the listing they
 * fetch, and fetch it under a lock). A second read on the same gate waits for the first: nothing
 * here changes that, it only says who holds the gate and since when, so a preview can tell the user
 * what it is waiting for instead of spinning.
 *
 * The lock itself is not fair, so with three readers the one shown as holding may not be the one
 * the connector serves next: the order of arrival is the best guess there is.
 */
object ServiceReads {
    /** One read under way, or waiting: the sync it is for, the service it reads (its display name), since when. */
    data class Reader(val jobId: String, val jobName: String, val service: String, val since: Long)

    /** A [Reader] on its gate; [seq] tells two entries of the same sync apart (a preview of a running sync). */
    class Entry internal constructor(val gate: String, val reader: Reader, internal val seq: Long)

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private var next = 0L

    /** Every read on a gate, in the order they came. */
    val state: StateFlow<List<Entry>> get() = entries

    fun enter(gate: String, reader: Reader): Entry {
        val e = synchronized(this) { Entry(gate, reader, next++) }
        entries.update { it + e }
        return e
    }

    fun leave(entry: Entry) = entries.update { list -> list.filterNot { it === entry } }

    /** The reader holding [gate] (the first that came and has not finished), or null when it is free. */
    fun holder(gate: String, all: List<Entry> = entries.value): Reader? = all.firstOrNull { it.gate == gate }?.reader

    /** The reader [entry] waits behind: the one holding its gate, when that is not [entry] itself. */
    fun ahead(entry: Entry, all: List<Entry> = entries.value): Reader? =
        all.firstOrNull { it.gate == entry.gate }?.takeIf { it !== entry }?.reader

    /**
     * Runs [block], a read on [gate], noted as [reader]'s. [onWait] hears the reader it waits behind
     * whenever that changes, and null once it is its turn (or the read is over). Without a gate
     * [block] simply runs.
     */
    suspend fun <T> reading(gate: String?, reader: Reader, onWait: (Reader?) -> Unit, block: suspend () -> T): T {
        if (gate == null) return block()
        val me = enter(gate, reader)
        try {
            return coroutineScope {
                val watch = launch { entries.map { ahead(me, it) }.distinctUntilChanged().collect { onWait(it) } }
                try { block() } finally { watch.cancel() }
            }
        } finally {
            leave(me)
            onWait(null)
        }
    }
}
