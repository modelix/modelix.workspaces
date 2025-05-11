package org.modelix.services.gitconnector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.modelix.workspace.manager.SharedMutableState

class GitConnectorManager(
    val scope: CoroutineScope,
    val connectorData: SharedMutableState<GitConnectorData>,
) {
    fun triggerGitFetch(repositoryId: String) {
        val fetchTask = GitFetchTask(scope, connectorData.getValue().repositories[repositoryId]!!)
        scope.launch {
            val resultResult = fetchTask.waitForOutput()
            connectorData.update {
                val oldRepositoryData = it.repositories[repositoryId] ?: return@update it
                val newRepositoryData = oldRepositoryData.merge(resultResult.remoteRefs)
                it.copy(repositories = it.repositories + (repositoryId to newRepositoryData))
            }
        }
    }
}
