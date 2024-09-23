package org.modelix.workspace.manager.gitsync

import io.ktor.http.*
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.util.Yaml
import org.modelix.authorization.KeycloakResourceType
import org.modelix.authorization.KeycloakScope
import org.modelix.authorization.KeycloakUtils
import org.modelix.workspace.manager.WorkspaceJobQueue
import org.modelix.workspaces.CredentialsEncryption
import org.modelix.workspaces.Workspace
import org.modelix.workspaces.userDefinedOrDefaultMpsVersion
import java.time.format.DateTimeFormatter

// TODO MODELIX-597 Consider that removing workspace should remove jobs.
// They could be keep if the just delete themself after a period.
class KubernetesGitSyncJobService(private val modelServerV2Url: String,
                                  private val credentialsEncryption: CredentialsEncryption) : IGitSyncJobService {


    companion object {
        private val LOG = mu.KotlinLogging.logger {  }
        private val FULLY_QUALIFIED_MODELIX_APP_NAME = requireNotNull(System.getenv("FULLY_QUALIFIED_MODELIX_APP_NAME")) {
            "FULLY_QUALIFIED_MODELIX_APP_NAME must be specified."
        }
        private val KUBERNETES_NAMESPACE = System.getenv("KUBERNETES_NAMESPACE") ?: "default"
        private val DOCKER_PROXY_PREFIX = System.getenv("DOCKER_PROXY_PREFIX") ?: ""
        private val WORKSPACE_VERSION = System.getenv("WORKSPACE_VERSION") ?: "latest"
        private val IMAGE_VERSION = System.getenv("WORKSPACE_JOB_IMAGE_VERSION") ?: "latest"
        private val HELM_PREFIX = System.getenv("KUBERNETES_PREFIX") ?: ""
        // TODO MODELIX-597 Check why prefix might be needed and how to use it.
        private val GIT_SYNC_JOB_PREFIX = HELM_PREFIX + "ws-git-sync-job-"
        private const val SECONDS_TO_KEEP_FINISHED_JOB = 60 * 60 * 24 * 7 // seven days
        private const val JOB_DEADLINE_SECONDS = 60 * 60 // one hour
        private val GENERATE_NAME_GIT_SYNC_JOB = HELM_PREFIX + "ws-git-sync-job-"
        private const val CHECKOUT_STEP_IMAGE_NAME = "modelix/modelix-workspace-git-sync-checkout-step"
        private const val UPDATE_STEP_IMAGE_NAME = "modelix/modelix-workspace-git-sync-update-step"
        private const val PUSH_STEP_IMAGE_NAME = "modelix/modelix-workspace-git-sync-push-step"
        // TODO MODELIX-597 explain why label vs attribute
        // > Labels are intended to be used to specify identifying attributes
        // > of objects that are meaningful and relevant to users,
        // but do not directly imply semantics to the core system.
        // See https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/
        private const val LABEL_JOB_TYPE_KEY = "modelix.org/git-sync-job"
        private const val LABEL_WORKSPACE_ID_KEY = "modelix.org/workspace-id"
        private const val ANNOTATION_GIT_SYNC_JOB_VERSION = "modelix.org/git-sync-job-version"
        private const val ANNOTATION_GIT_SYNC_JOB_REPOSITORY = "modelix.org/git-sync-job-repository"
        private const val ANNOTATION_GIT_SYNC_JOB_SOURCE_REF = "modelix.org/git-sync-job-source-ref"
        private const val ANNOTATION_GIT_SYNC_JOB_TARGET_BRANCH = "modelix.org/git-sync-job-target-branch"
        // TODO extract common code
        private const val ENV_GIT_SYNC_JOB_WORKSPACE_MANAGER_URL = "MODELIX_WORKSPACE_MANAGER_URL"
        private const val ENV_GIT_SYNC_JOB_MODEL_SERVER_V2_URL =  "MODELIX_MODEL_SERVER_V2_URL"
        private const val ENV_GIT_SYNC_JOB_MODEL_SERVER_TOKEN =  "MODELIX_MODEL_SERVER_TOKEN"
        private const val ENV_GIT_SYNC_JOB_WORKSPACE_ID = "GIT_SYNC_JOB_WORKSPACE_ID"
        private const val ENV_GIT_SYNC_JOB_MODEL_REPOSITORY = "GIT_SYNC_JOB_MODEL_REPOSITORY"
        private const val ENV_GIT_SYNC_JOB_MODEL_VERSION = "GIT_SYNC_JOB_MODEL_VERSION_HASH"
        private const val ENV_GIT_SYNC_JOB_GIT_REPOSITORY = "GIT_SYNC_JOB_GIT_REPOSITORY"
        // TODO MODELIX-597 Encrypt credentials
        private const val ENV_GIT_SYNC_JOB_GIT_REPOSITORY_USERNAME = "GIT_SYNC_JOB_GIT_REPOSITORY_USERNAME"
        private const val ENV_GIT_SYNC_JOB_GIT_REPOSITORY_PASSWORD = "GIT_SYNC_JOB_GIT_REPOSITORY_PASSWORD"
        private const val ENV_GIT_SYNC_JOB_GIT_SOURCE_REF = "GIT_SYNC_JOB_GIT_SOURCE_REF"
        private const val ENV_GIT_SYNC_JOB_GIT_TARGET_BRANCH = "GIT_SYNC_JOB_GIT_TARGET_BRANCH"
    }

    override fun createJob(workspace: Workspace, configuration: JobConfiguration, credentials: JobCredentials) {
        val jobYaml = generateJobYaml(workspace, configuration, credentials)
        val job: V1Job = Yaml.loadAs(jobYaml, V1Job::class.java)
        BatchV1Api().createNamespacedJob(WorkspaceJobQueue.KUBERNETES_NAMESPACE, job, null, null, null, null)
    }

    override fun getJobs(workspaceId: String): List<Job> {
        val labelSelector = getJobsLabelSelector(workspaceId)
        val kubernetesJobs = BatchV1Api()
            .listNamespacedJob(KUBERNETES_NAMESPACE, null, null, null, null, labelSelector, null, null, null, null, null, false)
            .items

        return kubernetesJobs.map { kubernetesJob ->

            val jobStatus = if(kubernetesJob.status!!.succeeded == 1) {
                JobStatus.SUCCEEDED
            } else if (kubernetesJob.status!!.active == 1) {
                JobStatus.ACTIVE
            } else if(kubernetesJob.status!!.failed == 1) {
                JobStatus.FAILED
            } else {
                JobStatus.SUBMITTED
            }

            Job(
                // TODO MODELIX-597 Proper error messages for missing data.
                kubernetesJob.metadata!!.name!!,
                kubernetesJob.metadata!!.creationTimestamp!!.format(DateTimeFormatter.ISO_DATE_TIME),
                jobStatus,
                JobConfiguration(
                    kubernetesJob.metadata!!.annotations!![ANNOTATION_GIT_SYNC_JOB_VERSION]!!,
                    kubernetesJob.metadata!!.annotations!![ANNOTATION_GIT_SYNC_JOB_REPOSITORY]!!,
                    kubernetesJob.metadata!!.annotations!![ANNOTATION_GIT_SYNC_JOB_SOURCE_REF]!!,
                    kubernetesJob.metadata!!.annotations!![ANNOTATION_GIT_SYNC_JOB_TARGET_BRANCH]!!,
                )
            )
        }
    }

    private fun getJobsLabelSelector(workspaceId: String): String {
        val labelSelector =
            "$LABEL_JOB_TYPE_KEY,$LABEL_WORKSPACE_ID_KEY == $workspaceId"
        return labelSelector
    }

    override fun getLog(workspaceId: String, jobId: String): String {
        // The field selector `metadata.name=$jobId` could be used, but then we would have to validate the jobId properly.
        val labelSelector = getJobsLabelSelector(workspaceId)
        val kubernetesJobs = BatchV1Api()
            .listNamespacedJob(KUBERNETES_NAMESPACE, null, null, null, null, labelSelector, null, null, null, null, null, false)
            .items
            .filter { kubernetesJob -> kubernetesJob.metadata!!.name == jobId }

        // TODO MODELIX-597 Proper error message if more or less the on job matches.
        val kubernetesJob = kubernetesJobs.single()
        val pod = CoreV1Api()
            .listNamespacedPod(KUBERNETES_NAMESPACE, null, null, null, null, "batch.kubernetes.io/job-name == $jobId", null, null, null, null, null, false)
            .items
            // TODO MODELIX-597 Proper error message if more or less the on pod matches.
            // TODO MODELIX-597 Check restart policy to start pods multiple times.
            .single()

        val checkoutStepLog =
            try {
                CoreV1Api().readNamespacedPodLog(
                    pod.metadata!!.name,
                    KUBERNETES_NAMESPACE,
                    "checkout-step",  // TODO MODELIX-597 use string constants
                    null, null, null, "true", null, null, null, false
                )
            } catch (e: ApiException) {
                if (e.code == HttpStatusCode.BadRequest.value && e.responseBody.contains(" is waiting to start: PodInitializing")) {
                    "No logs available."
                } else {
                    throw e
                }
            }
        val updateStepLog =
            try {
                CoreV1Api().readNamespacedPodLog(
                    pod.metadata!!.name,
                    KUBERNETES_NAMESPACE,
                    "update-step", // TODO MODELIX-597 use string constants
                    null, null, null, "true", null, null, null, false
                )
            } catch (e: ApiException) {
                if (e.code == HttpStatusCode.BadRequest.value && e.responseBody.contains(" is waiting to start: PodInitializing")) {
                    "No logs available."
                } else {
                    throw e
                }
            }

        val pushStepLog =
            try {
                CoreV1Api().readNamespacedPodLog(
                    pod.metadata!!.name,
                    KUBERNETES_NAMESPACE,
                    "push-step", // TODO MODELIX-597 use string constants
                    null, null, null, "true", null, null, null, false
                )
            } catch (e: ApiException) {
                // TODO MODELIX-597 Check if there actually exists an API for that.
                // We have not API to check if container has started before.
                // Example for response body if container did not start yet.
                // {
                //  "kind": "Status",
                //  "apiVersion": "v1",
                //  "metadata": {},
                //  "status": "Failure",
                //  "message": "container \"push-step\" in pod \"dev-modelix-ws-git-sync-job-kmc5j-ld5qs\" is waiting to start: PodInitializing",
                //  "reason": "BadRequest",
                //  "code": 400
                //}
                if (e.code == HttpStatusCode.BadRequest.value && e.responseBody.contains(" is waiting to start: PodInitializing")) {
                    "No logs available."
                } else {
                    throw e
                }
            }

        // Newline separators are unnecessary, because each log ends with a newline.
        val combinedLogs = sequenceOf("CHECKOUT", checkoutStepLog, "UPDATE", updateStepLog, "PUSH",  pushStepLog)
            .joinToString(System.lineSeparator())
        return combinedLogs
    }

    override fun deleteJob(workspaceId: String, jobId: JobId) {
        val labelSelector = getJobsLabelSelector(workspaceId)
        // TODO  MODELIX-597 deduplicate finding of jobs
        val kubernetesJobs = BatchV1Api()
            .listNamespacedJob(KUBERNETES_NAMESPACE, null, null, null, null, labelSelector, null, null, null, null, null, false)
            .items
            .filter { kubernetesJob -> kubernetesJob.metadata!!.name == jobId }
        val kubernetesJob = kubernetesJobs.single()
        BatchV1Api().deleteNamespacedJob(kubernetesJob.metadata!!.name, KUBERNETES_NAMESPACE, null, null, null, null, null, null)
    }

    private fun generateJobYaml(workspace: Workspace, configuration: JobConfiguration, credentials: JobCredentials): String {
        val imageVersionWithoutMpsVersion = IMAGE_VERSION
        // If we do not validate the MPS Version provided by the user when saving workspaces,
        // the user could break the job string generate Job YAML by inserting special YAML characters.
        val mpsVersion = workspace.userDefinedOrDefaultMpsVersion
        val imageVersionWitMpsVersion = "$imageVersionWithoutMpsVersion-$mpsVersion"


        // TODO MODELIX-597 Use memory limit configured in workspaces also for MPS running the sunc.
        val memoryLimit = workspace.memoryLimit

        val modelServerResource = KeycloakResourceType.MODEL_SERVER_ENTRY.createInstance("workspace-" + workspace.id)
        val modelServerToken = KeycloakUtils.createToken(listOf(modelServerResource to setOf(KeycloakScope.READ)))
        val modelServerTokenEncrypted = credentialsEncryption.encrypt(modelServerToken.token)

        // TODO MODELIX-597 Rewrite using an the API to prevent inputs from breaking YAML (e.g inputs with spaces or `"`s)
        return """
                apiVersion: batch/v1
                kind: Job
                metadata:
                  generateName: "$GENERATE_NAME_GIT_SYNC_JOB"
                  labels:
                    "$LABEL_JOB_TYPE_KEY": ""
                    "$LABEL_WORKSPACE_ID_KEY": "${workspace.id}"
                  annotations:
                    "$ANNOTATION_GIT_SYNC_JOB_VERSION": "${configuration.version}"                
                    "$ANNOTATION_GIT_SYNC_JOB_REPOSITORY": "${configuration.repository}"
                    "$ANNOTATION_GIT_SYNC_JOB_SOURCE_REF": "${configuration.sourceRef}"
                    "$ANNOTATION_GIT_SYNC_JOB_TARGET_BRANCH": "${configuration.targetBranch}"
                spec:
                  backoffLimit: 0
                  ttlSecondsAfterFinished: $SECONDS_TO_KEEP_FINISHED_JOB
                  activeDeadlineSeconds: $JOB_DEADLINE_SECONDS
                  template:
                    spec:
                      restartPolicy: Never
                      initContainers:
                        - name: checkout-step
                          image: "$CHECKOUT_STEP_IMAGE_NAME:$imageVersionWithoutMpsVersion"
                          env:                
                            - name: "$ENV_GIT_SYNC_JOB_MODEL_SERVER_V2_URL"
                              value: "$modelServerV2Url"
                            - name: "$ENV_GIT_SYNC_JOB_MODEL_SERVER_TOKEN"
                              value: "$modelServerTokenEncrypted"
                            - name: "$ENV_GIT_SYNC_JOB_WORKSPACE_MANAGER_URL"
                              value: "http://${WorkspaceJobQueue.HELM_PREFIX}workspace-manager:28104/"
                            - name: "$ENV_GIT_SYNC_JOB_WORKSPACE_ID"
                              value: "${workspace.id}"
                            - name: "$ENV_GIT_SYNC_JOB_MODEL_REPOSITORY"
                              value: "workspace_${workspace.id}"
                            - name: "$ENV_GIT_SYNC_JOB_MODEL_VERSION"
                              value: "${configuration.version}"
                            - name: "$ENV_GIT_SYNC_JOB_GIT_REPOSITORY"
                              value: "${configuration.repository}"
                            - name: "$ENV_GIT_SYNC_JOB_GIT_REPOSITORY_USERNAME"
                              value: "${credentials.username}"
                            - name: "$ENV_GIT_SYNC_JOB_GIT_REPOSITORY_PASSWORD"
                              value: "${credentials.password}"
                            - name: "$ENV_GIT_SYNC_JOB_GIT_SOURCE_REF"
                              value: "${configuration.sourceRef}"
                          volumeMounts:
                            - mountPath: /model-data
                              name: model-data
                            - mountPath: "/git-data"
                              name: git-data
                            - mountPath: /secrets/workspacesecret
                              name: "$FULLY_QUALIFIED_MODELIX_APP_NAME-workspace-secret"
                              readOnly: true
                        - name: update-step
                          image: "$UPDATE_STEP_IMAGE_NAME:$imageVersionWitMpsVersion"
                          volumeMounts:
                            - mountPath: /model-data
                              name: model-data
                            - mountPath: "/git-data"
                              name: git-data
                      containers:
                        - name: push-step
                          image: "$PUSH_STEP_IMAGE_NAME:$imageVersionWithoutMpsVersion"
                          env:                
                            - name: "$ENV_GIT_SYNC_JOB_GIT_REPOSITORY_USERNAME"
                              value: "${credentials.username}"
                            - name: "$ENV_GIT_SYNC_JOB_GIT_REPOSITORY_PASSWORD"
                              value: "${credentials.password}"
                          volumeMounts:
                            - mountPath: "/model-data"
                              name: model-data
                            - mountPath: "/git-data"
                              name: git-data
                            - mountPath: /secrets/workspacesecret
                              name: "$FULLY_QUALIFIED_MODELIX_APP_NAME-workspace-secret"
                              readOnly: true
                      volumes:
                        - name: model-data
                        - name: git-data
                        - name: "$FULLY_QUALIFIED_MODELIX_APP_NAME-workspace-secret"
                          secret:
                            secretName: "$FULLY_QUALIFIED_MODELIX_APP_NAME-workspace-secret"
                            items:
                              - key: workspace-secret
                                path: workspace-credentials-key.txt
            """.trimIndent()
    }
}