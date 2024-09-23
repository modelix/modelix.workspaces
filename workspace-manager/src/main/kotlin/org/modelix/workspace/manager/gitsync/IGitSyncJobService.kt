package org.modelix.workspace.manager.gitsync

import org.modelix.workspaces.Workspace

/**
 * Service responsible for orchestrating sync of model data to Git.
 */
interface IGitSyncJobService {
    fun createJob(workspace: Workspace, configuration: JobConfiguration, credentials: JobCredentials)
    fun getJobs(workspaceId: String): List<Job>
    fun getLog(workspaceId: String, jobId: JobId): String
    fun deleteJob(workspaceId: String, jobId: JobId)
}