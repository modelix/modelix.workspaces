package org.modelix.services.gitconnector

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorDraftsController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorDraftsController.Companion.modelixGitConnectorDraftsRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesController.Companion.modelixGitConnectorRepositoriesRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesDraftsController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesDraftsController.Companion.modelixGitConnectorRepositoriesDraftsRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesFetchController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesFetchController.Companion.modelixGitConnectorRepositoriesFetchRoutes
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesStatusController
import org.modelix.services.gitconnector.stubs.controllers.ModelixGitConnectorRepositoriesStatusController.Companion.modelixGitConnectorRepositoriesStatusRoutes
import org.modelix.services.gitconnector.stubs.controllers.TypedApplicationCall
import org.modelix.services.gitconnector.stubs.models.DraftConfig
import org.modelix.services.gitconnector.stubs.models.DraftConfigList
import org.modelix.services.gitconnector.stubs.models.GitRemoteConfig
import org.modelix.services.gitconnector.stubs.models.GitRepositoryConfig
import org.modelix.services.gitconnector.stubs.models.GitRepositoryConfigList
import org.modelix.services.gitconnector.stubs.models.GitRepositoryStatusData
import org.modelix.services.workspaces.FileSystemPersistence
import org.modelix.services.workspaces.PersistedState
import java.io.File
import java.util.UUID

class GitConnectorConfig {
    val data: PersistedState<GitConnectorData> = PersistedState(
        persistence = FileSystemPersistence(
            file = File("/workspace-manager/config/git-connector.json"),
            serializer = GitConnectorData.serializer(),
        ),
        defaultState = { GitConnectorData() },
    )

    fun getState(): GitConnectorData = data.state.getValue()
    fun updateState(updater: (GitConnectorData) -> GitConnectorData) = data.state.update(updater)
}

@Serializable
data class GitConnectorData(
    val repositories: Map<String, GitRepositoryConfig> = emptyMap(),
    val drafts: Map<String, DraftConfig> = emptyMap(),
)

val GitConnectorPlugin = createRouteScopedPlugin(name = "gitConnector", createConfiguration = ::GitConnectorConfig) {
    val manager = GitConnectorManager(application, pluginConfig.data.state)
    (route ?: application.routing({})).installControllers(manager, pluginConfig)
}

private fun Route.installControllers(manager: GitConnectorManager, pluginConfig: GitConnectorConfig) {
    modelixGitConnectorRepositoriesRoutes(object : ModelixGitConnectorRepositoriesController {
        override suspend fun listGitRepositories(call: TypedApplicationCall<GitRepositoryConfigList>) {
            call.respondTyped(GitRepositoryConfigList(pluginConfig.getState().repositories.values.toList()).maskCredentials())
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

            pluginConfig.updateState {
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
            val repo = pluginConfig.getState().repositories[repositoryId]
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
            pluginConfig.updateState {
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
            pluginConfig.updateState {
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
                    pluginConfig.getState().drafts.values.filter { it.gitRepositoryId == repositoryId },
                ),
            )
        }

        override suspend fun createDraftInRepository(
            repositoryId: String,
            draftConfig: DraftConfig,
            call: TypedApplicationCall<DraftConfig>,
        ) {
            val draftId = UUID.randomUUID().toString()
            val newDraft = draftConfig.copy(
                id = draftId,
                gitRepositoryId = repositoryId,
                modelixBranchName = "drafts/$draftId",
            )
            pluginConfig.updateState {
                it.copy(
                    drafts = it.drafts + (draftId to newDraft),
                )
            }
            call.respondTyped(newDraft)
        }
    })

    modelixGitConnectorDraftsRoutes(object : ModelixGitConnectorDraftsController {
        override suspend fun deleteDraft(draftId: String, call: ApplicationCall) {
            pluginConfig.updateState {
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
            val draft = pluginConfig.getState().drafts[draftId]
            if (draft == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondTyped(draft)
            }
        }
    })

    modelixGitConnectorRepositoriesFetchRoutes(object : ModelixGitConnectorRepositoriesFetchController {
        override suspend fun triggerGitFetch(
            repositoryId: String,
            call: ApplicationCall,
        ) {
            manager.triggerGitFetch(repositoryId)
            call.respond(HttpStatusCode.OK)
        }
    })

    modelixGitConnectorRepositoriesStatusRoutes(object : ModelixGitConnectorRepositoriesStatusController {
        override suspend fun getGitRepositoryStatus(
            repositoryId: String,
            call: TypedApplicationCall<GitRepositoryStatusData>,
        ) {
            call.respondTyped(pluginConfig.getState().repositories[repositoryId]?.status ?: GitRepositoryStatusData())
        }
    })
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
