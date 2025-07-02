package org.modelix.services.gitconnector

import kotlinx.coroutines.CoroutineScope
import org.modelix.model.IVersion
import org.modelix.model.VersionMerger
import org.modelix.model.client2.IModelClientV2
import org.modelix.model.lazy.BranchReference
import org.modelix.workspace.manager.TaskInstance

class DraftRebaseTask(
    val key: Key,
    scope: CoroutineScope,
    val gitManager: GitConnectorManager,
    val modelClient: IModelClientV2,
) : TaskInstance<IVersion>(scope) {

    override suspend fun process(): IVersion {
        val importTask = gitManager.getOrCreateImportTask(key.importTaskKey)
        val draftBranch = key.draftBranch
        val currentDraftHead = modelClient.lazyLoadVersion(draftBranch)
        val newBaseVersion = importTask.waitForOutput()
        val mergedVersion = VersionMerger().mergeChange(newBaseVersion, currentDraftHead)
        val newVersion = modelClient.push(draftBranch, mergedVersion, listOf(currentDraftHead, newBaseVersion))
        gitManager.updateDraftConfig(key.draftId) {
            it.copy(
                baseGitCommit = key.importTaskKey.gitRevision,
                gitBranchName = key.importTaskKey.gitBranchName,
            )
        }
        return newVersion
    }

    data class Key(
        val importTaskKey: GitImportTask.Key,
        val draftId: String,
        val draftBranch: BranchReference,
    )
}
