package org.modelix.workspace.manager.gitsync

typealias JobId = String

/**
 * Created sync job to Git.
 */
data class Job(
    val id: JobId,
    val startTime: String,
    val status: JobStatus,
    val configuration: JobConfiguration)

/**
 * Configuration for a sync job to Git.
 */
data class JobConfiguration(
    val version: String,
    val repository: String,
    val sourceRef: String,
    val targetBranch: String
)

/**
 * User provided credentials for a job.
 */
data class JobCredentials(
    val username: String?,
    val password: String?
)

/**
 * The status of a [Job].
 */
enum class JobStatus {
    SUBMITTED,
    ACTIVE,
    FAILED,
    SUCCEEDED
}