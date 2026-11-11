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
 * does not stop it: it ends by itself, and its result is there when the page opens again.
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

    private var job: Job? = null

    /** Starts [work] for [key] unless a run is going on (then false). [work] reports its steps to the function it is given. */
    fun start(key: String?, work: suspend (progress: (done: Int, total: Int) -> Unit) -> T): Boolean {
        if (job?.isActive == true) return false
        progress = 0 to 0
        error = null
        job = scope.launch {
            try {
                val r = work { done, total -> progress = done to total }
                result = r
                this@KeptWork.key = key
                at = now()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message.orEmpty()
            } finally {
                progress = null
            }
        }
        return true
    }

    /** Stops the run going on, if any; the last result stays. */
    fun cancel() { job?.cancel() }

    /** Forgets the result (another playlist, country or window was picked). */
    fun clear() { result = null; key = null }
}
