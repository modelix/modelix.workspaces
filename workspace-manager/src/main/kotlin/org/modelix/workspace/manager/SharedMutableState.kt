package org.modelix.workspace.manager

private val LOG = mu.KotlinLogging.logger { }

class SharedMutableState<E>(initialValue: E) {
    private var value: E = initialValue
    private val listeners = mutableListOf<(E) -> Unit>()

    @Synchronized
    fun update(updater: (E) -> E): E {
        val newValue = updater(value)
        if (newValue == value) return value
        value = newValue
        notifyListeners()
        return newValue
    }

    fun getValue() = value

    @Synchronized
    fun addListener(listener: (E) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (E) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (it in listeners) {
            try {
                it(value)
            } catch (ex: Exception) {
                LOG.error("Exception in listener", ex)
            }
        }
    }
}