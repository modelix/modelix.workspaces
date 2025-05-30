/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modelix.instancesmanager

import com.google.common.cache.CacheBuilder
import io.ktor.server.auth.jwt.JWTPrincipal
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.collections4.map.LRUMap
import org.eclipse.jetty.server.Request
import org.modelix.authorization.ModelixJWTUtil
import org.modelix.authorization.getUserName
import org.modelix.authorization.permissions.AccessControlData
import org.modelix.authorization.permissions.PermissionParts
import org.modelix.model.server.ModelServerPermissionSchema
import org.modelix.workspace.manager.WorkspaceManager
import org.modelix.workspaces.WorkspaceAndHash
import org.modelix.workspaces.WorkspaceBuildStatus
import org.modelix.workspaces.WorkspaceHash
import org.modelix.workspaces.WorkspacesPermissionSchema
import org.modelix.workspaces.withHash
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.regex.Pattern
import javax.servlet.http.HttpServletRequest
import kotlin.time.Duration.Companion.seconds

const val TIMEOUT_SECONDS = 10

class DeploymentManager(val workspaceManager: WorkspaceManager) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val cleanupJob: Job
    private val managerId = java.lang.Long.toHexString(System.currentTimeMillis() / 1000)
    private val deploymentSuffixSequence = AtomicLong(0xf)
    private val assignments = Collections.synchronizedMap(HashMap<WorkspaceHash, Assignments>())
    private val disabledInstances = HashSet<InstanceName>()
    private val dirty = AtomicBoolean(true)
    private val jwtUtil = ModelixJWTUtil().also { it.loadKeysFromEnvironment() }
    private val userTokens: MutableMap<InstanceOwner, JWTPrincipal> = Collections.synchronizedMap(HashMap())
    private val reconcileLock = Any()
    private val indexWasReady: MutableSet<InstanceName> = Collections.synchronizedSet(HashSet())

    init {
        Configuration.setDefaultApiClient(ClientBuilder.cluster().build())
        reconcileDeployments()
        cleanupJob = coroutineScope.launch {
            while (true) {
                try {
                    createAssignmentsForAllWorkspaces()
                    reconcileDeployments()
                } catch (ex: Exception) {
                    LOG.error("", ex)
                }
                delay(10.seconds)
            }
        }
    }

    private fun getAssignments(workspace: WorkspaceAndHash): Assignments {
        return assignments.getOrPut(workspace.hash()) { Assignments(workspace) }
    }

    private fun getWorkspaceByHash(hash: WorkspaceHash): WorkspaceAndHash {
        return requireNotNull(workspaceManager.getWorkspaceForHash(hash)) {
            "Workspace not found: $hash"
        }
    }

    private fun getAccessControlData(): AccessControlData {
        return workspaceManager.accessControlPersistence.read()
    }

    private val statusCache = CacheBuilder<WorkspaceHash, WorkspaceBuildStatus>.newBuilder()
        .expireAfterWrite(1, TimeUnit.SECONDS)
        .build<WorkspaceHash, WorkspaceBuildStatus>()
    fun getWorkspaceStatus(workspaceHash: WorkspaceHash): WorkspaceBuildStatus {
        return workspaceManager.buildWorkspaceDownloadFileAsync(workspaceHash).status
    }

    fun getWorkspaceBuildLog(workspaceHash: WorkspaceHash): String {
        return workspaceManager.buildWorkspaceDownloadFileAsync(workspaceHash).getLog()
    }

    fun getAssignments(): List<AssignmentData> {
        val latestWorkspaces = workspaceManager.getAllWorkspaces()
        var hash2workspace: Map<WorkspaceHash, WorkspaceAndHash> =
            latestWorkspaces.map { it.withHash() }.associateBy { it.hash() }
        val latestWorkspaceHashes = hash2workspace.keys.toSet()

        var assignmentsCopy: HashMap<WorkspaceHash, Assignments>
        synchronized(assignments) {
            assignmentsCopy = HashMap(assignments)
        }

        hash2workspace += assignmentsCopy.map { it.key to it.value.workspace }

        return hash2workspace.map {
            val assignment = assignmentsCopy[it.key]
            val workspace = it.value
            AssignmentData(
                workspace = workspace,
                unassignedInstances = assignment?.getNumberOfUnassigned() ?: 0,
                (assignment?.listDeployments() ?: emptyList()).map { deployment ->
                    InstanceStatus(
                        workspace,
                        deployment.first,
                        deployment.second,
                        disabledInstances.contains(deployment.second),
                    )
                },
                isLatest = latestWorkspaceHashes.contains(it.key),
            )
        }
    }

    fun listDeployments(): List<InstanceStatus> {
        return assignments.entries.flatMap { assignment ->
            assignment.value.listDeployments().map { deployment ->
                InstanceStatus(
                    assignment.value.workspace,
                    deployment.first,
                    deployment.second,
                    disabledInstances.contains(deployment.second),
                )
            }
        }
    }

    fun disableInstance(instanceId: InstanceName) {
        disabledInstances += instanceId
        dirty.set(true)
        reconcileDeployments()
    }

    fun enableInstance(instanceId: InstanceName) {
        disabledInstances -= instanceId
        dirty.set(true)
        reconcileDeployments()
    }

    fun changeNumberOfAssigned(workspaceHash: WorkspaceHash, newNumber: Int) {
        getAssignments(getWorkspaceByHash(workspaceHash)).setNumberOfUnassigned(newNumber, true)
    }

    fun isInstanceDisabled(instanceId: InstanceName): Boolean = disabledInstances.contains(instanceId)

    private fun generateInstanceName(workspace: WorkspaceAndHash): InstanceName {
        val cleanName =
            (workspace.id + "-" + workspace.hash()).lowercase(Locale.getDefault()).replace("[^a-z0-9-]".toRegex(), "")
        var deploymentName = INSTANCE_PREFIX + cleanName
        val suffix = "-" + java.lang.Long.toHexString(deploymentSuffixSequence.incrementAndGet()) + "-" + managerId
        val charsToRemove = deploymentName.length + suffix.length - (63 - 16)
        if (charsToRemove > 0) deploymentName = deploymentName.substring(0, deploymentName.length - charsToRemove)
        return InstanceName(deploymentName + suffix)
    }

    @Synchronized
    fun redirect(baseRequest: Request?, request: HttpServletRequest): RedirectedURL? {
        val redirected: RedirectedURL = RedirectedURL.redirect(baseRequest, request)
            ?: return null
        return redirect(redirected)
    }

    @Synchronized
    fun redirect(redirected: RedirectedURL): RedirectedURL? {
        val userToken = redirected.userToken
        if (userToken == null) return redirected
        val userId = userToken.getUserName()
        if (userId != null) {
            userTokens[UserInstanceOwner(userId)] = userToken
        }
        val workspaceReference = redirected.workspaceReference
        if (!WORKSPACE_PATTERN.matcher(workspaceReference).matches()) return null
        val workspace = getWorkspaceForPath(workspaceReference) ?: return null

        val permissionEvaluator = ModelixJWTUtil().createPermissionEvaluator(userToken.payload, WorkspacesPermissionSchema.SCHEMA)
        if (userId != null) {
            getAccessControlData().load(userToken.payload, permissionEvaluator)
        }

        val isSharedInstance = redirected.sharedInstanceName != "own"
        if (isSharedInstance) {
            if (!permissionEvaluator.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspace.id).sharedInstance.access)) return null
        } else {
            if (!permissionEvaluator.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspace.id).instance.run)) return null
        }

        val assignments = getAssignments(workspace)
        redirected.instanceName = if (redirected.sharedInstanceName == "own") {
            assignments.getOrCreate(userToken)
        } else {
            assignments.getSharedInstance(redirected.sharedInstanceName) ?: return null
        }
        assignments.reconcile()
        reconcileIfDirty()
        return redirected
    }

    private fun createAssignmentsForAllWorkspaces() {
        val latestVersions = workspaceManager.getAllWorkspaces().map { it.withHash() }.associateBy { it.id }
        val allExistingVersions = assignments.entries.groupBy { it.value.workspace.id }

        for (latestVersion in latestVersions) {
            val existingVersions: List<MutableMap.MutableEntry<WorkspaceHash, Assignments>>? =
                allExistingVersions[latestVersion.key]
            if (existingVersions != null && existingVersions.any { it.key == latestVersion.value.hash() }) continue
            val assignment = getAssignments(latestVersion.value)
            val unassigned = existingVersions?.maxOfOrNull { it.value.getNumberOfUnassigned() } ?: 0
            assignment.setNumberOfUnassigned(unassigned, false)
            existingVersions?.forEach { it.value.resetNumberOfUnassigned() }
        }

        val deletedIds = allExistingVersions.keys - latestVersions.keys
        for (deleted in deletedIds.flatMap { allExistingVersions[it]!! }) {
            deleted.value.resetNumberOfUnassigned()
        }
    }

    private fun reconcileDeployments() {
        // TODO doesn't work with multiple instances of this proxy
        synchronized(reconcileLock) {
            try {
                val expectedDeployments: MutableMap<InstanceName, Pair<WorkspaceAndHash, InstanceOwner>> = HashMap()
                val existingDeployments: MutableSet<InstanceName> = HashSet()
                synchronized(assignments) {
                    for (assignment in assignments.values) {
                        assignment.removeTimedOut()
                        for (deployment in assignment.getAllDeploymentNamesAndUserIds()) {
                            if (disabledInstances.contains(deployment.first)) continue
                            if (!getWorkspaceStatus(assignment.workspace.hash()).canStartInstance()) continue
                            expectedDeployments[deployment.first] = assignment.workspace to deployment.second
                        }
                    }
                }
                val appsApi = AppsV1Api()
                val coreApi = CoreV1Api()
                val deployments = appsApi
                    .listNamespacedDeployment(KUBERNETES_NAMESPACE)
                    .timeoutSeconds(TIMEOUT_SECONDS)
                    .execute()
                for (deployment in deployments.items) {
                    val name = deployment.metadata!!.name!!
                    if (name.startsWith(INSTANCE_PREFIX)) {
                        existingDeployments.add(InstanceName(name))
                    }
                }
                val toAdd = expectedDeployments.keys - existingDeployments
                val toRemove = existingDeployments - expectedDeployments.keys
                for (d in toRemove) {
                    try {
                        appsApi.deleteNamespacedDeployment(d.name, KUBERNETES_NAMESPACE).execute()
                    } catch (e: Exception) {
                        LOG.error("Failed to delete deployment $d", e)
                    }
                    try {
                        coreApi.deleteNamespacedService(d.name, KUBERNETES_NAMESPACE).execute()
                    } catch (e: Exception) {
                        LOG.error("Failed to delete service $d", e)
                    }
                }
                for (d in toAdd) {
                    val (workspace, owner) = expectedDeployments[d]!!
                    try {
                        createDeployment(workspace, owner, d, userTokens[owner])
                    } catch (e: Exception) {
                        LOG.error("Failed to create deployment for workspace ${workspace.id} / $d", e)
                    }
                }

                synchronized(indexWasReady) {
                    indexWasReady.removeAll(indexWasReady - expectedDeployments.keys)
                }
            } catch (e: ApiException) {
                LOG.error("Deployment cleanup failed", e)
            }
        }
    }

    private fun reconcileIfDirty() {
        if (dirty.getAndSet(false)) reconcileDeployments()
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

    fun getPodLogs(deploymentName: InstanceName): String? {
        try {
            val coreApi = CoreV1Api()
            val pods = coreApi.listNamespacedPod(KUBERNETES_NAMESPACE).timeoutSeconds(TIMEOUT_SECONDS).execute()
            for (pod in pods.items) {
                if (!pod.metadata!!.name!!.startsWith(deploymentName.name)) continue
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

    fun isIndexerReady(deploymentName: InstanceName): Boolean {
        // avoid doing the expensive check again
        // also the relevant line may be truncated from the log if there is too much output
        if (indexWasReady.contains(deploymentName)) return true

        val log = getPodLogs(deploymentName) ?: return false
        val isReady = log.contains("### Index is ready")
        if (isReady) {
            indexWasReady.add(deploymentName)
        }
        return isReady
    }

    fun getEvents(deploymentName: String?): List<CoreV1Event> {
        if (deploymentName == null) return emptyList()
        val events: CoreV1EventList = CoreV1Api()
            .listNamespacedEvent(KUBERNETES_NAMESPACE)
            .timeoutSeconds(TIMEOUT_SECONDS)
            .execute()
        return events.items
            .filter { (it.involvedObject.name ?: "").contains(deploymentName) }
    }

    private val workspaceCache = LRUMap<WorkspaceHash, WorkspaceAndHash?>(100)
    fun getWorkspaceForPath(path: String): WorkspaceAndHash? {
        val matcher = WORKSPACE_PATTERN.matcher(path)
        if (!matcher.matches()) return null
        var workspaceId = matcher.group(1)
        var workspaceHash = matcher.group(2) ?: return null
        if (!workspaceHash.contains("*")) {
            workspaceHash =
                workspaceHash.substring(0, 5) + "*" + workspaceHash.substring(5)
        }
        return workspaceCache.computeIfAbsent(WorkspaceHash(workspaceHash)) {
            getWorkspaceByHash(it)
        }
    }

    @Synchronized
    fun getWorkspaceForInstance(instanceId: InstanceName): WorkspaceAndHash? {
        return assignments.values.filter { it.getAllDeploymentNames().any { it == instanceId } }
            .map { it.workspace }.firstOrNull()
    }

    fun createDeployment(
        workspace: WorkspaceAndHash,
        owner: InstanceOwner,
        instanceName: InstanceName,
        userToken: JWTPrincipal?,
    ): Boolean {
        val originalDeploymentName = WORKSPACE_CLIENT_DEPLOYMENT_NAME
        val appsApi = AppsV1Api()
        val deployments = appsApi.listNamespacedDeployment(KUBERNETES_NAMESPACE).timeoutSeconds(5).execute()
        val deploymentExists =
            deployments.items.stream().anyMatch { d: V1Deployment -> instanceName.name == d.metadata!!.name }
        if (!deploymentExists) {
            val deployment = Yaml.loadAs(File("/workspace-client-templates/deployment"), V1Deployment::class.java)
            deployment.metadata!!.creationTimestamp(null)
            deployment.metadata!!.managedFields = null
            deployment.metadata!!.uid = null
            deployment.metadata!!.resourceVersion(null)
            deployment.status = null
            deployment.metadata!!.putAnnotationsItem("kubectl.kubernetes.io/last-applied-configuration", null)
            // deployment.metadata!!.putAnnotationsItem(INSTANCE_PER_USER_ANNOTATION_KEY, null)
            // deployment.metadata!!.putAnnotationsItem(MAX_UNASSIGNED_INSTANCES_ANNOTATION_KEY, null)
            deployment.metadata!!.name(instanceName.name)
            deployment.metadata!!.putLabelsItem("component", instanceName.name)
            deployment.spec!!.selector.putMatchLabelsItem("component", instanceName.name)
            deployment.spec!!.template.metadata!!.putLabelsItem("component", instanceName.name)
            deployment.spec!!.replicas(1)
            deployment.spec!!.template.spec!!.containers[0].apply {
                addEnvItem(V1EnvVar().name("modelix_workspace_id").value(workspace.id))
                addEnvItem(V1EnvVar().name("REPOSITORY_ID").value("workspace_${workspace.id}"))
                addEnvItem(V1EnvVar().name("modelix_workspace_hash").value(workspace.hash().hash))
                addEnvItem(V1EnvVar().name("WORKSPACE_MODEL_SYNC_ENABLED").value(workspace.workspace.modelSyncEnabled.toString()))
            }

            val originalJwt = userToken?.payload
            var userId: String? = null
            var hasWritePermission = false
            val newPermissions = ArrayList<PermissionParts>()
            newPermissions += WorkspacesPermissionSchema.workspaces.workspace(workspace.id).config.read

            if (originalJwt == null) {
                if (owner is SharedInstanceOwner && workspace.sharedInstances.find { it.name == owner.name }?.allowWrite == true) {
                    hasWritePermission = true
                }
            } else {
                userId = ModelixJWTUtil().extractUserId(originalJwt)
                val permissionEvaluator = jwtUtil.createPermissionEvaluator(originalJwt, WorkspacesPermissionSchema.SCHEMA)
                getAccessControlData().load(originalJwt, permissionEvaluator)
                if (permissionEvaluator.hasPermission(WorkspacesPermissionSchema.workspaces.workspace(workspace.id).modelRepository.write)) {
                    hasWritePermission = true
                }
            }
            newPermissions += ModelServerPermissionSchema.repository("workspace_" + workspace.id).let { if (hasWritePermission) it.write else it.read }

            val newToken = jwtUtil.createAccessToken(userId ?: "workspace-user@modelix.org", newPermissions.map { it.fullId })
            deployment.spec!!.template.spec!!.containers[0].addEnvItem(V1EnvVar().name("INITIAL_JWT_TOKEN").value(newToken))
            loadWorkspaceSpecificValues(workspace, deployment)
            println("Creating deployment: ")
            println(Yaml.dump(deployment))
            appsApi.createNamespacedDeployment(KUBERNETES_NAMESPACE, deployment).execute()
        }
        val coreApi = CoreV1Api()
        val services = coreApi.listNamespacedService(KUBERNETES_NAMESPACE).timeoutSeconds(TIMEOUT_SECONDS).execute()
        val serviceExists = services.items.stream().anyMatch { s: V1Service -> instanceName.name == s.metadata!!.name }
        if (!serviceExists) {
            val service = Yaml.loadAs(File("/workspace-client-templates/service"), V1Service::class.java)
            service.metadata!!.putAnnotationsItem("kubectl.kubernetes.io/last-applied-configuration", null)
            service.metadata!!.managedFields = null
            service.metadata!!.uid = null
            service.metadata!!.resourceVersion(null)
            // The "template" service got assigned cluster IPs.
            // We do not want to use them for the service we are going to create.
            // Therefore, we are resetting them.
            // Leaving them would result in Kubernetes refusing to create the new service.
            service.spec!!.clusterIPs = null
            service.spec!!.clusterIP = null
            service.spec!!.ports!!.forEach(Consumer { p: V1ServicePort -> p.nodePort(null) })
            service.status = null
            service.metadata!!.name(instanceName.name)
            service.metadata!!.putLabelsItem("component", instanceName.name)
            service.metadata!!.name(instanceName.name)
            service.spec!!.putSelectorItem("component", instanceName.name)
            println("Creating service: ")
            println(Yaml.dump(service))
            coreApi.createNamespacedService(KUBERNETES_NAMESPACE, service).execute()
        }
        return true
    }

    private fun loadWorkspaceSpecificValues(workspace: WorkspaceAndHash, deployment: V1Deployment) {
        try {
            val container = deployment.spec!!.template.spec!!.containers[0]

            // The image registry is made available to the container runtime via a NodePort
            // localhost in this case is the kubernetes node, not the instances-manager
            container.image = "${INTERNAL_DOCKER_REGISTRY_AUTHORITY}/modelix-workspaces/ws${workspace.id}:${workspace.hash().toValidImageTag()}"

            val resources = container.resources ?: return
            val memoryLimit = Quantity.fromString(workspace.memoryLimit)
            val limits = resources.limits
            if (limits != null) limits["memory"] = memoryLimit
            val requests = resources.requests
            if (requests != null) requests["memory"] = memoryLimit
        } catch (ex: Exception) {
            LOG.error("Failed to configure the deployment for the workspace ${workspace.id}", ex)
        }
    }

    private inner class Assignments(val workspace: WorkspaceAndHash) {
        private val owner2deployment: MutableMap<InstanceOwner, InstanceName> = HashMap()
        private var numberOfUnassignedAuto: Int? = null
        private var numberOfUnassignedSetByUser: Int? = null

        fun listDeployments(): List<Pair<InstanceOwner, InstanceName>> {
            return owner2deployment.map { it.key to it.value }
        }

        @Synchronized
        fun getSharedInstance(name: String): InstanceName? {
            return owner2deployment.entries.find { (it.key as? SharedInstanceOwner)?.name == name }?.value
        }

        @Synchronized
        fun getOrCreate(userToken: JWTPrincipal): InstanceName {
            val userId: String = userToken.getUserName() ?: throw RuntimeException("Token doesn't contain a user ID")
            var workspaceInstanceId = owner2deployment[UserInstanceOwner(userId)]
            if (workspaceInstanceId == null) {
                val unassignedInstanceKey =
                    owner2deployment.keys.filterIsInstance<UnassignedInstanceOwner>().firstOrNull()
                if (unassignedInstanceKey == null) {
                    workspaceInstanceId = generateInstanceName(workspace)
                } else {
                    workspaceInstanceId = owner2deployment.remove(unassignedInstanceKey)!!
                    owner2deployment[unassignedInstanceKey] = generateInstanceName(workspace)
                }
                owner2deployment[UserInstanceOwner(userId)] = workspaceInstanceId
                dirty.set(true)
            }
            DeploymentTimeouts.update(workspaceInstanceId)
            return workspaceInstanceId
        }

        @Synchronized
        fun setNumberOfUnassigned(targetNumber: Int, setByUser: Boolean) {
            if (setByUser) {
                numberOfUnassignedSetByUser = targetNumber
            } else {
                numberOfUnassignedAuto = targetNumber
            }
            reconcile()
        }

        @Synchronized
        fun resetNumberOfUnassigned() {
            numberOfUnassignedSetByUser = null
            numberOfUnassignedAuto = null
            reconcile()
        }

        fun getNumberOfUnassigned() = numberOfUnassignedSetByUser ?: numberOfUnassignedAuto ?: 0

        @Synchronized
        fun reconcile() {
            val expectedNumUnassigned = getNumberOfUnassigned()

            val expectedInstances = (
                workspace.sharedInstances.map { SharedInstanceOwner(it.name) } +
                    (0 until expectedNumUnassigned).map { UnassignedInstanceOwner(it) }
                ).toSet()
            val existingInstances = owner2deployment.keys.filterNot { it is UserInstanceOwner }.toSet()
            for (toRemove in (existingInstances - expectedInstances)) {
                owner2deployment.remove(toRemove)
                dirty.set(true)
            }
            for (toAdd in (expectedInstances - existingInstances)) {
                owner2deployment[toAdd] = generateInstanceName(workspace)
                dirty.set(true)
            }
        }

        @Synchronized
        fun getAllDeploymentNames(): List<InstanceName> = owner2deployment.values.toList()

        @Synchronized
        fun getAllDeploymentNamesAndUserIds(): List<Pair<InstanceName, InstanceOwner>> =
            owner2deployment.map { it.value to it.key }

        @Synchronized
        fun removeTimedOut() {
            val entries = owner2deployment.entries.toList()
            for ((owner, instanceName) in entries) {
                if (owner is UserInstanceOwner && DeploymentTimeouts.isTimedOut(instanceName)) {
                    owner2deployment.remove(owner, instanceName)
                    dirty.set(true)
                }
            }
        }
    }

    companion object {
        private val LOG = mu.KotlinLogging.logger {}
        val KUBERNETES_NAMESPACE = System.getenv("WORKSPACE_CLIENT_NAMESPACE") ?: "default"
        val INSTANCE_PREFIX = System.getenv("WORKSPACE_CLIENT_PREFIX") ?: "wsclt-"
        val WORKSPACE_CLIENT_DEPLOYMENT_NAME = System.getenv("WORKSPACE_CLIENT_DEPLOYMENT_NAME") ?: "workspace-client"
        val WORKSPACE_PATTERN = Pattern.compile("workspace-([a-f0-9]+)-([a-zA-Z0-9\\-_\\*]+)")
        val INTERNAL_DOCKER_REGISTRY_AUTHORITY = requireNotNull(System.getenv("INTERNAL_DOCKER_REGISTRY_AUTHORITY"))
    }
}

@JvmInline
value class InstanceName(val name: String)

fun WorkspaceBuildStatus.canStartInstance(): Boolean = when (this) {
    WorkspaceBuildStatus.New -> false
    WorkspaceBuildStatus.Queued -> false
    WorkspaceBuildStatus.Running -> false
    WorkspaceBuildStatus.FailedBuild -> false
    WorkspaceBuildStatus.FailedZip -> false
    WorkspaceBuildStatus.AllSuccessful -> true
    WorkspaceBuildStatus.ZipSuccessful -> true
}
