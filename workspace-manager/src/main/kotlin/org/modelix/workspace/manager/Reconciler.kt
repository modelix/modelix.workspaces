package org.modelix.workspace.manager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val LOG = mu.KotlinLogging.logger {}

class Reconciler<E>(val coroutinesScope: CoroutineScope, initialState: E, reconcile: suspend (E) -> Unit) {
    private val stateChanges = Channel<E>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val desiredState = SharedMutableState<E>(initialState)
        .also { it.addListener { stateChanges.trySend(it) } }
    private val reconciliationJob = coroutinesScope.launch {
        try {
            while (isActive) {
                try {
                    reconcile(stateChanges.receive())
                } catch (ex: CancellationException) {
                    break
                } catch (ex: Throwable) {
                    LOG.error("Exception during reconciliation", ex)
                }
            }
        } finally {
            LOG.info("Reconciliation job stopped")
        }
    }

    init {
        stateChanges.trySend(initialState)
    }

    fun dispose() {
        reconciliationJob.cancel()
    }

    fun updateDesiredState(updater: (E) -> E) {
        desiredState.update(updater)
    }

    fun getDesiredState(): E = desiredState.getValue()

    fun trigger() {
        desiredState.update {
            stateChanges.trySend(it)
            it
        }
    }
}
