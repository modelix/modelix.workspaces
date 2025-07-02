package org.modelix.services.gitconnector

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorDraftsController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorDraftsController.Companion.modelixGitConnectorDraftsRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorDraftsPreparationJobController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorDraftsPreparationJobController.Companion.modelixGitConnectorDraftsPreparationJobRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorDraftsRebaseJobController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorDraftsRebaseJobController.Companion.modelixGitConnectorDraftsRebaseJobRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesBranchesController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesBranchesController.Companion.modelixGitConnectorRepositoriesBranchesRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesBranchesUpdateController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesBranchesUpdateController.Companion.modelixGitConnectorRepositoriesBranchesUpdateRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesController.Companion.modelixGitConnectorRepositoriesRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesDraftsController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesDraftsController.Companion.modelixGitConnectorRepositoriesDraftsRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesStatusController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesStatusController.Companion.modelixGitConnectorRepositoriesStatusRoutes
import org.modelix.services.gitconnector.stubs.controllers.TypedApplicationCall
import org.modelix.services.gitconnector.stubs.models.DraftConfig
import org.modelix.services.gitconnector.stubs.models.DraftConfigList
import org.modelix.services.gitconnector.stubs.models.DraftPreparationJob
import org.modelix.services.gitconnector.stubs.models.DraftRebaseJob
import org.modelix.services.gitconnector.stubs.models.GitBranchList
import org.modelix.services.gitconnector.stubs.models.GitRemoteConfig
import org.modelix.services.gitconnector.stubs.models.GitRepositoryConfig
import org.modelix.services.gitconnector.stubs.models.GitRepositoryConfigList
import org.modelix.services.gitconnector.stubs.models.GitRepositoryStatusData
import org.modelix.workspace.manager.SharedMutableState
import org.modelix.workspace.manager.TaskState
import java.util.UUID

@Serializable
data class GitConnectorData(
    val repositories: Map<String, GitRepositoryConfig> = emptyMap(),
    val drafts: Map<String, DraftConfig> = emptyMap(),
)

class GitConnectorController(val manager: GitConnectorManager) {

    private val data: SharedMutableState<GitConnectorData> get() = manager.connectorData

    fun install(route: Route) {
        route.install_()
    }

    private fun Route.install_() {
        modelixGitConnectorRepositoriesRoutes(object : ModelixGitConnectorRepositoriesController {
            override suspend fun listGitRepositories(
                includeStatus: Boolean?,
                call: TypedApplicationCall<GitRepositoryConfigList>,
            ) {
                call.respondTyped(
                    GitRepositoryConfigList(data.getValue().repositories.values.toList())
                        .maskCredentials()
                        .maskStatus(includeStatus),
                )
            }

            override suspend fun createGitRepository(
                gitRepositoryConfig: GitRepositoryConfig,
                call: TypedApplicationCall<GitRepositoryConfig>,
            ) {
                val newId = UUID.randomUUID().toString()
                val newRepository = gitRepositoryConfig.copy(
                    id = newId,
                    status = null,
                    modelixRepository = newId,
                )

                data.update {
                    it.copy(
                        repositories = it.repositories + (newRepository.id to newRepository),
                    )
                }

                call.respondTyped(newRepository.maskCredentials())
            }

            override suspend fun getGitRepository(
                repositoryId: String,
                includeStatus: Boolean?,
                call: TypedApplicationCall<GitRepositoryConfig>,
            ) {
                val repo = data.getValue().repositories[repositoryId]
                if (repo == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondTyped(repo.maskCredentials().copy(status = if (includeStatus == true) repo.status else null))
                }
            }

            override suspend fun updateGitRepository(
                repositoryId: String,
                gitRepositoryConfig: GitRepositoryConfig,
                call: ApplicationCall,
            ) {
                data.update {
                    val mergedConfig = it.repositories[repositoryId].merge(gitRepositoryConfig)
                    it.copy(
                        repositories = it.repositories + (repositoryId to mergedConfig),
                    )
                }

                call.respond(HttpStatusCode.OK)
            }

            override suspend fun deleteGitRepository(
                repositoryId: String,
                call: ApplicationCall,
            ) {
                data.update {
                    it.copy(
                        repositories = it.repositories - repositoryId,
                    )
                }

                call.respond(HttpStatusCode.OK)
            }
        })

        modelixGitConnectorRepositoriesDraftsRoutes(object : ModelixGitConnectorRepositoriesDraftsController {
            override suspend fun listDraftsInRepository(
                repositoryId: String,
                call: TypedApplicationCall<DraftConfigList>,
            ) {
                call.respondTyped(
                    DraftConfigList(
                        data.getValue().drafts.values.filter { it.gitRepositoryId == repositoryId },
                    ),
                )
            }

            override suspend fun createDraftInRepository(
                repositoryId: String,
                draftConfig: DraftConfig,
                call: TypedApplicationCall<DraftConfig>,
            ) {
                val draftId = UUID.randomUUID().toString()
                val branch = manager.getRepository(repositoryId)?.status?.branches?.find { it.name == draftConfig.gitBranchName }
                val newDraft = draftConfig.copy(
                    id = draftId,
                    gitRepositoryId = repositoryId,
                    baseGitCommit = draftConfig.baseGitCommit.takeIf { it.isNotEmpty() } ?: branch?.gitCommitHash ?: "",
                    modelixBranchName = "drafts/$draftId",
                )
                data.update {
                    it.copy(
                        drafts = it.drafts + (draftId to newDraft),
                    )
                }
                call.respondTyped(newDraft)
            }
        })

        modelixGitConnectorDraftsRoutes(object : ModelixGitConnectorDraftsController {
            override suspend fun deleteDraft(draftId: String, call: ApplicationCall) {
                data.update {
                    it.copy(
                        drafts = it.drafts - draftId,
                    )
                }
                call.respond(HttpStatusCode.OK)
            }

            override suspend fun getDraft(
                draftId: String,
                call: TypedApplicationCall<DraftConfig>,
            ) {
                val draft = data.getValue().drafts[draftId]
                if (draft == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondTyped(draft)
                }
            }
        })

        modelixGitConnectorRepositoriesBranchesRoutes(object : ModelixGitConnectorRepositoriesBranchesController {
            override suspend fun listBranches(
                repositoryId: String,
                call: TypedApplicationCall<GitBranchList>,
            ) {
                val repository = data.getValue().repositories[repositoryId]
                if (repository == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return
                }
                call.respondTyped(GitBranchList(repository.status?.branches ?: emptyList()))
            }
        })

        modelixGitConnectorRepositoriesBranchesUpdateRoutes(object : ModelixGitConnectorRepositoriesBranchesUpdateController {
            override suspend fun updateBranches(
                repositoryId: String,
                call: TypedApplicationCall<GitBranchList>,
            ) {
                val repository = data.getValue().repositories[repositoryId]
                if (repository == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return
                }
                val newBranches = manager.updateRemoteBranches(repository)
                call.respondTyped(GitBranchList(newBranches))
            }
        })

        modelixGitConnectorRepositoriesStatusRoutes(object : ModelixGitConnectorRepositoriesStatusController {
            override suspend fun getGitRepositoryStatus(
                repositoryId: String,
                call: TypedApplicationCall<GitRepositoryStatusData>,
            ) {
                call.respondTyped(data.getValue().repositories[repositoryId]?.status ?: GitRepositoryStatusData())
            }
        })

        modelixGitConnectorDraftsRebaseJobRoutes(object : ModelixGitConnectorDraftsRebaseJobController {
            override suspend fun getDraftRebaseJob(
                draftId: String,
                call: TypedApplicationCall<DraftRebaseJob>,
            ) {
                val draft = data.getValue().drafts[draftId]
                if (draft == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    val task = manager.getRebaseTask(draftId)
                    if (task == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respondTyped(
                            DraftRebaseJob(
                                baseGitCommit = task.key.importTaskKey.gitRevision,
                                gitBranchName = task.key.importTaskKey.gitBranchName,
                                active = when (task.getState()) {
                                    TaskState.CREATED, TaskState.ACTIVE -> true
                                    TaskState.CANCELLED -> false
                                    TaskState.COMPLETED -> false
                                    TaskState.UNKNOWN -> false
                                },
                                errorMessage = task.getOutput()?.exceptionOrNull()?.stackTraceToString(),
                            ),
                        )
                    }
                }
            }

            override suspend fun rebaseDraft(
                draftId: String,
                draftRebaseJob: DraftRebaseJob,
                call: ApplicationCall,
            ) {
                val draft = data.getValue().drafts[draftId]
                if (draft == null) {
                    call.respondText("Draft not found: $draftId", status = HttpStatusCode.NotFound)
                } else {
                    manager.rebaseDraft(
                        draftId = draftId,
                        newGitCommitId = draftRebaseJob.baseGitCommit,
                        gitBranchName = draftRebaseJob.gitBranchName,
                    )
                    call.respond(HttpStatusCode.OK)
                }
            }
        })

        modelixGitConnectorDraftsPreparationJobRoutes(object : ModelixGitConnectorDraftsPreparationJobController {

            suspend fun TypedApplicationCall<DraftPreparationJob>.respondJob(task: DraftPreparationTask) {
                respondTyped(
                    DraftPreparationJob(
                        active = when (task.getState()) {
                            TaskState.CREATED, TaskState.ACTIVE -> true
                            TaskState.CANCELLED, TaskState.COMPLETED, TaskState.UNKNOWN -> false
                        },
                        errorMessage = task.getOutput()?.exceptionOrNull()?.stackTraceToString(),
                    ),
                )
            }

            override suspend fun getDraftBranchPreparationJob(
                draftId: String,
                call: TypedApplicationCall<DraftPreparationJob>,
            ) {
                val task = manager.draftPreparationTasks.getAll().lastOrNull { it.key.draftId == draftId }
                if (task == null) {
                    call.respondText("Draft not found: $draftId", status = HttpStatusCode.NotFound)
                } else {
                    call.respondJob(task)
                }
            }

            override suspend fun prepareDraftBranch(
                draftId: String,
                draftPreparationJob: DraftPreparationJob,
                call: TypedApplicationCall<DraftPreparationJob>,
            ) {
                val task = manager.getOrCreateDraftPreparationTask(draftId).also { it.launch() }
                call.respondJob(task)
            }
        })
    }
}

fun GitRepositoryConfigList.maskCredentials() = copy(
    repositories = repositories.map { it.maskCredentials() },
)
fun GitConnectorData.maskCredentials() = copy(
    repositories = repositories.mapValues { it.value.maskCredentials() },
)
fun GitRepositoryConfig.maskCredentials() = copy(
    remotes = remotes?.map { it.maskCredentials() },
)
fun GitRemoteConfig.maskCredentials() = copy(
    credentials = null,
)

fun GitRepositoryConfig?.merge(newData: GitRepositoryConfig): GitRepositoryConfig {
    if (this == null) return newData
    return copy(
        name = newData.name ?: name,
        remotes = (remotes ?: emptyList()).merge(newData.remotes ?: emptyList()),
    )
}

fun List<GitRemoteConfig>.merge(newData: List<GitRemoteConfig>): List<GitRemoteConfig> {
    return newData.map { newConfig ->
        val oldConfig = this.find { it.url == newConfig.url }
        val mergedCredentials = (newConfig.credentials ?: oldConfig?.credentials).takeIf { newConfig.hasCredentials }
        GitRemoteConfig(
            name = newConfig.name,
            url = newConfig.url,
            credentials = mergedCredentials,
            hasCredentials = newConfig.hasCredentials,
        )
    }
}

fun GitRepositoryConfigList.maskStatus(includeStatus: Boolean?): GitRepositoryConfigList {
    if (includeStatus == true) return this
    return copy(
        repositories = repositories.map { it.maskStatus(includeStatus) },
    )
}

fun GitRepositoryConfig.maskStatus(includeStatus: Boolean?): GitRepositoryConfig {
    if (includeStatus == true) return this
    return copy(status = null)
}
