package com.xlollx.songport.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * A piece of work that belongs to the app, not to the screen that asked for it: it goes on when the
 * screen is left, and its outcome waits in [finished] for whichever screen is showing next (as
 * [BackupExport] does for a backup). One at a time: a second start while one runs is refused.
 */
class AppRun<T>(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val running = MutableStateFlow(false)
    /** The outcome of the last run, until the screen that shows it sets it back to null. */
    val finished = MutableStateFlow<Result<T>?>(null)

    /** Starts [block] unless one is under way; false when it was not started. */
    fun start(block: suspend () -> T): Boolean {
        if (!running.compareAndSet(false, true)) return false
        scope.launch {
            val r = try {
                Result.success(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                running.value = false
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            finished.value = r
            running.value = false
        }
        return true
    }
}
