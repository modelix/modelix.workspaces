package org.modelix.services.gitconnector

import kotlinx.coroutines.CoroutineScope
import org.modelix.model.client2.IModelClientV2
import org.modelix.model.lazy.RepositoryId
import org.modelix.services.gitconnector.stubs.models.GitBranchStatusData
import org.modelix.services.gitconnector.stubs.models.GitRepositoryConfig
import org.modelix.services.workspaces.FileSystemPersistence
import org.modelix.services.workspaces.PersistedState
import org.modelix.workspace.manager.KestraClient
import org.modelix.workspace.manager.ReusableTasks
import org.modelix.workspace.manager.SharedMutableState
import java.io.File

class GitConnectorManager(
    val scope: CoroutineScope,
    val connectorData: SharedMutableState<GitConnectorData> = PersistedState(
        persistence = FileSystemPersistence(
            file = File("/workspace-manager/config/git-connector.json"),
            serializer = GitConnectorData.serializer(),
        ),
        defaultState = { GitConnectorData() },
    ).state,
    val modelClient: IModelClientV2,
    val kestraClient: KestraClient,
) {

    private val importTasks = ReusableTasks<GitImportTask.Key, GitImportTask>()
    private val draftTasks = ReusableTasks<DraftPreparationTask.Key, DraftPreparationTask>()

    fun getOrCreateImportTask(gitRepoId: String, gitBranchName: String): GitImportTask {
        val data = connectorData.getValue()
        val repo = requireNotNull(data.repositories[gitRepoId]) { "Repository not found: $gitRepoId" }
        val branch = requireNotNull(repo.status?.branches?.find { it.name == gitBranchName }) {
            "Branch not found: $gitBranchName"
        }
        val gitRevision = requireNotNull(branch.gitCommitHash) {
            "Git commit hash for branch unknown: $gitBranchName"
        }
        val key = GitImportTask.Key(
            repo = repo.copy(status = null),
            gitBranchName = gitBranchName,
            gitRevision = gitRevision,
            modelixBranchName = "git-import/$gitBranchName",
        )
        return importTasks.getOrCreateTask(key) {
            GitImportTask(
                key = key,
                scope = scope,
                kestraClient = kestraClient,
                modelClient = modelClient,
            )
        }
    }

    fun getOrCreateDraftPreparationTask(draftId: String): DraftPreparationTask {
        val key = DraftPreparationTask.Key(
            draftId = draftId,
        )

        return draftTasks.getOrCreateTask(key) {
            DraftPreparationTask(
                scope = scope,
                key = key,
                gitManager = this,
                modelClient = modelClient,
            )
        }
    }

    suspend fun updateRemoteBranches(repository: GitRepositoryConfig): List<GitBranchStatusData> {
        val gitRepositoryId = repository.id
        val fetchTask = GitFetchTask(scope, repository)
        val resultResult = fetchTask.waitForOutput()
        return connectorData.update {
            val oldRepositoryData = it.repositories[gitRepositoryId] ?: return@update it
            val newRepositoryData = oldRepositoryData.merge(resultResult.remoteRefs)
            it.copy(repositories = it.repositories + (gitRepositoryId to newRepositoryData))
        }.repositories[gitRepositoryId]?.status?.branches.orEmpty()
    }

    fun getRepository(id: String): GitRepositoryConfig? {
        return connectorData.getValue().repositories[id]
    }

    fun getDraft(id: String) = connectorData.getValue().drafts[id]
}

fun GitRepositoryConfig.getModelixRepositoryId() = RepositoryId((modelixRepository ?: id))
