package org.modelix.workspace.manager

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.AppsV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.CoreV1Event
import io.kubernetes.client.openapi.models.CoreV1EventList
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1EnvVar
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1Service
import io.kubernetes.client.openapi.models.V1ServicePort
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Yaml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.modelix.authorization.ModelixJWTUtil
import org.modelix.authorization.permissions.AccessControlData
import org.modelix.authorization.permissions.PermissionParts
import org.modelix.model.lazy.BranchReference
import org.modelix.model.server.ModelServerPermissionSchema
import org.modelix.services.gitconnector.GitConnectorManager
import org.modelix.services.workspaces.ContinuingCallback
import org.modelix.services.workspaces.configForBuild
import org.modelix.services.workspaces.executeSuspending
import org.modelix.services.workspaces.metadata
import org.modelix.services.workspaces.spec
import org.modelix.services.workspaces.stubs.models.WorkspaceInstance
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceState
import org.modelix.workspaces.WorkspacesPermissionSchema
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlin.coroutines.suspendCoroutine

private val LOG = KotlinLogging.logger {}

private data class InstancesManagerState(
    val instances: Map<String, WorkspaceInstance> = emptyMap(),
)

class WorkspaceInstanceStateValues(
    var imageTask: WorkspaceImageTask? = null,
    var draftBranches: List<Result<BranchReference>?> = emptyList(),
    var deployment: V1Deployment? = null,
    var pod: V1Pod? = null,
    var enabled: Boolean = false,
) {
    fun deriveState(): WorkspaceInstanceState {
        val image = imageTask?.getOutput()
        val imageTaskState = imageTask?.getState()
        return when {
            !enabled -> WorkspaceInstanceState.DISABLED
            (deployment?.status?.readyReplicas ?: 0) >= 1 -> WorkspaceInstanceState.RUNNING
            deployment != null -> WorkspaceInstanceState.LAUNCHING
            image?.isFailure == true -> WorkspaceInstanceState.BUILD_FAILED
            image?.getOrNull() != null && draftBranches.all { it?.getOrNull() != null } -> WorkspaceInstanceState.LAUNCHING
            else -> when (imageTaskState) {
                null -> WorkspaceInstanceState.CREATED
                TaskState.CANCELLED -> WorkspaceInstanceState.BUILD_FAILED
                TaskState.CREATED -> WorkspaceInstanceState.WAITING_FOR_BUILD
                TaskState.ACTIVE -> WorkspaceInstanceState.WAITING_FOR_BUILD
                TaskState.COMPLETED -> WorkspaceInstanceState.WAITING_FOR_BUILD
                TaskState.UNKNOWN -> WorkspaceInstanceState.WAITING_FOR_BUILD
            }
        }
    }

    fun statusText(): String {
        val text = ArrayList<String>()

        if (!enabled) text += "Instance is disabled."
        if (deployment != null) text += "Deployment created."
        if ((deployment?.status?.readyReplicas ?: 0) >= 1) text += "Pod is ready."
        if (imageTask == null) {
            text += "Build task not created yet."
        } else {
            text += "Build task state: ${imageTask?.getState()}."
            imageTask?.getOutput()?.exceptionOrNull()?.message?.let {
                text += it
            }
            if (imageTask?.getOutput()?.getOrNull() != null) {
                text += "Image created."
            }
        }
        for (draftBranchTask in draftBranches) {
            if (draftBranchTask?.getOrNull() != null) text += "Draft branch created."
            draftBranchTask?.exceptionOrNull()?.message?.let {
                text += "Draft branch creation failed: $it."
            }
        }

        return text.joinToString(" ")
    }
}

class WorkspaceInstancesManager(
    val workspaceManager: WorkspaceManager,
    val buildManager: WorkspaceBuildManager,
    val gitManager: GitConnectorManager,
    val coroutinesScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    companion object {
        val KUBERNETES_NAMESPACE = System.getenv("WORKSPACE_CLIENT_NAMESPACE") ?: "default"
        val INSTANCE_PREFIX = System.getenv("WORKSPACE_CLIENT_PREFIX") ?: "wsclt-"
        val INTERNAL_DOCKER_REGISTRY_AUTHORITY = requireNotNull(System.getenv("INTERNAL_DOCKER_REGISTRY_AUTHORITY"))
        const val TIMEOUT_SECONDS = 10
        const val INSTANCE_ID_LABEL = "modelix.workspace.instance.id"

        fun WorkspaceInstance.instanceName() = INSTANCE_PREFIX + id
    }

    init {
        Configuration.setDefaultApiClient(ClientBuilder.cluster().build())
    }

    private val indexWasReady: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val jwtUtil = ModelixJWTUtil().also { it.loadKeysFromEnvironment() }

    private val reconciler = Reconciler(coroutinesScope, InstancesManagerState(), ::reconcile)
    private val reconcileJob = coroutinesScope.launch {
        while (isActive) {
            delay(2000)
            reconciler.trigger()
        }
    }

    fun dispose() {
        reconciler.dispose()
        reconcileJob.cancel("disposed")
    }

    fun updateInstancesMap(updater: (Map<String, WorkspaceInstance>) -> Map<String, WorkspaceInstance>) {
        reconciler.updateDesiredState {
            it.copy(instances = updater(it.instances))
        }
    }

    fun getInstancesMap(): Map<String, WorkspaceInstance> = reconciler.getDesiredState().instances

    suspend fun getInstanceStates(): Map<String, WorkspaceInstanceStateValues> {
        val managerState = reconciler.getDesiredState()

        val instances: Map<String, WorkspaceInstance> = managerState.instances
        val stateValues = instances.keys.associateWith { WorkspaceInstanceStateValues() }

        for ((instanceId, deployment) in getExistingDeployments()) {
            stateValues[instanceId]?.deployment = deployment
        }

        for ((instanceId, pod) in getExistingPods()) {
            stateValues[instanceId]?.pod = pod
        }

        for (config in instances.values) {
            val values = stateValues[config.id] ?: continue
            values.enabled = config.enabled

            val imageTask = buildManager.getOrCreateWorkspaceImageTask(config.configForBuild(gitManager))
            values.imageTask = imageTask

            values.draftBranches = config.drafts.orEmpty().map { draftId ->
                gitManager.getOrCreateDraftPreparationTask(draftId).also { it.launch() }
            }.map { it.getOutput() }
        }

        return stateValues
    }

    private suspend fun getExistingDeployments(): Map<String, V1Deployment> {
        val existingDeployments: MutableMap<String, V1Deployment> = HashMap()
        val appsApi = AppsV1Api()
        val deployments = suspendCoroutine {
            appsApi
                .listNamespacedDeployment(KUBERNETES_NAMESPACE)
                .timeoutSeconds(TIMEOUT_SECONDS)
                .executeAsync(ContinuingCallback(it))
        }
        for (deployment in deployments.items) {
            val instanceId = deployment.metadata?.labels?.get(INSTANCE_ID_LABEL) ?: continue
            existingDeployments[instanceId] = deployment
        }
        return existingDeployments
    }

    private suspend fun getExistingPods(): Map<String, V1Pod> {
        val existingPods: MutableMap<String, V1Pod> = HashMap()
        val coreApi = CoreV1Api()
        val pods = suspendCoroutine {
            coreApi
                .listNamespacedPod(KUBERNETES_NAMESPACE)
                .timeoutSeconds(TIMEOUT_SECONDS)
                .executeAsync(ContinuingCallback(it))
        }
        for (pod in pods.items) {
            val instanceId = pod.metadata?.labels?.get(INSTANCE_ID_LABEL) ?: continue
            existingPods[instanceId] = pod
        }
        return existingPods
    }

    private suspend fun reconcile(newState: InstancesManagerState) {
        val appsApi = AppsV1Api()
        val coreApi = CoreV1Api()
        val expectedInstances = newState.instances.filter { it.value.enabled }
        val existingInstances = getExistingDeployments()

        val toAdd = expectedInstances - existingInstances.keys
        val toRemove = existingInstances - expectedInstances.keys
        for (deployment in toRemove.values) {
            val name = deployment.metadata.name
            try {
                appsApi.deleteNamespacedDeployment(name, KUBERNETES_NAMESPACE)
                    .execute()
            } catch (e: Exception) {
                LOG.error("Failed to delete deployment $deployment", e)
            }
            try {
                coreApi.deleteNamespacedService(name, KUBERNETES_NAMESPACE)
                    .execute()
            } catch (e: Exception) {
                LOG.error("Failed to delete service $deployment", e)
            }
        }
        for (instance in toAdd.values) {
            try {
                val workspaceConfig = instance.configForBuild(gitManager)
                buildManager.getOrCreateWorkspaceImageTask(workspaceConfig)
                val imageTask = buildManager.getOrCreateWorkspaceImageTask(workspaceConfig).also { it.launch() }

                val draftPreparationTasks = instance.drafts.orEmpty().map { draftId ->
                    gitManager.getOrCreateDraftPreparationTask(draftId).also { it.launch() }
                }
                val draftBranches = draftPreparationTasks.map { it.getOutput()?.getOrNull() }

                val image = imageTask.getOutput()?.getOrNull()
                if (image != null && draftBranches.all { it != null }) {
                    createDeployment(instance, image, draftBranches.map { it!! })
                    createService(instance)
                }
            } catch (e: Exception) {
                LOG.error("Failed to create deployment for workspace instance ${instance.id}", e)
            }
        }

        synchronized(indexWasReady) {
            indexWasReady.removeAll(indexWasReady - expectedInstances.keys)
        }
    }

    fun getTargetHost(instanceId: UUID): String {
        return INSTANCE_PREFIX + instanceId
    }

    fun getDeployment(name: String, attempts: Int): V1Deployment? {
        val appsApi = AppsV1Api()
        var deployment: V1Deployment? = null
        for (i in 0 until attempts) {
            try {
                deployment = appsApi.readNamespacedDeployment(name, KUBERNETES_NAMESPACE).execute()
            } catch (ex: ApiException) {
                LOG.error("Failed to read deployment: $name", ex)
            }
            if (deployment != null) break
            try {
                Thread.sleep(1000L)
            } catch (e: InterruptedException) {
                return null
            }
        }
        return deployment
    }

    fun getPod(deploymentName: String): V1Pod? {
        try {
            val coreApi = CoreV1Api()
            val pods = coreApi.listNamespacedPod(KUBERNETES_NAMESPACE).timeoutSeconds(TIMEOUT_SECONDS).execute()
            for (pod in pods.items) {
                if (!pod.metadata!!.name!!.startsWith(deploymentName)) continue
                return pod
            }
        } catch (e: Exception) {
            LOG.error("", e)
            return null
        }
        return null
    }

    fun getPodLogs(instanceId: String): String? {
        try {
            val coreApi = CoreV1Api()
            val pods = coreApi.listNamespacedPod(KUBERNETES_NAMESPACE).timeoutSeconds(TIMEOUT_SECONDS).execute()
            for (pod in pods.items) {
                if (pod.metadata!!.labels?.get(INSTANCE_ID_LABEL) != instanceId) continue
                return coreApi
                    .readNamespacedPodLog(pod.metadata!!.name, KUBERNETES_NAMESPACE)
                    .container(pod.spec!!.containers[0].name)
                    .pretty("true")
                    .tailLines(10_000)
                    .execute()
            }
        } catch (e: Exception) {
            LOG.error("", e)
            return null
        }
        return null
    }

    fun isIndexerReady(instanceId: String): Boolean {
        // avoid doing the expensive check again
        // also the relevant line may be truncated from the log if there is too much output
        if (indexWasReady.contains(instanceId)) return true

        val log = getPodLogs(instanceId) ?: return false
        val isReady = log.contains("### Index is ready")
        if (isReady) {
            indexWasReady.add(instanceId)
        }
        return isReady
    }

    fun getEvents(deploymentName: String): List<CoreV1Event> {
        val events: CoreV1EventList = CoreV1Api()
            .listNamespacedEvent(KUBERNETES_NAMESPACE)
            .timeoutSeconds(TIMEOUT_SECONDS)
            .execute()
        return events.items
            .filter { (it.involvedObject.name ?: "").contains(deploymentName) }
    }

    suspend fun createDeployment(
        workspaceInstance: WorkspaceInstance,
        image: ImageNameAndTag,
        draftBranches: List<BranchReference>,
    ): V1Deployment {
        val instanceName = workspaceInstance.instanceName()
        val workspaceId = workspaceInstance.config.id

        val appsApi = AppsV1Api()

        val existingDeployment = appsApi.listNamespacedDeployment(KUBERNETES_NAMESPACE)
            .timeoutSeconds(TIMEOUT_SECONDS)
            .executeSuspending()
            .items
            .firstOrNull { it.metadata.labels?.get(INSTANCE_ID_LABEL) == workspaceInstance.id }

        if (existingDeployment != null) return existingDeployment

        val deployment = Yaml.loadAs(File("/workspace-client-templates/deployment"), V1Deployment::class.java)
        deployment.metadata {
            name(instanceName)
            putLabelsItem(INSTANCE_ID_LABEL, workspaceInstance.id)
        }
        deployment.spec {
            selector.putMatchLabelsItem(INSTANCE_ID_LABEL, workspaceInstance.id)
            template.metadata!!.putLabelsItem(INSTANCE_ID_LABEL, workspaceInstance.id)
            replicas(1)
            template.spec!!.containers[0].apply {
                addEnvItem(V1EnvVar().name("modelix_workspace_id").value(workspaceId))
                draftBranches.firstOrNull()?.let { draftBranch ->
                    addEnvItem(V1EnvVar().name("REPOSITORY_ID").value(draftBranch.repositoryId.id))
                    addEnvItem(V1EnvVar().name("REPOSITORY_BRANCH").value(draftBranch.branchName))
                }
                // addEnvItem(V1EnvVar().name("modelix_workspace_hash").value(workspace.hash().hash))
                addEnvItem(V1EnvVar().name("WORKSPACE_MODEL_SYNC_ENABLED").value(true.toString()))
            }
        }

        val hasWritePermission = workspaceInstance.readonly == false
        val newPermissions = ArrayList<PermissionParts>()
        newPermissions += WorkspacesPermissionSchema.workspaces.workspace(workspaceId).config.read
        for (draft in workspaceInstance.drafts.orEmpty().mapNotNull { gitManager.getDraft(it) }) {
            val gitRepo = gitManager.getRepository(draft.gitRepositoryId) ?: continue
            val modelixRepo = gitRepo.modelixRepository ?: continue
            newPermissions += ModelServerPermissionSchema.repository(modelixRepo).branch(draft.modelixBranchName)
                .let { if (hasWritePermission) it.write else it.read }
        }

        val newToken = jwtUtil.createAccessToken(workspaceInstance.owner ?: "workspace-user@modelix.org", newPermissions.map { it.fullId })
        deployment.spec!!.template.spec!!.containers[0].addEnvItem(V1EnvVar().name("INITIAL_JWT_TOKEN").value(newToken))
        loadWorkspaceSpecificValues(workspaceInstance, deployment, image)
        return appsApi.createNamespacedDeployment(KUBERNETES_NAMESPACE, deployment).executeSuspending()
    }

    suspend fun createService(
        workspaceInstance: WorkspaceInstance,
    ): V1Service {
        val instanceName = workspaceInstance.instanceName()
        val coreApi = CoreV1Api()
        val existingService = coreApi.listNamespacedService(KUBERNETES_NAMESPACE)
            .timeoutSeconds(TIMEOUT_SECONDS)
            .executeSuspending()
            .items
            .firstOrNull { it.metadata.labels?.get(INSTANCE_ID_LABEL) == workspaceInstance.id }
        if (existingService != null) return existingService

        val service = Yaml.loadAs(File("/workspace-client-templates/service"), V1Service::class.java)
        service.spec!!.ports!!.forEach { p: V1ServicePort -> p.nodePort(null) }
        service.metadata!!.name(instanceName)
        service.metadata!!.putLabelsItem(INSTANCE_ID_LABEL, workspaceInstance.id)
        service.spec!!.putSelectorItem(INSTANCE_ID_LABEL, workspaceInstance.id)
        println("Creating service: ")
        println(Yaml.dump(service))
        return coreApi.createNamespacedService(KUBERNETES_NAMESPACE, service).execute()
    }

    private fun loadWorkspaceSpecificValues(workspaceInstance: WorkspaceInstance, deployment: V1Deployment, image: ImageNameAndTag) {
        try {
            val container = deployment.spec!!.template.spec!!.containers[0]

            // The image registry is made available to the container runtime via a NodePort
            // localhost in this case is the kubernetes node, not the instances-manager
            container.image = "${INTERNAL_DOCKER_REGISTRY_AUTHORITY}/${image.name}:${image.tag}"

            val resources = container.resources ?: return
            val memoryLimit = Quantity.fromString(workspaceInstance.config.memoryLimit)
            val limits = resources.limits
            if (limits != null) limits["memory"] = memoryLimit
            val requests = resources.requests
            if (requests != null) requests["memory"] = memoryLimit
        } catch (ex: Exception) {
            LOG.error("Failed to configure the deployment for the workspace ${workspaceInstance.config.id}", ex)
        }
    }

    private fun getAccessControlData(): AccessControlData {
        return workspaceManager.accessControlPersistence.read()
    }
}
