package org.modelix.workspace.manager

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.util.encodeBase64
import io.kubernetes.client.custom.Quantity
import org.modelix.authorization.getUserName
import org.modelix.services.gitconnector.GitConnectorManager
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesController.Companion.modelixWorkspacesInstancesRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesEnabledController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesEnabledController.Companion.modelixWorkspacesInstancesEnabledRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesStateController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesStateController.Companion.modelixWorkspacesInstancesStateRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesTasksConfigController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesTasksConfigController.Companion.modelixWorkspacesTasksConfigRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesTasksContextTarGzController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesTasksContextTarGzController.Companion.modelixWorkspacesTasksContextTarGzRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesWorkspacesController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesWorkspacesController.Companion.modelixWorkspacesWorkspacesRoutes
import org.modelix.services.workspaces.stubs.controllers.TypedApplicationCall
import org.modelix.services.workspaces.stubs.models.WorkspaceConfig
import org.modelix.services.workspaces.stubs.models.WorkspaceInstance
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceEnabled
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceList
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceState
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceStateObject
import org.modelix.services.workspaces.stubs.models.WorkspaceList
import org.modelix.workspace.manager.WorkspaceJobQueue.Companion.HELM_PREFIX
import org.modelix.workspaces.DEFAULT_MPS_VERSION
import org.modelix.workspaces.WorkspaceConfigForBuild
import org.modelix.workspaces.WorkspaceProgressItems
import org.modelix.workspaces.WorkspacesPermissionSchema
import java.util.UUID

class WorkspacesController(
    val manager: WorkspaceManager,
    val instancesManager: WorkspaceInstancesManager,
    val buildManager: WorkspaceBuildManager,
    val gitConnectorManager: GitConnectorManager,
) {

    fun install(route: Route) {
        route.install_()
    }

    private fun Route.install_() {
        modelixWorkspacesWorkspacesRoutes(object : ModelixWorkspacesWorkspacesController {
            override suspend fun getWorkspace(
                workspaceId: String,
                call: TypedApplicationCall<WorkspaceConfig>,
            ) {
                val workspace = manager.getWorkspace(workspaceId)
                if (workspace == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondTyped(workspace)
                }
            }

            override suspend fun listWorkspaces(call: TypedApplicationCall<WorkspaceList>) {
                call.respondTyped(WorkspaceList(workspaces = manager.getAllWorkspaces()))
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
                workspaceConfig: WorkspaceConfig,
                call: ApplicationCall,
            ) {
                manager.updateWorkspace(workspaceId) { oldConfig ->
                    workspaceConfig.copy(id = workspaceId)
                }
                call.respond(HttpStatusCode.OK)
            }

            override suspend fun createWorkspace(
                workspaceConfig: WorkspaceConfig,
                call: TypedApplicationCall<WorkspaceConfig>,
            ) {
                val newWorkspace = workspaceConfig.copy(
                    id = UUID.randomUUID().toString(),
                    mpsVersion = workspaceConfig.mpsVersion.takeIf { it.isNotEmpty() } ?: DEFAULT_MPS_VERSION,
                    memoryLimit = workspaceConfig.memoryLimit?.takeIf { it.isNotEmpty() }?.let { runCatching { Quantity(it).toSuffixedString() }.getOrNull() } ?: "2Gi",
                )
                manager.putWorkspace(newWorkspace)
                call.getUserName()?.let { manager.assignOwner(newWorkspace.id, it) }
                call.respondTyped(newWorkspace)
            }
        })

        modelixWorkspacesInstancesRoutes(object : ModelixWorkspacesInstancesController {
            override suspend fun getInstance(
                instanceId: String,
                call: TypedApplicationCall<WorkspaceInstance>,
            ) {
                val instance = instancesManager.getInstancesMap()[instanceId]
                if (instance == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondTyped(instance)
                }
            }

            override suspend fun listInstances(workspaceId: String?, call: TypedApplicationCall<WorkspaceInstanceList>) {
                val allInstances = instancesManager.getInstancesMap().values
                val filteredInstances = if (workspaceId != null) {
                    allInstances.filter { it.config.id == workspaceId }
                } else {
                    allInstances
                }
                val states = instancesManager.getInstanceStates()
                call.respondTyped(
                    WorkspaceInstanceList(
                        instances = filteredInstances.map {
                            it.copy(state = states[it.id]?.deriveState() ?: WorkspaceInstanceState.UNKNOWN)
                        },
                    ),
                )
            }

            override suspend fun createInstance(
                workspaceInstance: WorkspaceInstance,
                call: TypedApplicationCall<WorkspaceInstance>,
            ) {
                var readonly = workspaceInstance.readonly ?: false
                if (readonly == false) {
                    val token = call.principal<JWTPrincipal>()?.payload
                    if (token == null) {
                        readonly = true
                    } else {
                        val permissionEvaluator = manager.jwtUtil.createPermissionEvaluator(token, WorkspacesPermissionSchema.SCHEMA)
                        manager.accessControlPersistence.read().load(token, permissionEvaluator)
                        if (!permissionEvaluator.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspaceInstance.config.id).modelRepository.write)) {
                            readonly = true
                        }
                    }
                }

                val id = UUID.randomUUID().toString()
                instancesManager.updateInstancesMap { instances ->
                    instances.plus(
                        id to workspaceInstance.copy(
                            id = id,
                            owner = call.getUserName(),
                            state = WorkspaceInstanceState.CREATED,
                            readonly = readonly,
                        ),
                    )
                }
                call.respondTyped(instancesManager.getInstancesMap().getValue(id))
            }

            override suspend fun deleteInstance(
                instanceId: String,
                call: ApplicationCall,
            ) {
                instancesManager.updateInstancesMap { it - instanceId }
                call.respond(HttpStatusCode.OK)
            }
        })

        modelixWorkspacesInstancesEnabledRoutes(object : ModelixWorkspacesInstancesEnabledController {
            override suspend fun enableInstance(
                instanceId: String,
                workspaceInstanceEnabled: WorkspaceInstanceEnabled,
                call: ApplicationCall,
            ) {
                instancesManager.updateInstancesMap { instances ->
                    instances.plus(instanceId to instances.getValue(instanceId).copy(enabled = workspaceInstanceEnabled.enabled))
                }
                call.respond(HttpStatusCode.OK)
            }
        })

        modelixWorkspacesTasksConfigRoutes(object : ModelixWorkspacesTasksConfigController {
            override suspend fun getWorkspaceByTaskId(
                taskId: String,
                call: TypedApplicationCall<Any>,
            ) {
                val config = buildManager.getWorkspaceConfigByTaskId(UUID.fromString(taskId))
                if (config == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(config)
                }
            }
        })

        modelixWorkspacesTasksContextTarGzRoutes(object : ModelixWorkspacesTasksContextTarGzController {
            override suspend fun getContextForTaskId(
                taskId: String,
                call: TypedApplicationCall<ByteArray>,
            ) {
                val taskUUID = UUID.fromString(taskId)
                val config = buildManager.getWorkspaceConfigByTaskId(taskUUID)
                if (config == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    respondBuildContext(call, config, taskUUID)
                }
            }
        })

        modelixWorkspacesInstancesStateRoutes(object : ModelixWorkspacesInstancesStateController {
            override suspend fun changeInstanceState(
                instanceId: String,
                workspaceInstanceStateObject: WorkspaceInstanceStateObject,
                call: TypedApplicationCall<WorkspaceInstanceStateObject>,
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun getInstanceState(
                instanceId: String,
                call: TypedApplicationCall<WorkspaceInstanceStateObject>,
            ) {
                val state = instancesManager.getInstanceStates()[instanceId]?.deriveState() ?: WorkspaceInstanceState.UNKNOWN
                call.respondTyped(WorkspaceInstanceStateObject(state))
            }
        })
    }

    private suspend fun respondBuildContext(call: ApplicationCall, workspace: WorkspaceConfigForBuild, taskId: UUID) {
        val httpProxy: String? = System.getenv("MODELIX_HTTP_PROXY")?.takeIf { it.isNotEmpty() }

//        call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspace.id).config.readCredentials)
//
//        // more extensive check to ensure only the build job has access
//        if (!run {
//                val token = call.principal<JWTPrincipal>()?.payload ?: return@run false
//                if (!manager.jwtUtil.isAccessToken(token)) return@run false
//                if (call.getUnverifiedJwt()?.keyId != manager.jwtUtil.getPrivateKey()?.keyID) return@run false
//                true
//            }
//        ) {
//            throw NoPermissionException("Only permitted to the workspace-job")
//        }

        val mpsVersion = workspace.mpsVersion
        val jwtToken = manager.workspaceJobTokenGenerator(workspace)

        val containerMemoryBytes = workspace.memoryLimit.toBigDecimal()
        var maxHeapSizeBytes = heapSizeFromContainerLimit(containerMemoryBytes)
        val maxHeapSizeMega = (maxHeapSizeBytes / 1024.toBigDecimal() / 1024.toBigDecimal()).toBigInteger()

        call.respondTarGz { tar ->
            @Suppress("ktlint")
            tar.putFile("Dockerfile", """
                FROM ${HELM_PREFIX}docker-registry:5000/modelix/workspace-client-baseimage:${System.getenv("MPS_BASEIMAGE_VERSION")}-mps$mpsVersion
                
                ENV modelix_workspace_id=${workspace.id}  
                ENV modelix_workspace_task_id=${taskId}   
                ENV modelix_workspace_server=http://${HELM_PREFIX}workspace-manager:28104/      
                ENV INITIAL_JWT_TOKEN=$jwtToken  
                
                RUN /etc/cont-init.d/10-init-users.sh && /etc/cont-init.d/99-set-user-home.sh
                
                RUN sed -i.bak '/-Xmx/d' /mps/bin/mps64.vmoptions \
                    && sed -i.bak '/-XX:MaxRAMPercentage/d' /mps/bin/mps64.vmoptions \
                    && echo "-Xmx${maxHeapSizeMega}m" >> /mps/bin/mps64.vmoptions \
                    && cat /mps/bin/mps64.vmoptions > /mps/bin/mps.vmoptions
                
                COPY clone.sh /clone.sh
                RUN chmod +x /clone.sh && chown app:app /clone.sh
                USER app
                RUN /clone.sh
                USER root
                RUN rm /clone.sh
                USER app
                
                RUN rm -rf /mps-projects/default-mps-project
                
                RUN mkdir /config/home/job \
                    && cd /config/home/job \ 
                    && wget -q "http://${HELM_PREFIX}workspace-manager:28104/static/workspace-job.tar" \
                    && tar -xf workspace-job.tar \
                    && cd /mps-projects/workspace-${workspace.id} \
                    && /config/home/job/workspace-job/bin/workspace-job \
                    && rm -rf /config/home/job
                    
                RUN /update-recent-projects.sh \
                    && echo "${WorkspaceProgressItems().build.runIndexer.logMessageStart}" \
                    && ( /run-indexer.sh || echo "${WorkspaceProgressItems().build.runIndexer.logMessageFailed}" ) \
                    && echo "${WorkspaceProgressItems().build.runIndexer.logMessageDone}"
                
                USER root
            """.trimIndent().toByteArray())

            // Separate file for git command because they may contain the credentials
            // and the commands shouldn't appear in the log
            @Suppress("ktlint")
            tar.putFile("clone.sh", """
                #!/bin/sh
                
                echo "### START build-gitClone ###"
                
                ${if (httpProxy == null) "" else """
                    export http_proxy="$httpProxy"
                    export https_proxy="$httpProxy"
                    export HTTP_PROXY="$httpProxy"
                    export HTTPS_PROXY="$httpProxy"
                """}
                
                if ${
                    workspace.gitRepositories.flatMapIndexed { index, git ->
                        val dir = "/mps-projects/workspace-${workspace.id}/git/$index/"
    
                        // https://learn.microsoft.com/en-us/azure/devops/organizations/accounts/use-personal-access-tokens-to-authenticate?view=azure-devops&tabs=Linux#use-a-pat
                        val authHeader = git.password?.let {
                            """ -c http.extraheader="Authorization: Basic ${(git.username.orEmpty() + ":" + git.password).encodeBase64()}""""
                        } ?: ""
    
                        listOf(
                            "mkdir -p $dir",
                            "cd $dir",
                            "git$authHeader clone \"${git.url}\"",
                            "cd *",
                            "git checkout -b \"${git.branch}\" \"${git.commitHash}\"",
                            "git branch --set-upstream-to=\"origin/${git.branch}\"",
                        )
                    }.ifEmpty { listOf("true") }.joinToString(" && ")
                }
                then
                  echo "### DONE build-gitClone ###"
                else
                  echo "### FAILED build-gitClone ###"
                fi
            """.lines().joinToString("\n") { it.trim() }.toByteArray())
        }
    }
}
