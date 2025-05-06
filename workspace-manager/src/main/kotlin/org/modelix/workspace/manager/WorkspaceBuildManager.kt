package org.modelix.workspace.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.util.Yaml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.modelix.services.workspaces.ContinuingCallback
import org.modelix.workspace.manager.WorkspaceJobQueue.Companion.HELM_PREFIX
import org.modelix.workspace.manager.WorkspaceJobQueue.Companion.JOB_IMAGE
import org.modelix.workspace.manager.WorkspaceJobQueue.Companion.KUBERNETES_NAMESPACE
import org.modelix.workspaces.DEFAULT_MPS_VERSION
import org.modelix.workspaces.InternalWorkspaceConfig
import org.modelix.workspaces.withHash
import java.util.UUID
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.minutes

private val LOG = mu.KotlinLogging.logger { }

class WorkspaceBuildManager(
    val coroutinesScope: CoroutineScope,
    val tokenGenerator: (InternalWorkspaceConfig) -> String,
) {

    private val workspaceImageTasks = ReusableTasks<InternalWorkspaceConfig, WorkspaceImageTask>()

    fun getOrCreateWorkspaceImageTask(workspaceConfig: InternalWorkspaceConfig): WorkspaceImageTask {
        return workspaceImageTasks.getOrCreateTask(workspaceConfig.normalizeForBuild()) {
            WorkspaceImageTask(workspaceConfig, tokenGenerator, coroutinesScope)
        }
    }

    fun getWorkspaceConfigByTaskId(taskId: UUID): InternalWorkspaceConfig? {
        return workspaceImageTasks.getAll().find { it.id == taskId }?.workspaceConfig
    }
}

enum class TaskState {
    CREATED,
    ACTIVE,
    CANCELLED,
    COMPLETED,
    UNKNOWN,
}

class WorkspaceBaseImageTask(val mpsVersion: String, scope: CoroutineScope) : TaskInstance<ImageNameAndTag>(scope) {
    private val resultImage = ImageNameAndTag(
        "modelix/workspace-client-baseimage",
        "${System.getenv("MPS_BASEIMAGE_VERSION")}-mps$mpsVersion",
    )
    override suspend fun process(): ImageNameAndTag {
        return resultImage
    }
}

data class ImageNameAndTag(val name: String, val tag: String) {
    override fun toString(): String = "$name:$tag"
}

class WorkspaceImageTask(
    val workspaceConfig: InternalWorkspaceConfig,
    val tokenGenerator: (InternalWorkspaceConfig) -> String,
    scope: CoroutineScope,
) : TaskInstance<ImageNameAndTag>(scope) {
    companion object {
        const val JOB_ID_LABEL = "modelix.workspace.job.id"
    }

    private val resultImage = ImageNameAndTag(
        "modelix-workspaces/ws${workspaceConfig.id}",
        workspaceConfig.withHash().hash().toValidImageTag(),
    )

    override suspend fun process(): ImageNameAndTag {
        withTimeout(30.minutes) {
            if (checkImageExists(resultImage)) return@withTimeout

            findJob()?.let { deleteJob(it) }
            createJob()

            var jobFailureConfirmations = 0
            while (true) {
                delay(1000)

                if (checkImageExists(resultImage)) break

                if (findJob() == null) {
                    jobFailureConfirmations++
                } else {
                    jobFailureConfirmations = 0
                }
                if (jobFailureConfirmations > 10 && !checkImageExists(resultImage)) {
                    throw IllegalStateException("Job finished without uploading the result image")
                }
            }
        }
        return resultImage
    }

    private suspend fun createJob() {
        suspendCoroutine {
            val yamlString = generateJobYaml()
            BatchV1Api().createNamespacedJob(
                KUBERNETES_NAMESPACE,
                Yaml.loadAs(yamlString, V1Job::class.java),
            ).executeAsync(ContinuingCallback(it))
        }
    }

    private suspend fun findJob(): V1Job? {
        val jobs = suspendCoroutine {
            BatchV1Api().listNamespacedJob(KUBERNETES_NAMESPACE)
                .executeAsync(ContinuingCallback(it))
        }
        return jobs.items.firstOrNull { it.metadata.labels?.get(JOB_ID_LABEL) == id.toString() }
    }

    private suspend fun deleteJob(job: V1Job) {
        suspendCoroutine {
            BatchV1Api().deleteNamespacedJob(job.metadata!!.name, job.metadata!!.namespace)
                .executeAsync(ContinuingCallback(it))
        }
    }

    @Suppress("ktlint")
    fun generateJobYaml(): String {
        val jobName = "wsjob-$id"
        val mpsVersion = workspaceConfig.mpsVersion?.takeIf { it.isNotEmpty() } ?: DEFAULT_MPS_VERSION

        val containerMemoryBytes = Quantity.fromString(workspaceConfig.memoryLimit).number
        val baseImageBytes = BASE_IMAGE_MAX_HEAP_SIZE_MEGA.toBigDecimal() * 1024.toBigDecimal() * 1024.toBigDecimal()
        val heapSizeBytes = heapSizeFromContainerLimit(containerMemoryBytes).coerceAtLeast(baseImageBytes)
        val additionalJobMemoryBytes = Quantity.fromString("1Gi").number
        val jobContainerMemoryBytes = containerLimitFromHeapSize(heapSizeBytes.coerceAtLeast(baseImageBytes)) + additionalJobMemoryBytes
        val jobContainerMemoryMega = (jobContainerMemoryBytes / 1024.toBigDecimal()).toBigInteger().toBigDecimal()
        val memoryLimit = Quantity(jobContainerMemoryMega * 1024.toBigDecimal(), Quantity.Format.BINARY_SI).toSuffixedString()

        val jwtToken = tokenGenerator(workspaceConfig)
        val dockerConfigSecretName = System.getenv("DOCKER_CONFIG_SECRET_NAME")
        val dockerConfigInternalRegistrySecretName = System.getenv("DOCKER_CONFIG_INTERN_REGISTRY_SECRET_NAME")

        return """
            apiVersion: batch/v1
            kind: Job
            metadata:
              name: "$jobName"
              labels:
                ${JOB_ID_LABEL}: $id
            spec:
              ttlSecondsAfterFinished: 60
              activeDeadlineSeconds: 3600
              template:
                metadata:
                  labels:
                    ${JOB_ID_LABEL}: $id
                spec:
                  activeDeadlineSeconds: 3600
                  tolerations:
                  - key: "workspace-client"
                    operator: "Exists"
                    effect: "NoExecute"
                  containers:
                  - name: wsjob
                    image: $JOB_IMAGE
                    env:
                    - name: TARGET_REGISTRY
                      value: ${HELM_PREFIX}docker-registry:5000
                    - name: WORKSPACE_DESTINATION_IMAGE_NAME
                      value: ${resultImage.name}
                    - name: WORKSPACE_DESTINATION_IMAGE_TAG
                      value: ${resultImage.tag}
                    - name: WORKSPACE_CONTEXT_URL
                      value: http://${HELM_PREFIX}workspace-manager:28104/modelix/workspaces/tasks/$id/context.tar.gz
                    - name: modelix_workspace_task_id
                      value: $id
                    - name: modelix_workspace_server
                      value: http://${HELM_PREFIX}workspace-manager:28104/      
                    - name: INITIAL_JWT_TOKEN
                      value: $jwtToken
                    - name: BASEIMAGE_CONTEXT_URL
                      value: http://${HELM_PREFIX}workspace-manager:28104/baseimage/$mpsVersion/context.tar.gz
                    - name: BASEIMAGE_TARGET
                      value: ${HELM_PREFIX}docker-registry:5000/modelix/workspace-client-baseimage:${System.getenv("MPS_BASEIMAGE_VERSION")}-mps$mpsVersion
                    resources: 
                      requests:
                        memory: $memoryLimit
                        cpu: "0.1"
                      limits:
                        memory: $memoryLimit
                        cpu: "1.0" 
                    volumeMounts:
                    ${if (dockerConfigSecretName != null) """
                    - name: "docker-config"
                      mountPath: /secrets/config-external-registry.json
                      subPath: config.json
                      readOnly: true
                    - name: "docker-proxy-ca"
                      mountPath: /kaniko/ssl/certs/docker-proxy-ca.crt
                      subPath: docker-proxy-ca.crt
                      readOnly: true
                    """ else ""}
                    - name: "docker-config-internal-registry"
                      mountPath: /secrets/config-internal-registry.json
                      subPath: config.json
                      readOnly: true
                  restartPolicy: Never
                  volumes:
                  - name: "docker-config-internal-registry"
                    secret:
                      secretName: "$dockerConfigInternalRegistrySecretName"
                      items:
                        - key: .dockerconfigjsonUsingServiceName
                          path: config.json
                  ${if (dockerConfigSecretName != null) """
                  - name: "docker-config"
                    secret:
                      secretName: "$dockerConfigSecretName"
                      items:
                        - key: .dockerconfigjson
                          path: config.json
                  - name: "docker-proxy-ca"
                    secret:
                      secretName: "$dockerConfigSecretName"
                      items:
                        - key: caCertificate
                          path: docker-proxy-ca.crt
                  """ else ""}
              backoffLimit: 2
        """.trimIndent()
    }
}

private suspend fun checkImageExists(image: ImageNameAndTag): Boolean {
    val response = HttpClient(CIO).get("http://${HELM_PREFIX}docker-registry:5000/v2/${image.name}/manifests/${image.tag}") {
        basicAuth(System.getenv("INTERNAL_DOCKER_REGISTRY_USER"), System.getenv("INTERNAL_DOCKER_REGISTRY_PASSWORD"))
        header("Accept", "application/vnd.oci.image.manifest.v1+json")
    }
    return when (response.status) {
        HttpStatusCode.NotFound -> false
        HttpStatusCode.OK -> true
        else -> {
            throw IllegalStateException("Unexpected response: ${response.status}\n${response.bodyAsText()}")
        }
    }
}
