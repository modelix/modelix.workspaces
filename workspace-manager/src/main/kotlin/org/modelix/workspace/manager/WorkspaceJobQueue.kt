/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.modelix.workspace.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Yaml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.modelix.model.persistent.HashUtil
import org.modelix.workspaces.Workspace
import org.modelix.workspaces.WorkspaceAndHash
import org.modelix.workspaces.WorkspaceBuildStatus
import org.modelix.workspaces.WorkspaceHash
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class WorkspaceJobQueue(val tokenGenerator: (Workspace) -> String) {

    private val workspaceHash2job: MutableMap<WorkspaceHash, Job> = HashMap()
    private val coroutinesScope = CoroutineScope(Dispatchers.Default)

    init {
        try {
            Configuration.setDefaultApiClient(ClientBuilder.cluster().build())
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        coroutinesScope.launch {
            while (coroutinesScope.isActive) {
                delay(3.seconds)
                try {
                    if (workspaceHash2job.isNotEmpty()) {
                        reconcileKubernetesJobs()
                        workspaceHash2job.values.forEach { it.updateLog() }
                    }
                } catch (ex: Exception) {
                    LOG.error(ex) { "" }
                }
            }
        }
    }

    private fun baseImageExists(mpsVersion: String): Boolean {
        return try {
            runBlocking {
                HttpClient(CIO).get("http://${HELM_PREFIX}docker-registry:5000/v2/modelix/workspace-client-baseimage/manifests/${System.getenv("MPS_BASEIMAGE_VERSION")}-mps$mpsVersion ") {
                    header("Accept", "application/vnd.oci.image.manifest.v1+json")
                }.status == HttpStatusCode.OK
            }
        } catch (ex: Throwable) {
            LOG.error(ex) { "Failed to check if the base image for MPS version $mpsVersion exists" }
            false
        }
    }

    fun dispose() {
        coroutinesScope.cancel("disposed")
    }

    fun removeByWorkspaceId(workspaceId: String) {
        synchronized(workspaceHash2job) {
            workspaceHash2job -= workspaceHash2job.filter { it.value.workspace.id == workspaceId }.keys
        }
    }

    fun getOrCreateJob(workspace: WorkspaceAndHash): Job {
        synchronized(workspaceHash2job) {
            return workspaceHash2job.getOrPut(workspace.hash()) { Job(workspace) }
        }
    }

    private fun reconcileKubernetesJobs() {
        val expectedJobs: Map<String, Job> = synchronized(workspaceHash2job) {
            workspaceHash2job.values.associateBy { it.kubernetesJobName }
        }
        val existingJobs: Map<String?, V1Job> = BatchV1Api()
            .listNamespacedJob(KUBERNETES_NAMESPACE)
            .execute()
            .items.filter { it.metadata?.name?.startsWith(JOB_PREFIX) == true }
            .associateBy { it.metadata?.name }

        val unexpected: Map<String?, V1Job> = existingJobs - expectedJobs.keys
        for (toRemove in unexpected) {
            expectedJobs[toRemove.key]?.updateLog()
            BatchV1Api().deleteNamespacedJob(toRemove.key, KUBERNETES_NAMESPACE).execute()
        }

        val missingJobs: Map<String?, Job> = expectedJobs - existingJobs.keys
        for (missingJob in missingJobs.values) {
            when (missingJob.status) {
                WorkspaceBuildStatus.New, WorkspaceBuildStatus.Queued, WorkspaceBuildStatus.Running -> {}
                WorkspaceBuildStatus.FailedBuild, WorkspaceBuildStatus.FailedZip, WorkspaceBuildStatus.AllSuccessful, WorkspaceBuildStatus.ZipSuccessful -> continue
            }

            val yamlString = missingJob.generateJobYaml()
            try {
                val job: V1Job = Yaml.loadAs(yamlString, V1Job::class.java)
                BatchV1Api().createNamespacedJob(KUBERNETES_NAMESPACE, job).execute()
                missingJob.kubernetesJob = job
                missingJob.status = WorkspaceBuildStatus.Queued
            } catch (ex: Exception) {
                throw RuntimeException("Cannot create job:\n$yamlString", ex)
            }
        }
    }

    private fun getPodLogs(podNamePrefix: String): String? {
        try {
            val coreApi = CoreV1Api()
            val pods = coreApi.listNamespacedPod(KUBERNETES_NAMESPACE).execute()
            val matchingPods = pods.items.filter { it.metadata!!.name!!.startsWith(podNamePrefix) }
            if (matchingPods.isEmpty()) return null
            return matchingPods.joinToString("\n----------------------------------------------------------------------------\n") { pod ->
                try {
                    coreApi.readNamespacedPodLog(pod.metadata!!.name, KUBERNETES_NAMESPACE)
                        .container(pod.spec!!.containers[0].name)
                        .pretty("true")
                        .tailLines(10_000)
                        .execute()
                } catch (ex: Exception) {
                    ex.stackTraceToString()
                }
            }
        } catch (ex: Exception) {
            return ex.stackTraceToString()
        }
    }

    companion object {
        private val LOG = mu.KotlinLogging.logger {  }
        val KUBERNETES_NAMESPACE = System.getenv("KUBERNETES_NAMESPACE") ?: "default"
        val IMAGE_NAME = System.getenv("WORKSPACE_JOB_IMAGE_NAME") ?: "modelix/workspace-job"
        // XXX The name WORKSPACE_JOB_IMAGE_VERSION is misleading as it is only the prefix.
        // e.g. `latest` becomes `latest-2020.3`
        val IMAGE_VERSION = System.getenv("WORKSPACE_JOB_IMAGE_VERSION") ?: "latest"
        val HELM_PREFIX = System.getenv("KUBERNETES_PREFIX") ?: ""
        val JOB_PREFIX = HELM_PREFIX + "wsjob-"
    }

    inner class Job(val workspace: WorkspaceAndHash) {
        val kubernetesJobName = generateKubernetesJobName()
        var kubernetesJob: V1Job? = null
        var status: WorkspaceBuildStatus = WorkspaceBuildStatus.New

        private var cachedPodLog: String? = null
        fun updateLog() {
            val log = getPodLogs(kubernetesJobName) ?: return
            cachedPodLog = log
            val lastStatusAsText = Regex("""###${WorkspaceBuildStatus::class.simpleName} = (.+)###""")
                .findAll(log).lastOrNull()?.let { it.groupValues[1] }
            if (lastStatusAsText != null) {
                try {
                    val lastStatus = WorkspaceBuildStatus.valueOf(lastStatusAsText)
                    status = lastStatus
                } catch (ex: IllegalArgumentException) {}
            }
        }
        fun getLog(): String {
            return cachedPodLog ?: ""
        }

        fun generateJobYaml(jobName: String = kubernetesJobName): String {
            val mpsVersion = workspace.userDefinedOrDefaultMpsVersion

            val memoryLimit = workspace.memoryLimit
            val jwtToken = tokenGenerator(workspace.workspace)
            val dockerConfigSecretName = System.getenv("DOCKER_CONFIG_SECRET_NAME")
            val dockerConfigInternalRegistrySecretName = System.getenv("DOCKER_CONFIG_INTERN_REGISTRY_SECRET_NAME")

            return """
                apiVersion: batch/v1
                kind: Job
                metadata:
                  name: "$jobName"
                spec:
                  ttlSecondsAfterFinished: 60
                  activeDeadlineSeconds: 3600
                  template:
                    spec:
                      activeDeadlineSeconds: 3600
                      tolerations:
                      - key: "workspace-client"
                        operator: "Exists"
                        effect: "NoExecute"
                      containers:
                      - name: wsjob
                        image: $IMAGE_NAME:$IMAGE_VERSION
                        env:
                        - name: TARGET_REGISTRY
                          value: ${HELM_PREFIX}docker-registry:5000
                        - name: WORKSPACE_DESTINATION_IMAGE_NAME
                          value: modelix-workspaces/ws${workspace.workspace.id}
                        - name: WORKSPACE_DESTINATION_IMAGE_TAG
                          value: ${workspace.hash().toValidImageTag()}
                        - name: WORKSPACE_CONTEXT_URL
                          value: http://${HELM_PREFIX}workspace-manager:28104/${workspace.hash().hash.replace("*", "%2A")}/context.tar.gz
                        - name: modelix_workspace_id
                          value: ${workspace.id}  
                        - name: modelix_workspace_hash
                          value: ${workspace.hash()}   
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

        private fun generateKubernetesJobName(): String {
            val jobYamlHash = HashUtil.sha256(generateJobYaml("<unspecified>"))
            val cleanName = (workspace.id + "-" + workspace.hash().hash.take(5) + "-" + jobYamlHash).lowercase(Locale.getDefault()).replace("[^a-z0-9-]".toRegex(), "")
            var jobName = JOB_PREFIX + cleanName
            val charsToRemove = jobName.length - (63 - 16)
            if (charsToRemove > 0) jobName = jobName.substring(0, jobName.length - charsToRemove)
            // Delete forbidden trailing hyphens ("-") that could be part of `HashUtil.sha256`.
            // HashUtil.sha256 uses Base64 for URL which might contain "-".
            return jobName.trimEnd('-')
        }
    }
}