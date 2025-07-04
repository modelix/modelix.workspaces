package org.modelix.workspace.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.util.Yaml
import kotlinx.coroutines.CoroutineScope
import org.modelix.services.workspaces.toValidImageTag
import org.modelix.workspace.manager.WorkspaceJobQueue.Companion.HELM_PREFIX
import org.modelix.workspaces.WorkspaceConfigForBuild
import org.modelix.workspaces.hash

class WorkspaceImageTask(
    val workspaceConfig: WorkspaceConfigForBuild,
    val tokenGenerator: (WorkspaceConfigForBuild) -> String,
    scope: CoroutineScope,
) : KubernetesJobTask<ImageNameAndTag>(scope) {
    companion object {
        const val JOB_ID_LABEL = "modelix.workspace.job.id"
    }

    private val resultImage = ImageNameAndTag(
        "modelix-workspaces/ws${workspaceConfig.id}",
        workspaceConfig.hash().toValidImageTag(),
    )

    override suspend fun tryGetResult(): ImageNameAndTag? {
        return resultImage.takeIf { checkImageExists(it) }
    }

    @Suppress("ktlint")
    override fun generateJobYaml(): V1Job {
        val jobName = "wsjob-$id"
        val mpsVersion = workspaceConfig.mpsVersion

        val containerMemoryBytes = workspaceConfig.memoryLimit.toBigDecimal()
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
                    image: ${WorkspaceJobQueue.Companion.JOB_IMAGE}
                    env:
                    - name: TARGET_REGISTRY
                      value: ${WorkspaceJobQueue.Companion.HELM_PREFIX}docker-registry:5000
                    - name: WORKSPACE_DESTINATION_IMAGE_NAME
                      value: ${resultImage.name}
                    - name: WORKSPACE_DESTINATION_IMAGE_TAG
                      value: ${resultImage.tag}
                    - name: WORKSPACE_CONTEXT_URL
                      value: http://${WorkspaceJobQueue.Companion.HELM_PREFIX}workspace-manager:28104/modelix/workspaces/tasks/$id/context.tar.gz
                    - name: modelix_workspace_task_id
                      value: $id
                    - name: modelix_workspace_server
                      value: http://${WorkspaceJobQueue.Companion.HELM_PREFIX}workspace-manager:28104/      
                    - name: INITIAL_JWT_TOKEN
                      value: $jwtToken
                    - name: BASEIMAGE_CONTEXT_URL
                      value: http://${WorkspaceJobQueue.Companion.HELM_PREFIX}workspace-manager:28104/baseimage/$mpsVersion/context.tar.gz
                    - name: BASEIMAGE_TARGET
                      value: ${WorkspaceJobQueue.Companion.HELM_PREFIX}docker-registry:5000/modelix/workspace-client-baseimage:${System.getenv("MPS_BASEIMAGE_VERSION")}-mps$mpsVersion
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
        """.trimIndent().let { Yaml.loadAs(it, V1Job::class.java) }
    }
}

data class ImageNameAndTag(val name: String, val tag: String) {
    override fun toString(): String = "$name:$tag"
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
