package org.modelix.services.workspaces

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.modelix.workspace.manager.SharedMutableState

interface IStatePersistence<E> {
    fun load(): E?
    fun save(value: E)
}

class FileSystemPersistence<E>(val file: java.io.File, val serializer: KSerializer<E>) : IStatePersistence<E> {
    override fun load(): E? {
        if (!file.exists()) return null
        return try {
            val json = file.readText()
            if (json.isEmpty()) return null
            Json.decodeFromString(serializer, json)
        } catch (e: Exception) {
            LOG.warn("Failed to load state from ${file.absolutePath}", e)
            null
        }
    }

    override fun save(value: E) {
        try {
            file.writeText(Json.encodeToString(serializer, value))
        } catch (e: Exception) {
            LOG.warn("Failed to save state to ${file.absolutePath}", e)
        }
    }

    companion object {
        private val LOG = mu.KotlinLogging.logger {}
    }
}

class PersistedState<E>(persistence: IStatePersistence<E>, defaultState: () -> E) {
    val state: SharedMutableState<E> = SharedMutableState(persistence.load() ?: defaultState()).also {
        it.addListener {
            persistence.save(it)
        }
    }
}
