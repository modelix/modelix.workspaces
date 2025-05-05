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
import mu.KotlinLogging
import org.modelix.authorization.ModelixJWTUtil
import org.modelix.authorization.permissions.AccessControlData
import org.modelix.authorization.permissions.PermissionParts
import org.modelix.instancesmanager.DeploymentManager
import org.modelix.instancesmanager.InstanceName
import org.modelix.model.server.ModelServerPermissionSchema
import org.modelix.services.workspaces.InternalWorkspaceInstanceConfig
import org.modelix.services.workspaces.executeSuspending
import org.modelix.services.workspaces.metadata
import org.modelix.services.workspaces.spec
import org.modelix.services.workspaces.stubs.models.WorkspaceConfig
import org.modelix.services.workspaces.stubs.models.WorkspaceInstance
import org.modelix.services.workspaces.stubs.models.WorkspaceInstanceList
import org.modelix.workspaces.InternalWorkspaceConfig
import org.modelix.workspaces.WorkspaceAndHash
import org.modelix.workspaces.WorkspaceHash
import org.modelix.workspaces.WorkspacesPermissionSchema
import java.io.File
import java.util.Collections
import java.util.regex.Pattern

private val LOG = KotlinLogging.logger {}

data class InstancesState(
    val instances: List<InternalWorkspaceInstanceConfig> = emptyList(),
    val images: Map<InternalWorkspaceConfig, ImageNameAndTag> = emptyMap()
)

class WorkspaceInstancesManager(
    val workspaceManager: WorkspaceManager,
    val buildManager: WorkspaceBuildManager,
    val coroutinesScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    companion object {
        val KUBERNETES_NAMESPACE = System.getenv("WORKSPACE_CLIENT_NAMESPACE") ?: "default"
        val INSTANCE_PREFIX = System.getenv("WORKSPACE_CLIENT_PREFIX") ?: "wsclt-"
        val WORKSPACE_CLIENT_DEPLOYMENT_NAME = System.getenv("WORKSPACE_CLIENT_DEPLOYMENT_NAME") ?: "workspace-client"
        val WORKSPACE_PATTERN = Pattern.compile("workspace-([a-f0-9]+)-([a-zA-Z0-9\\-_\\*]+)")
        val INTERNAL_DOCKER_REGISTRY_AUTHORITY = requireNotNull(System.getenv("INTERNAL_DOCKER_REGISTRY_AUTHORITY"))
        const val TIMEOUT_SECONDS = 10
        const val INSTANCE_ID_LABEL = "modelix.workspace.instance.id"

        fun WorkspaceInstance.instanceName() = InstanceName(INSTANCE_PREFIX + id)
    }

    init {
        Configuration.setDefaultApiClient(ClientBuilder.cluster().build())
    }

    private val indexWasReady: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val jwtUtil = ModelixJWTUtil().also { it.loadKeysFromEnvironment() }

    private val reconciler = Reconciler(coroutinesScope, InstancesState(), ::reconcile)
    private val imageUpdateTasks = ReusableTasks<InternalWorkspaceConfig, ImageUpdateTask>()

    fun dispose() {
        reconciler.dispose()
    }

    fun updateInstancesList(updater: (List<InternalWorkspaceInstanceConfig>) -> List<InternalWorkspaceInstanceConfig>) {
        reconciler.updateDesiredState {
            it.copy(instances = updater(it.instances))
        }
    }

    fun getInstancesList(): List<InternalWorkspaceInstanceConfig> = reconciler.getDesiredState().instances

    private fun getExistingDeployments(): Map<String, V1Deployment> {
        val existingDeployments: MutableMap<String, V1Deployment> = HashMap()
        val appsApi = AppsV1Api()
        val deployments = appsApi
            .listNamespacedDeployment(KUBERNETES_NAMESPACE)
            .timeoutSeconds(TIMEOUT_SECONDS)
            .execute()
        for (deployment in deployments.items) {
            val instanceId = deployment.metadata?.labels?.get(INSTANCE_ID_LABEL) ?: continue
            existingDeployments[instanceId] = deployment
        }
        return existingDeployments
    }

    private suspend fun reconcile(newState: InstancesState) {
        val appsApi = AppsV1Api()
        val coreApi = CoreV1Api()
        val expectedInstances = newState.instances.filter { it.instanceConfig.enabled }.associateBy { it.instanceConfig.id }
        val existingInstances = getExistingDeployments()

        val toAdd = expectedInstances - existingInstances.keys
        val toRemove = existingInstances - expectedInstances.keys
        for (deployment in toRemove.values) {
            val name = deployment.metadata.name
            try {
                appsApi.deleteNamespacedDeployment(name, DeploymentManager.Companion.KUBERNETES_NAMESPACE)
                    .execute()
            } catch (e: Exception) {
                LOG.error("Failed to delete deployment $deployment", e)
            }
            try {
                coreApi.deleteNamespacedService(name, DeploymentManager.Companion.KUBERNETES_NAMESPACE)
                    .execute()
            } catch (e: Exception) {
                LOG.error("Failed to delete service $deployment", e)
            }
        }
        for (instance in toAdd.values) {
            try {
                val workspaceConfig = instance.workspaceConfig
                val image = newState.images[workspaceConfig.normalizeForBuild()]
                if (image == null) {
                    imageUpdateTasks.getOrCreateTask(workspaceConfig.normalizeForBuild()) {
                        ImageUpdateTask(buildManager.getOrCreateWorkspaceImageTask(workspaceConfig))
                            .also { it.launch() }
                    }
                } else {
                    createDeployment(instance.instanceConfig, image)
                    createService(instance.instanceConfig)
                }
            } catch (e: Exception) {
                LOG.error("Failed to create deployment for workspace instance ${instance.instanceConfig.id}", e)
            }
        }

        synchronized(indexWasReady) {
            indexWasReady.removeAll(indexWasReady - expectedInstances.keys)
        }
    }

    fun getDeployment(name: InstanceName, attempts: Int): V1Deployment? {
        val appsApi = AppsV1Api()
        var deployment: V1Deployment? = null
        for (i in 0 until attempts) {
            try {
                deployment = appsApi.readNamespacedDeployment(name.name, KUBERNETES_NAMESPACE).execute()
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

    fun getPod(deploymentName: InstanceName): V1Pod? {
        try {
            val coreApi = CoreV1Api()
            val pods = coreApi.listNamespacedPod(KUBERNETES_NAMESPACE).timeoutSeconds(TIMEOUT_SECONDS).execute()
            for (pod in pods.items) {
                if (!pod.metadata!!.name!!.startsWith(deploymentName.name)) continue
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
    ): V1Deployment {
        val instanceName = workspaceInstance.instanceName()
        val workspaceId = workspaceInstance.config.id

        val appsApi = AppsV1Api()

        val existingDeployment = appsApi.listNamespacedDeployment(KUBERNETES_NAMESPACE)
            .labelSelector("$INSTANCE_ID_LABEL=${workspaceInstance.id}")
            .timeoutSeconds(TIMEOUT_SECONDS)
            .executeSuspending()
            .items
            .firstOrNull()

        if (existingDeployment != null) return existingDeployment

        val deployment = Yaml.loadAs(File("/workspace-client-templates/deployment"), V1Deployment::class.java)
        deployment.metadata {
            name(instanceName.name)
            putLabelsItem(INSTANCE_ID_LABEL, workspaceInstance.id)
        }
        deployment.spec {
            selector.putMatchLabelsItem(INSTANCE_ID_LABEL, workspaceInstance.id)
            template.metadata!!.putLabelsItem(INSTANCE_ID_LABEL, workspaceInstance.id)
            replicas(1)
            template.spec!!.containers[0].apply {
                addEnvItem(V1EnvVar().name("modelix_workspace_id").value(workspaceId))
                addEnvItem(V1EnvVar().name("REPOSITORY_ID").value("workspace_$workspaceId"))
                //addEnvItem(V1EnvVar().name("modelix_workspace_hash").value(workspace.hash().hash))
                addEnvItem(V1EnvVar().name("WORKSPACE_MODEL_SYNC_ENABLED").value(false.toString()))
            }
        }

        val hasWritePermission = workspaceInstance.readonly == false
        val newPermissions = ArrayList<PermissionParts>()
        newPermissions += WorkspacesPermissionSchema.workspaces.workspace(workspaceId).config.read
        newPermissions += ModelServerPermissionSchema.repository("workspace_" + workspaceId)
            .let { if (hasWritePermission) it.write else it.read }

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
            .labelSelector("$INSTANCE_ID_LABEL=${workspaceInstance.id}")
            .timeoutSeconds(TIMEOUT_SECONDS)
            .executeSuspending()
            .items
            .firstOrNull()
        if (existingService != null) return existingService

        val service = Yaml.loadAs(File("/workspace-client-templates/service"), V1Service::class.java)
        service.spec!!.ports!!.forEach { p: V1ServicePort -> p.nodePort(null) }
        service.metadata!!.name(instanceName.name)
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

    private fun getWorkspaceByHash(hash: WorkspaceHash): WorkspaceAndHash {
        return requireNotNull(workspaceManager.getWorkspaceForHash(hash)) {
            "Workspace not found: $hash"
        }
    }

    private fun getAccessControlData(): AccessControlData {
        return workspaceManager.accessControlPersistence.read()
    }

    inner class ImageUpdateTask(
        val imageTask: WorkspaceImageTask,
    ) : Task<Unit>(coroutinesScope) {
        override suspend fun process() {
            val image = imageTask.waitForOutput()
            reconciler.updateDesiredState {
                it.copy(
                    images = it.images + (imageTask.workspaceConfig to image)
                )
            }
        }
    }
}
