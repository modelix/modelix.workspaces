package org.modelix.workspace.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.UUID

interface ITaskInstance<R> {
    fun getOutput(): Result<R>?
    suspend fun waitForOutput(): R
    fun getState(): TaskState
    fun launch(): Deferred<R>
}

abstract class TaskInstance<R>(val scope: CoroutineScope) : ITaskInstance<R> {
    val id: UUID = UUID.randomUUID()
    private var job: Deferred<R>? = null
    private var result: Result<R>? = null
    protected abstract suspend fun process(): R

    @Synchronized
    override fun launch(): Deferred<R> {
        return job ?: scope.async {
            runCatching { process() }.also { result = it }.getOrThrow()
        }.also { job = it }
    }

    override fun getOutput(): Result<R>? = result

    override suspend fun waitForOutput(): R {
        return launch().await()
    }

    override fun getState(): TaskState = job.let {
        when {
            it == null -> TaskState.CREATED
            it.isCompleted -> TaskState.COMPLETED
            it.isCancelled -> TaskState.CANCELLED
            it.isActive -> TaskState.ACTIVE
            else -> TaskState.UNKNOWN
        }
    }
}

class ReusableTasks<K, V : ITaskInstance<*>> {
    private val tasks = LinkedHashMap<K, V>()

    fun getOrCreateTask(key: K, factory: (K) -> V): V {
        return synchronized(tasks) {
            val existing = tasks[key]
            if (existing != null && existing.getState() != TaskState.CANCELLED) return@synchronized existing
            val newTask = factory(key)
            tasks[key] = newTask
            newTask
        }
    }

    fun getEntries(): Map<K, V> = synchronized(tasks) { tasks.toMap() }

    fun getAll(): List<V> = synchronized(tasks) { tasks.values.toList() }
}
