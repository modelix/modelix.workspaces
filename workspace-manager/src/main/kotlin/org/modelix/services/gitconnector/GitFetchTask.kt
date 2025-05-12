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
        val fetchedBranches = ArrayList<FetchedBranch>()

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
                fetchedBranches.add(
                    FetchedBranch(
                        remoteName = remoteConfig.name,
                        branchName = branchName,
                        commitHash = ref.objectId.name,
                    ),
                )
            }
        }

        return FetchResult(remoteRefs = fetchedBranches)
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
    val newBranchStatusMap = mutableMapOf<Pair<String, String>, GitBranchStatusData>()
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
            branches = newBranchStatusMap.values.toList().sortedWith { a, b -> (a.name).compareTo(b.name, ignoreCase = true) },
        ),
    )
}
