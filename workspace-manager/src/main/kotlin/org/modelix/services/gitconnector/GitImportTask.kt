package org.modelix.services.gitconnector

import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.modelix.authorization.ModelixJWTUtil
import org.modelix.model.IVersion
import org.modelix.model.client2.IModelClientV2
import org.modelix.model.lazy.RepositoryId
import org.modelix.model.server.ModelServerPermissionSchema
import org.modelix.services.gitconnector.stubs.models.GitRepositoryConfig
import org.modelix.services.workspaces.metadata
import org.modelix.services.workspaces.spec
import org.modelix.services.workspaces.template
import org.modelix.workspace.manager.ITaskInstance
import org.modelix.workspace.manager.KestraClient
import org.modelix.workspace.manager.KubernetesJobTask
import org.modelix.workspace.manager.TaskInstance
import kotlin.time.Duration.Companion.seconds

interface GitImportTask : ITaskInstance<IVersion> {
    data class Key(
        val repo: GitRepositoryConfig,
        val gitBranchName: String,
        val gitRevision: String,
        val modelixBranchName: String,
    )
}

class GitImportTaskUsingKubernetesJob(
    val key: GitImportTask.Key,
    scope: CoroutineScope,
    val modelClient: IModelClientV2,
    val jwtUtil: ModelixJWTUtil,
) : GitImportTask, KubernetesJobTask<IVersion>(scope) {

    private val repoId = requireNotNull(key.repo.modelixRepository?.let { RepositoryId(it) }) { "Repository ID missing" }
    private val branchRef = repoId.getBranchReference(key.modelixBranchName)
    private suspend fun modelixBranchExists() = modelClient.listBranches(repoId).contains(branchRef)

    override suspend fun tryGetResult(): IVersion? {
        return if (modelixBranchExists()) {
            return modelClient.lazyLoadVersion(branchRef).takeIf { it.gitCommit == key.gitRevision }
        } else {
            return null
        }
    }

    @Suppress("ktlint")
    override fun generateJobYaml(): V1Job {
        val remote = requireNotNull(key.repo.remotes?.firstOrNull()) { "No remotes specified" }
        val modelixBranch =
            RepositoryId((key.repo.modelixRepository ?: key.repo.id)).getBranchReference("git-import/${key.gitBranchName}")
        val token = jwtUtil.createAccessToken(
            "git-import@modelix.org",
            listOf(
                ModelServerPermissionSchema.repository(modelixBranch.repositoryId).create.fullId,
                ModelServerPermissionSchema.repository(modelixBranch.repositoryId).read.fullId,
                ModelServerPermissionSchema.branch(modelixBranch).rewrite.fullId,
            ),
        )

        return V1Job().apply {
            metadata {
                name = "gitimportjob-$id"
            }
            spec {
                template {
                    spec {
                        addContainersItem(V1Container().apply {
                            name = "importer"
                            image = System.getenv("GIT_IMPORT_IMAGE")
                            args = listOf(
                                "git-import-remote",
                                remote.url,
                                "--git-user",
                                remote.credentials?.username,
                                "--git-password",
                                remote.credentials?.password,
                                "--limit",
                                "50",
                                "--model-server",
                                System.getenv("model_server_url"),
                                "--token",
                                token,
                                "--repository",
                                modelixBranch.repositoryId.id,
                                "--branch",
                                modelixBranch.branchName,
                                "--rev",
                                key.gitRevision,
                            )
                        })
                    }
                }
            }
        }
    }
}

class GitImportTaskUsingKestra(
    val key: GitImportTask.Key,
    scope: CoroutineScope,
    val kestraClient: KestraClient,
    val modelClient: IModelClientV2,
) : GitImportTask, TaskInstance<IVersion>(scope) {

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
}

val IVersion.gitCommit: String? get() = getAttributes()["git-commit"]
