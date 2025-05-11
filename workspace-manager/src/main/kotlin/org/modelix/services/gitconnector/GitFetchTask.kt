package org.modelix.services.gitconnector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.modelix.services.gitconnector.stubs.models.GitBranchStatusData
import org.modelix.services.gitconnector.stubs.models.GitRepositoryConfig
import org.modelix.services.gitconnector.stubs.models.GitRepositoryStatusData
import org.modelix.workspace.manager.TaskInstance

class GitFetchTask(scope: CoroutineScope, val repo: GitRepositoryConfig) : TaskInstance<FetchResult>(scope) {
    override suspend fun process(): FetchResult {
        val oldBranchStatusMap: Map<Pair<String?, String?>, GitBranchStatusData> = (repo.status?.branches ?: emptyList()).associateBy { it.key() }
        val newBranchStatusMap = mutableMapOf<Pair<String?, String?>, GitBranchStatusData>()

        for (remoteConfig in (repo.remotes ?: emptyList())) {
            val cmd = Git.lsRemoteRepository()
            cmd.setRemote(remoteConfig.url)

            val username = remoteConfig.credentials?.username.orEmpty()
            val password = remoteConfig.credentials?.password.orEmpty()
            if (password.isNotEmpty()) {
                cmd.applyCredentials(username, password)
            }

            val refs = withContext(Dispatchers.IO) {
                cmd.call()
            }

            for (ref in refs) {
                if (!ref.name.startsWith("refs/heads/")) continue
                val branchName = ref.name.removePrefix("refs/heads/")
                val branchKey = remoteConfig.name to branchName
                val branchStatus = oldBranchStatusMap[branchKey] ?: GitBranchStatusData()
                newBranchStatusMap[branchKey] = branchStatus.copy(
                    gitCommitHash = ref.objectId.name,
                )
            }
        }

        return FetchResult(
            remoteRefs = newBranchStatusMap.map {
                FetchedBranch(
                    remoteName = it.key.first ?: "",
                    branchName = it.key.second ?: "",
                    commitHash = it.value.gitCommitHash ?: "",
                )
            },
        )
    }
}

data class FetchResult(
    val remoteRefs: List<FetchedBranch>,
)

data class FetchedBranch(
    val remoteName: String,
    val branchName: String,
    val commitHash: String,
)

private fun GitBranchStatusData.key() = remoteRepositoryName to name

fun GitRepositoryConfig.merge(newRefs: List<FetchedBranch>): GitRepositoryConfig {
    val oldBranchStatusMap = (status?.branches ?: emptyList()).associateBy { it.key() }
    val newBranchStatusMap = mutableMapOf<Pair<String?, String?>, GitBranchStatusData>()
    for (ref in newRefs) {
        val branchKey = ref.remoteName to ref.branchName
        val branchStatus = oldBranchStatusMap[branchKey]
            ?: GitBranchStatusData(remoteRepositoryName = ref.remoteName, name = ref.branchName)
        newBranchStatusMap[branchKey] = branchStatus.copy(
            gitCommitHash = ref.commitHash,
        )
    }
    return copy(
        status = (status ?: GitRepositoryStatusData()).copy(
            branches = newBranchStatusMap.values.toList().sortedBy { it.name },
        ),
    )
}
