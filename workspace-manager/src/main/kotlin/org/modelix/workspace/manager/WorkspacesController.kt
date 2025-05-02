package org.modelix.workspace.manager

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.modelix.authorization.getUserName
import org.modelix.instancesmanager.DeploymentManager
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesDraftsController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesDraftsController.Companion.modelixWorkspacesDraftsRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesController.Companion.modelixWorkspacesInstancesRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesEnabledController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesEnabledController.Companion.modelixWorkspacesInstancesEnabledRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesWorkspacesController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesWorkspacesController.Companion.modelixWorkspacesWorkspacesRoutes
import org.modelix.services.workspaces.stubs.controllers.TypedApplicationCall
import org.modelix.services.workspaces.stubs.models.GitChangeDraft
import org.modelix.services.workspaces.stubs.models.GitChangeDraftList
import org.modelix.services.workspaces.stubs.models.GitRepository
import org.modelix.services.workspaces.stubs.models.WorkspaceConfig
import org.modelix.services.workspaces.stubs.models.WorkspaceInstance
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceEnabled
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceList
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceState
import org.modelix.services.workspaces.stubs.models.WorkspaceList
import org.modelix.workspaces.DEFAULT_MPS_VERSION
import org.modelix.workspaces.MavenRepository
import org.modelix.workspaces.Workspace
import java.util.UUID

class WorkspacesController(val manager: WorkspaceManager, val deployments: DeploymentManager) {

    private var workspaceInstances: WorkspaceInstanceList = WorkspaceInstanceList(emptyList())
    private val drafts: GitChangeDraftList = GitChangeDraftList(emptyList())

    fun install(route: Route) {
        route.install_()
    }

    private fun Route.install_() {
        modelixWorkspacesWorkspacesRoutes(object : ModelixWorkspacesWorkspacesController {
            override suspend fun getWorkspace(
                workspaceId: String,
                call: TypedApplicationCall<WorkspaceConfig>,
            ) {
                val workspace = manager.getWorkspaceForId(workspaceId)
                if (workspace == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondTyped(workspace.workspace.convert())
                }
            }

            override suspend fun listWorkspaces(call: TypedApplicationCall<WorkspaceList>) {
                call.respondTyped(
                    WorkspaceList(
                        workspaces = manager.getAllWorkspaces().map { it.convert() },
                    ),
                )
            }

            override suspend fun deleteWorkspace(
                workspaceId: String,
                call: ApplicationCall,
            ) {
                manager.removeWorkspace(workspaceId)
                call.respond(HttpStatusCode.OK)
            }

            override suspend fun updateWorkspace(
                workspaceId: String,
                legacyWorkspaceConfig: WorkspaceConfig,
                call: ApplicationCall,
            ) {
                val oldConfig = manager.getWorkspaceForId(workspaceId)?.workspace
                    ?: manager.newWorkspace(owner = call.getUserName())
                manager.update(
                    oldConfig.copy(
                        name = legacyWorkspaceConfig.name,
                        mpsVersion = legacyWorkspaceConfig.mpsVersion,
                        memoryLimit = legacyWorkspaceConfig.memoryLimit,
                        gitRepositories = legacyWorkspaceConfig.gitRepositories.map {
                            org.modelix.workspaces.GitRepository(it.url, null)
                        },
                        mavenRepositories = (legacyWorkspaceConfig.mavenRepositories ?: emptyList()).map {
                            MavenRepository(it.url)
                        }
                    ),
                )
                call.respond(HttpStatusCode.OK)
            }
        })

        modelixWorkspacesInstancesRoutes(object : ModelixWorkspacesInstancesController {
            override suspend fun getInstance(
                instanceId: String,
                call: TypedApplicationCall<WorkspaceInstance>,
            ) {
                val instance = workspaceInstances.instances.find { it.id == instanceId }
                if (instance == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondTyped(instance)
                }
            }

            override suspend fun listInstances(workspaceId: String?, call: TypedApplicationCall<WorkspaceInstanceList>) {
                val filteredInstances = if (workspaceId == null) {
                    workspaceInstances
                } else {
                    workspaceInstances.copy(instances = workspaceInstances.instances.filter { it.workspaceId == workspaceId })
                }
                call.respondTyped(filteredInstances)
            }

            override suspend fun createInstance(
                workspaceInstance: WorkspaceInstance,
                call: TypedApplicationCall<WorkspaceInstance>
            ) {
                workspaceInstances = workspaceInstances.copy(
                    instances = workspaceInstances.instances + workspaceInstance.copy(
                        id = UUID.randomUUID().toString(),
                        workspaceId = workspaceInstance.workspaceId,
                        drafts = emptyList(),
                        owner = call.getUserName(),
                        state = WorkspaceInstanceState.CREATED
                    )
                )
            }
        })

        modelixWorkspacesInstancesEnabledRoutes(object : ModelixWorkspacesInstancesEnabledController {
            override suspend fun enableInstance(
                instanceId: String,
                workspaceInstanceEnabled: WorkspaceInstanceEnabled,
                call: ApplicationCall
            ) {
                workspaceInstances = workspaceInstances.copy(
                    instances = workspaceInstances.instances.map {
                        if (it.id == instanceId) {
                            it.copy(enabled = workspaceInstanceEnabled.enabled)
                        } else {
                            it
                        }
                    }
                )
                call.respond(HttpStatusCode.OK)
            }
        })

        modelixWorkspacesDraftsRoutes(object : ModelixWorkspacesDraftsController {
            override suspend fun getDraft(
                draftId: String,
                call: TypedApplicationCall<GitChangeDraft>,
            ) {
                val draft = drafts.drafts.find { it.id == draftId }
                if (draft == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondTyped(draft)
                }
            }

            override suspend fun listDrafts(call: TypedApplicationCall<GitChangeDraftList>) {
                call.respondTyped(drafts)
            }
        })
    }

    private fun Workspace.convert() = WorkspaceConfig(
        id = id,
        name = name ?: "",
        mpsVersion = mpsVersion ?: DEFAULT_MPS_VERSION,
        memoryLimit = memoryLimit,
        gitRepositories = gitRepositories.map { GitRepository(it.url, null) },
        mavenRepositories = mavenRepositories.map { org.modelix.services.workspaces.stubs.models.MavenRepository(it.url) },
    )
}
