package org.modelix.workspace.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.modelix.workspaces.InternalWorkspaceConfig
import java.util.UUID

abstract class Task<R>(val scope: CoroutineScope) {
    val id: UUID = UUID.randomUUID()
    private var job: Deferred<R>? = null
    protected abstract suspend fun process(): R

    @Synchronized
    fun launch(): Deferred<R> {
        return job ?: scope.async { process() }.also { job = it }
    }

    suspend fun waitForOutput(): R {
        return launch().await()
    }

    fun getState() = job.let {
        when {
            it == null -> TaskState.CREATED
            it.isCompleted -> TaskState.COMPLETED
            it.isCancelled -> TaskState.CANCELLED
            it.isActive -> TaskState.ACTIVE
            else -> TaskState.UNKNOWN
        }
    }
}

class ReusableTasks<K, V : Task<*>> {
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

    fun getAll(): List<V> = synchronized(tasks) { tasks.values.toList() }
}
