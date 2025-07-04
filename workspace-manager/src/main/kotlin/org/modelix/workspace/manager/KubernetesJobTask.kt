package org.modelix.workspace.manager

import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1Toleration
import io.kubernetes.client.util.Yaml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.modelix.services.workspaces.ContinuingCallback
import org.modelix.services.workspaces.metadata
import org.modelix.services.workspaces.spec
import org.modelix.services.workspaces.template
import org.modelix.workspace.manager.WorkspaceJobQueue.Companion.KUBERNETES_NAMESPACE
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.minutes

abstract class KubernetesJobTask<Out : Any>(scope: CoroutineScope) : TaskInstance<Out>(scope) {
    companion object {
        const val JOB_ID_LABEL = "modelix.workspace.job.id"
        private val LOG = mu.KotlinLogging.logger {}
    }

    abstract suspend fun tryGetResult(): Out?
    abstract fun generateJobYaml(): V1Job

    override suspend fun process() = withTimeout(30.minutes) {
        tryGetResult()?.let { return@withTimeout it }

        findJob()?.let { deleteJob(it) }
        createJob()

        var jobCreationFailedConfirmations = 0
        while (true) {
            delay(1000)

            tryGetResult()?.let { return@withTimeout it }

            val job = findJob()

            // https://kubernetes.io/docs/concepts/workloads/controllers/job/#terminal-job-conditions
            val jobFailed = job?.status?.conditions.orEmpty().any { it.type == "Failed" }
            val jobSucceeded = job?.status?.conditions.orEmpty().any { it.type == "Complete" }
            if (jobFailed || jobSucceeded) break

            if (job == null) {
                jobCreationFailedConfirmations++
            } else {
                jobCreationFailedConfirmations = 0
            }
            if (jobCreationFailedConfirmations > 10) {
                break
            }
        }
        checkNotNull(tryGetResult()) {
            "Job finished without producing the expected result. \nStatus: ${findJob()?.status?.let { Yaml.dump(it) }}\nPod logs:\n ${getPodLogs()}"
        }
    }

    fun getPodLogs(): String? {
        try {
            val coreApi = CoreV1Api()
            val pods = coreApi.listNamespacedPod(KUBERNETES_NAMESPACE).timeoutSeconds(10).execute()
            for (pod in pods.items) {
                if (pod.metadata!!.labels?.get(JOB_ID_LABEL) != id.toString()) continue
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

    private suspend fun createJob() {
        suspendCoroutine {
            val job = generateJobYaml().apply {
                apiVersion = "batch/v1"
                kind = "Job"
                metadata {
                    putLabelsItem(JOB_ID_LABEL, id.toString())
                }
                spec {
                    ttlSecondsAfterFinished = 300
                    activeDeadlineSeconds = 3600
                    backoffLimit = 0
                    template {
                        spec {
                            addTolerationsItem(
                                V1Toleration().apply {
                                    key = "workspace-client"
                                    operator = "Exists"
                                    effect = "NoExecute"
                                },
                            )
                            activeDeadlineSeconds = 3600
                            restartPolicy = "Never"
                        }
                        metadata {
                            putLabelsItem(JOB_ID_LABEL, id.toString())
                        }
                    }
                }
            }
            BatchV1Api().createNamespacedJob(KUBERNETES_NAMESPACE, job).executeAsync(ContinuingCallback(it))
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
}
