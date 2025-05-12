package org.modelix.services.gitconnector

import kotlinx.coroutines.CoroutineScope
import org.modelix.services.gitconnector.stubs.models.GitBranchStatusData
import org.modelix.services.gitconnector.stubs.models.GitRepositoryConfig
import org.modelix.workspace.manager.SharedMutableState

class GitConnectorManager(
    val scope: CoroutineScope,
    val connectorData: SharedMutableState<GitConnectorData>,
) {
    suspend fun updateRemoteBranches(repository: GitRepositoryConfig): List<GitBranchStatusData> {
        val repositoryId = repository.id
        val fetchTask = GitFetchTask(scope, repository)
        val resultResult = fetchTask.waitForOutput()
        return connectorData.update {
            val oldRepositoryData = it.repositories[repositoryId] ?: return@update it
            val newRepositoryData = oldRepositoryData.merge(resultResult.remoteRefs)
            it.copy(repositories = it.repositories + (repositoryId to newRepositoryData))
        }.repositories[repositoryId]?.status?.branches ?: emptyList()
    }

    fun getRepository(id: String): GitRepositoryConfig? {
        return connectorData.getValue().repositories[id]
    }
}
