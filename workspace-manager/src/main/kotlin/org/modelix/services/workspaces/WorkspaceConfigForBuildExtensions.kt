package org.modelix.services.workspaces

import io.kubernetes.client.custom.Quantity
import org.modelix.services.gitconnector.GitConnectorManager
import org.modelix.services.workspaces.stubs.models.WorkspaceInstance
import org.modelix.workspaces.DEFAULT_MPS_VERSION
import org.modelix.workspaces.GitConfigForBuild
import org.modelix.workspaces.MavenRepositoryForBuild
import org.modelix.workspaces.WorkspaceConfigForBuild

fun WorkspaceInstance.configForBuild(gitManager: GitConnectorManager) = WorkspaceConfigForBuild(
    id = config.id,
    mpsVersion = config.validMPSVersion() ?: DEFAULT_MPS_VERSION,
    gitRepositories = drafts.orEmpty().mapNotNull { gitManager.getDraft(it) }.mapNotNull { draft ->
        val repo = gitManager.getRepository(draft.gitRepositoryId) ?: return@mapNotNull null
        val remote = repo.remotes?.firstOrNull() ?: return@mapNotNull null
        GitConfigForBuild(
            url = remote.url,
            username = remote.credentials?.username,
            password = remote.credentials?.password,
            branch = draft.gitBranchName,
            commitHash = draft.baseGitCommit,
        )
    }.toSet(),
    memoryLimit = Quantity.fromString(config.validMemoryLimit() ?: "2G").number.toLong(),
    mavenRepositories = config.mavenRepositories.orEmpty().map {
        MavenRepositoryForBuild(
            url = it.url,
            username = null,
            password = null
        )
    }.toSet(),
    mavenArtifacts = config.mavenArtifacts.orEmpty().map { "${it.groupId}:${it.artifactId}:${it.version ?: "*"}" }.toSet(),
    ignoredModules = config.buildConfig?.ignoredModules.orEmpty().toSet(),
    additionalGenerationDependencies = config.buildConfig?.additionalGenerationDependencies.orEmpty().map { it.from to it.to }.toSet(),
    loadUsedModulesOnly = config.runConfig?.loadUsedModulesOnly ?: false,
)
