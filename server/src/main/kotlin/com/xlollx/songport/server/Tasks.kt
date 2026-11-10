package com.xlollx.songport.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Long tool runs (a full backup, a scan of every playlist) in the background, so a page never waits
 * on them: the page that started one shows its progress, refreshing, then its result. Kept in memory
 * only; a run's page outlives it by an hour.
 */
object Tasks {
    /** [owner]: whose run it is on a shared server ([ServerEnv.owner]); the others do not see it. */
    class Task(val id: String, val title: String, val back: String, val owner: String = "") {
        @Volatile var progress: String = "Starting…"
        @Volatile var done: Boolean = false
        /** The result as HTML (escaped by whoever wrote it), or the error. */
        @Volatile var resultHtml: String? = null
        @Volatile var error: String? = null
        @Volatile var finishedAt: Long = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, Task>()

    /** Starts [block], which reports through the task and returns the result's HTML. */
    fun start(title: String, back: String, log: (String) -> Unit, owner: String = "", block: suspend (Task) -> String): Task {
        val now = System.currentTimeMillis()
        tasks.values.removeIf { it.done && now - it.finishedAt > KEEP_MS }
        val task = Task(UUID.randomUUID().toString(), title, back, owner)
        tasks[task.id] = task
        scope.launch {
            try {
                task.resultHtml = block(task)
            } catch (e: Exception) {
                task.error = e.message ?: e.javaClass.simpleName
                log("$title: ${task.error}")
            } finally {
                task.finishedAt = System.currentTimeMillis()
                task.done = true
            }
        }
        return task
    }

    /** The task with this id; with [owner], only when it is theirs ([ServerEnv.sees]: null sees all). */
    fun get(id: String?, owner: String? = null): Task? = id?.let { tasks[it] }?.takeIf { owner == null || it.owner == owner }

    /** Every task kept: those running, and those ended in the last hour; with [owner], theirs only. */
    fun all(owner: String? = null): List<Task> = tasks.values.filter { owner == null || it.owner == owner }

    /** True while a task with this title runs (one full backup at a time, say). */
    fun running(title: String): Boolean = tasks.values.any { it.title == title && !it.done }

    private const val KEEP_MS = 60 * 60_000L
}
