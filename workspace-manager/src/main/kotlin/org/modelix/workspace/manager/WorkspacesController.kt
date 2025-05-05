package org.modelix.workspace.manager

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.util.encodeBase64
import io.kubernetes.client.custom.Quantity
import org.modelix.authorization.NoPermissionException
import org.modelix.authorization.checkPermission
import org.modelix.authorization.getUnverifiedJwt
import org.modelix.authorization.getUserName
import org.modelix.services.workspaces.InternalWorkspaceInstanceConfig
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesDraftsController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesDraftsController.Companion.modelixWorkspacesDraftsRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesController.Companion.modelixWorkspacesInstancesRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesEnabledController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesInstancesEnabledController.Companion.modelixWorkspacesInstancesEnabledRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesTasksConfigController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesTasksConfigController.Companion.modelixWorkspacesTasksConfigRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesTasksContextTarGzController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesTasksContextTarGzController.Companion.modelixWorkspacesTasksContextTarGzRoutes
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesWorkspacesController
import org.modelix.services.workspaces.stubs.controllers.ModelixWorkspacesWorkspacesController.Companion.modelixWorkspacesWorkspacesRoutes
import org.modelix.services.workspaces.stubs.controllers.TypedApplicationCall
import org.modelix.services.workspaces.stubs.models.GitChangeDraft
import org.modelix.services.workspaces.stubs.models.GitChangeDraftList
import org.modelix.services.workspaces.stubs.models.GitCredentials
import org.modelix.services.workspaces.stubs.models.GitRepository
import org.modelix.services.workspaces.stubs.models.MavenArtifact
import org.modelix.services.workspaces.stubs.models.WorkspaceConfig
import org.modelix.services.workspaces.stubs.models.WorkspaceInstance
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceEnabled
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceList
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceState
import org.modelix.services.workspaces.stubs.models.WorkspaceList
import org.modelix.workspace.manager.WorkspaceJobQueue.Companion.HELM_PREFIX
import org.modelix.workspaces.Credentials
import org.modelix.workspaces.DEFAULT_MPS_VERSION
import org.modelix.workspaces.GenerationDependency
import org.modelix.workspaces.InternalWorkspaceConfig
import org.modelix.workspaces.MavenRepository
import org.modelix.workspaces.WorkspaceProgressItems
import org.modelix.workspaces.WorkspacesPermissionSchema
import java.util.UUID

class WorkspacesController(
    val manager: WorkspaceManager,
    val instancesManager: WorkspaceInstancesManager,
    val buildManager: WorkspaceBuildManager,
) {

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
                val instance = instancesManager.getInstancesList().find { it.instanceConfig.id == instanceId }
                if (instance == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondTyped(instance.instanceConfig)
                }
            }

            override suspend fun listInstances(workspaceId: String?, call: TypedApplicationCall<WorkspaceInstanceList>) {
                val allInstances = instancesManager.getInstancesList()
                val filteredInstances = if (workspaceId != null) {
                    allInstances.filter { it.workspaceConfig.id == workspaceId }
                } else {
                    allInstances
                }
                call.respondTyped(WorkspaceInstanceList(instances = filteredInstances.map { it.instanceConfig }))
            }

            override suspend fun createInstance(
                workspaceInstance: WorkspaceInstance,
                call: TypedApplicationCall<WorkspaceInstance>
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

                val workspaceConfig = manager.getWorkspaceForId(workspaceInstance.config.id)?.workspace
                if (workspaceConfig == null) {
                    call.respond(HttpStatusCode.NotFound, "Workspace ${workspaceInstance.config.id} not found")
                    return
                }

                instancesManager.updateInstancesList { list ->
                    list.filter { it.instanceId != workspaceInstance.id } + InternalWorkspaceInstanceConfig(
                        instanceConfig = workspaceInstance.copy(
                            id = UUID.randomUUID().toString(),
                            drafts = emptyList(),
                            owner = call.getUserName(),
                            state = WorkspaceInstanceState.CREATED,
                            readonly = readonly
                        ),
                        workspaceConfig = workspaceConfig.merge(workspaceInstance.config)
                    )
                }
            }

            override suspend fun deleteInstance(
                instanceId: String,
                call: ApplicationCall
            ) {
                instancesManager.updateInstancesList { list ->
                    list.filter { it.instanceId != instanceId }
                }
            }
        })

        modelixWorkspacesInstancesEnabledRoutes(object : ModelixWorkspacesInstancesEnabledController {
            override suspend fun enableInstance(
                instanceId: String,
                workspaceInstanceEnabled: WorkspaceInstanceEnabled,
                call: ApplicationCall
            ) {
                instancesManager.updateInstancesList { list ->
                    list.map {
                        if (it.instanceId == instanceId) {
                            it.copy(
                                instanceConfig = it.instanceConfig.copy(
                                    enabled = workspaceInstanceEnabled.enabled
                                )
                            )
                        } else {
                            it
                        }
                    }
                }
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

        modelixWorkspacesTasksConfigRoutes(object : ModelixWorkspacesTasksConfigController {
            override suspend fun getWorkspaceByTaskId(
                taskId: String,
                call: TypedApplicationCall<Any>
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
                call: TypedApplicationCall<ByteArray>
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
    }


    private suspend fun respondBuildContext(call: ApplicationCall, workspace: InternalWorkspaceConfig, taskId: UUID) {
        val httpProxy: String? = System.getenv("MODELIX_HTTP_PROXY")?.takeIf { it.isNotEmpty() }

        call.checkPermission(WorkspacesPermissionSchema.workspaces.workspace(workspace.id).config.readCredentials)

        // more extensive check to ensure only the build job has access
        if (!run {
                val token = call.principal<JWTPrincipal>()?.payload ?: return@run false
                if (!manager.jwtUtil.isAccessToken(token)) return@run false
                if (call.getUnverifiedJwt()?.keyId != manager.jwtUtil.getPrivateKey()?.keyID) return@run false
                true
            }
        ) {
            throw NoPermissionException("Only permitted to the workspace-job")
        }

        val mpsVersion = workspace.mpsVersion ?: DEFAULT_MPS_VERSION
        val jwtToken = manager.workspaceJobTokenGenerator(workspace)

        val containerMemoryBytes = Quantity.fromString(workspace.memoryLimit).number
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
                        val authHeader = git.credentials?.let {
                            manager.credentialsEncryption.decrypt(it)
                        }?.let {
                            """ -c http.extraheader="Authorization: Basic ${(it.user + ":" + it.password).encodeBase64()}""""
                        } ?: ""
    
                        listOf(
                            "mkdir -p $dir",
                            "cd $dir",
                            "git$authHeader clone ${git.url}",
                            "cd *",
                            "git checkout " + (git.commitHash ?: ("origin/" + git.branch)),
                        )
                    }.joinToString(" && ")
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


fun InternalWorkspaceConfig.convert() = WorkspaceConfig(
    id = id,
    name = name ?: "",
    mpsVersion = mpsVersion ?: DEFAULT_MPS_VERSION,
    memoryLimit = memoryLimit,
    gitRepositories = gitRepositories.map { GitRepository(it.url, null) },
    mavenRepositories = mavenRepositories.map { org.modelix.services.workspaces.stubs.models.MavenRepository(it.url) },
    mavenArtifacts = mavenDependencies.map {
        val parts = it.split(":")
        MavenArtifact(
            groupId = parts[0],
            artifactId = parts[1],
            version = parts.getOrNull(2)
        )
    }
)

fun WorkspaceConfig.convert() = InternalWorkspaceConfig(
    id = id,
    name = name,
    mpsVersion = mpsVersion,
    memoryLimit = memoryLimit,
    gitRepositories = gitRepositories.map { org.modelix.workspaces.GitRepository(it.url, null) },
    mavenRepositories = mavenRepositories?.map { MavenRepository(it.url) } ?: emptyList(),
    mavenDependencies = mavenArtifacts?.map { "${it.groupId}:${it.artifactId}:${it.version ?: "*"}" } ?: emptyList(),
)

fun InternalWorkspaceConfig.merge(other: WorkspaceConfig) = copy(
    name = other.name.takeIf { it.isNotEmpty() } ?: name,
    mpsVersion = other.mpsVersion.takeIf { it.isNotEmpty() } ?: mpsVersion,
    memoryLimit = other.memoryLimit.takeIf { it.isNotEmpty() } ?: memoryLimit,
    gitRepositories = gitRepositories.merge(other.gitRepositories),
    mavenRepositories = (mavenRepositories.map { it.url } + other.mavenRepositories.orEmpty().map { it.url }).distinct().map { MavenRepository(it) },
    mavenDependencies = (mavenDependencies + (other.mavenArtifacts ?: emptyList()).map { "${it.groupId}:${it.artifactId}:${it.version ?: "*"}" }).distinct(),
    ignoredModules = (ignoredModules + other.buildConfig?.ignoredModules.orEmpty()).distinct(),
    additionalGenerationDependencies = (additionalGenerationDependencies + other.buildConfig?.additionalGenerationDependencies.orEmpty().map { it.convert() }).distinct(),
    loadUsedModulesOnly = other.runConfig?.loadUsedModulesOnly ?: loadUsedModulesOnly,
)

fun List<org.modelix.workspaces.GitRepository>.merge(other: List<GitRepository>): List<org.modelix.workspaces.GitRepository> {
    val oldEntries = associateBy { it.url }
    val newEntries = other.associateBy { it.url }

    return (oldEntries.keys + newEntries.keys).map { url ->
        val oldEntry = oldEntries[url]
        val newEntry = newEntries[url]
        org.modelix.workspaces.GitRepository(
            url = url,
            name = oldEntry?.name,
            branch = oldEntry?.branch ?: "master",
            commitHash = oldEntry?.commitHash,
            paths = oldEntry?.paths ?: emptyList(),
            credentials = newEntry?.credentials?.convert() ?: oldEntry?.credentials
        )
    }
}

fun GitCredentials.convert() = Credentials(
    user = username,
    password = password
)

fun org.modelix.services.workspaces.stubs.models.GenerationDependency.convert() = GenerationDependency(
    from = from,
    to = to
)
