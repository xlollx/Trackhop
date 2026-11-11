package com.xlollx.songport.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The scope of work a page starts that must outlive the page and its activity (see [KeptWork]). */
internal object AppWork {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

/**
 * A lookup a page starts (records, concerts, a roast, two libraries compared), kept outside the
 * page: it goes on when the screen turns, the theme or the text size changes, the window is split,
 * or another tab is opened, and the page, drawn again, shows how far it is and then the result.
 * In the app's scope, like [ReleasesKept] and [WrappedRun]; one run at a time. Leaving the page
 * does not stop it: it ends by itself, and its result is there when the page opens again. The
 * user can [stop] it: what it found so far ([keep]) becomes the result.
 */
internal class KeptWork<T>(private val scope: CoroutineScope = AppWork.scope, private val now: () -> Long = System::currentTimeMillis) {
    /** "done/total" while it runs (0/0 before the first step); null when idle. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    val running: Boolean get() = progress != null

    var result by mutableStateOf<T?>(null)
        private set

    /** What [result] was asked for (a playlist's id): the page drops a result of another one. */
    var key by mutableStateOf<String?>(null)
        private set

    /** When [result] arrived. */
    var at = 0L
        private set

    /** A failure not shown yet ("" when it had no message): the page shows it once, then sets it to null. */
    var error by mutableStateOf<String?>(null)

    /** The run was stopped by the user when it was at "done/total" ([stop]); null otherwise. */
    var stopped by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    private var job: Job? = null
    private var runKey: String? = null
    private var found: T? = null
    private var runs = 0

    /** Starts [work] for [key] unless a run is going on (then false). [work] reports its steps to the function it is given. */
    fun start(key: String?, work: suspend (progress: (done: Int, total: Int) -> Unit) -> T): Boolean {
        if (job?.isActive == true) return false
        progress = 0 to 0
        error = null
        stopped = null
        runKey = key
        found = null
        // A stopped run may still be finishing a request: it no longer touches what the page shows.
        val run = ++runs
        job = scope.launch {
            try {
                val r = work { done, total -> if (run == runs) progress = done to total }
                if (run == runs) {
                    result = r
                    this@KeptWork.key = key
                    at = now()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (run == runs) error = e.message.orEmpty()
            } finally {
                if (run == runs) progress = null
            }
        }
        return true
    }

    /** Shows [result] for [key] at once (one put together from what was kept), unless a run is going on. */
    fun show(key: String?, result: T) {
        if (job?.isActive == true) return
        this.result = result; this.key = key; at = now(); stopped = null
    }

    /** Stops the run going on, if any; the last result stays. */
    fun cancel() { job?.cancel() }

    /** What the run going on has found so far: the result if the user [stop]s it. */
    fun keep(partial: T) { found = partial }

    /**
     * The user stops the run going on: its requests are cancelled, what it found so far ([keep])
     * becomes the result, and another run can start at once.
     */
    fun stop() {
        val j = job?.takeIf { it.isActive } ?: return
        val at = progress ?: (0 to 0)
        j.cancel()
        runs++
        progress = null
        found?.let { result = it; key = runKey; this.at = now() }
        found = null
        stopped = at
    }

    /** Forgets the result (another playlist, country or window was picked). */
    fun clear() { result = null; key = null; stopped = null }
}
