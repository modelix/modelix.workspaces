package org.modelix.services.gitconnector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.modelix.model.IVersion
import org.modelix.model.client2.IModelClientV2
import org.modelix.model.lazy.RepositoryId
import org.modelix.services.gitconnector.stubs.models.GitRepositoryConfig
import org.modelix.workspace.manager.KestraClient
import org.modelix.workspace.manager.TaskInstance
import kotlin.time.Duration.Companion.seconds

class GitImportTask(
    val key: Key,
    scope: CoroutineScope,
    val kestraClient: KestraClient,
    val modelClient: IModelClientV2,
) : TaskInstance<IVersion>(scope) {

    private val repoId = requireNotNull(key.repo.modelixRepository?.let { RepositoryId(it) }) { "Repository ID missing" }
    private val branchRef = repoId.getBranchReference(key.modelixBranchName)
    private val jobLabels = mapOf("taskId" to id.toString())
    private suspend fun modelixBranchExists() = modelClient.listBranches(repoId).contains(branchRef)
    private suspend fun jobIsRunning() = kestraClient.getRunningImportJobIds(jobLabels).isNotEmpty()

    override suspend fun process(): IVersion {
        if (modelixBranchExists()) {
            return modelClient.lazyLoadVersion(branchRef)
        }

        val remote = requireNotNull(key.repo.remotes?.firstOrNull()) { "No remotes specified" }
        val modelixBranch =
            RepositoryId((key.repo.modelixRepository ?: key.repo.id)).getBranchReference("git-import/${key.gitBranchName}")
        kestraClient.enqueueGitImport(
            gitRepoUrl = remote.url,
            gitUser = remote.credentials?.username,
            gitPassword = remote.credentials?.password,
            gitRevision = key.gitRevision,
            modelixBranch = modelixBranch,
            labels = jobLabels,
        )

        while (true) {
            if (modelixBranchExists()) {
                val version = modelClient.lazyLoadVersion(modelixBranch)
                if (version.gitCommit == key.gitRevision) {
                    return version
                }
            }
            check(jobIsRunning()) { "Import failed" }
            delay(3.seconds)
        }
    }

    data class Key(
        val repo: GitRepositoryConfig,
        val gitBranchName: String,
        val gitRevision: String,
        val modelixBranchName: String,
    )
}

val IVersion.gitCommit: String? get() = getAttributes()["git-commit"]
