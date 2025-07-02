package org.modelix.services.gitconnector

import kotlinx.coroutines.CoroutineScope
import org.modelix.model.client2.IModelClientV2
import org.modelix.model.lazy.RepositoryId
import org.modelix.services.gitconnector.stubs.models.DraftConfig
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
    val draftPreparationTasks = ReusableTasks<DraftPreparationTask.Key, DraftPreparationTask>()
    private val draftRebaseTasks = ReusableTasks<DraftRebaseTask.Key, DraftRebaseTask>()

    fun getOrCreateImportTask(gitRepoId: String, gitBranchName: String): GitImportTask {
        val data = connectorData.getValue()
        val repo = requireNotNull(data.repositories[gitRepoId]) { "Repository not found: $gitRepoId" }
        val branch = requireNotNull(repo.status?.branches?.find { it.name == gitBranchName }) {
            "Branch not found: $gitBranchName"
        }
        val gitRevision = requireNotNull(branch.gitCommitHash) {
            "Git commit hash for branch unknown: $gitBranchName"
        }
        return getOrCreateImportTask(repo, gitBranchName, gitRevision)
    }

    fun getOrCreateImportTask(gitRepo: GitRepositoryConfig, gitBranchName: String, gitRevision: String): GitImportTask {
        val key = GitImportTask.Key(
            repo = gitRepo.copy(status = null),
            gitBranchName = gitBranchName,
            gitRevision = gitRevision,
            modelixBranchName = "git-import/$gitBranchName",
        )
        return getOrCreateImportTask(key)
    }

    fun getOrCreateImportTask(taskKey: GitImportTask.Key): GitImportTask {
        return importTasks.getOrCreateTask(taskKey) {
            GitImportTask(
                key = taskKey,
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

        return draftPreparationTasks.getOrCreateTask(key) {
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

    fun updateDraftConfig(draftId: String, updater: (DraftConfig) -> DraftConfig) {
        connectorData.update { connectorData ->
            connectorData.copy(
                drafts = connectorData.drafts + (draftId to updater(connectorData.drafts.getValue(draftId))),
            )
        }
    }

    fun rebaseDraft(draftId: String, newGitCommitId: String, gitBranchName: String?): DraftRebaseTask {
        val draft = requireNotNull(getDraft(draftId)) { "Draft not found: $draftId" }
        val gitRepoConfig = requireNotNull(getRepository(draft.gitRepositoryId)) {
            "Git repository config not found: ${draft.gitRepositoryId}"
        }
        val draftBranch = gitRepoConfig.getModelixRepositoryId().getBranchReference(draft.modelixBranchName)
        val gitBranchName = gitBranchName ?: draft.gitBranchName
        val key = DraftRebaseTask.Key(
            importTaskKey = GitImportTask.Key(
                repo = gitRepoConfig,
                gitBranchName = gitBranchName,
                gitRevision = newGitCommitId,
                modelixBranchName = "git-import/$gitBranchName",
            ),
            draftId = draftId,
            draftBranch = draftBranch,
        )
        return draftRebaseTasks.getOrCreateTask(key) {
            DraftRebaseTask(
                key = key,
                scope = scope,
                gitManager = this,
                modelClient = modelClient,
            )
        }.also { it.launch() }
    }

    fun getRebaseTask(draftId: String): DraftRebaseTask? {
        return draftRebaseTasks.getAll().lastOrNull { it.key.draftId == draftId }
    }
}

fun GitRepositoryConfig.getModelixRepositoryId() = RepositoryId((modelixRepository ?: id))
