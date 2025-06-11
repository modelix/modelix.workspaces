package org.modelix.services.gitconnector

import kotlinx.coroutines.CoroutineScope
import org.modelix.model.client2.IModelClientV2
import org.modelix.model.lazy.BranchReference
import org.modelix.workspace.manager.TaskInstance

class DraftPreparationTask(
    scope: CoroutineScope,
    val key: Key,
    val gitManager: GitConnectorManager,
    val modelClient: IModelClientV2,
) : TaskInstance<BranchReference>(scope) {
    override suspend fun process(): BranchReference {
        val draft = requireNotNull(gitManager.getDraft(key.draftId)) { "Draft not found: ${key.draftId}" }
        val gitRepoConfig = requireNotNull(gitManager.getRepository(draft.gitRepositoryId)) {
            "Git repository config not found: ${draft.gitRepositoryId}"
        }
        val modelixBranch = gitRepoConfig.getModelixRepositoryId().getBranchReference(draft.modelixBranchName)
        if (modelClient.listBranches(modelixBranch.repositoryId).contains(modelixBranch)) {
            return modelixBranch
        }

        val importTask = gitManager.getOrCreateImportTask(draft.gitRepositoryId, draft.gitBranchName)
        val importedVersion = importTask.waitForOutput()
        modelClient.push(modelixBranch, importedVersion, importedVersion)
        return modelixBranch
    }

    data class Key(
        val draftId: String,
    )
}
